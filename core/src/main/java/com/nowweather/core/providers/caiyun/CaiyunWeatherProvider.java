package com.nowweather.core.providers.caiyun;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProvider;
import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpResult;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;
import com.nowweather.core.weather.WindScale;

import java.time.Duration;
import java.util.Locale;

/**
 * <b>彩云天气（Caiyun）实况数据源</b> —— 国内精度最高的免费方案。
 *
 * <pre>{@code
 * GET https://api.caiyunapp.com/v2.6/{token}/{经度},{纬度}/realtime?lang=zh_CN&unit=metric
 * }</pre>
 *
 * <h2>★ URL 里是「经度在前、纬度在后」</h2>
 * 这一点和几乎所有其它天气接口相反，写反了会返回另一个半球的天气（而且照样 status=ok）。
 * 响应里的 {@code location} 字段又是 {@code [纬度, 经度]}，两个顺序不一致，很容易踩。
 *
 * <h2>为什么把它排在第一位</h2>
 * <ul>
 *   <li><b>雷达实测降水</b>：{@code precipitation.local} 是雷达反演的降水强度，
 *       {@code nearest.distance} 还能告诉你雨带离你多远 —— 实测同一时刻
 *       彩云说「中雨 9.18mm/h、雨带距离 0」，而 UApiPro 还在说「多云 34°C」；</li>
 *   <li><b>1 km 空间分辨率、分钟级刷新</b>，不像城市级接口那样把整个市当一个点；</li>
 *   <li>直接给出<b>总云量</b> {@code cloudrate}（0~1）—— 别的接口要另外算，
 *       而这个值正好喂给「阴天仿真」；</li>
 *   <li>直接给出<b>时区</b> {@code tzshift} 与 {@code timezone}，
 *       省掉我们自己按经度估算。</li>
 * </ul>
 *
 * <p>它和 Open-Meteo 一样<b>需要经纬度</b>，所以没有坐标时返回 {@link ProviderStatus#NO_LOCATION}，
 * 交给回退链的下一个数据源（能按 IP 定位的 UApiPro 先顶上，解析出城市后坐标会写进地点画像，
 * 下一次取数本数据源就能接手）。</p>
 */
public final class CaiyunWeatherProvider implements WeatherProvider {

    public static final String ID = "caiyun";

    /**
     * 优先级：<b>数字越小越先尝试</b>（{@code WeatherProviderRegistry} 用的是
     * {@code sort(Comparator.comparingInt(priority))}，升序）。
     *
     * <p>所以「最高优先级」= 最小数字。当前的尝试顺序是：</p>
     * <pre>
     *   彩云 80  ->  Open-Meteo 90  ->  UApiPro 100
     * </pre>
     * <p>注意别写反了：数字大的反而是最后兜底的。</p>
     */
    public static final int PRIORITY = 80;

    public static final String DEFAULT_ENDPOINT = "https://api.caiyunapp.com/v2.6";

    /** 彩云实况是分钟级刷新的，10 分钟还拿不到新数据就算过期。 */
    public static final long DEFAULT_REFRESH_MILLIS = 10L * 60L * 1000L;

    private final HttpTransport transport;
    private final String endpoint;

    public CaiyunWeatherProvider(HttpTransport transport, String endpoint) {
        this.transport = transport;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return Text.tr("nowweather.provider.caiyun");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean requiresNetwork() {
        return true;
    }

    /** 不做 IP 定位：没有坐标就交给下一个数据源（和 Open-Meteo 同样的策略）。 */
    @Override
    public boolean providesLocation() {
        return false;
    }

    @Override
    public long stalenessMillis() {
        return 30L * 60L * 1000L;
    }

    @Override
    public boolean isEnabled(ProviderContext context) {
        return !token(context).isBlank();
    }

    /**
     * 取 token：优先用配置里的 {@code [api] caiyunToken}，没有才退回 {@code apiKey}。
     *
     * <p>为什么要分开：{@code apiKey} 还是 UApiPro 的密钥，而彩云和 UApiPro
     * 是两个完全不同的服务、密钥不通用。只留一个字段的话，把 UApiPro 的 key 填进去
     * 彩云就会一直 401，反之亦然。</p>
     */
    private static String token(ProviderContext context) {
        try {
            JsonObject settings = context.settings();
            JsonObject mapping = settings == null ? null : settings.optObject("mapping");
            String dedicated = mapping == null ? null : mapping.optString("caiyunToken", "");
            if (dedicated != null && !dedicated.isBlank()) {
                return dedicated.trim();
            }
        } catch (RuntimeException ignored) {
            // 配置结构异常时退回 apiKey
        }
        String key = context.apiKey();
        return key == null ? "" : key.trim();
    }

    @Override
    public ProviderResult fetch(ProviderContext context) {
        String token = token(context);
        if (token.isBlank()) {
            return ProviderResult.failure(id(), ProviderStatus.DISABLED,
                    "彩云天气需要 token：请在配置里填 [api] caiyunToken（或退回用 apiKey）");
        }
        double longitude = context.longitude();
        double latitude = context.latitude();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) {
            return ProviderResult.failure(id(), ProviderStatus.NO_LOCATION,
                    "彩云天气需要经纬度：请在配置里填 coordinates，或让能按 IP 定位的数据源先跑一次");
        }

        String url = buildUrl(token, longitude, latitude);
        try {
            HttpResult response = transport.execute(HttpRequestSpec.get(url)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(Math.max(2_000L, context.timeoutMillis())))
                    .build());
            if (!response.isSuccess()) {
                return ProviderResult.failure(id(), ProviderStatus.HTTP_ERROR,
                        "HTTP " + response.statusCode());
            }
            return parse(response.body(), context, url);
        } catch (HttpException e) {
            return ProviderResult.failure(id(), e.isNetworkLevel()
                    ? ProviderStatus.NETWORK_ERROR : ProviderStatus.HTTP_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            return ProviderResult.failure(id(), ProviderStatus.PARSE_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 组装 URL。<b>路径里经度在前、纬度在后</b>。 */
    public String buildUrl(String token, double longitude, double latitude) {
        return String.format(Locale.ROOT, "%s/%s/%.4f,%.4f/realtime?lang=zh_CN&unit=metric",
                endpoint, token, longitude, latitude);
    }

    /** 解析响应；结构不对时返回失败而不是抛异常。 */
    public static ProviderResult parse(String body, ProviderContext context, String url) {
        JsonValue document = JsonValue.parseOr(body, JsonValue.nul());
        if (!document.isObject()) {
            return ProviderResult.failure(ID, ProviderStatus.PARSE_ERROR, "返回内容不是 JSON");
        }
        JsonObject root = document.asObject();
        if (!"ok".equalsIgnoreCase(root.optString("status", ""))) {
            String message = root.optString("error", root.optString("message", "上游返回 " + root.optString("status", "?")));
            return ProviderResult.failure(ID, ProviderStatus.NO_DATA, message);
        }
        JsonObject result = root.optObject("result");
        JsonObject realtime = result == null ? null : result.optObject("realtime");
        if (realtime == null) {
            return ProviderResult.failure(ID, ProviderStatus.NO_DATA, "缺少 result.realtime");
        }

        String skycon = realtime.optString("skycon", "UNKNOWN");
        WeatherType type = skyconToType(skycon);

        long now = context.nowEpochMillis();
        WeatherObservation.Builder builder = WeatherObservation.builder(ID)
                .weatherType(type)
                .conditionText(skyconToChinese(skycon))
                .iconCode(skycon)
                .fetchedAt(now)
                .observedAt(now)
                .reportTimeText("彩云雷达实况")
                // 彩云自带时区，直接用它，比按经度估算准
                .utcOffsetSeconds(root.optInt("tzshift", -1))
                // 彩云实况接口不返回省/市，用 ProviderContext 里已有的（来自配置或缓存画像）
                .city(context.city())
                .adcode(context.adcode());

        double temperature = realtime.optDouble("temperature", Double.NaN);
        if (!Double.isNaN(temperature)) {
            builder.temperatureC(temperature);
        }
        double feels = realtime.optDouble("apparent_temperature", Double.NaN);
        if (!Double.isNaN(feels)) {
            builder.feelsLikeC(feels);
        }
        double humidity = realtime.optDouble("humidity", Double.NaN);
        if (!Double.isNaN(humidity)) {
            // 彩云给的是 0~1，我们是百分比
            builder.humidityPercent(MathUtil.clamp(humidity <= 1.0D ? humidity * 100.0D : humidity,
                    0.0D, 100.0D));
        }
        double cloudrate = realtime.optDouble("cloudrate", Double.NaN);
        if (!Double.isNaN(cloudrate)) {
            builder.cloudCover01(MathUtil.clamp(cloudrate, 0.0D, 1.0D));
        }
        double visibility = realtime.optDouble("visibility", Double.NaN);
        if (!Double.isNaN(visibility)) {
            builder.visibilityKm(Math.max(0.0D, visibility));
        }
        double pressure = realtime.optDouble("pressure", Double.NaN);
        if (!Double.isNaN(pressure)) {
            // 彩云给的是帕斯卡
            builder.pressureHpa(pressure > 2000.0D ? pressure / 100.0D : pressure);
        }
        JsonObject wind = realtime.optObject("wind");
        if (wind != null) {
            double speedMs = wind.optDouble("speed", Double.NaN);
            if (!Double.isNaN(speedMs)) {
                // unit=metric 时是 m/s
                double kmh = speedMs * 3.6D;
                builder.windSpeedKmh(kmh);
                builder.windScale(WindScale.fromKmh(kmh));
            }
            double direction = wind.optDouble("direction", Double.NaN);
            if (!Double.isNaN(direction)) {
                builder.windDirection(WindDirection.fromDegrees(direction));
            }
        }

        // 置信度：有雷达降水数据（datasource=radar）时最可信
        boolean radar = "radar".equalsIgnoreCase(localOf(realtime, "datasource", ""));
        builder.confidence(radar ? 0.95D : 0.85D);

        WeatherObservation observation = builder.build();
        return ProviderResult.ok(ID, observation)
                .withDiagnostic("url", url)
                .withDiagnostic("skycon", skycon)
                .withDiagnostic("datasource", localOf(realtime, "datasource", ""))
                .withDiagnostic("precipIntensity",
                        String.valueOf(localDouble(realtime, "intensity")))
                .withDiagnostic("nearestDistance",
                        String.valueOf(nearestDouble(realtime, "distance")));
    }

    private static String localOf(JsonObject realtime, String field, String fallback) {
        JsonObject precipitation = realtime.optObject("precipitation");
        JsonObject local = precipitation == null ? null : precipitation.optObject("local");
        return local == null ? fallback : local.optString(field, fallback);
    }

    private static double localDouble(JsonObject realtime, String field) {
        JsonObject precipitation = realtime.optObject("precipitation");
        JsonObject local = precipitation == null ? null : precipitation.optObject("local");
        return local == null ? Double.NaN : local.optDouble(field, Double.NaN);
    }

    private static double nearestDouble(JsonObject realtime, String field) {
        JsonObject precipitation = realtime.optObject("precipitation");
        JsonObject nearest = precipitation == null ? null : precipitation.optObject("nearest");
        return nearest == null ? Double.NaN : nearest.optDouble(field, Double.NaN);
    }

    /**
     * 彩云的 {@code skycon} → 我们的 {@link WeatherType}。
     *
     * <p>彩云的天气现象优先级：降雪 &gt; 降雨 &gt; 雾 &gt; 沙尘 &gt; 浮尘 &gt; 雾霾 &gt; 大风 &gt; 阴 &gt; 多云 &gt; 晴。
     * 而且它<b>分别给白天与夜间的晴/多云</b>，我们这边不区分昼夜，所以两组映射到同一个类型。</p>
     */
    public static WeatherType skyconToType(String skycon) {
        if (skycon == null) {
            return WeatherType.UNKNOWN;
        }
        return switch (skycon.trim().toUpperCase(Locale.ROOT)) {
            case "CLEAR_DAY", "CLEAR_NIGHT" -> WeatherType.CLEAR;
            case "PARTLY_CLOUDY_DAY", "PARTLY_CLOUDY_NIGHT" -> WeatherType.PARTLY_CLOUDY;
            case "CLOUDY" -> WeatherType.OVERCAST;
            case "LIGHT_HAZE", "MODERATE_HAZE", "HEAVY_HAZE" -> WeatherType.HAZE;
            case "FOG" -> WeatherType.FOG;
            case "LIGHT_RAIN" -> WeatherType.LIGHT_RAIN;
            case "MODERATE_RAIN" -> WeatherType.RAIN;
            case "HEAVY_RAIN" -> WeatherType.HEAVY_RAIN;
            // 暴雨现在有独立分档了（不是雷暴：暴雨不等于打雷）
            case "STORM_RAIN" -> WeatherType.STORM_RAIN;
            case "LIGHT_SNOW" -> WeatherType.LIGHT_SNOW;
            case "MODERATE_SNOW" -> WeatherType.SNOW;
            case "HEAVY_SNOW" -> WeatherType.HEAVY_SNOW;
            case "STORM_SNOW" -> WeatherType.BLIZZARD;
            // 浮尘接近霾，沙尘才是沙尘暴
            case "DUST" -> WeatherType.HAZE;
            case "SAND" -> WeatherType.SANDSTORM;
            case "WIND" -> WeatherType.WINDY;
            default -> WeatherType.UNKNOWN;
        };
    }

    /**
     * skycon 的展示名（彩云官方文案的中文原文）。
     *
     * <p>已本地化：每个 skycon 一个语言键 {@code nowweather.skycon.<小写>}，
     * 这里只负责挑键，未安装翻译器时返回键名。</p>
     */
    public static String skyconToChinese(String skycon) {
        if (skycon == null) {
            return Text.tr("nowweather.skycon.unknown");
        }
        return switch (skycon.trim().toUpperCase(Locale.ROOT)) {
            case "CLEAR_DAY", "CLEAR_NIGHT" -> Text.tr("nowweather.skycon.clear");
            case "PARTLY_CLOUDY_DAY", "PARTLY_CLOUDY_NIGHT" -> Text.tr("nowweather.skycon.partly_cloudy");
            case "CLOUDY" -> Text.tr("nowweather.skycon.cloudy");
            case "LIGHT_HAZE" -> Text.tr("nowweather.skycon.light_haze");
            case "MODERATE_HAZE" -> Text.tr("nowweather.skycon.moderate_haze");
            case "HEAVY_HAZE" -> Text.tr("nowweather.skycon.heavy_haze");
            case "FOG" -> Text.tr("nowweather.skycon.fog");
            case "LIGHT_RAIN" -> Text.tr("nowweather.skycon.light_rain");
            case "MODERATE_RAIN" -> Text.tr("nowweather.skycon.moderate_rain");
            case "HEAVY_RAIN" -> Text.tr("nowweather.skycon.heavy_rain");
            case "STORM_RAIN" -> Text.tr("nowweather.skycon.storm_rain");
            case "LIGHT_SNOW" -> Text.tr("nowweather.skycon.light_snow");
            case "MODERATE_SNOW" -> Text.tr("nowweather.skycon.moderate_snow");
            case "HEAVY_SNOW" -> Text.tr("nowweather.skycon.heavy_snow");
            case "STORM_SNOW" -> Text.tr("nowweather.skycon.storm_snow");
            case "DUST" -> Text.tr("nowweather.skycon.dust");
            case "SAND" -> Text.tr("nowweather.skycon.sand");
            case "WIND" -> Text.tr("nowweather.skycon.wind");
            default -> Text.tr("nowweather.skycon.unknown");
        };
    }
}

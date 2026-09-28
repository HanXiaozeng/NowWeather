package com.nowweather.core.providers.openmeteo;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProvider;
import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpResult;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonException;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;
import com.nowweather.core.weather.WindScale;

import java.time.Duration;
import java.util.Locale;

/**
 * <b>Open-Meteo 数据源</b>（全球覆盖，无需 API Key）。
 *
 * <p>接口：{@code GET https://api.open-meteo.com/v1/forecast}</p>
 *
 * <pre>{@code
 * ?latitude=30.2741&longitude=120.1551
 * &current=temperature_2m,relative_humidity_2m,precipitation,rain,snowfall,
 *          weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,surface_pressure
 * &timezone=auto
 * }</pre>
 *
 * <p>为什么值得作为首选数据源：</p>
 * <ul>
 *   <li><b>全球覆盖</b>：任何经纬度都能查，不像国内城市类 API 只覆盖境内；</li>
 *   <li><b>无需 API Key</b>，免费额度对独立开发者足够（本模组默认 20 分钟刷新一次，
 *       一天约 72 次请求）；非商业用途免费，需保留 Open-Meteo 署名（见 README）；</li>
 *   <li><b>WMO 标准天气代码</b>：判断天气用的是国际标准代码而不是中文文案，稳定得多；</li>
 *   <li>数据本身每 15 分钟更新一次（响应里的 {@code interval} 字段），
 *       所以刷新间隔设成 20 分钟是合理的 —— 刷得更快只是浪费配额。</li>
 * </ul>
 *
 * <p>它需要经纬度：优先用配置里的坐标；否则用城市名去 Open-Meteo 的地理编码接口解析
 * （支持中文城市名，结果会缓存）。两者都没有时返回 {@link ProviderStatus#NO_LOCATION}，
 * 由回退链交给下一个数据源（例如能按 IP 定位的 UApiPro）。</p>
 */
public final class OpenMeteoWeatherProvider implements WeatherProvider {

    public static final String ID = "openmeteo";
    /** 优先级：数字比 UApiPro(100) 小，所以默认先尝试它。 */
    public static final int PRIORITY = 90;

    public static final String DEFAULT_ENDPOINT = "https://api.open-meteo.com/v1/forecast";

    /** 默认刷新间隔 20 分钟（与上游 15 分钟的数据更新节奏匹配）。 */
    public static final long DEFAULT_REFRESH_MILLIS = 20L * 60L * 1000L;

    private static final String CURRENT_FIELDS = String.join(",",
            "temperature_2m", "relative_humidity_2m", "apparent_temperature", "precipitation",
            "rain", "snowfall", "weather_code", "cloud_cover", "wind_speed_10m",
            "wind_direction_10m", "surface_pressure");

    private final HttpTransport transport;
    private final OpenMeteoGeocoder geocoder;
    private final String endpoint;
    private final boolean geocodingEnabled;
    private final long refreshMillis;

    public OpenMeteoWeatherProvider(HttpTransport transport) {
        this(transport, DEFAULT_ENDPOINT, true, DEFAULT_REFRESH_MILLIS,
                new OpenMeteoGeocoder(transport));
    }

    public OpenMeteoWeatherProvider(HttpTransport transport, String endpoint, boolean geocodingEnabled,
                                    long refreshMillis, OpenMeteoGeocoder geocoder) {
        this.transport = transport;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.geocodingEnabled = geocodingEnabled;
        this.refreshMillis = refreshMillis <= 0L ? DEFAULT_REFRESH_MILLIS : refreshMillis;
        this.geocoder = geocoder;
    }

    /** 多坐标请求的响应是顶层数组；取第一个元素。 */
    private static JsonObject firstObjectOf(JsonValue value) {
        JsonArray array = value.asArrayOrNull();
        if (array == null || array.isEmpty()) {
            return null;
        }
        return array.get(0).asObjectOrNull();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return Text.tr("nowweather.provider.openmeteo");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public long minIntervalMillis() {
        return refreshMillis;
    }

    @Override
    public long stalenessMillis() {
        // 上游 15 分钟更新一次，45 分钟还没拿到新数据就说明有问题
        return 45L * 60L * 1000L;
    }

    /** 自己不做 IP 定位：没有坐标就交给下一个数据源。 */
    @Override
    public boolean providesLocation() {
        return false;
    }

    @Override
    public ProviderResult fetch(ProviderContext context) {
        Location location = resolveLocation(context);
        if (location == null) {
            return ProviderResult.failure(id(), ProviderStatus.NO_LOCATION,
                    "Open-Meteo 需要经纬度：请在配置里填坐标（latitude/longitude）或城市名（用于地理编码）");
        }

        String url = endpoint
                + (endpoint.contains("?") ? "&" : "?")
                + String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", location.latitude(),
                        location.longitude())
                + "&current=" + CURRENT_FIELDS
                + "&timezone=auto&wind_speed_unit=kmh"
                // ★ elevation：让 Open-Meteo 自己把温度/露点/气压订正到目标海拔。
                // 实测（2026-09 对官方 API 逐项核对）：它会按 6.5 K/km 同时平移气温与露点
                //（因此相对湿度基本不变），气压按气压高度公式下推；
                // 但**风、云量、降水量、天气代码不受影响** —— 那几项仍由我们自己的地形订正负责。
                + (Double.isFinite(context.elevationMeters())
                        ? String.format(Locale.ROOT, "&elevation=%.0f", context.elevationMeters())
                        : "");

        HttpResult response;
        try {
            response = transport.execute(HttpRequestSpec.get(url)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(Math.max(2_000L, context.timeoutMillis())))
                    .build());
        } catch (HttpException e) {
            return ProviderResult.failure(id(), e.isNetworkLevel() ? ProviderStatus.NETWORK_ERROR
                    : ProviderStatus.HTTP_ERROR, e.getMessage());
        }

        if (!response.isSuccess()) {
            ProviderStatus status = response.statusCode() == 429
                    ? ProviderStatus.RATE_LIMITED : ProviderStatus.HTTP_ERROR;
            return ProviderResult.failure(id(), status, "HTTP " + response.statusCode(),
                    status == ProviderStatus.RATE_LIMITED ? 60_000L : 0L);
        }

        JsonObject root;
        try {
            // ★ 多坐标请求会返回顶层 JSON 数组，直接 asObjectOrNull() 会得到 null
            // 并被误报成解析失败。这里两种形态都接受。
            JsonValue parsed = JsonValue.parse(response.body());
            root = parsed.asObjectOrNull() != null ? parsed.asObjectOrNull() : firstObjectOf(parsed);
        } catch (JsonException e) {
            return ProviderResult.failure(id(), ProviderStatus.PARSE_ERROR, "JSON 解析失败: " + e.getMessage());
        }
        if (root == null || root.optObject("current") == null) {
            return ProviderResult.failure(id(), ProviderStatus.NO_DATA, "响应缺少 current 字段");
        }

        // 注意传的是「展示名字符串」而不是 Location 记录 —— 传错了观察里就没有城市名，
        // 而离线推算正需要这个城市名来展示与写缓存（真机验证时发现城市为空）
        WeatherObservation observation = parseObservation(root, location.display(), context);
        if (observation == null) {
            return ProviderResult.failure(id(), ProviderStatus.NO_DATA, "响应缺少可用天气字段");
        }
        return ProviderResult.ok(id(), observation)
                .withDiagnostic("latitude", String.format(Locale.ROOT, "%.4f", location.latitude()))
                .withDiagnostic("longitude", String.format(Locale.ROOT, "%.4f", location.longitude()))
                .withDiagnostic("location", location.display())
                // 海拔用于离线推算的气压/温度订正（标准大气 6.5°C/km）
                .withDiagnostic("elevation", String.format(Locale.ROOT, "%.1f",
                        root.optDouble("elevation", Double.NaN)))
                .withDiagnostic("timezone", root.optString("timezone", ""));
    }

    // ------------------------------------------------------------------ 位置

    private record Location(double latitude, double longitude, String display) {
    }

    private Location resolveLocation(ProviderContext context) {
        if (context.hasCoordinates()) {
            return new Location(context.latitude(), context.longitude(),
                    String.format(Locale.ROOT, "%.3f,%.3f", context.latitude(), context.longitude()));
        }
        String city = context.city();
        if (city == null || city.isBlank() || !geocodingEnabled) {
            return null;
        }
        OpenMeteoGeocoder.Result result = geocoder.resolve(city);
        if (result == null || !result.isValid()) {
            return null;
        }
        return new Location(result.latitude(), result.longitude(),
                result.display().isBlank() ? city : result.display());
    }

    // ------------------------------------------------------------------ 解析

    /** 把 Open-Meteo 的响应转成标准化观测（抽成静态方法便于单元测试）。 */
    public static WeatherObservation parseObservation(JsonObject root, Object locationHint,
                                                      ProviderContext context) {
        JsonObject current = root.optObject("current");
        if (current == null) {
            return null;
        }
        int code = current.hasNonNull("weather_code") ? current.optInt("weather_code", -1) : -1;
        boolean hasTemperature = current.hasNonNull("temperature_2m");
        if (code < 0 && !hasTemperature) {
            return null;
        }

        WeatherType type = WmoWeatherCode.toWeatherType(code);
        if (type == null) {
            type = WeatherType.UNKNOWN;
        }
        String condition = WmoWeatherCode.describe(code);

        double temperature = current.optDouble("temperature_2m", Double.NaN);
        double feelsLike = current.optDouble("apparent_temperature", Double.NaN);
        double humidity = current.optDouble("relative_humidity_2m", -1.0D);
        double pressure = current.optDouble("surface_pressure", Double.NaN);
        double windKmh = current.optDouble("wind_speed_10m", Double.NaN);
        double windDegrees = current.optDouble("wind_direction_10m", Double.NaN);
        double precipitation = current.optDouble("precipitation", Double.NaN);
        double snowfall = current.optDouble("snowfall", Double.NaN);

        // 上游给了 WMO 代码，但代码与实测降水不一致时以实测为准（例如降水 3mm 却报「晴」）
        if (!Double.isNaN(precipitation) && precipitation > 0.5D && !type.isPrecipitation()) {
            type = temperature <= 0.5D ? WeatherType.SNOW : WeatherType.RAIN;
            condition = condition + Text.tr("nowweather.condition.measured_precip");
        }
        if (!Double.isNaN(snowfall) && snowfall > 0.0D && !type.isFrozen() && type.isPrecipitation()) {
            type = type.asFrozen(true, temperature);
        }

        String display = locationHint instanceof String text ? text : "";
        String city = display.isBlank() ? context.city() : display;

        return WeatherObservation.builder(ID)
                .weatherType(type)
                .conditionText(condition)
                .temperatureC(temperature)
                .feelsLikeC(feelsLike)
                .humidityPercent(humidity)
                .pressureHpa(pressure)
                .windSpeedKmh(windKmh)
                .windDirection(WindDirection.fromDegrees(windDegrees))
                .windScale(WindScale.fromKmh(windKmh))
                .iconCode(code >= 0 ? String.valueOf(code) : "")
                .reportTimeText(current.optString("time", ""))
                .location("", city, context.adcode())
                .confidence(0.95D)
                // 时区：Open-Meteo 会返回所在地的 UTC 偏移与 IANA 时区名，直接带进观测
                .utcOffsetSeconds(root.optInt("utc_offset_seconds", -1))
                .build();
    }
}

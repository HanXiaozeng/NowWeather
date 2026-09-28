package com.nowweather.core.providers.uapipro;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProvider;
import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpResult;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.json.JsonException;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;
import com.nowweather.core.weather.WindScale;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * UApiPro 实时天气数据源（优先级 100，是三家里的<b>最后兜底</b>）。
 *
 * <p>实际尝试顺序由优先级决定（数字小者先试）：彩云 80 → Open-Meteo 90 → UApiPro 100。
 * 它的独特价值是<b>能按 IP 定位</b>，所以常常是「还不知道坐标时」唯一能用的那一个。</p>
 *
 * <p>接口：{@code GET https://uapis.cn/api/v1/misc/weather}</p>
 * <ul>
 *   <li>{@code ?city=北京} —— 按城市名查询；</li>
 *   <li>{@code ?adcode=110000} —— 按行政区划代码查询（最精确，推荐）；</li>
 *   <li>不带参数 —— 由服务端按请求方 IP 定位（单机游戏时正好是玩家所在地）。</li>
 * </ul>
 *
 * <p>返回示例：</p>
 * <pre>{@code
 * {"province":"北京市","city":"北京","adcode":"110000","weather":"晴",
 *  "weather_icon":"100","temperature":30,"wind_direction":"西风",
 *  "wind_power":"4级","humidity":16,"report_time":"11 分钟前发布"}
 * }</pre>
 */
public final class UApiProWeatherProvider implements WeatherProvider {

    public static final String ID = "uapipro";
    /** 优先级：50~199 段为在线 API。 */
    public static final int PRIORITY = 100;

    private static final String DEFAULT_ENDPOINT = "https://uapis.cn/api/v1/misc/weather";

    private final HttpTransport transport;
    private final String endpoint;
    private final boolean allowIpLocation;
    private final boolean allowApiKeyHeader;

    public UApiProWeatherProvider(HttpTransport transport) {
        this(transport, DEFAULT_ENDPOINT, true, true);
    }

    public UApiProWeatherProvider(HttpTransport transport, String endpoint, boolean allowIpLocation,
                                  boolean allowApiKeyHeader) {
        this.transport = transport;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.allowIpLocation = allowIpLocation;
        this.allowApiKeyHeader = allowApiKeyHeader;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return Text.tr("nowweather.provider.uapipro");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean providesLocation() {
        return allowIpLocation;
    }

    @Override
    public long minIntervalMillis() {
        return 5L * 60L * 1000L;
    }

    @Override
    public ProviderResult fetch(ProviderContext context) {
        String url = buildUrl(context);
        if (url == null) {
            return ProviderResult.failure(id(), ProviderStatus.NO_LOCATION, "未配置城市/adcode，且 IP 定位已关闭");
        }

        HttpRequestSpec.Builder spec = HttpRequestSpec.get(url)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(Math.max(1_000L, context.timeoutMillis())));
        if (allowApiKeyHeader && context.hasApiKey()) {
            spec.bearerToken(context.apiKey());
        }

        HttpResult response;
        try {
            response = transport.execute(spec.build());
        } catch (HttpException e) {
            ProviderStatus status = e.isNetworkLevel() ? ProviderStatus.NETWORK_ERROR : ProviderStatus.HTTP_ERROR;
            return ProviderResult.failure(id(), status, e.getMessage());
        }

        if (!response.isSuccess()) {
            String snippet = snippet(response.body());
            ProviderStatus status = response.statusCode() == 429
                    ? ProviderStatus.RATE_LIMITED : ProviderStatus.HTTP_ERROR;
            return ProviderResult.failure(id(), status,
                    "HTTP " + response.statusCode() + (snippet.isEmpty() ? "" : " - " + snippet),
                    status == ProviderStatus.RATE_LIMITED ? 60_000L : 0L);
        }

        JsonObject json;
        try {
            JsonValue parsed = JsonValue.parse(response.body());
            json = parsed.asObjectOrNull();
            if (json == null) {
                return ProviderResult.failure(id(), ProviderStatus.PARSE_ERROR, "返回内容不是 JSON 对象");
            }
        } catch (JsonException e) {
            return ProviderResult.failure(id(), ProviderStatus.PARSE_ERROR, "JSON 解析失败: " + e.getMessage());
        }

        // 上游错误结构：{"code":400,"msg":"..."} 之类
        if (json.hasNonNull("code") && !"200".equals(json.optString("code", ""))) {
            String msg = json.optString("msg", json.optString("message", "上游返回错误"));
            long code = json.optNumberish("code", -1.0D) == -1.0D ? -1L : (long) json.optNumberish("code", -1.0D);
            boolean rateLimited = code == 429;
            return ProviderResult.failure(id(), rateLimited ? ProviderStatus.RATE_LIMITED : ProviderStatus.NO_DATA,
                    "code=" + code + " " + msg);
        }

        WeatherObservation observation = parseObservation(json, context);
        if (observation == null) {
            return ProviderResult.failure(id(), ProviderStatus.NO_DATA,
                    "返回内容缺少天气字段: " + snippet(response.body()));
        }

        return ProviderResult.ok(id(), observation)
                .withDiagnostic("url", sanitize(url))
                .withDiagnostic("endpoint", endpoint);
    }

    // ------------------------------------------------------------------ 构造请求

    /** 组装查询 URL；返回 null 表示无可用位置。 */
    public String buildUrl(ProviderContext context) {
        StringBuilder sb = new StringBuilder(endpoint);
        String query = "";
        if (context.adcode() != null && !context.adcode().isBlank()) {
            query = "adcode=" + encode(context.adcode());
        } else if (context.city() != null && !context.city().isBlank()) {
            query = "city=" + encode(context.city());
        } else if (allowIpLocation) {
            query = ""; // 交给上游按 IP 定位
        } else {
            return null;
        }
        if (!query.isEmpty()) {
            sb.append(endpoint.contains("?") ? '&' : '?').append(query);
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 解析响应

    /** 把 UApiPro 的 JSON 转成标准化观测。 */
    public static WeatherObservation parseObservation(JsonObject json, ProviderContext context) {
        String weatherText = json.optString("weather", json.optString("weather_text", ""));
        String icon = json.optString("weather_icon", json.optString("icon", ""));
        boolean hasTemperature = json.hasNonNull("temperature")
                || json.hasNonNull("temp")
                || json.hasNonNull("temp_c");
        if (weatherText.isEmpty() && icon.isEmpty() && !hasTemperature) {
            return null;
        }

        WeatherType type = UApiProConditionMapper.map(weatherText, icon);
        double temperature = json.hasNonNull("temperature")
                ? json.optNumberish("temperature", Double.NaN)
                : json.optNumberish("temp", json.optNumberish("temp_c", Double.NaN));
        double humidity = json.optNumberish("humidity", -1.0D);
        int windScale = WindScale.parse(json.optString("wind_power", json.optString("windScale", "")));
        WindDirection direction = WindDirection.parse(json.optString("wind_direction",
                json.optString("windDirection", "")));

        String city = json.optString("city", context.city());
        String province = json.optString("province", "");
        String district = json.optString("district", "");
        String adcode = json.optString("adcode", context.adcode());
        if (!district.isEmpty() && !city.contains(district)) {
            city = city + district;
        }

        return WeatherObservation.builder(ID)
                .weatherType(type)
                .conditionText(weatherText.isEmpty() ? type.zhName() : weatherText)
                .temperatureC(temperature)
                .humidityPercent(humidity)
                .windDirection(direction)
                .windScale(windScale)
                .iconCode(icon)
                .reportTimeText(json.optString("report_time", json.optString("reportTime", "")))
                .location(province, city, adcode)
                .observedAt(System.currentTimeMillis())
                .fetchedAt(System.currentTimeMillis())
                .confidence(0.9D)
                .build();
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160) + "…";
    }

    /** 去掉 URL 里的密钥，避免写进日志。 */
    private static String sanitize(String url) {
        return url.replaceAll("(?i)(key|token|api_key)=[^&]*", "$1=***");
    }
}

package com.nowweather.core.providers.openmeteo;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.forecast.SkyForecast;
import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpResult;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 Open-Meteo 取<b>真实逐小时天色预报</b>（云量 / 能见度 / 天气码 / 降水概率）。
 *
 * <h2>为什么单独一个类，而不是塞进 {@link OpenMeteoWeatherProvider}</h2>
 * 两者是<b>两次不同的取数、两个不同的语义</b>：
 * <ul>
 *   <li>实况（{@code current=}）→ 决定「现在游戏里该是什么天气」，失败必须能退避重试；</li>
 *   <li>天色预报（{@code hourly=}）→ 只影响视觉表现，失败就安静地退回原版天色，<b>绝不能让天气刷新失败</b>。</li>
 * </ul>
 * 把失败域分开，出问题时才查得清。
 *
 * <h2>用到的字段</h2>
 * <pre>{@code
 * hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,
 *        visibility,weather_code,precipitation,precipitation_probability,temperature_2m
 * timeformat=unixtime   // 直接给 epoch 秒，避免猜时区/格式
 * timezone=GMT
 * }</pre>
 *
 * <p>{@code visibility} 是天气类型完全给不出的信息 —— 雾天/霾天的判据就靠它。</p>
 */
public final class OpenMeteoSkyForecastClient {

    /** 默认向后取 13 个整点（覆盖未来 12 小时）。 */
    public static final int DEFAULT_HOURS = 13;

    private final HttpTransport transport;
    private final String endpoint;

    public OpenMeteoSkyForecastClient(HttpTransport transport, String endpoint) {
        this.transport = transport;
        this.endpoint = endpoint == null || endpoint.isBlank()
                ? OpenMeteoWeatherProvider.DEFAULT_ENDPOINT : endpoint;
    }

    /**
     * 取一次天色预报。
     *
     * @return 成功时返回 {@link SkyForecast}；失败返回 {@code null}（调用方静默处理）
     */
    public SkyForecast fetch(ProviderContext context, double latitude, double longitude, int hours) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return null;
        }
        String url = endpoint
                + (endpoint.contains("?") ? "&" : "?")
                + String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", latitude, longitude)
                + "&hourly=" + String.join(",", "cloud_cover", "cloud_cover_low", "cloud_cover_mid",
                        "cloud_cover_high", "visibility", "weather_code", "precipitation",
                        "precipitation_probability", "temperature_2m")
                + "&forecast_hours=" + Math.max(2, hours)
                // 直接要 epoch 秒：省掉「本地时间字符串 + 时区」的解析歧义
                + "&timeformat=unixtime&timezone=GMT&wind_speed_unit=kmh"
                + (Double.isFinite(context.elevationMeters())
                        ? String.format(Locale.ROOT, "&elevation=%.0f", context.elevationMeters())
                        : "");
        try {
            HttpResult response = transport.execute(HttpRequestSpec.get(url)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(Math.max(2_000L, context.timeoutMillis())))
                    .build());
            if (!response.isSuccess()) {
                return null;
            }
            return parse(response.body(), context.nowEpochMillis());
        } catch (HttpException | RuntimeException e) {
            // 天色预报是「锦上添花」：失败就退回原版天色，绝不影响天气主链路
            return null;
        }
    }

    /**
     * 解析响应；字段缺失、长度不一致或根本不是 JSON 时返回 {@code null}。
     *
     * <p>刻意用 {@code parseOr} 而不是 {@code parse}：后者在文本为空或非法时会抛
     * {@link com.nowweather.core.json.JsonException}，而天色预报属于「锦上添花」，
     * 上游返回一坨 HTML 错误页时应该安静跳过，而不是把异常往上抛。</p>
     */
    public static SkyForecast parse(String body, long nowMillis) {
        JsonValue document = JsonValue.parseOr(body, JsonValue.nul());
        if (!document.isObject()) {
            return null;
        }
        JsonObject root = document.asObject();
        JsonObject hourly = root.optObject("hourly");
        if (hourly == null) {
            return null;
        }
        JsonArray times = hourly.optArray("time");
        if (times == null || times.size() == 0) {
            return null;
        }
        List<SkyForecast.Hour> hours = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            long epochMillis = readLong(times, i, 0L) * 1000L;
            if (epochMillis <= 0L) {
                continue;
            }
            hours.add(new SkyForecast.Hour(
                    epochMillis,
                    percent(hourly, "cloud_cover", i),
                    percent(hourly, "cloud_cover_low", i),
                    percent(hourly, "cloud_cover_mid", i),
                    percent(hourly, "cloud_cover_high", i),
                    readDouble(hourly, "visibility", i, 20_000.0D) / 1000.0D,   // 米 → 公里
                    (int) readDouble(hourly, "weather_code", i, -1.0D),
                    readDouble(hourly, "precipitation", i, 0.0D),
                    percent(hourly, "precipitation_probability", i),
                    readDouble(hourly, "temperature_2m", i, Double.NaN)));
        }
        if (hours.isEmpty()) {
            return null;
        }
        return new SkyForecast(OpenMeteoWeatherProvider.ID, nowMillis, hours);
    }

    /** 百分比 → 0~1；缺失返回 0。 */
    private static double percent(JsonObject hourly, String field, int index) {
        double raw = readDouble(hourly, field, index, 0.0D);
        return MathUtil.clamp(raw / 100.0D, 0.0D, 1.0D);
    }

    private static double readDouble(JsonObject object, String field, int index, double fallback) {
        JsonArray array = object.optArray(field);
        if (array == null || index >= array.size()) {
            return fallback;
        }
        var element = array.get(index);
        if (element == null || element.isNull()) {
            return fallback;
        }
        double value = element.asDoubleOr(Double.NaN);
        return Double.isNaN(value) ? fallback : value;
    }

    private static long readLong(JsonArray array, int index, long fallback) {
        var element = array.get(index);
        if (element == null || element.isNull()) {
            return fallback;
        }
        double value = element.asDoubleOr(Double.NaN);
        return Double.isNaN(value) ? fallback : (long) value;
    }

    /** 供诊断：把一次预报渲染成多行。数字先按 {@code Locale.ROOT} 格式化，再交给翻译缝排版。 */
    public static String describe(SkyForecast forecast) {
        if (forecast == null || forecast.isEmpty()) {
            return Text.tr("nowweather.sky_forecast.empty");
        }
        StringBuilder sb = new StringBuilder();
        for (SkyForecast.Hour hour : forecast.hours()) {
            sb.append(Text.tr("nowweather.sky_forecast.line",
                    java.time.Instant.ofEpochMilli(hour.epochMillis())
                            .atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().substring(0, 5),
                    pad(hour.cloudCover01() * 100.0D, 3, 0),
                    pad(hour.cloudLow01() * 100.0D, 3, 0),
                    pad(hour.cloudMid01() * 100.0D, 3, 0),
                    pad(hour.cloudHigh01() * 100.0D, 3, 0),
                    pad(hour.visibilityKm(), 5, 1),
                    pad(hour.precipitationMm(), 4, 1),
                    pad(hour.precipitationProbability01() * 100.0D, 3, 0),
                    Integer.toString(hour.weatherCode())));
        }
        return sb.toString();
    }

    /** 定宽定点格式化：只决定数字长什么样，不含任何文案。 */
    private static String pad(double value, int width, int decimals) {
        return String.format(Locale.ROOT, "%" + width + "." + decimals + "f", value);
    }
}

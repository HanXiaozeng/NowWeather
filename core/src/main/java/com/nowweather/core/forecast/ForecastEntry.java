package com.nowweather.core.forecast;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 预报中的一格（一个时间段）。
 *
 * <p>{@code violations} 记录了这条数据在生成时被判定为「不符合该生物群系气候」的地方 ——
 * 既用于调试，也让玩家能在 HUD 上看到「已按本地气候修正」。</p>
 */
public record ForecastEntry(
        int index,
        long startTick,
        long endTick,
        long startEpochMillis,
        WeatherType weatherType,
        double temperatureC,
        double humidity01,
        double wind01,
        double confidence,
        List<SanityViolation> violations) {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    public ForecastEntry {
        violations = List.copyOf(violations == null ? List.of() : violations);
    }

    public long durationTicks() {
        return Math.max(1L, endTick - startTick);
    }

    public double precipitation() {
        return weatherType.precipitation();
    }

    public boolean wasAdjusted() {
        return !violations.isEmpty();
    }

    public String clockDisplay() {
        return startEpochMillis <= 0L ? "--:--" : CLOCK.format(Instant.ofEpochMilli(startEpochMillis));
    }

    /** 供 HUD / 命令输出的一行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(clockDisplay()).append(' ')
                .append(weatherType.zhName());
        if (!Double.isNaN(temperatureC)) {
            sb.append(String.format(Locale.ROOT, " %.1f°C", temperatureC));
        }
        sb.append(Text.tr("nowweather.forecast.confidence",
                String.format(Locale.ROOT, "%.0f", confidence * 100.0D)));
        if (wasAdjusted()) {
            sb.append(Text.tr("nowweather.forecast.adjusted"));
        }
        return sb.toString();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("index", index);
        json.put("startTick", startTick);
        json.put("endTick", endTick);
        json.put("startEpochMillis", startEpochMillis);
        json.put("weatherType", weatherType.name());
        if (!Double.isNaN(temperatureC)) {
            json.put("temperatureC", temperatureC);
        }
        json.put("humidity01", humidity01);
        json.put("wind01", wind01);
        json.put("confidence", confidence);
        if (!violations.isEmpty()) {
            var arr = new com.nowweather.core.json.JsonArray();
            violations.forEach(v -> arr.add(v.name()));
            json.put("violations", arr);
        }
        return json;
    }

    public static ForecastEntry fromJson(JsonObject json) {
        List<SanityViolation> violations = new java.util.ArrayList<>();
        var arr = json.optArray("violations");
        if (arr != null) {
            for (String name : arr.asStringList()) {
                try {
                    violations.add(SanityViolation.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // 版本不一致时忽略未知违规项
                }
            }
        }
        long startTick = json.optLong("startTick", 0L);
        return new ForecastEntry(
                json.optInt("index", 0),
                startTick,
                json.optLong("endTick", startTick + 1000L),
                json.optLong("startEpochMillis", 0L),
                WeatherType.parse(json.optString("weatherType", null), WeatherType.UNKNOWN),
                json.optDouble("temperatureC", Double.NaN),
                MathUtil.clamp(json.optDouble("humidity01", 0.5D), 0.0D, 1.0D),
                MathUtil.clamp(json.optDouble("wind01", 0.3D), 0.0D, 1.0D),
                MathUtil.clamp(json.optDouble("confidence", 0.5D), 0.0D, 1.0D),
                violations);
    }
}

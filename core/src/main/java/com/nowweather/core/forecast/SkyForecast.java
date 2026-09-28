package com.nowweather.core.forecast;

import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>真实天色预报</b> —— 直接来自气象服务的逐小时预报，而不是我们自己合成的。
 *
 * <h2>为什么需要它</h2>
 * 模组原有的 {@link Forecast} 是「用真实天气当锚点 + 马尔可夫链 + 生物群系气候约束」
 * 生成出来的<b>合理序列</b>，它保证「这个群系该出现什么天气」，
 * 但<b>不是真实世界的预报</b> —— 它不知道三小时后会不会转晴。
 *
 * <p>而 Open-Meteo 免费提供逐小时的 {@code cloud_cover}（还分低/中/高云）、
 * {@code visibility}、{@code weather_code}、{@code precipitation_probability}。
 * 拿这些就能做真正的「未来几小时天色预测」：</p>
 * <ul>
 *   <li><b>云量</b> → 天色由亮到阴沉，而不是只能靠天气类型猜；</li>
 *   <li><b>能见度</b> → 雾天/霾天的雾距，这是天气类型完全给不出的信息；</li>
 *   <li><b>降水概率</b> → 「即将下雨」的提前量。</li>
 * </ul>
 *
 * <p>两者分工明确：{@link Forecast} 决定<b>游戏内该下什么天气</b>，
 * {@code SkyForecast} 决定<b>天色长什么样</b>。</p>
 */
public record SkyForecast(String providerId, long generatedAtMillis, List<Hour> hours) {

    /** 一个整点的真实预报。 */
    public record Hour(long epochMillis,
                       double cloudCover01,
                       double cloudLow01,
                       double cloudMid01,
                       double cloudHigh01,
                       double visibilityKm,
                       int weatherCode,
                       double precipitationMm,
                       double precipitationProbability01,
                       double temperatureC) {

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.put("t", epochMillis);
            json.put("cloud", cloudCover01);
            json.put("cloudLow", cloudLow01);
            json.put("cloudMid", cloudMid01);
            json.put("cloudHigh", cloudHigh01);
            json.put("visibilityKm", visibilityKm);
            json.put("code", weatherCode);
            json.put("precipMm", precipitationMm);
            json.put("precipProb", precipitationProbability01);
            json.put("tempC", temperatureC);
            return json;
        }

        public static Hour fromJson(JsonObject json) {
            return new Hour(
                    json.optLong("t", 0L),
                    MathUtil.clamp(json.optDouble("cloud", 0.0D), 0.0D, 1.0D),
                    MathUtil.clamp(json.optDouble("cloudLow", 0.0D), 0.0D, 1.0D),
                    MathUtil.clamp(json.optDouble("cloudMid", 0.0D), 0.0D, 1.0D),
                    MathUtil.clamp(json.optDouble("cloudHigh", 0.0D), 0.0D, 1.0D),
                    Math.max(0.0D, json.optDouble("visibilityKm", 20.0D)),
                    json.optInt("code", -1),
                    Math.max(0.0D, json.optDouble("precipMm", 0.0D)),
                    MathUtil.clamp(json.optDouble("precipProb", 0.0D), 0.0D, 1.0D),
                    json.optDouble("tempC", Double.NaN));
        }
    }

    public SkyForecast {
        hours = List.copyOf(hours == null ? List.of() : hours);
    }

    public static SkyForecast empty() {
        return new SkyForecast("none", 0L, List.of());
    }

    public boolean isEmpty() {
        return hours.isEmpty();
    }

    public int size() {
        return hours.size();
    }

    /**
     * 某一时刻的云量（0~1），在相邻整点之间<b>线性插值</b>。
     *
     * <p>插值很重要：天色如果按整点跳变，玩家会看到「到点整突然变暗」——
     * 真实世界的云是连续飘过来的。</p>
     *
     * @return 取不到时返回 {@code NaN}
     */
    public double cloudCoverAt(long epochMillis) {
        return interpolate(epochMillis, 0);
    }

    /** 某一时刻的能见度（km）；取不到时返回 {@code NaN}。 */
    public double visibilityAt(long epochMillis) {
        return interpolate(epochMillis, 1);
    }

    private double interpolate(long epochMillis, int which) {
        if (hours.isEmpty()) {
            return Double.NaN;
        }
        if (epochMillis <= hours.get(0).epochMillis()) {
            return value(hours.get(0), which);
        }
        Hour last = hours.get(hours.size() - 1);
        if (epochMillis >= last.epochMillis()) {
            return value(last, which);
        }
        for (int i = 1; i < hours.size(); i++) {
            Hour b = hours.get(i);
            if (epochMillis <= b.epochMillis()) {
                Hour a = hours.get(i - 1);
                double span = Math.max(1.0D, b.epochMillis() - a.epochMillis());
                double ratio = (epochMillis - a.epochMillis()) / span;
                return MathUtil.lerp(value(a, which), value(b, which), ratio);
            }
        }
        return value(last, which);
    }

    private static double value(Hour hour, int which) {
        return which == 0 ? hour.cloudCover01() : hour.visibilityKm();
    }

    /** 距离该时刻最近的整点预报（用于取降水概率、天气码这类不能插值的量）。 */
    public Hour nearest(long epochMillis) {
        if (hours.isEmpty()) {
            return null;
        }
        Hour best = hours.get(0);
        long bestDelta = Math.abs(best.epochMillis() - epochMillis);
        for (Hour hour : hours) {
            long delta = Math.abs(hour.epochMillis() - epochMillis);
            if (delta < bestDelta) {
                best = hour;
                bestDelta = delta;
            }
        }
        return best;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("provider", providerId);
        json.put("generatedAt", generatedAtMillis);
        JsonArray array = new JsonArray();
        hours.forEach(hour -> array.add(hour.toJson()));
        json.put("hours", array);
        return json;
    }

    public static SkyForecast fromJson(JsonObject json) {
        List<Hour> parsed = new ArrayList<>();
        JsonArray array = json.optArray("hours");
        if (array != null) {
            for (var element : array) {
                JsonObject item = element.asObjectOrNull();
                if (item != null) {
                    parsed.add(Hour.fromJson(item));
                }
            }
        }
        return new SkyForecast(json.optString("provider", "none"),
                json.optLong("generatedAt", 0L), parsed);
    }
}

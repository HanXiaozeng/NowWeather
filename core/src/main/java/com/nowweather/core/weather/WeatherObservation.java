package com.nowweather.core.weather;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * 一次「真实天气观测」的不可变快照。
 *
 * <p>它可以是来自 UApiPro 的真实天气，也可以是其它天气模组、玩家手动设定、
 * 或离线兜底生成的结果 —— 对下游（预报、同步策略、HUD）而言完全一样。</p>
 */
public final class WeatherObservation {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String providerId;
    private final WeatherType weatherType;
    private final String conditionText;

    private final double temperatureC;
    private final double feelsLikeC;
    private final double humidityPercent;
    private final double pressureHpa;
    private final double visibilityKm;
    /** 总云量 0~1；NaN = 该数据源没给。彩云天气的 cloudrate 就是它。 */
    private final double cloudCover01;

    private final WindDirection windDirection;
    private final int windScale;
    private final double windSpeedKmh;

    private final String city;
    private final String province;
    private final String adcode;
    private final String iconCode;
    private final String reportTimeText;

    private final long observedAtEpochMillis;
    private final long fetchedAtEpochMillis;
    private final boolean stale;
    private final double confidence;
    /** 观测地点所在的 UTC 偏移（秒）；-1 表示数据源没给。用于「真实时间同步」与离线推算。 */
    private final int utcOffsetSeconds;

    private WeatherObservation(Builder b) {
        this.providerId = Objects.requireNonNull(b.providerId, "providerId");
        this.weatherType = b.weatherType == null ? WeatherType.UNKNOWN : b.weatherType;
        this.conditionText = b.conditionText == null ? "" : b.conditionText;
        this.temperatureC = b.temperatureC;
        this.feelsLikeC = b.feelsLikeC;
        this.humidityPercent = b.humidityPercent;
        this.pressureHpa = b.pressureHpa;
        this.visibilityKm = b.visibilityKm;
        this.cloudCover01 = b.cloudCover01;
        this.windDirection = b.windDirection == null ? WindDirection.UNKNOWN : b.windDirection;
        this.windScale = b.windScale;
        this.windSpeedKmh = Double.isNaN(b.windSpeedKmh) && b.windScale >= 0
                ? WindScale.toKmh(b.windScale) : b.windSpeedKmh;
        this.city = b.city == null ? "" : b.city;
        this.province = b.province == null ? "" : b.province;
        this.adcode = b.adcode == null ? "" : b.adcode;
        this.iconCode = b.iconCode == null ? "" : b.iconCode;
        this.reportTimeText = b.reportTimeText == null ? "" : b.reportTimeText;
        this.observedAtEpochMillis = b.observedAtEpochMillis;
        this.fetchedAtEpochMillis = b.fetchedAtEpochMillis;
        this.stale = b.stale;
        this.confidence = MathUtil.clamp(b.confidence, 0.0D, 1.0D);
        this.utcOffsetSeconds = b.utcOffsetSeconds;
    }

    public static Builder builder(String providerId) {
        return new Builder(providerId);
    }

    public static Builder builder(WeatherObservation template) {
        return new Builder(template);
    }

    // ------------------------------------------------------------------ 访问器

    public String providerId() {
        return providerId;
    }

    public WeatherType weatherType() {
        return weatherType;
    }

    /** 原始天气文本（例如 UApiPro 的「晴」「雷阵雨」）。 */
    public String conditionText() {
        return conditionText;
    }

    public double temperatureC() {
        return temperatureC;
    }

    public boolean hasTemperature() {
        return !Double.isNaN(temperatureC);
    }

    public double feelsLikeC() {
        return feelsLikeC;
    }

    /** 相对湿度百分比（0~100），未知为 -1。 */
    public double humidityPercent() {
        return humidityPercent;
    }

    /** 归一化湿度 0~1，未知时按 0.5 处理。 */
    public double humidity01() {
        return humidityPercent < 0.0D ? 0.5D : MathUtil.clamp(humidityPercent / 100.0D, 0.0D, 1.0D);
    }

    public double pressureHpa() {
        return pressureHpa;
    }

    public double visibilityKm() {
        return visibilityKm;
    }

    /** 总云量 0~1；{@code NaN} 表示数据源没提供。 */
    public double cloudCover01() {
        return cloudCover01;
    }

    public WindDirection windDirection() {
        return windDirection;
    }

    public int windScale() {
        return windScale;
    }

    public double windSpeedKmh() {
        return windSpeedKmh;
    }

    /** 归一化风力 0~1。 */
    public double windiness01() {
        if (windScale >= 0) {
            return WindScale.toNormalized(windScale);
        }
        if (!Double.isNaN(windSpeedKmh)) {
            return MathUtil.clamp(windSpeedKmh / 120.0D, 0.0D, 1.0D);
        }
        return MathUtil.clamp(weatherType.wind(), 0.0D, 1.0D);
    }

    public String city() {
        return city;
    }

    public String province() {
        return province;
    }

    public String adcode() {
        return adcode;
    }

    public String iconCode() {
        return iconCode;
    }

    public String reportTimeText() {
        return reportTimeText;
    }

    public long observedAtEpochMillis() {
        return observedAtEpochMillis;
    }

    public long fetchedAtEpochMillis() {
        return fetchedAtEpochMillis;
    }

    /** 观测数据是否已过期（网络失败后沿用旧数据时会置位）。 */
    public boolean stale() {
        return stale;
    }

    /** 提供方给出的可信度 0~1。 */
    public double confidence() {
        return confidence;
    }

    /** 观测地点所在的 UTC 偏移（秒）；-1 表示未知。 */
    public int utcOffsetSeconds() {
        return utcOffsetSeconds;
    }

    public boolean hasUtcOffset() {
        return utcOffsetSeconds != -1;
    }

    public String locationDisplay() {
        if (!city.isEmpty() && !province.isEmpty() && !city.equals(province)) {
            return province + city;
        }
        if (!city.isEmpty()) {
            return city;
        }
        if (!adcode.isEmpty()) {
            return "adcode:" + adcode;
        }
        return Text.tr("nowweather.observation.unknown_area");
    }

    public long ageMillis(long nowMillis) {
        if (fetchedAtEpochMillis <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, nowMillis - fetchedAtEpochMillis);
    }

    public boolean isOlderThan(long millis, long nowMillis) {
        return ageMillis(nowMillis) > millis;
    }

    public WeatherObservation withStale(boolean newStale) {
        return builder(this).stale(newStale).build();
    }

    public WeatherObservation withWeatherType(WeatherType type) {
        return builder(this).weatherType(type).build();
    }

    public WeatherObservation withTemperature(double celsius) {
        return builder(this).temperatureC(celsius).build();
    }

    // ------------------------------------------------------------------ 序列化

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("providerId", providerId);
        json.put("weatherType", weatherType.name());
        json.put("conditionText", conditionText);
        if (!Double.isNaN(temperatureC)) {
            json.put("temperatureC", temperatureC);
        }
        if (!Double.isNaN(feelsLikeC)) {
            json.put("feelsLikeC", feelsLikeC);
        }
        if (humidityPercent >= 0.0D) {
            json.put("humidityPercent", humidityPercent);
        }
        if (!Double.isNaN(pressureHpa)) {
            json.put("pressureHpa", pressureHpa);
        }
        if (!Double.isNaN(visibilityKm)) {
            json.put("visibilityKm", visibilityKm);
        }
        if (!Double.isNaN(cloudCover01)) {
            json.put("cloudCover01", cloudCover01);
        }
        json.put("windDirection", windDirection.name());
        if (windScale >= 0) {
            json.put("windScale", windScale);
        }
        if (!Double.isNaN(windSpeedKmh)) {
            json.put("windSpeedKmh", windSpeedKmh);
        }
        json.put("city", city);
        json.put("province", province);
        json.put("adcode", adcode);
        if (!iconCode.isEmpty()) {
            json.put("iconCode", iconCode);
        }
        if (!reportTimeText.isEmpty()) {
            json.put("reportTimeText", reportTimeText);
        }
        json.put("observedAt", observedAtEpochMillis);
        json.put("fetchedAt", fetchedAtEpochMillis);
        json.put("stale", stale);
        json.put("confidence", confidence);
        if (utcOffsetSeconds != -1) {
            json.put("utcOffsetSeconds", utcOffsetSeconds);
        }
        return json;
    }

    public static WeatherObservation fromJson(JsonObject json) {
        Builder b = builder(json.optString("providerId", "unknown"))
                .weatherType(WeatherType.parse(json.optString("weatherType", null), WeatherType.UNKNOWN))
                .conditionText(json.optString("conditionText", ""))
                .temperatureC(json.optDouble("temperatureC", Double.NaN))
                .feelsLikeC(json.optDouble("feelsLikeC", Double.NaN))
                .humidityPercent(json.optDouble("humidityPercent", -1.0D))
                .pressureHpa(json.optDouble("pressureHpa", Double.NaN))
                .visibilityKm(json.optDouble("visibilityKm", Double.NaN))
                .cloudCover01(json.optDouble("cloudCover01", Double.NaN))
                .windDirection(WindDirection.parse(json.optString("windDirection", null)))
                .windScale(json.optInt("windScale", -1))
                .windSpeedKmh(json.optDouble("windSpeedKmh", Double.NaN))
                .city(json.optString("city", ""))
                .province(json.optString("province", ""))
                .adcode(json.optString("adcode", ""))
                .iconCode(json.optString("iconCode", ""))
                .reportTimeText(json.optString("reportTimeText", ""))
                .observedAt(json.optLong("observedAt", System.currentTimeMillis()))
                .fetchedAt(json.optLong("fetchedAt", System.currentTimeMillis()))
                .stale(json.optBoolean("stale", false))
                .confidence(json.optDouble("confidence", 0.8D))
                .utcOffsetSeconds(json.optInt("utcOffsetSeconds", -1));
        return b.build();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "%s @%s %s(%.1f°C rh=%.0f%% wind=%s/%s%s)%s",
                providerId, locationDisplay(), weatherType.name(), temperatureC, humidityPercent,
                windDirection.name(), WindScale.describe(windScale),
                stale ? " [过期]" : "", reportTimeText.isEmpty() ? "" : " " + reportTimeText);
    }

    public String describeForPlayer() {
        StringBuilder sb = new StringBuilder();
        sb.append(locationDisplay()).append(Text.tr("nowweather.observation.location_sep"));
        sb.append(conditionText.isEmpty() ? weatherType.zhName() : conditionText);
        if (hasTemperature()) {
            sb.append(String.format(Locale.ROOT, " %.1f°C", temperatureC));
        }
        if (humidityPercent >= 0.0D) {
            sb.append(Text.tr("nowweather.observation.humidity",
                    String.format(Locale.ROOT, "%.0f", humidityPercent)));
        }
        if (windDirection.isKnown() || windScale >= 0) {
            sb.append(' ').append(windDirection.zhName());
            if (windScale >= 0) {
                sb.append(WindScale.describe(windScale));
            }
        }
        if (stale) {
            sb.append(Text.tr("nowweather.observation.cached"));
        }
        return sb.toString();
    }

    public String observedAtDisplay() {
        return observedAtEpochMillis <= 0L
                ? Text.tr("nowweather.observation.unknown_time")
                : TIME_FORMAT.format(Instant.ofEpochMilli(observedAtEpochMillis));
    }

    // ------------------------------------------------------------------ Builder

    public static final class Builder {
        private final String providerId;
        private WeatherType weatherType = WeatherType.UNKNOWN;
        private String conditionText = "";
        private double temperatureC = Double.NaN;
        private double feelsLikeC = Double.NaN;
        private double humidityPercent = -1.0D;
        private double pressureHpa = Double.NaN;
        private double visibilityKm = Double.NaN;
        private double cloudCover01 = Double.NaN;
        private WindDirection windDirection = WindDirection.UNKNOWN;
        private int windScale = -1;
        private double windSpeedKmh = Double.NaN;
        private String city = "";
        private String province = "";
        private String adcode = "";
        private String iconCode = "";
        private String reportTimeText = "";
        private long observedAtEpochMillis = System.currentTimeMillis();
        private long fetchedAtEpochMillis = System.currentTimeMillis();
        private boolean stale;
        private double confidence = 0.8D;
        private int utcOffsetSeconds = -1;

        private Builder(String providerId) {
            this.providerId = providerId;
        }

        private Builder(WeatherObservation t) {
            this.providerId = t.providerId;
            this.weatherType = t.weatherType;
            this.conditionText = t.conditionText;
            this.temperatureC = t.temperatureC;
            this.feelsLikeC = t.feelsLikeC;
            this.humidityPercent = t.humidityPercent;
            this.pressureHpa = t.pressureHpa;
            this.visibilityKm = t.visibilityKm;
            this.cloudCover01 = t.cloudCover01;
            this.windDirection = t.windDirection;
            this.windScale = t.windScale;
            this.windSpeedKmh = t.windSpeedKmh;
            this.city = t.city;
            this.province = t.province;
            this.adcode = t.adcode;
            this.iconCode = t.iconCode;
            this.reportTimeText = t.reportTimeText;
            this.observedAtEpochMillis = t.observedAtEpochMillis;
            this.fetchedAtEpochMillis = t.fetchedAtEpochMillis;
            this.stale = t.stale;
            this.confidence = t.confidence;
            this.utcOffsetSeconds = t.utcOffsetSeconds;
        }

        public Builder weatherType(WeatherType weatherType) {
            this.weatherType = weatherType;
            return this;
        }

        public Builder conditionText(String conditionText) {
            this.conditionText = conditionText == null ? "" : conditionText;
            return this;
        }

        public Builder temperatureC(double temperatureC) {
            this.temperatureC = temperatureC;
            return this;
        }

        public Builder feelsLikeC(double feelsLikeC) {
            this.feelsLikeC = feelsLikeC;
            return this;
        }

        public Builder humidityPercent(double humidityPercent) {
            this.humidityPercent = humidityPercent;
            return this;
        }

        public Builder pressureHpa(double pressureHpa) {
            this.pressureHpa = pressureHpa;
            return this;
        }

        public Builder cloudCover01(double cloudCover01) {
            this.cloudCover01 = cloudCover01;
            return this;
        }

        public Builder visibilityKm(double visibilityKm) {
            this.visibilityKm = visibilityKm;
            return this;
        }

        public Builder windDirection(WindDirection windDirection) {
            this.windDirection = windDirection;
            return this;
        }

        public Builder windScale(int windScale) {
            this.windScale = windScale;
            return this;
        }

        /** 由归一化风力（0~1）反推蒲福风级，用于离线生成的数据。 */
        public Builder windinessFromClimate(double wind01) {
            this.windScale = WindScale.fromKmh(MathUtil.clamp(wind01, 0.0D, 1.0D) * 120.0D);
            return this;
        }

        public Builder windSpeedKmh(double windSpeedKmh) {
            this.windSpeedKmh = windSpeedKmh;
            return this;
        }

        public Builder location(String province, String city, String adcode) {
            this.province = province == null ? "" : province;
            this.city = city == null ? "" : city;
            this.adcode = adcode == null ? "" : adcode;
            return this;
        }

        public Builder city(String city) {
            this.city = city == null ? "" : city;
            return this;
        }

        public Builder province(String province) {
            this.province = province == null ? "" : province;
            return this;
        }

        public Builder adcode(String adcode) {
            this.adcode = adcode == null ? "" : adcode;
            return this;
        }

        public Builder iconCode(String iconCode) {
            this.iconCode = iconCode == null ? "" : iconCode;
            return this;
        }

        public Builder reportTimeText(String reportTimeText) {
            this.reportTimeText = reportTimeText == null ? "" : reportTimeText;
            return this;
        }

        public Builder observedAt(long epochMillis) {
            this.observedAtEpochMillis = epochMillis;
            return this;
        }

        public Builder fetchedAt(long epochMillis) {
            this.fetchedAtEpochMillis = epochMillis;
            return this;
        }

        public Builder stale(boolean stale) {
            this.stale = stale;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder utcOffsetSeconds(int seconds) {
            this.utcOffsetSeconds = seconds;
            return this;
        }

        public WeatherObservation build() {
            return new WeatherObservation(this);
        }
    }
}

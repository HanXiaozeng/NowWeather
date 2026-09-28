package com.nowweather.core.cache;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;

/**
 * 地点画像 —— 「离线也能推算天气」所依赖的元数据。
 *
 * <p>它保存的是<b>与时间无关的地点信息</b>：坐标、海拔、时区、半球、运营商来源。
 * 有了它，即使断网很久、缓存里的天气早就不适用，也能按「纬度 + 海拔 + 年内进度 + 昼夜」
 * 推出一份接近当地真实的天气（见 {@code OfflineWeatherSynthesizer}）。</p>
 *
 * <p>这些信息会在每次成功获取实况时更新，并写入本地缓存目录。</p>
 */
public record LocationProfile(
        String city,
        String adcode,
        double latitude,
        double longitude,
        double elevationMeters,
        int utcOffsetSeconds,
        String providerId,
        long updatedAtEpochMillis,
        String source) {

    public static final double UNKNOWN_ELEVATION = Double.NaN;

    public LocationProfile {
        city = city == null ? "" : city;
        adcode = adcode == null ? "" : adcode;
        providerId = providerId == null ? "" : providerId;
        source = source == null ? "" : source;
    }

    public static LocationProfile empty() {
        return new LocationProfile("", "", Double.NaN, Double.NaN, UNKNOWN_ELEVATION, -1, "",
                0L, "none");
    }

    public boolean hasCoordinates() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    public boolean hasElevation() {
        return !Double.isNaN(elevationMeters);
    }

    public boolean hasUtcOffset() {
        return utcOffsetSeconds != -1;
    }

    /** 是否是有内容的地点画像（至少知道坐标）。 */
    public boolean isUsable() {
        return hasCoordinates();
    }

    /** 南北半球。 */
    public Hemisphere hemisphere() {
        if (!hasCoordinates()) {
            return Hemisphere.UNKNOWN;
        }
        if (latitude > 0.5D) {
            return Hemisphere.NORTH;
        }
        return latitude < -0.5D ? Hemisphere.SOUTH : Hemisphere.EQUATORIAL;
    }

    /** 纬度绝对值（度）。 */
    public double absoluteLatitude() {
        return hasCoordinates() ? Math.abs(latitude) : Double.NaN;
    }

    public LocationProfile withCoordinates(double lat, double lon) {
        return new LocationProfile(city, adcode, lat, lon, elevationMeters, utcOffsetSeconds,
                providerId, updatedAtEpochMillis, source);
    }

    public LocationProfile withElevation(double meters) {
        return new LocationProfile(city, adcode, latitude, longitude, meters, utcOffsetSeconds,
                providerId, updatedAtEpochMillis, source);
    }

    public LocationProfile withUtcOffset(int seconds) {
        return new LocationProfile(city, adcode, latitude, longitude, elevationMeters, seconds,
                providerId, updatedAtEpochMillis, source);
    }

    public LocationProfile withIdentity(String city, String adcode, String providerId) {
        return new LocationProfile(city == null ? this.city : city, adcode == null ? this.adcode : adcode,
                latitude, longitude, elevationMeters, utcOffsetSeconds,
                providerId == null ? this.providerId : providerId, updatedAtEpochMillis, source);
    }

    public LocationProfile touched(long epochMillis, String newSource) {
        return new LocationProfile(city, adcode, latitude, longitude, elevationMeters, utcOffsetSeconds,
                providerId, epochMillis, newSource == null ? source : newSource);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("schema", 1L);
        json.put("city", city);
        json.put("adcode", adcode);
        if (!Double.isNaN(latitude)) {
            json.put("latitude", latitude);
            json.put("longitude", longitude);
        }
        if (hasElevation()) {
            json.put("elevationMeters", elevationMeters);
        }
        json.put("utcOffsetSeconds", utcOffsetSeconds);
        json.put("providerId", providerId);
        json.put("updatedAt", updatedAtEpochMillis);
        json.put("source", source);
        return json;
    }

    public static LocationProfile fromJson(JsonObject json) {
        if (json == null) {
            return empty();
        }
        return new LocationProfile(
                json.optString("city", ""),
                json.optString("adcode", ""),
                json.optDouble("latitude", Double.NaN),
                json.optDouble("longitude", Double.NaN),
                json.optDouble("elevationMeters", UNKNOWN_ELEVATION),
                json.hasNonNull("utcOffsetSeconds") ? json.optInt("utcOffsetSeconds", -1) : -1,
                json.optString("providerId", ""),
                json.optLong("updatedAt", 0L),
                json.optString("source", ""));
    }

    /** 供命令/诊断的一行描述。数字用 {@code Locale.ROOT} 格式化，文案走翻译缝。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!city.isBlank()) {
            sb.append(city);
        } else if (!adcode.isBlank()) {
            sb.append("adcode:").append(adcode);
        } else {
            sb.append(Text.tr("nowweather.location.unknown"));
        }
        if (hasCoordinates()) {
            sb.append(Text.tr("nowweather.location.coords",
                    fixed(latitude, 3), fixed(longitude, 3), hemisphere().zhName()));
        }
        if (hasElevation()) {
            sb.append(Text.tr("nowweather.location.elevation", fixed(elevationMeters, 0)));
        }
        if (hasUtcOffset()) {
            sb.append(" UTC").append(String.format(java.util.Locale.ROOT, "%+d", utcOffsetSeconds / 3600));
        }
        return sb.toString();
    }

    /** 定点格式化：数字形态与语言无关，只有文案会变。 */
    private static String fixed(double value, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
    }

    /** 南北半球。 */
    public enum Hemisphere {
        NORTH("北半球"),
        SOUTH("南半球"),
        EQUATORIAL("赤道附近"),
        UNKNOWN("未知半球");

        /** 中文原文，仅作语言文件（{@code nowweather.hemisphere.*}）的对照保留，不再是返回值。 */
        private final String zhName;

        Hemisphere(String zhName) {
            this.zhName = zhName;
        }

        /** 稳定语言键，例如 {@code nowweather.hemisphere.equatorial}。 */
        public String key() {
            return "nowweather.hemisphere." + Text.segment(this);
        }

        /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
        public String zhName() {
            return Text.tr(key());
        }

        /** 季节相位偏移（天）：南半球季节相反。 */
        public double seasonPhaseOffsetDays() {
            return this == SOUTH ? 182.6D : 0.0D;
        }
    }

    /** 由纬度粗判气候带（用于诊断与离线推算的风向带）。 */
    public static String climateBand(double latitude) {
        double lat = MathUtil.clamp(Math.abs(latitude), 0.0D, 90.0D);
        if (lat < 10.0D) {
            return Text.tr("nowweather.climate_band.equatorial");
        }
        if (lat < 23.5D) {
            return Text.tr("nowweather.climate_band.trade_winds");
        }
        if (lat < 35.0D) {
            return Text.tr("nowweather.climate_band.subtropical");
        }
        if (lat < 60.0D) {
            return Text.tr("nowweather.climate_band.westerlies");
        }
        return Text.tr("nowweather.climate_band.polar_easterlies");
    }
}

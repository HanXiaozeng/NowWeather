package com.nowweather.core.api;

import com.nowweather.core.json.JsonObject;

/**
 * 一次天气查询的上下文（位置、维度、生物群系、凭据……）。
 *
 * <p>所有 provider 都只从这个对象里取输入，因此「同一个 provider 服务不同服务器配置」
 * 只是换一个 context 而已。</p>
 */
public final class ProviderContext {

    private final String city;
    private final String adcode;
    private final double latitude;
    private final double longitude;
    private final String dimensionId;
    private final String biomeId;
    private final String apiKey;
    private final long timeoutMillis;
    private final String language;
    private final long nowEpochMillis;
    private final JsonObject settings;
    /** 目标海拔（米）；NaN = 未指定。 */
    private final double elevationMeters;

    private ProviderContext(Builder b) {
        this.city = b.city;
        this.adcode = b.adcode;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
        this.elevationMeters = b.elevationMeters;
        this.dimensionId = b.dimensionId;
        this.biomeId = b.biomeId;
        this.apiKey = b.apiKey;
        this.timeoutMillis = b.timeoutMillis;
        this.language = b.language;
        this.nowEpochMillis = b.nowEpochMillis;
        this.settings = b.settings;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ProviderContext of(String city) {
        return builder().city(city).build();
    }

    public String city() {
        return city;
    }

    public String adcode() {
        return adcode;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    /** 目标海拔（米）；NaN 表示未指定，让数据源用它自己的网格海拔。 */
    public double elevationMeters() {
        return elevationMeters;
    }

    public boolean hasCoordinates() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String biomeId() {
        return biomeId;
    }

    public String apiKey() {
        return apiKey;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public String language() {
        return language;
    }

    public long nowEpochMillis() {
        return nowEpochMillis;
    }

    /** provider 专属设置（来自配置文件）。 */
    public JsonObject settings() {
        return settings;
    }

    /** 是否给定了可查询的位置。 */
    public boolean hasLocation() {
        return (city != null && !city.isBlank())
                || (adcode != null && !adcode.isBlank())
                || hasCoordinates();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.city = city;
        b.adcode = adcode;
        b.latitude = latitude;
        b.longitude = longitude;
        b.dimensionId = dimensionId;
        b.biomeId = biomeId;
        b.apiKey = apiKey;
        b.timeoutMillis = timeoutMillis;
        b.language = language;
        b.nowEpochMillis = nowEpochMillis;
        b.settings = settings;
        return b;
    }

    public static final class Builder {
        private String city = "";
        private String adcode = "";
        private double latitude = Double.NaN;
        private double longitude = Double.NaN;
        /** 目标海拔（米）：游戏内玩家所在地形。NaN = 不指定，由数据源用它自己的网格海拔。 */
        private double elevationMeters = Double.NaN;
        private String dimensionId = "minecraft:overworld";
        private String biomeId = "";
        private String apiKey = "";
        private long timeoutMillis = 10_000L;
        private String language = "zh_cn";
        private long nowEpochMillis = System.currentTimeMillis();
        private JsonObject settings = new JsonObject();

        public Builder city(String city) {
            this.city = city == null ? "" : city;
            return this;
        }

        public Builder adcode(String adcode) {
            this.adcode = adcode == null ? "" : adcode;
            return this;
        }

        public Builder coordinates(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            return this;
        }

        public Builder dimensionId(String dimensionId) {
            this.dimensionId = dimensionId == null ? "minecraft:overworld" : dimensionId;
            return this;
        }

        public Builder biomeId(String biomeId) {
            this.biomeId = biomeId == null ? "" : biomeId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
            return this;
        }

        /** 目标海拔（米）：让数据源把数据订正到该高度（Open-Meteo 支持 elevation 参数）。 */
    public Builder elevationMeters(double meters) {
        this.elevationMeters = meters;
        return this;
    }

    public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder language(String language) {
            this.language = language == null ? "zh_cn" : language;
            return this;
        }

        public Builder now(long epochMillis) {
            this.nowEpochMillis = epochMillis;
            return this;
        }

        public Builder settings(JsonObject settings) {
            this.settings = settings == null ? new JsonObject() : settings;
            return this;
        }

        public ProviderContext build() {
            return new ProviderContext(this);
        }
    }
}

package com.nowweather.core.climate;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.util.Ids;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherType;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 一个生物群系的气候画像：气候大类 + <b>正常温度范围</b> + 降水/湿度/风等。
 *
 * <p>这是预报系统与天气同步策略共同依赖的核心数据对象。不可变，可安全跨线程共享。</p>
 */
public final class BiomeClimate {

    private final String biomeId;
    private final ClimateClass climateClass;
    private final TempRange tempRange;
    private final float baseTemperature;
    private final PrecipitationType precipitation;
    private final double humidity;
    private final double windiness;
    private final double thunderBias;
    private final Set<WeatherType> possibleWeather;
    private final String source;
    private final Confidence confidence;

    private BiomeClimate(Builder builder) {
        this.biomeId = Ids.normalize(builder.biomeId);
        this.climateClass = builder.climateClass == null ? ClimateClass.UNKNOWN : builder.climateClass;
        this.tempRange = builder.tempRange == null ? this.climateClass.defaultRange() : builder.tempRange;
        this.baseTemperature = builder.baseTemperature;
        this.precipitation = builder.precipitation == null ? this.climateClass.precipitation() : builder.precipitation;
        this.humidity = MathUtil.clamp(builder.humidity, 0.0D, 1.0D);
        this.windiness = MathUtil.clamp(builder.windiness, 0.0D, 1.0D);
        this.thunderBias = MathUtil.clamp(builder.thunderBias, 0.0D, 4.0D);
        this.possibleWeather = builder.possibleWeather == null
                ? Set.of() : Set.copyOf(builder.possibleWeather);
        this.source = builder.source == null ? "unknown" : builder.source;
        this.confidence = builder.confidence == null ? Confidence.MEDIUM : builder.confidence;
    }

    public static Builder builder(String biomeId) {
        return new Builder(biomeId);
    }

    public static Builder builder(BiomeClimate template) {
        return new Builder(template);
    }

    public String biomeId() {
        return biomeId;
    }

    public ClimateClass climateClass() {
        return climateClass;
    }

    /** 「正常温度范围」，预报数据的合理性判据。 */
    public TempRange tempRange() {
        return tempRange;
    }

    public float baseTemperature() {
        return baseTemperature;
    }

    public PrecipitationType precipitation() {
        return precipitation;
    }

    public double humidity() {
        return humidity;
    }

    public double windiness() {
        return windiness;
    }

    /** 雷暴倾向倍率：<1 少雷（沙漠），>1 多雷（热带）。 */
    public double thunderBias() {
        return thunderBias;
    }

    public String source() {
        return source;
    }

    public Confidence confidence() {
        return confidence;
    }

    // ------------------------------------------------------------------ 业务判定

    /** 该温度是否落在「正常温度范围」内。 */
    public boolean isTemperaturePlausible(double celsius) {
        return tempRange.contains(celsius);
    }

    public boolean isTemperaturePlausible(double celsius, double tolerance) {
        return tempRange.contains(celsius, tolerance);
    }

    /** 把温度夹进正常范围。 */
    public double clampTemperature(double celsius) {
        return tempRange.clamp(celsius);
    }

    /**
     * 依温度决定该群系此刻的降水形态。
     *
     * @param celsius 实际温度
     */
    public PrecipitationType precipitationAt(double celsius) {
        return PrecipitationType.fromTemperature(celsius, precipitation);
    }

    /**
     * 是否可能降水。
     *
     * <p><b>只认本群系自己声明的降水形态</b>，不用气候大类的默认值二次否决。
     * 大类（{@link ClimateClass}）只是粗分类，而每个群系的 {@code precipitation} 才是具体事实 ——
     * 两者冲突时应当以具体事实为准。</p>
     *
     * <p>以前的写法是 {@code precipitation.isWet() && !climateClass.isDry()}，
     * 于是「显式声明了能下雨、但归在干旱大类下的群系」永远被判成不能降水：
     * 玩家看到外面真在下雨，游戏里却被换成沙尘暴/霾。超多生物群系的
     * <b>荒地（wasteland）/ 旱地（dryland）</b> 就是这种情形 —— 实机是下雨的。</p>
     *
     * <p>沙漠仍然是 false：它的 {@code precipitation} 本来就是 {@code NONE}。
     * 下界 / 末地 / 虚空 / 洞穴同理（也都是 {@code NONE}）。</p>
     */
    public boolean canPrecipitate() {
        return precipitation.isWet();
    }

    /** 该气候下雷暴的最大允许强度（0~1），用于压制「沙漠暴雷」这类不合理数据。 */
    public double maxStormIntensity() {
        if (!canPrecipitate()) {
            return 0.0D;
        }
        return MathUtil.clamp(0.45D + humidity * 0.55D * thunderBias, 0.0D, 1.0D);
    }

    // ------------------------------------------------------------------ 天气白名单

    /**
     * 该生物群系<b>可能出现</b>的全部天气。
     *
     * <p>配置里显式写了 {@code possibleWeather} / {@code possibleMcWeather} 就用配置的；
     * 否则由 {@link WeatherAllowance} 依据气候大类、温度范围、降水形态、湿度与雷暴倾向推导。</p>
     */
    public Set<WeatherType> possibleWeather() {
        if (!possibleWeather.isEmpty()) {
            return possibleWeather;
        }
        return WeatherAllowance.derive(this);
    }

    /** 配置里显式声明的天气白名单（可能为空，表示走推导）。 */
    public Set<WeatherType> explicitPossibleWeather() {
        return possibleWeather;
    }

    /** 该天气在此群系是否可能发生。 */
    public boolean allows(WeatherType type) {
        if (type == null || type == WeatherType.UNKNOWN) {
            return true;
        }
        if (possibleWeather().contains(type)) {
            return true;
        }
        // ★ 降水强度「家族规则」：能下大雨的群系就能下暴雨，能下大雪的就能下暴风雪。
        // 白名单是逐档列举的（250 个群系每个都单独列了 HEAVY_RAIN 之类），
        // 每加一档强度都去改 250 条数据不现实；而「同种现象但更强」本来就该被允许 ——
        // 暴雨不是另一种天气，只是更大的大雨。
        if (type == WeatherType.STORM_RAIN) {
            return possibleWeather().contains(WeatherType.HEAVY_RAIN);
        }
        if (type == WeatherType.BLIZZARD) {
            return possibleWeather().contains(WeatherType.HEAVY_SNOW);
        }
        return false;
    }

    /** 换算成原版三档天气白名单（玩家真正会看到的天气）。 */
    public Set<PlatformWeather> possiblePlatformWeather() {
        return WeatherAllowance.toPlatformWeather(possibleWeather());
    }

    /**
     * 在白名单里找与给定天气最接近的替代品（用于修正不合理数据）。
     *
     * <h2>★ 降水必须保住</h2>
     * 白名单是<b>逐档列举</b>的：一个群系可能列了「中雨」却没列「大雨 / 暴雨」。
     * 原来的实现会在整个白名单里找最接近的，于是暴雨经常被「修正」成多云 ——
     * 外面在下暴雨，游戏里却是晴天。
     *
     * <p>只要该群系<b>本来就能降水</b>（{@link #canPrecipitate()}），且真实天气是降水，
     * 就只在<b>同形态的降水类型</b>里挑最接近的（雨改雨、雪改雪），
     * 绝不允许修正成「不下雨」。只有沙漠这类<b>完全不能降水</b>的群系才会被改成多云/霾。</p>
     */
    public WeatherType nearestAllowed(WeatherType type) {
        Set<WeatherType> allowed = possibleWeather();
        if (type == null || allowed.contains(type)) {
            return type;
        }
        Set<WeatherType> pool = allowed;
        if (type.isPrecipitation() && canPrecipitate()) {
            Set<WeatherType> sameForm = allowed.stream()
                    .filter(candidate -> candidate.isPrecipitation()
                            && candidate.isFrozen() == type.isFrozen())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (sameForm.isEmpty()) {
                // 没找到同形态的（例如群系只列了降雨，而真实天气是雪）：
                // 退一步，只要是降水就行 —— 保住「在下」这件事比形态更重要
                sameForm = allowed.stream()
                        .filter(WeatherType::isPrecipitation)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            }
            if (!sameForm.isEmpty()) {
                pool = sameForm;
            }
        }
        WeatherType best = null;
        double bestScore = Double.MAX_VALUE;
        for (WeatherType candidate : pool) {
            double score = Math.abs(candidate.severity() - type.severity()) * 10.0D;
            // 同类优先（雨改雨、雪改雪）
            if (candidate.category() == type.category()) {
                score -= 1.0D;
            }
            // 降水形态一致优先
            if (candidate.isFrozen() == type.isFrozen()) {
                score -= 0.5D;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? WeatherType.CLEAR : best;
    }

    // ------------------------------------------------------------------ 序列化

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("id", biomeId);
        json.put("climateClass", climateClass.name());
        json.put("tempMinC", tempRange.minC());
        json.put("tempMaxC", tempRange.maxC());
        json.put("baseTemperature", (double) baseTemperature);
        json.put("precipitation", precipitation.name());
        json.put("humidity", humidity);
        json.put("windiness", windiness);
        json.put("thunderBias", thunderBias);

        // ★ 必须排序后再写：possibleWeather 是 Set.copyOf(...)，而 JDK 的不可变集合
        // 迭代顺序取决于每次 JVM 启动生成的随机 SALT，因此不排序会导致
        // 「同一个数据源每次生成出不同字节的 JSON」—— 数据集生成器因此不可复现，
        // 每次重跑都会污染整个仓库的 diff，也无法判断数据到底有没有变。
        JsonArray weather = new JsonArray();
        possibleWeather().stream()
                .sorted(java.util.Comparator.comparing(Enum::name))
                .forEach(t -> weather.add(t.name()));
        json.put("possibleWeather", weather);

        JsonArray mcWeather = new JsonArray();
        possiblePlatformWeather().stream()
                .sorted(java.util.Comparator.comparing(Enum::name))
                .forEach(w -> mcWeather.add(w.name()));
        json.put("possibleMcWeather", mcWeather);

        json.put("source", source);
        json.put("confidence", confidence.name());
        return json;
    }

    /** 宽松解析：缺字段时用 {@code fallback} 补。 */
    public static BiomeClimate fromJson(String id, JsonObject json, BiomeClimate fallback) {
        ClimateClass climateClass = ClimateClass.parse(json.optString("climateClass",
                json.optString("climate_class", fallback == null ? "UNKNOWN" : fallback.climateClass().name())),
                fallback == null ? ClimateClass.UNKNOWN : fallback.climateClass());
        TempRange range = TempRange.fromJson(json, fallback == null ? climateClass.defaultRange() : fallback.tempRange());

        Builder builder = builder(id)
                .climateClass(climateClass)
                .tempRange(range)
                .baseTemperature((float) json.optDouble("baseTemperature",
                        fallback == null ? ClimateMath.celsiusToMcBase(range.midpoint()) : fallback.baseTemperature()))
                .precipitation(PrecipitationType.parse(json.optString("precipitation", null),
                        fallback == null ? climateClass.precipitation() : fallback.precipitation()))
                .humidity(json.optDouble("humidity", fallback == null ? climateClass.humidity() : fallback.humidity()))
                .windiness(json.optDouble("windiness", fallback == null ? climateClass.windiness() : fallback.windiness()))
                .thunderBias(json.optDouble("thunderBias", fallback == null ? 1.0D : fallback.thunderBias()))
                .source(json.optString("source", fallback == null ? "json" : fallback.source()))
                .confidence(Confidence.parse(json.optString("confidence", null),
                        fallback == null ? Confidence.MEDIUM : fallback.confidence()));
        Set<WeatherType> possible = parsePossibleWeather(json, fallback);
        if (!possible.isEmpty()) {
            builder.possibleWeather(possible);
        }
        return builder.build();
    }

    /**
     * 解析天气白名单，支持两种写法：
     * <ul>
     *   <li>{@code "possibleWeather": ["CLEAR","RAIN","THUNDERSTORM"]} —— 扩展天气名</li>
     *   <li>{@code "possibleMcWeather": ["CLEAR","RAIN","THUNDER"]} —— 原版三档，自动展开成扩展天气</li>
     * </ul>
     */
    private static Set<WeatherType> parsePossibleWeather(JsonObject json, BiomeClimate fallback) {
        JsonArray extended = json.optArray("possibleWeather");
        if (extended != null) {
            Set<WeatherType> result = EnumSet.noneOf(WeatherType.class);
            for (String name : extended.asStringList()) {
                WeatherType type = WeatherType.parse(name, null);
                if (type != null && type != WeatherType.UNKNOWN) {
                    result.add(type);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        JsonArray mc = json.optArray("possibleMcWeather");
        if (mc != null) {
            Set<WeatherType> result = EnumSet.noneOf(WeatherType.class);
            for (String name : mc.asStringList()) {
                result.addAll(expandPlatformWeather(PlatformWeather.parse(name, PlatformWeather.CLEAR)));
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return fallback == null ? Set.of() : fallback.explicitPossibleWeather();
    }

    /** 把原版三档展开成扩展天气集合。 */
    public static Set<WeatherType> expandPlatformWeather(PlatformWeather weather) {
        LinkedHashSet<WeatherType> result = new LinkedHashSet<>();
        switch (weather == null ? PlatformWeather.CLEAR : weather) {
            case CLEAR -> {
                result.add(WeatherType.CLEAR);
                result.add(WeatherType.MAINLY_CLEAR);
                result.add(WeatherType.PARTLY_CLOUDY);
                result.add(WeatherType.CLOUDY);
                result.add(WeatherType.OVERCAST);
                result.add(WeatherType.FOG);
                result.add(WeatherType.HAZE);
                result.add(WeatherType.WINDY);
                result.add(WeatherType.SANDSTORM);
            }
            case RAIN -> {
                result.add(WeatherType.CLEAR);
                result.add(WeatherType.CLOUDY);
                result.add(WeatherType.OVERCAST);
                result.add(WeatherType.DRIZZLE);
                result.add(WeatherType.LIGHT_RAIN);
                result.add(WeatherType.RAIN);
                result.add(WeatherType.HEAVY_RAIN);
                result.add(WeatherType.FREEZING_RAIN);
                result.add(WeatherType.SLEET);
                result.add(WeatherType.LIGHT_SNOW);
                result.add(WeatherType.SNOW);
                result.add(WeatherType.HEAVY_SNOW);
                result.add(WeatherType.BLIZZARD);
                result.add(WeatherType.HAIL);
            }
            case THUNDER -> {
                result.add(WeatherType.CLEAR);
                result.add(WeatherType.OVERCAST);
                result.add(WeatherType.RAIN);
                result.add(WeatherType.HEAVY_RAIN);
                result.add(WeatherType.THUNDERSTORM);
                result.add(WeatherType.SEVERE_THUNDERSTORM);
                result.add(WeatherType.HAIL);
                result.add(WeatherType.TYPHOON);
            }
        }
        return result;
    }

    public static BiomeClimate fromJsonObject(JsonObject json) {
        String id = json.optString("id", json.optString("biome", json.optString("biomeId", null)));
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("气候条目缺少 id 字段");
        }
        return fromJson(id, json, null);
    }

    public JsonValue asJsonValue() {
        return toJson();
    }

    /**
     * 供命令 / 诊断输出的一行描述（已本地化：气候大类、温度范围、降水形态、可信度都走翻译缝）。
     *
     * <p>{@link #toString()} 保持原样不翻译 —— 它是紧凑的调试转储，里面的枚举名
     * （{@code ARID} / {@code NONE} …）是拿来在日志里搜索的。</p>
     */
    public String describe() {
        return String.format(Locale.ROOT, "%s[%s %s %s hum=%.2f wind=%.2f src=%s/%s]",
                biomeId, climateClass.zhName(), tempRange.describe(), precipitation.zhName(),
                humidity, windiness, source, confidence.zhName());
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s[%s %s %s hum=%.2f wind=%.2f src=%s/%s]",
                biomeId, climateClass.name(), tempRange.describe(), precipitation.name(),
                humidity, windiness, source, confidence.name());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BiomeClimate other)) {
            return false;
        }
        return biomeId.equals(other.biomeId)
                && climateClass == other.climateClass
                && tempRange.equals(other.tempRange)
                && Float.compare(baseTemperature, other.baseTemperature) == 0
                && precipitation == other.precipitation
                && Double.compare(humidity, other.humidity) == 0
                && Double.compare(windiness, other.windiness) == 0
                && Double.compare(thunderBias, other.thunderBias) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(biomeId, climateClass, tempRange, baseTemperature, precipitation,
                humidity, windiness, thunderBias);
    }

    // ------------------------------------------------------------------ Builder

    public static final class Builder {
        private String biomeId;
        private ClimateClass climateClass;
        private TempRange tempRange;
        private float baseTemperature;
        private PrecipitationType precipitation;
        private double humidity;
        private double windiness = 0.4D;
        private double thunderBias = 1.0D;
        private Set<WeatherType> possibleWeather = Set.of();
        private String source = "unknown";
        private Confidence confidence = Confidence.MEDIUM;

        private Builder(String biomeId) {
            this.biomeId = Objects.requireNonNull(biomeId, "biomeId");
        }

        private Builder(BiomeClimate template) {
            this.biomeId = template.biomeId;
            this.climateClass = template.climateClass;
            this.tempRange = template.tempRange;
            this.baseTemperature = template.baseTemperature;
            this.precipitation = template.precipitation;
            this.humidity = template.humidity;
            this.windiness = template.windiness;
            this.thunderBias = template.thunderBias;
            this.possibleWeather = template.possibleWeather;
            this.source = template.source;
            this.confidence = template.confidence;
        }

        /** 复制模板时改写 biomeId（标签/分类规则套用到具体群系时会用到）。 */
        public Builder id(String id) {
            this.biomeId = Objects.requireNonNull(id, "biomeId");
            return this;
        }

        public Builder climateClass(ClimateClass climateClass) {
            this.climateClass = climateClass;
            return this;
        }

        public Builder tempRange(TempRange tempRange) {
            this.tempRange = tempRange;
            return this;
        }

        public Builder tempRange(double minC, double maxC) {
            this.tempRange = TempRange.of(minC, maxC);
            return this;
        }

        public Builder baseTemperature(float baseTemperature) {
            this.baseTemperature = baseTemperature;
            return this;
        }

        public Builder precipitation(PrecipitationType precipitation) {
            this.precipitation = precipitation;
            return this;
        }

        public Builder humidity(double humidity) {
            this.humidity = humidity;
            return this;
        }

        public Builder windiness(double windiness) {
            this.windiness = windiness;
            return this;
        }

        public Builder thunderBias(double thunderBias) {
            this.thunderBias = thunderBias;
            return this;
        }

        /** 显式指定该群系可能出现的天气；不设置则由 {@link WeatherAllowance} 推导。 */
        public Builder possibleWeather(Set<WeatherType> possibleWeather) {
            this.possibleWeather = possibleWeather == null ? Set.of() : Set.copyOf(possibleWeather);
            return this;
        }

        public Builder possibleWeather(WeatherType... types) {
            Set<WeatherType> set = EnumSet.noneOf(WeatherType.class);
            for (WeatherType type : types) {
                if (type != null) {
                    set.add(type);
                }
            }
            return possibleWeather(set);
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder confidence(Confidence confidence) {
            this.confidence = confidence;
            return this;
        }

        public BiomeClimate build() {
            return new BiomeClimate(this);
        }
    }
}

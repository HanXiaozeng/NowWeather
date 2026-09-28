package com.nowweather.core.climate;

import com.nowweather.core.text.Text;

import java.util.Locale;

/**
 * 气候大类 —— 生物群系与气候之间的「中间语言」。
 *
 * <p>有了它，未知模组的生物群系也能被合理归类（按名称关键词 / 原版温度 / 降水 / 标签），
 * 而不需要为每个模组单独硬编码一张表。</p>
 */
public enum ClimateClass {
    TROPICAL("热带", TempRange.of(18, 42), 0.85D, 0.55D, PrecipitationType.RAIN),
    SUBTROPICAL("亚热带", TempRange.of(5, 38), 0.75D, 0.45D, PrecipitationType.RAIN),
    ARID("干旱/沙漠", TempRange.of(12, 52), 0.10D, 0.65D, PrecipitationType.NONE),
    MEDITERRANEAN("地中海", TempRange.of(2, 40), 0.45D, 0.50D, PrecipitationType.RAIN),
    TEMPERATE("温带", TempRange.of(-12, 35), 0.60D, 0.40D, PrecipitationType.RAIN),
    CONTINENTAL("大陆性", TempRange.of(-22, 32), 0.55D, 0.45D, PrecipitationType.MIXED),
    SUBPOLAR("亚寒带", TempRange.of(-32, 24), 0.50D, 0.50D, PrecipitationType.MIXED),
    POLAR("极地/雪原", TempRange.of(-45, 5), 0.40D, 0.60D, PrecipitationType.SNOW),
    OCEANIC("海洋", TempRange.of(-5, 32), 0.80D, 0.75D, PrecipitationType.RAIN),
    COASTAL("海岸", TempRange.of(-8, 36), 0.70D, 0.80D, PrecipitationType.RAIN),
    WETLAND("湿地/沼泽", TempRange.of(-5, 35), 0.90D, 0.30D, PrecipitationType.RAIN),
    MOUNTAIN("山地", TempRange.of(-30, 22), 0.55D, 0.70D, PrecipitationType.MIXED),
    PLATEAU("高原", TempRange.of(-20, 28), 0.35D, 0.65D, PrecipitationType.MIXED),
    VOLCANIC("火山/荒原", TempRange.of(5, 60), 0.15D, 0.55D, PrecipitationType.NONE),
    MUSHROOM("蘑菇岛", TempRange.of(0, 28), 0.85D, 0.35D, PrecipitationType.RAIN),
    CAVE("洞穴", TempRange.of(2, 22), 0.80D, 0.05D, PrecipitationType.NONE),
    NETHER("下界", TempRange.of(35, 90), 0.05D, 0.25D, PrecipitationType.NONE),
    END("末地", TempRange.of(-20, 30), 0.05D, 0.15D, PrecipitationType.NONE),
    VOID("虚空", TempRange.of(-60, 60), 0.00D, 0.00D, PrecipitationType.NONE),
    UNKNOWN("未知", TempRange.of(-20, 40), 0.50D, 0.40D, PrecipitationType.MIXED);

    /** 中文原文，仅作语言文件（{@code nowweather.climate_class.*}）的对照保留，不再是返回值。 */
    private final String zhName;
    private final TempRange defaultRange;
    private final double humidity;
    private final double windiness;
    private final PrecipitationType precipitation;

    ClimateClass(String zhName, TempRange defaultRange, double humidity, double windiness,
                 PrecipitationType precipitation) {
        this.zhName = zhName;
        this.defaultRange = defaultRange;
        this.humidity = humidity;
        this.windiness = windiness;
        this.precipitation = precipitation;
    }

    /** 稳定语言键，例如 {@code nowweather.climate_class.arid}。 */
    public String key() {
        return "nowweather.climate_class." + Text.segment(this);
    }

    /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
    public String zhName() {
        return Text.tr(key());
    }

    public TempRange defaultRange() {
        return defaultRange;
    }

    /** 默认湿度 0~1。 */
    public double humidity() {
        return humidity;
    }

    /** 默认风强度 0~1。 */
    public double windiness() {
        return windiness;
    }

    public PrecipitationType precipitation() {
        return precipitation;
    }

    /** 是否属于「不会下雨」的气候（沙漠、火山、下界、末地、洞穴）。 */
    public boolean isDry() {
        return precipitation == PrecipitationType.NONE;
    }

    /**
     * 是否属于「没有天气系统」的环境：下界、末地、虚空。
     *
     * <p>这些地方原版就没有下雨/下雪/打雷；天气模组也不该去动它们 ——
     * 这是模组的硬保证（配置项 {@code leaveNetherAndEndAlone} 默认开启）。</p>
     */
    public boolean isWeatherFree() {
        return this == NETHER || this == END || this == VOID;
    }

    /** 是否极端气候（用于预报时提高/降低波动幅度）。 */
    public boolean isExtreme() {
        return this == POLAR || this == ARID || this == NETHER || this == VOID || this == VOLCANIC;
    }

    public static ClimateClass parse(String raw, ClimateClass fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ClimateClass cls : values()) {
            if (cls.name().equals(key)) {
                return cls;
            }
        }
        // 常见别名
        return switch (key) {
            case "DESERT", "BADLANDS", "MESA", "SAVANNA_DRY", "沙漠", "干旱" -> ARID;
            case "SNOWY", "ICE", "FROZEN", "TUNDRA", "雪原", "极地" -> POLAR;
            case "JUNGLE", "RAINFOREST", "热带" -> TROPICAL;
            case "FOREST", "PLAINS", "MEADOW", "温带" -> TEMPERATE;
            case "SWAMP", "MARSH", "BAYOU", "沼泽", "湿地" -> WETLAND;
            case "OCEAN", "DEEP_OCEAN", "海洋" -> OCEANIC;
            case "BEACH", "SHORE", "海岸" -> COASTAL;
            case "HILLS", "PEAKS", "ALPS", "山地" -> MOUNTAIN;
            case "HIGH_LAND", "高原" -> PLATEAU;
            case "HELL", "下界" -> NETHER;
            case "THE_END", "末地" -> END;
            case "CAVE", "洞穴" -> CAVE;
            case "MUSHROOM_ISLAND", "蘑菇" -> MUSHROOM;
            default -> fallback;
        };
    }
}

package com.nowweather.core.climate;

import com.nowweather.core.text.Text;

import java.util.Locale;

/** 降水形态。 */
public enum PrecipitationType {
    /** 完全不下雨（沙漠、下界、末地等）。 */
    NONE("无", false),
    RAIN("雨", false),
    SNOW("雪", true),
    /** 雨夹雪 / 冻雨。 */
    SLEET("雨夹雪", true),
    /** 随温度在雨雪之间切换。 */
    MIXED("雨或雪", false);

    /** 中文原文，仅作语言文件（{@code nowweather.precipitation.*}）的对照保留，不再是返回值。 */
    private final String zhName;
    private final boolean frozen;

    PrecipitationType(String zhName, boolean frozen) {
        this.zhName = zhName;
        this.frozen = frozen;
    }

    /** 稳定语言键，例如 {@code nowweather.precipitation.sleet}。 */
    public String key() {
        return "nowweather.precipitation." + Text.segment(this);
    }

    /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
    public String zhName() {
        return Text.tr(key());
    }

    /** 是否属于冻结形态（雪/雨夹雪）。 */
    public boolean isFrozen() {
        return frozen;
    }

    public boolean isWet() {
        return this != NONE;
    }

    public static PrecipitationType parse(String raw, PrecipitationType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (PrecipitationType type : values()) {
            if (type.name().equals(key)) {
                return type;
            }
        }
        return switch (key) {
            case "雨", "RAINY", "SHOWER" -> RAIN;
            case "雪", "SNOWY" -> SNOW;
            case "无", "NONE", "DRY", "ARID", "NO" -> NONE;
            case "雨夹雪", "冻雨", "SLEET", "FREEZING_RAIN" -> SLEET;
            default -> fallback;
        };
    }

    /**
     * 依据实际温度决定降水形态。
     *
     * @param celsius  实际温度
     * @param declared 该生物群系的声明降水类型（NONE 表示沙漠等绝不下雨）
     */
    public static PrecipitationType fromTemperature(double celsius, PrecipitationType declared) {
        if (declared == NONE) {
            return NONE;
        }
        if (celsius <= -2.0D) {
            return SNOW;
        }
        if (celsius < 2.0D) {
            return SLEET;
        }
        return RAIN;
    }
}

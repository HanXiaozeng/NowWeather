package com.nowweather.core.weather;

import com.nowweather.core.text.Text;

import java.util.Locale;

/** 风向（16 方位）。同时支持 UApiPro 的中文风向（{@code 西北风}）。 */
public enum WindDirection {
    N("北风", 0.0D),
    NNE("北东北风", 22.5D),
    NE("东北风", 45.0D),
    ENE("东东北风", 67.5D),
    E("东风", 90.0D),
    ESE("东东南风", 112.5D),
    SE("东南风", 135.0D),
    SSE("南东南风", 157.5D),
    S("南风", 180.0D),
    SSW("南西南风", 202.5D),
    SW("西南风", 225.0D),
    WSW("西西南风", 247.5D),
    W("西风", 270.0D),
    WNW("西西北风", 292.5D),
    NW("西北风", 315.0D),
    NNW("北西北风", 337.5D),
    UNKNOWN("未知", Double.NaN);

    /**
     * 中文原文（北风 / 西北风 …）。
     *
     * <p>语言键（{@code nowweather.wind_direction.*}）之外的第二个用途：{@link #parse(String)}
     * 解析的是上游 API 给的<b>中文</b>风向，与玩家当前语言无关，所以那里仍然读这个字段。</p>
     */
    private final String zhName;
    private final double degrees;

    WindDirection(String zhName, double degrees) {
        this.zhName = zhName;
        this.degrees = degrees;
    }

    /** 稳定语言键，例如 {@code nowweather.wind_direction.nne}。 */
    public String key() {
        return "nowweather.wind_direction." + Text.segment(this);
    }

    /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
    public String zhName() {
        return Text.tr(key());
    }

    /** 角度，未知时为 NaN。 */
    public double degrees() {
        return degrees;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    public static WindDirection fromDegrees(double degrees) {
        if (Double.isNaN(degrees)) {
            return UNKNOWN;
        }
        double normalized = ((degrees % 360.0D) + 360.0D) % 360.0D;
        int index = (int) Math.round(normalized / 22.5D) % 16;
        return values()[index];
    }

    /** 解析中文/英文风向文本。 */
    public static WindDirection parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String text = raw.trim();
        // 英文缩写直接匹配
        String upper = text.toUpperCase(Locale.ROOT);
        for (WindDirection d : values()) {
            if (d.name().equals(upper)) {
                return d;
            }
        }
        // 中文：去掉「风」与「偏」后匹配
        String zh = text.replace("风", "").replace("偏", "");
        for (WindDirection d : values()) {
            String candidate = d.zhName.replace("风", "");
            if (candidate.equals(zh)) {
                return d;
            }
        }
        // 兜底：见「北/南/东/西」推断
        boolean north = zh.contains("北");
        boolean south = zh.contains("南");
        boolean east = zh.contains("东");
        boolean west = zh.contains("西");
        if (north && west) {
            return NW;
        }
        if (north && east) {
            return NE;
        }
        if (south && west) {
            return SW;
        }
        if (south && east) {
            return SE;
        }
        if (north) {
            return N;
        }
        if (south) {
            return S;
        }
        if (east) {
            return E;
        }
        if (west) {
            return W;
        }
        return UNKNOWN;
    }
}

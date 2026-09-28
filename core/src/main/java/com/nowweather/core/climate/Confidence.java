package com.nowweather.core.climate;

import com.nowweather.core.text.Text;

/** 数据可信度 —— 让玩家/服主知道某条气候数据是「官方核对」还是「猜的」。 */
public enum Confidence {
    /** 数据来自官方/源码核对或人工配置。 */
    HIGH("已核对", 1.0D),
    /** 来自原版温度 + 降水等事实推导。 */
    MEDIUM("推导", 0.7D),
    /** 只匹配到关键词，范围较粗。 */
    LOW("关键词推断", 0.45D),
    /** 完全未知，用了兜底值。 */
    GUESS("兜底值", 0.2D);

    /** 中文原文，仅作语言文件（{@code nowweather.confidence.*}）的对照保留，不再是返回值。 */
    private final String zhName;
    private final double weight;

    Confidence(String zhName, double weight) {
        this.zhName = zhName;
        this.weight = weight;
    }

    /** 稳定语言键，例如 {@code nowweather.confidence.high}。 */
    public String key() {
        return "nowweather.confidence." + Text.segment(this);
    }

    /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
    public String zhName() {
        return Text.tr(key());
    }

    /** 0~1 权重，用于预报时决定「允许多大波动」。 */
    public double weight() {
        return weight;
    }

    public Confidence worseOf(Confidence other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    public static Confidence parse(String raw, Confidence fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        for (Confidence c : values()) {
            if (c.name().equalsIgnoreCase(raw.trim())) {
                return c;
            }
        }
        return fallback;
    }
}

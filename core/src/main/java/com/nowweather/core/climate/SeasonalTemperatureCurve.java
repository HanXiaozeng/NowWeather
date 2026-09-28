package com.nowweather.core.climate;

import com.nowweather.core.util.MathUtil;

import java.util.Set;

/**
 * 季节性温度曲线 —— 把「一年中的进度」换算成「正常温度范围的平移量」。
 *
 * <p>刻意做成与 Minecraft 无关的纯计算：季节模组（Serene Seasons 等）只负责告诉我们
 * 「现在是第几个季节、在这个季节里过了多少比例」，剩下的换算在这里，
 * 于是它可以被单元测试完整验证。</p>
 *
 * <p>为什么平移「正常温度范围」就够了：天气白名单是从温度范围推导出来的 ——
 * 温带群系冬天温度范围下移到冰点以下后，<b>雪就自动变成可能出现的天气</b>，
 * 夏天回暖后雪又自动消失。不需要为季节单独写一套天气规则。</p>
 */
public final class SeasonalTemperatureCurve {

    /** 默认锚点（摄氏度，按 春 → 夏 → 秋 → 冬 排序，取每个季节的中间状态）。 */
    public static final double[] DEFAULT_ANCHORS = {0.0D, 8.0D, -4.0D, -14.0D};

    /** 默认向中点收窄的比例（见 {@link #apply(BiomeClimate, double, double, String)}）。 */
    public static final double DEFAULT_NARROW_FACTOR = 0.35D;

    private SeasonalTemperatureCurve() {
    }

    /**
     * 计算温度平移量。
     *
     * @param seasonOrdinal      季节序号：0=春 1=夏 2=秋 3=冬
     * @param progressInSeason   在该季节中的进度 0~1
     * @param anchors            四季锚点（长度必须为 4）
     * @return 温度平移量（摄氏度）
     */
    public static double offsetFor(int seasonOrdinal, double progressInSeason, double[] anchors) {
        double[] table = anchors == null || anchors.length != 4 ? DEFAULT_ANCHORS : anchors;
        int ordinal = MathUtil.floorMod(seasonOrdinal, 4);
        double progress = MathUtil.clamp(progressInSeason, 0.0D, 1.0D);

        // 把「季节序号 + 季节内进度」映射到连续刻度，再在锚点之间做余弦插值。
        // 相位上要减掉半个季节：锚点代表的是**季节中点**的温度
        //（否则「盛夏最热」会变成「盛夏正好是两个锚点的中间值」，季节幅度直接减半）。
        // 例：盛夏中点 → scaled = 1.0 → 正好落在 SUMMER 锚点上。
        double scaled = ordinal + progress - 0.5D;
        double floor = Math.floor(scaled);
        int from = (int) MathUtil.floorMod((long) floor, 4);
        int to = (from + 1) % 4;
        double fraction = scaled - floor;
        double smooth = (1.0D - Math.cos(fraction * Math.PI)) / 2.0D;
        return table[from] + (table[to] - table[from]) * smooth;
    }

    /** 春秋相邻时季节序号可能跨年（冬→春），这里做了取模处理。 */
    public static double offsetForSeasonName(String seasonName, double progressInSeason, double[] anchors) {
        int ordinal = switch (seasonName == null ? "" : seasonName.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SPRING" -> 0;
            case "SUMMER" -> 1;
            case "AUTUMN", "FALL" -> 2;
            case "WINTER" -> 3;
            default -> 0;
        };
        return offsetFor(ordinal, progressInSeason, anchors);
    }

    /**
     * 把温度平移量套用到气候上。
     *
     * <p>平移后<b>清空显式天气白名单</b>，让它按新的温度范围重新推导 ——
     * 这正是季节联动的意义所在：冬天该下雪就允许下雪，夏天回暖就不该再下雪。</p>
     *
     * @param base     基础气候
     * @param offsetC  温度平移量
     * @param source   记录到 {@code source} 字段的来源标记（例如 {@code sereneseasons:WINTER}）
     */
    public static BiomeClimate apply(BiomeClimate base, double offsetC, String source) {
        return apply(base, offsetC, DEFAULT_NARROW_FACTOR, source);
    }

    /**
     * 把「温度平移 + 范围收窄」套用到气候上。
     *
     * <p>为什么还要收窄：内置的「正常温度范围」是一个很宽的包络（要容纳昼夜温差、地区差异），
     * 单纯平移的话，温带群系在夏天的最小值依然低于冰点，「夏天不下雪」就无法体现。
     * 收窄之后再平移，季节语义才正确：</p>
     *
     * <pre>
     * 平原 基准 [-16, 36]（宽 52）
     *   收窄 35% → [-6.9, 26.9]
     *   夏天 +8  → [ 1.1, 34.9]  → 最低温高于冰点 → 不下雪 ✔
     *   冬天 -14 → [-20.9, 12.9] → 允许降雪 ✔
     * </pre>
     *
     * @param narrowFactor 向中点收窄的比例 0~1（0 = 不收窄）
     */
    public static BiomeClimate apply(BiomeClimate base, double offsetC, double narrowFactor, String source) {
        if (base == null) {
            return null;
        }
        double factor = MathUtil.clamp(narrowFactor, 0.0D, 0.9D);
        TempRange range = base.tempRange();
        double shrink = range.span() * factor / 2.0D;
        double min = range.minC() + shrink + offsetC;
        double max = range.maxC() - shrink + offsetC;
        if (max - min < 6.0D) {
            // 收窄过头就退回成「以中点为中心的 12°C 区间」，避免出现不合理的窄范围
            double center = (min + max) / 2.0D;
            min = center - 6.0D;
            max = center + 6.0D;
        }
        TempRange shifted = new TempRange(min, max);
        return BiomeClimate.builder(base)
                .tempRange(shifted)
                // 关键：清空硬编码白名单 → 由平移后的温度范围重新推导（冬天自动允许雪）
                .possibleWeather(Set.of())
                .source(base.source() + "|" + source)
                .confidence(base.confidence())
                .build();
    }
}

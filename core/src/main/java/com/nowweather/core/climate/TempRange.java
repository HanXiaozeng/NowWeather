package com.nowweather.core.climate;

import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;

/**
 * 摄氏温度区间 —— 「正常温度范围」的载体。
 *
 * <p>预报系统依赖每个生物群系配置好的正常温度范围来判断生成的气象数据是否合理：
 * 超出范围的数据会被 {@code ClimateSanityFilter} 记录并修正。</p>
 */
public record TempRange(double minC, double maxC) {

    public TempRange {
        if (Double.isNaN(minC) || Double.isNaN(maxC)) {
            throw new IllegalArgumentException("温度范围不能为 NaN");
        }
        if (minC > maxC) {
            double tmp = minC;
            minC = maxC;
            maxC = tmp;
        }
    }

    public static TempRange of(double a, double b) {
        return new TempRange(a, b);
    }

    /** 以中心值 ± 半径构造。 */
    public static TempRange around(double center, double radius) {
        return new TempRange(center - Math.abs(radius), center + Math.abs(radius));
    }

    public double span() {
        return maxC - minC;
    }

    public double midpoint() {
        return (minC + maxC) / 2.0D;
    }

    public boolean contains(double celsius) {
        return celsius >= minC && celsius <= maxC;
    }

    public boolean contains(double celsius, double tolerance) {
        double tol = Math.abs(tolerance);
        return celsius >= minC - tol && celsius <= maxC + tol;
    }

    /** 把温度夹到范围内。 */
    public double clamp(double celsius) {
        return MathUtil.clamp(celsius, minC, maxC);
    }

    /** 返回该温度在范围内的归一化位置（0 = 最冷端，1 = 最热端）。 */
    public double normalize(double celsius) {
        return MathUtil.inverseLerp(minC, maxC, celsius);
    }

    public TempRange expand(double delta) {
        return new TempRange(minC - delta, maxC + delta);
    }

    public TempRange union(TempRange other) {
        return new TempRange(Math.min(minC, other.minC), Math.max(maxC, other.maxC));
    }

    /** 与另一个范围的交集；不相交时返回较窄的一方（避免产生非法区间）。 */
    public TempRange intersect(TempRange other) {
        double lo = Math.max(minC, other.minC);
        double hi = Math.min(maxC, other.maxC);
        if (lo > hi) {
            return span() <= other.span() ? this : other;
        }
        return new TempRange(lo, hi);
    }

    /** 该温度相对范围的「偏离度」：0 表示在范围内，>0 表示超出多少度。 */
    public double deviation(double celsius) {
        if (celsius < minC) {
            return minC - celsius;
        }
        if (celsius > maxC) {
            return celsius - maxC;
        }
        return 0.0D;
    }

    /** 供玩家/命令展示的一行描述；数字用 {@code Locale.ROOT} 格式化，不随语言变化。 */
    public String describe() {
        return Text.tr("nowweather.temp_range.range", fmt(minC), fmt(maxC));
    }

    /** 只做数字格式化，保证数字在任何语言下长得一样。 */
    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    public JsonValue toJson() {
        JsonArray array = new JsonArray();
        array.add(minC).add(maxC);
        return array;
    }

    /**
     * 支持多种写法：
     * <ul>
     *   <li>{@code "tempRange": [-5, 30]}</li>
     *   <li>{@code "tempMinC": -5, "tempMaxC": 30}</li>
     *   <li>{@code "tempMinC": -5}（单边时以默认半径补齐）</li>
     * </ul>
     */
    public static TempRange fromJson(JsonObject json, TempRange fallback) {
        JsonArray range = json.optArray("tempRange");
        if (range != null && range.size() >= 2) {
            return new TempRange(range.optDouble(0, fallback.minC), range.optDouble(1, fallback.maxC));
        }
        boolean hasMin = json.hasNonNull("tempMinC") || json.hasNonNull("temp_min_c");
        boolean hasMax = json.hasNonNull("tempMaxC") || json.hasNonNull("temp_max_c");
        if (!hasMin && !hasMax) {
            return fallback;
        }
        double min = json.hasNonNull("tempMinC")
                ? json.optNumberish("tempMinC", fallback.minC)
                : json.optNumberish("temp_min_c", fallback.minC);
        double max = json.hasNonNull("tempMaxC")
                ? json.optNumberish("tempMaxC", fallback.maxC)
                : json.optNumberish("temp_max_c", fallback.maxC);
        if (!hasMin && hasMax) {
            min = max - fallback.span();
        }
        if (hasMin && !hasMax) {
            max = min + fallback.span();
        }
        return new TempRange(min, max);
    }
}

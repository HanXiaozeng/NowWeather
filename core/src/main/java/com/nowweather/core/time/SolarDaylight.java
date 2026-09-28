package com.nowweather.core.time;

import com.nowweather.core.util.MathUtil;

/**
 * 极简太阳位置 / 昼长计算（纯解析式，无查表、无第三方依赖）。
 *
 * <p>离线天气的温度曲线需要知道「当地几点日出、几点太阳最高」。
 * 固定用「最高温出现在 15:00」是不对的：那等价于假定最低温永远在 03:00，
 * 而真实世界里最低温出现在<b>日出前后</b> —— 中纬度冬季日出约 08:00，
 * 用固定相位会让整个夜间分支差 5 个小时、温度误差 1~3 K。</p>
 *
 * <p>本类只做两件事，都是标准天文学公式，精度对气象用途绰绰有余（昼长误差 &lt; 1 分钟）：</p>
 * <ul>
 *   <li><b>赤纬 δ</b>：Spencer (1971) 的傅里叶级数（7 项，RMS 误差约 0.01°）；</li>
 *   <li><b>时差 EoT</b>：同一组级数（误差约 0.5 分钟），用于把「平太阳时」换算成「真太阳时」。</li>
 * </ul>
 *
 * <p>公式来源：Spencer, J.W. (1971), <i>Fourier series representation of the position of the sun</i>；
 * 以及 NOAA Solar Calculator 使用的同一套系数。</p>
 */
public final class SolarDaylight {

    /** 一年中的天数（用于级数归一化，忽略闰年差异 —— 对温度曲线无影响）。 */
    private static final double DAYS_PER_YEAR = 365.0D;

    /** 最高温出现在太阳正午之后多少小时（地表热量收支的滞后）。 */
    public static final double PEAK_LAG_HOURS = 2.5D;

    private SolarDaylight() {
    }

    /** 太阳赤纬（度），正值表示太阳直射北半球。 */
    public static double declinationDegrees(double dayOfYear) {
        double gamma = 2.0D * Math.PI * (dayOfYear - 1.0D) / DAYS_PER_YEAR;
        double radians = 0.006918D
                - 0.399912D * Math.cos(gamma) + 0.070257D * Math.sin(gamma)
                - 0.006758D * Math.cos(2.0D * gamma) + 0.000907D * Math.sin(2.0D * gamma)
                - 0.002697D * Math.cos(3.0D * gamma) + 0.001480D * Math.sin(3.0D * gamma);
        return Math.toDegrees(radians);
    }

    /** 时差（分钟）：真太阳时 − 平太阳时，全年在 ±16 分钟内摆动。 */
    public static double equationOfTimeMinutes(double dayOfYear) {
        double gamma = 2.0D * Math.PI * (dayOfYear - 1.0D) / DAYS_PER_YEAR;
        return 229.18D * (0.000075D
                + 0.001868D * Math.cos(gamma) - 0.032077D * Math.sin(gamma)
                - 0.014615D * Math.cos(2.0D * gamma) - 0.040849D * Math.sin(2.0D * gamma));
    }

    /**
     * 当地「太阳正午」对应的钟点（0~24，当地时区钟表时间）。
     *
     * <p>两处修正：① 经度与时区中央经线的偏差（每度 4 分钟）；
     * ② 时差方程。少了这两项，太阳正午最多会偏出 1 小时以上。</p>
     */
    public static double solarNoonLocalHour(double longitude, int utcOffsetSeconds, double dayOfYear) {
        double standardMeridian = utcOffsetSeconds / 3600.0D * 15.0D;
        return 12.0D + (standardMeridian - longitude) / 15.0D - equationOfTimeMinutes(dayOfYear) / 60.0D;
    }

    /**
     * 昼长（小时）。极点附近返回 24（极昼）或 0（极夜）。
     *
     * <p>时角 {@code H = acos(−tanφ·tanδ)}，昼长 = 2H/15。</p>
     */
    public static double daylightHours(double latitude, double dayOfYear) {
        double phi = Math.toRadians(MathUtil.clamp(latitude, -89.9D, 89.9D));
        double delta = Math.toRadians(declinationDegrees(dayOfYear));
        double cosH = -Math.tan(phi) * Math.tan(delta);
        if (cosH <= -1.0D) {
            return 24.0D;      // 极昼
        }
        if (cosH >= 1.0D) {
            return 0.0D;       // 极夜
        }
        return 2.0D * Math.toDegrees(Math.acos(cosH)) / 15.0D;
    }

    /** 日出钟点（当地时区钟表时间）。极昼/极夜时退回太阳正午。 */
    public static double sunriseLocalHour(double latitude, double longitude, int utcOffsetSeconds,
                                          double dayOfYear) {
        double noon = solarNoonLocalHour(longitude, utcOffsetSeconds, dayOfYear);
        return noon - daylightHours(latitude, dayOfYear) / 2.0D;
    }

    /**
     * 昼夜温度曲线的形状函数，取值 {@code [-1, +1]}：
     * {@code -1} 是当日最低温（日出），{@code +1} 是当日最高温（太阳正午后 2.5 小时）。
     *
     * <p>用<b>两段半余弦</b>而不是一条完整余弦：升温段（日出→峰值）与降温段（峰值→次日日出）
     * 各自是半个余弦，因此最低温落在日出、而不是被强行按到「峰值前 12 小时」。</p>
     *
     * @param localHour 当地钟点 0~24
     * @param sunrise   日出钟点（可超出 0~24 范围，函数内部按 24 小时循环处理）
     * @param peak      峰值钟点（= 太阳正午 + 2.5 小时）
     */
    public static double shape(double localHour, double sunrise, double peak) {
        // 归一到「日出 ≤ localHour < 日出 + 24」这个周期里
        double t = localHour;
        while (t < sunrise) {
            t += 24.0D;
        }
        while (t >= sunrise + 24.0D) {
            t -= 24.0D;
        }
        double peakInCycle = peak;
        while (peakInCycle < sunrise) {
            peakInCycle += 24.0D;
        }
        if (t <= peakInCycle) {
            double span = peakInCycle - sunrise;
            double f = span <= 1.0E-6D ? 1.0D : (t - sunrise) / span;
            return -Math.cos(Math.PI * MathUtil.clamp(f, 0.0D, 1.0D));   // 升温段：-1 → +1
        }
        double span = sunrise + 24.0D - peakInCycle;
        double f = span <= 1.0E-6D ? 1.0D : (t - peakInCycle) / span;
        return Math.cos(Math.PI * MathUtil.clamp(f, 0.0D, 1.0D));        // 降温段：+1 → -1
    }

    /** 没有坐标时的兜底：固定峰值钟点的完整余弦（等价于老行为，仅用于降级路径）。 */
    public static double fallbackShape(double localHour, double peakHour) {
        return Math.cos(2.0D * Math.PI * (localHour - peakHour) / 24.0D);
    }
}

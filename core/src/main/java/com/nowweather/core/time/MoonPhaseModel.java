package com.nowweather.core.time;

import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;

/**
 * 真实世界的月相模型 —— 把「真实日期」换算成 Minecraft 的月相索引。
 *
 * <h2>为什么需要它</h2>
 * Minecraft 的月相是 {@code (dayTime / 24000) % 8}，也就是<b>世界天数 % 8</b>
 * （见 {@code DimensionType#moonPhase(long)}，已用字节码核对）。
 * 真实时间同步只保证「游戏内天数连续、且跟着真实日历走」，
 * 于是世界天数与<b>朔望月</b>之间没有任何关系 ——
 * 中秋节（农历八月十五）本该是满月，游戏里却可能是任意一个月相。
 *
 * <h2>算法</h2>
 * 用朔望月平均长度外推：以 {@code 2000-01-06 18:14 UTC}（儒略日 2451550.1）的新月为基准，
 * <pre>
 *   月龄（天） = (当前儒略日 − 2451550.1) mod 29.530588853
 * </pre>
 * 月龄 0 = 新月、14.765 = 满月。再映射到 MC 的 8 个相位：
 * <pre>
 *   MC 相位 = floor(月龄 / 29.530588853 × 8 + 4.5) mod 8
 * </pre>
 * 其中 {@code +4.5} 是因为 MC 的 <b>相位 0 是满月、相位 4 是新月</b>，与月龄的起点正好相差半个朔望月。
 *
 * <p>用平均朔望月而不是查表：真实满月与平均值的偏差在 ±0.7 天以内，
 * 折算到 MC 的 8 个相位（每相位 3.7 天）里几乎不会翻格，
 * 而查表要背几十年的历表，得不偿失。已用 2024-09-17（中秋节，满月）等已知日期校验。</p>
 */
public final class MoonPhaseModel {

    /** 朔望月平均长度（天）。来源：Meeus, <i>Astronomical Algorithms</i>。 */
    public static final double SYNODIC_MONTH_DAYS = 29.530588853D;

    /**
     * 参考新月时刻：2000-01-06 18:14 UTC。
     *
     * <p>对应儒略日 2451550.1 —— 这是天文算法里最常用的新月基准点。</p>
     */
    public static final long REFERENCE_NEW_MOON_EPOCH_MILLIS = 947_182_440_000L;

    /** 儒略日 2000-01-01 12:00 UTC 的 Unix 毫秒（用于把毫秒换算成儒略日）。 */
    private static final long J2000_EPOCH_MILLIS = 946_728_000_000L;

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * MC 月相索引的中文原文（0 = 满月，4 = 新月，与 Minecraft 的顺序一致）。
     *
     * <p>这里只作语言文件（{@code nowweather.moon_phase.0} … {@code .7}）的对照保留，
     * {@link #zhName(int)} 不再读这个数组。</p>
     */
    private static final String[] ZH_NAMES = {
            "满月", "亏凸月", "下弦月", "残月", "新月", "娥眉月", "上弦月", "盈凸月"
    };

    private MoonPhaseModel() {
    }

    /**
     * 当前的真实月龄（天）。0 = 新月，约 14.77 = 满月。
     */
    public static double ageDays(long epochMillis) {
        double days = (epochMillis - REFERENCE_NEW_MOON_EPOCH_MILLIS) / (double) MILLIS_PER_DAY;
        double age = days % SYNODIC_MONTH_DAYS;
        return age < 0.0D ? age + SYNODIC_MONTH_DAYS : age;
    }

    /**
     * 真实日期对应的 Minecraft 月相索引（0~7）。
     *
     * <p>与 {@code DimensionType#moonPhase(long)} 的取值域一致：
     * <b>0 = 满月，4 = 新月</b>。</p>
     */
    public static int phaseIndex(long epochMillis) {
        double turns = ageDays(epochMillis) / SYNODIC_MONTH_DAYS * 8.0D;
        // +4.5：MC 的相位 0 是满月，而月龄 0 是新月，两者相差半个朔望月（4 个相位）；
        // 取 4.5 而不是 4 是为了让「相位边界」落在半天处，避免月相在正午夜跳变。
        long index = (long) Math.floor(turns + 4.5D);
        return (int) MathUtil.floorMod(index, 8L);
    }

    /**
     * 月相索引的展示名。
     *
     * <p>方法名里的 {@code zh} 是历史包袱，<b>实际返回当前语言文本</b>；
     * 键名形如 {@code nowweather.moon_phase.0}（0 = 满月，4 = 新月）。</p>
     */
    public static String zhName(int phaseIndex) {
        long index = MathUtil.floorMod(phaseIndex, 8L);
        return Text.tr("nowweather.moon_phase." + index);
    }

    /**
     * MC 的 {@code moonPhase} 是否把「满月」算在索引 0 上。
     *
     * <p>用来自检 {@link #phaseIndex(long)} 的映射方向没搞反 ——
     * 反了的话中秋节会变成新月，是那种「看起来只是差一点」但完全错的 bug。</p>
     */
    public static boolean isFullMoon(int phaseIndex) {
        return MathUtil.floorMod(phaseIndex, 8L) == 0L;
    }

    /** 把 Unix 毫秒换算成儒略日（供测试与诊断使用）。 */
    public static double julianDay(long epochMillis) {
        return 2451545.0D + (epochMillis - J2000_EPOCH_MILLIS) / (double) MILLIS_PER_DAY;
    }
}

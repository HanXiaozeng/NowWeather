package com.nowweather.core.time;

import com.nowweather.core.util.MathUtil;

/**
 * 真实时间 → Minecraft 世界时间的换算模型。
 *
 * <h2>为什么不能照抄「秒数 / 3.6」</h2>
 * 原版的 {@code dayTime} 并不是「从午夜开始的刻数」：
 * <pre>
 *   dayTime = 0       → 游戏内 06:00（日出）
 *   dayTime = 6000    → 游戏内 12:00（正午）
 *   dayTime = 12000   → 游戏内 18:00（日落）
 *   dayTime = 18000   → 游戏内 00:00（午夜）
 * </pre>
 * 所以朴素的 {@code ticks = 自午夜秒数 / 3.6} 会让<b>真实午夜显示成清晨</b>。
 * 正确做法是再加上 18000 刻的相位偏移。本类把它写成了显式常量，并覆盖了四种边界。
 *
 * <h2>一天的长度</h2>
 * 原版一天 24000 刻 = 20 分钟；本模型让一天等于真实的 24 小时（86400 秒），
 * 也就是游戏内时钟与所在地的真实时钟一致 —— 太阳位置、月亮、生物生成时间全部跟着走。
 *
 * <h2>世界「天数」怎么办</h2>
 * 直接用真实日期当世界天数会让存档的「第几天」突然变成 20000 多天。
 * 所以初始化时记录一个<b>偏移</b>：{@code dayOffset = 当前世界天数 - 真实日期序号}，
 * 之后世界天数 = 真实日期序号 + 偏移 —— 既保持连续，又随真实日历推进（跨午夜自动 +1）。
 */
public final class RealTimeSyncModel {

    /** 一个 MC 日的刻数。 */
    public static final long TICKS_PER_DAY = 24000L;
    /** 真实一天的秒数。 */
    public static final long SECONDS_PER_DAY = 86400L;
    /** 真实一天的毫秒数。 */
    public static final long MILLIS_PER_DAY = SECONDS_PER_DAY * 1000L;
    /** 相位偏移：真实午夜对应 dayTime = 18000。 */
    public static final long MIDNIGHT_PHASE_TICKS = 18000L;

    private final long dayOffset;

    private RealTimeSyncModel(long dayOffset) {
        this.dayOffset = dayOffset;
    }

    /**
     * 用当前世界时间初始化（在服务器启动时调用一次）。
     *
     * @param currentDayTime   当前的世界总刻（{@code ServerLevel#getDayTime()}）
     * @param epochMillis      当前真实时间（UTC 毫秒）
     * @param utcOffsetSeconds 所在地 UTC 偏移（秒）—— 世界天数必须按<b>当地日历日</b>推进，
     *                         否则 UTC+8 的玩家会在当地下午看到天数提前 +1
     */
    public static RealTimeSyncModel initialize(long currentDayTime, long epochMillis, int utcOffsetSeconds) {
        return initialize(currentDayTime, epochMillis, utcOffsetSeconds, false);
    }

    /**
     * 同上，并可选地把<b>月相</b>也对齐到真实历法。
     *
     * <p>Minecraft 的月相就是「世界天数 % 8」
     * （{@code DimensionType#moonPhase(long)}：{@code dayTime / 24000 % 8}，0 = 满月、4 = 新月），
     * 而天数偏移只保证天数连续 —— 于是世界天数与朔望月毫无关系，中秋节可能不是满月。</p>
     *
     * <p>做法是把天数偏移再平移 0~7 天，使 {@code 世界天数 % 8} 等于真实月相索引。
     * 平移是幂等的：下一次启动时相位已经对上，算出来的平移量就是 0。</p>
     */
    public static RealTimeSyncModel initialize(long currentDayTime, long epochMillis, int utcOffsetSeconds,
                                               boolean syncMoonPhase) {
        long worldDayCount = Math.floorDiv(currentDayTime, TICKS_PER_DAY);
        long offset = worldDayCount - localDayNumber(epochMillis, utcOffsetSeconds);
        if (syncMoonPhase) {
            long baseDay = localDayNumber(epochMillis, utcOffsetSeconds) + offset;
            long currentPhase = MathUtil.floorMod(baseDay, 8L);
            long targetPhase = MoonPhaseModel.phaseIndex(epochMillis);
            offset += MathUtil.floorMod(targetPhase - currentPhase, 8L);
        }
        return new RealTimeSyncModel(offset);
    }

    /** 所在地的「日历日序号」（按当地时区计算，这是世界天数该跟随的基准）。 */
    public static long localDayNumber(long epochMillis, int utcOffsetSeconds) {
        return Math.floorDiv(epochMillis + utcOffsetSeconds * 1000L, MILLIS_PER_DAY);
    }

    /**
     * 世界总刻对应的 Minecraft 月相索引（0 = 满月，4 = 新月）。
     *
     * <p>与 {@code DimensionType#moonPhase(long)} 完全一致：{@code (dayTime / 24000) % 8}。</p>
     */
    public static int moonPhaseOf(long totalDayTime) {
        return (int) MathUtil.floorMod(Math.floorDiv(totalDayTime, TICKS_PER_DAY), 8L);
    }

    public long dayOffset() {
        return dayOffset;
    }

    /**
     * 真实时刻在「一天之内」对应的 MC 刻数（0 ~ 23999，其中 0 = 游戏内 06:00）。
     */
    public static long ticksOfDay(long epochMillis, int utcOffsetSeconds) {
        long localSeconds = MathUtil.floorMod(Math.floorDiv(epochMillis, 1000L) + utcOffsetSeconds, SECONDS_PER_DAY);
        // 86400 秒 → 24000 刻，即 3.6 秒/刻
        long ticksSinceMidnight = Math.round(localSeconds / 3.6D);
        return MathUtil.floorMod(ticksSinceMidnight + MIDNIGHT_PHASE_TICKS, TICKS_PER_DAY);
    }

    /**
     * 计算应当写入世界的 {@code dayTime}。
     *
     * @param epochMillis      当前真实时间
     * @param utcOffsetSeconds 所在地 UTC 偏移（秒）
     */
    public long compute(long epochMillis, int utcOffsetSeconds) {
        long dayCount = localDayNumber(epochMillis, utcOffsetSeconds) + dayOffset;
        return dayCount * TICKS_PER_DAY + ticksOfDay(epochMillis, utcOffsetSeconds);
    }

    /** 所在地当天的本地小时/分钟（用于 HUD 与日志）。 */
    public static int[] localHourMinute(long epochMillis, int utcOffsetSeconds) {
        long localSeconds = MathUtil.floorMod(Math.floorDiv(epochMillis, 1000L) + utcOffsetSeconds, SECONDS_PER_DAY);
        return new int[]{(int) (localSeconds / 3600L), (int) ((localSeconds % 3600L) / 60L)};
    }

    /** 由 MC 刻数反推游戏内钟点（0~23），供日志/命令展示。 */
    public static int inGameHour(long ticksOfDay) {
        long shifted = MathUtil.floorMod(ticksOfDay - MIDNIGHT_PHASE_TICKS, TICKS_PER_DAY);
        return (int) (Math.round(shifted / 1000.0D) % 24);
    }

    /**
     * 太阳高度角的近似（-1 ~ 1），可用于按真实时间调整光照/温度曲线。
     *
     * @param ticksOfDay {@link #ticksOfDay}
     */
    public static double sunHeight(long ticksOfDay) {
        // 正午（6000 刻）最高，午夜（18000 刻）最低
        double phase = (ticksOfDay - 6000L) / (double) TICKS_PER_DAY * 2.0D * Math.PI;
        return Math.cos(phase);
    }
}

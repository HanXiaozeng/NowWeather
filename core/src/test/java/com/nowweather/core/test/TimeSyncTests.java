package com.nowweather.core.test;

import com.nowweather.core.time.RealTimeSyncModel;
import com.nowweather.core.time.TimeZoneService;

/**
 * 真实时间同步测试。
 *
 * <p>核心是「真实 24 小时 ↔ Minecraft 24000 刻」的换算。这里刻意把四种边界时刻都钉死 ——
 * 因为最容易踩的坑就是相位：原版的 {@code dayTime = 0} 是游戏内 <b>06:00</b> 而不是 00:00，
 * 直接用「自午夜秒数 / 3.6」会让真实午夜显示成清晨（参考实现就是这么写的）。</p>
 */
final class TimeSyncTests {

    /** 2026-09-13 00:00:00 UTC 的毫秒时间戳。 */
    private static final long UTC_MIDNIGHT = 1789257600000L;

    private TimeSyncTests() {
    }

    static void run() {
        phaseMapping();
        dayRollover();
        offsets();
        timeZoneService();
        clientAnchor();
        moonPhase();
    }

    /**
     * 月相跟随真实历法。
     *
     * <p>回归用例：Minecraft 的月相就是「世界天数 % 8」（0 = 满月、4 = 新月），
     * 而天数偏移只保证天数连续 —— 于是中秋节可能不是满月。</p>
     */
    private static void moonPhase() {
        T.section("月相跟随真实历法");
        int cst = 8 * 3600;

        // 已知事实校验：2024-09-17 是中秋节，当晚满月
        long midAutumn2024 = epochOf("2024-09-17T12:00:00Z");
        double age = com.nowweather.core.time.MoonPhaseModel.ageDays(midAutumn2024);
        T.check("2024 中秋节月龄接近 14.77 天（实际 " + String.format(java.util.Locale.ROOT, "%.2f", age) + "）",
                Math.abs(age - 14.765D) < 1.5D);
        T.check("2024 中秋节 → 满月相位 0",
                com.nowweather.core.time.MoonPhaseModel.isFullMoon(
                        com.nowweather.core.time.MoonPhaseModel.phaseIndex(midAutumn2024)));
        T.eq("满月的中文名", "满月",
                com.nowweather.core.time.MoonPhaseModel.zhName(
                        com.nowweather.core.time.MoonPhaseModel.phaseIndex(midAutumn2024)));

        // 方向不能反：新月必须是相位 4
        long newMoon = com.nowweather.core.time.MoonPhaseModel.REFERENCE_NEW_MOON_EPOCH_MILLIS;
        T.check("基准新月月龄为 0",
                Math.abs(com.nowweather.core.time.MoonPhaseModel.ageDays(newMoon)) < 1.0E-6D);
        T.eq("新月 → 相位 4", 4, com.nowweather.core.time.MoonPhaseModel.phaseIndex(newMoon));
        T.eq("新月的中文名", "新月", com.nowweather.core.time.MoonPhaseModel.zhName(4));

        // 相位索引必须落在 0~7
        int bad = 0;
        for (long ms = midAutumn2024; ms < midAutumn2024 + 40L * 86_400_000L; ms += 3_600_000L) {
            int phase = com.nowweather.core.time.MoonPhaseModel.phaseIndex(ms);
            if (phase < 0 || phase > 7) {
                bad++;
            }
        }
        T.eq("40 天内每小时取样的相位都在 0~7", 0, bad);

        // 一个朔望月正好走完 8 个相位
        long monthLater = midAutumn2024
                + (long) (com.nowweather.core.time.MoonPhaseModel.SYNODIC_MONTH_DAYS * 86_400_000D);
        T.eq("一个朔望月后回到同一相位",
                com.nowweather.core.time.MoonPhaseModel.phaseIndex(midAutumn2024),
                com.nowweather.core.time.MoonPhaseModel.phaseIndex(monthLater));

        // ★ 端到端：对齐之后，游戏内的月相必须等于真实月相
        long worldDayTime = 100L * 24000L;
        RealTimeSyncModel aligned = RealTimeSyncModel.initialize(worldDayTime, midAutumn2024, cst, true);
        long nowTicks = aligned.compute(midAutumn2024, cst);
        T.eq("对齐后游戏内月相 = 真实月相（满月）",
                com.nowweather.core.time.MoonPhaseModel.phaseIndex(midAutumn2024),
                RealTimeSyncModel.moonPhaseOf(nowTicks));

        // 平移量必须只有 0~7 天（不能把世界天数挪走几个星期）
        RealTimeSyncModel plain = RealTimeSyncModel.initialize(worldDayTime, midAutumn2024, cst, false);
        long shift = com.nowweather.core.util.MathUtil.floorMod(
                aligned.dayOffset() - plain.dayOffset(), 8L);
        T.check("月相平移量在 0~7 天之间（实际 " + shift + " 天）", shift >= 0L && shift <= 7L);

        // 幂等：用已经对齐过的世界时间再算一次，平移量必须是 0
        RealTimeSyncModel again = RealTimeSyncModel.initialize(nowTicks, midAutumn2024, cst, true);
        T.eq("二次初始化不再平移（幂等）", 0L, com.nowweather.core.util.MathUtil.floorMod(
                again.dayOffset() - aligned.dayOffset(), 8L));
        T.eq("二次初始化后月相仍然正确",
                com.nowweather.core.time.MoonPhaseModel.phaseIndex(midAutumn2024),
                RealTimeSyncModel.moonPhaseOf(again.compute(midAutumn2024, cst)));

        // 关闭开关时必须保持旧行为（不做任何平移）
        T.eq("关闭月相同步时不平移天数", plain.dayOffset(),
                RealTimeSyncModel.initialize(worldDayTime, midAutumn2024, cst, false).dayOffset());
    }

    private static long epochOf(String iso) {
        return java.time.Instant.parse(iso).toEpochMilli();
    }

    /**
     * 客户端锚点外推。
     *
     * <p>回归用例：客户端原版日光循环每刻 +1 刻，而真实时间同步下每刻应当是 3.6 秒；</p>
     * 只靠服务端周期校正会让天空「隔几秒往前挪一下又跳回去」。现在客户端拿一个锚点
     * （真实毫秒 + 当时世界刻）自己外推，所以这里钉死外推的正确性。
     */
    private static void clientAnchor() {
        T.section("客户端时间锚点外推（消除天空回跳）");
        var anchor = new com.nowweather.core.sync.SyncPayload.TimeAnchor(UTC_MIDNIGHT, 18000L);

        T.near("一刻 = 3600 毫秒", 3600.0D,
                com.nowweather.core.sync.SyncPayload.TimeAnchor.MILLIS_PER_TICK, 1.0E-9);
        T.eq("锚点时刻原样返回", 18000L, anchor.dayTimeAt(UTC_MIDNIGHT));
        T.eq("1 小时后 +1000 刻", 19000L, anchor.dayTimeAt(UTC_MIDNIGHT + 3600_000L));
        T.eq("6 小时后 +6000 刻", 24000L, anchor.dayTimeAt(UTC_MIDNIGHT + 6 * 3600_000L));
        T.eq("早于锚点不倒退", 18000L, anchor.dayTimeAt(UTC_MIDNIGHT - 5000L));

        // 外推曲线必须单调、且每 3.6 秒恰好 +1 刻（没有任何回跳）
        long previous = anchor.dayTimeAt(UTC_MIDNIGHT);
        boolean monotonic = true;
        int steps = 0;
        for (long ms = 0L; ms <= 3600_000L; ms += 100L) {
            long value = anchor.dayTimeAt(UTC_MIDNIGHT + ms);
            if (value < previous) {
                monotonic = false;
                break;
            }
            if (value > previous) {
                steps += (int) (value - previous);
            }
            previous = value;
        }
        T.check("外推严格单调（不会回跳）", monotonic);
        T.eq("1 小时恰好走 1000 刻", 1000, steps);

        // 与服务端模型的一致性：锚点外推 ≈ 服务端直接算
        int cst = 8 * 3600;
        RealTimeSyncModel model = RealTimeSyncModel.initialize(100L * 24000L, UTC_MIDNIGHT, cst);
        long serverNow = UTC_MIDNIGHT + 12 * 3600_000L + 1234L;
        var liveAnchor = new com.nowweather.core.sync.SyncPayload.TimeAnchor(UTC_MIDNIGHT,
                model.compute(UTC_MIDNIGHT, cst));
        long drift = Math.abs(liveAnchor.dayTimeAt(serverNow) - model.compute(serverNow, cst));
        T.check("外推与服务端模型偏差 ≤ 1 刻（实测 " + drift + "）", drift <= 1L);
    }

    private static void phaseMapping() {
        T.section("真实时刻 → Minecraft 刻（相位必须正确）");

        // 东八区，用 UTC+8 的当地钟点验证
        int cst = 8 * 3600;

        // 当地 00:00 → 游戏内 00:00 → dayTime 18000
        T.eq("当地 00:00 → dayTime 18000", 18000L,
                RealTimeSyncModel.ticksOfDay(UTC_MIDNIGHT - 8 * 3600_000L, cst));
        // 当地 06:00 → 游戏内 06:00 → dayTime 0
        T.eq("当地 06:00 → dayTime 0", 0L,
                RealTimeSyncModel.ticksOfDay(UTC_MIDNIGHT - 2 * 3600_000L, cst));
        // 当地 12:00 → 游戏内 12:00 → dayTime 6000
        T.eq("当地 12:00 → dayTime 6000", 6000L,
                RealTimeSyncModel.ticksOfDay(UTC_MIDNIGHT + 4 * 3600_000L, cst));
        // 当地 18:00 → 游戏内 18:00 → dayTime 12000
        T.eq("当地 18:00 → dayTime 12000", 12000L,
                RealTimeSyncModel.ticksOfDay(UTC_MIDNIGHT + 10 * 3600_000L, cst));

        // 反查：dayTime → 游戏内钟点
        T.eq("dayTime 0 → 06:00", 6, RealTimeSyncModel.inGameHour(0L));
        T.eq("dayTime 6000 → 12:00", 12, RealTimeSyncModel.inGameHour(6000L));
        T.eq("dayTime 12000 → 18:00", 18, RealTimeSyncModel.inGameHour(12000L));
        T.eq("dayTime 18000 → 00:00", 0, RealTimeSyncModel.inGameHour(18000L));

        // 一天的总刻数与真实一天一致
        long t0 = RealTimeSyncModel.ticksOfDay(UTC_MIDNIGHT - 8 * 3600_000L, cst);
        long t1 = RealTimeSyncModel.ticksOfDay(UTC_MIDNIGHT - 8 * 3600_000L + 86400_000L, cst);
        T.eq("真实 24 小时后回到同一刻", t0, t1);

        // 太阳高度：正午最高、午夜最低
        T.check("正午太阳高度最高", RealTimeSyncModel.sunHeight(6000L) > 0.99D);
        T.check("午夜太阳高度最低", RealTimeSyncModel.sunHeight(18000L) < -0.99D);
        T.check("日出附近接近 0", Math.abs(RealTimeSyncModel.sunHeight(0L)) < 0.01D);

        // 当地钟点解析
        int[] hm = RealTimeSyncModel.localHourMinute(UTC_MIDNIGHT + 5 * 3600_000L + 30 * 60_000L, cst);
        T.eq("当地 13:30 的小时", 13, hm[0]);
        T.eq("当地 13:30 的分钟", 30, hm[1]);
    }

    private static void dayRollover() {
        T.section("跨午夜时世界天数连续递增");
        int cst = 8 * 3600;
        // 初始化：世界时间 = 第 100 天的 06:00
        RealTimeSyncModel model = RealTimeSyncModel.initialize(100L * 24000L, UTC_MIDNIGHT, cst);

        long before = model.compute(UTC_MIDNIGHT - 8 * 3600_000L + 23 * 3600_000L, cst); // 当地 23:00
        long after = model.compute(UTC_MIDNIGHT - 8 * 3600_000L + 25 * 3600_000L, cst);  // 当地次日 01:00

        T.check("午夜前的天数 = 100", before / 24000L == 100L);
        T.eq("午夜前是 23 点", 23, RealTimeSyncModel.inGameHour(before % 24000L));
        T.check("跨越午夜后天数 +1", after / 24000L == 101L);
        T.eq("午夜后是 1 点", 1, RealTimeSyncModel.inGameHour(after % 24000L));
        // 差值 = 一天(24000 刻) + 当地 23:00 → 次日 01:00 的 2 小时(2000 刻)
        T.check("世界时间差值 = 一天 + 2 小时",
                Math.abs((after - before) - (24000L + 2000L)) < 400L);
        // 偏移是「世界天数 - 当地日历日序号」，只要保持一致即可（存档天数不会被真实日期顶掉）
        T.eq("初始化时世界天数保持原值", 100L,
                (RealTimeSyncModel.localDayNumber(UTC_MIDNIGHT, cst) + model.dayOffset()));
    }

    private static void offsets() {
        T.section("时区偏移与纬度兜底");
        // 同一真实时刻，在不同时区应当得到不同的游戏内钟点
        long noonUtc = UTC_MIDNIGHT + 12 * 3600_000L;
        T.eq("UTC+0 的 12:00 → dayTime 6000", 6000L, RealTimeSyncModel.ticksOfDay(noonUtc, 0));
        T.eq("UTC+8 的 20:00 → dayTime 14000", 14000L,
                RealTimeSyncModel.ticksOfDay(noonUtc, 8 * 3600));
        T.eq("UTC-5 的 07:00 → dayTime 1000", 1000L,
                RealTimeSyncModel.ticksOfDay(noonUtc, -5 * 3600));

        // 由经度估算时区（离线兜底）：每 15° = 1 小时
        T.eq("经度 120° → UTC+8", 8 * 3600,
                TimeZoneService.estimateOffsetSecondsFromLongitude(120.1551D));
        T.eq("经度 0° → UTC+0", 0, TimeZoneService.estimateOffsetSecondsFromLongitude(0.0D));
        T.eq("经度 -75° → UTC-5", -5 * 3600,
                TimeZoneService.estimateOffsetSecondsFromLongitude(-75.0D));
        T.eq("经度未知 → 未设置", TimeZoneService.UNSET,
                TimeZoneService.estimateOffsetSecondsFromLongitude(Double.NaN));

        T.eq("偏移格式化（正）", "GMT+8:00", TimeZoneService.formatOffset(8 * 3600));
        T.eq("偏移格式化（负）", "GMT-5:00", TimeZoneService.formatOffset(-5 * 3600));
        T.eq("偏移格式化（半小时）", "GMT+5:30", TimeZoneService.formatOffset(5 * 3600 + 1800));
    }

    private static void timeZoneService() {
        T.section("时区来源优先级（手动 > 天气接口 > 系统）");

        TimeZoneService service = new TimeZoneService();
        T.eq("默认取系统时区", "系统时区", service.source());

        service.applyFromProvider(8 * 3600, "Asia/Shanghai", "浙江杭州");
        T.eq("有接口数据后优先用接口", "天气接口（浙江杭州）", service.source());
        T.eq("时区名使用 IANA 名", "Asia/Shanghai", service.zoneName());
        T.eq("生效偏移", 8 * 3600, service.effectiveOffsetSeconds());

        // 离线重启：从缓存恢复时区
        TimeZoneService restored = new TimeZoneService();
        restored.restoreFromCache(8 * 3600, "Asia/Shanghai", "浙江杭州");
        T.eq("缓存恢复后离线仍知道时区", 8 * 3600, restored.effectiveOffsetSeconds());

        // 手动配置优先级最高
        service.setManualOffsetMinutes(330);
        T.eq("手动配置覆盖接口", "手动配置", service.source());
        T.eq("手动配置 330 分钟 = UTC+5:30", 5 * 3600 + 1800, service.effectiveOffsetSeconds());

        // 清除手动值后回到接口来源
        service.setManualOffsetMinutes(TimeZoneService.UNSET);
        T.eq("清除手动值后回到接口来源", "天气接口（浙江杭州）", service.source());

        // 接口没给时区时不覆盖已有值
        service.applyFromProvider(TimeZoneService.UNSET, null, null);
        T.eq("接口未提供时区时不覆盖", 8 * 3600, service.effectiveOffsetSeconds());
    }
}

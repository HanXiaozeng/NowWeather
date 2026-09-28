package com.nowweather.core.test;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.terrain.AtmosphericFormulas;
import com.nowweather.core.terrain.TerrainProfile;
import com.nowweather.core.terrain.TerrainTransfer;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

/**
 * 地形订正测试。
 *
 * <p>对照的是<b>可独立查证的大气物理量</b>（标准大气递减率、ISA 气压表、Magnus 饱和水汽压），
 * 而不是实现自己的输出 —— 否则测试只会固化错误。</p>
 */
final class TerrainTests {

    private TerrainTests() {
    }

    static void run() {
        T.section("标准大气公式");
        formulas();

        T.section("递减率选择：干绝热 ↔ 湿绝热");
        lapseRate();

        T.section("杭州平原 → 1500m 高原（用户提出的场景）");
        hangzhouToPlateau();

        T.section("夜间冷池 / 风 / 地形抬升降水");
        terrainEffects();
    }

    private static WeatherObservation hangzhou() {
        return WeatherObservation.builder("test")
                .weatherType(WeatherType.CLEAR)
                .conditionText("晴")
                .temperatureC(27.0D)
                .humidityPercent(60.0D)
                .pressureHpa(1006.0D)
                .windScale(2)
                .build();
    }

    private static LocationProfile hangzhouProfile() {
        return LocationProfile.empty().withCoordinates(30.274D, 120.155D)
                .withUtcOffset(28800).withElevation(13.0D);
    }

    private static void formulas() {
        // Magnus：0°C 饱和水汽压 6.112 hPa、20°C 约 23.4、100°C 约 1013
        T.near("0°C 饱和水汽压 ≈ 6.11 hPa", 6.11D,
                AtmosphericFormulas.saturationVapourPressureHpa(0.0D), 0.05D);
        T.near("20°C 饱和水汽压 ≈ 23.4 hPa", 23.4D,
                AtmosphericFormulas.saturationVapourPressureHpa(20.0D), 0.3D);
        // ★ Magnus 形式（Alduchov & Eskridge 1996）的标定区间是 -40~50°C，
        // 超出后会偏离：100°C 时它给 1041 hPa，而真实值是 1013 hPa。
        // 因此这里只断言到 50°C（在区间内），并把区间限制写成测试，防止有人拿它算蒸汽。
        T.near("50°C 饱和水汽压 ≈ 123.4 hPa", 123.4D,
                AtmosphericFormulas.saturationVapourPressureHpa(50.0D), 1.5D);
        T.check("Magnus 在 100°C 会偏离（已知限制：标定区间 -40~50°C）",
                AtmosphericFormulas.saturationVapourPressureHpa(100.0D) > 1030.0D);

        // 露点往返：27°C / 60% → 约 18.4°C，再反算回 60%
        double dp = AtmosphericFormulas.dewPointC(27.0D, 0.60D);
        T.near("27°C/60% 的露点 ≈ 18.4°C", 18.4D, dp, 0.3D);
        T.near("露点反算回相对湿度 = 60%", 0.60D,
                AtmosphericFormulas.relativeHumidity(27.0D, dp), 0.005D);
        T.near("饱和时露点 = 气温", 15.0D,
                AtmosphericFormulas.dewPointC(15.0D, 1.0D), 0.05D);

        // ISA：1500m ≈ 845 hPa，3000m ≈ 701 hPa（标准大气表）
        T.near("1500m 气压 ≈ 845 hPa", 845.0D,
                AtmosphericFormulas.pressureAtHpa(1013.25D, 1500.0D), 3.0D);
        T.near("3000m 气压 ≈ 701 hPa", 701.0D,
                AtmosphericFormulas.pressureAtHpa(1013.25D, 3000.0D), 3.0D);
        // 相对高度形式：参考点就用自己的实测气压
        T.near("13m/1006hPa → 1500m ≈ 840 hPa", 840.0D,
                AtmosphericFormulas.pressureAtHeightHpa(1006.0D, 13.0D, 1500.0D), 5.0D);
    }

    private static void lapseRate() {
        double dry = TerrainTransfer.effectiveLapseRate(0.20D, WeatherType.CLEAR, 500.0D);
        double humid = TerrainTransfer.effectiveLapseRate(0.90D, WeatherType.CLEAR, 500.0D);
        double saturated = TerrainTransfer.effectiveLapseRate(0.60D, WeatherType.RAIN, 500.0D);
        T.check("干燥气团接近干绝热（" + fmt(dry) + " K/km > 8）", dry > 8.0D);
        T.check("潮湿气团明显低于干绝热（" + fmt(humid) + " < " + fmt(dry) + "）", humid < dry);
        T.check("正在降水按饱和处理（" + fmt(saturated) + " < 6）", saturated < 6.0D);
        T.check("递减率恒在 4.5~9.8 内",
                dry <= 9.8D && saturated >= 4.5D);
        // 高原折减
        // 昼夜项（Mote, Lundquist & Minder 2009 实测：夏季白天 ≈-7、夜间 ≈-2.5、其余 ≈-5 K/km）。
        // 注意：早期版本里有一条「高原递减率折减」，调研后已删除 ——
        // 没有任何文献给出「递减率随海拔下降 X/km」的公式，真正起作用的是昼夜而不是海拔。
        double day = TerrainTransfer.effectiveLapseRate(0.60D, WeatherType.CLEAR, 1500.0D, 13.0D);
        double night = TerrainTransfer.effectiveLapseRate(0.60D, WeatherType.CLEAR, 1500.0D, 2.0D);
        double dawn = TerrainTransfer.effectiveLapseRate(0.60D, WeatherType.CLEAR, 1500.0D, 19.0D);
        T.check("白天递减率明显大于夜间（" + fmt(day) + " > " + fmt(night) + "）", day > night);
        T.check("夜间递减率被压到白天的 45% 左右（" + fmt(night) + " vs " + fmt(day) + "）",
                night < day * 0.55D);
        T.check("傍晚 19:00 介于两者之间（过渡时段）", dawn > night && dawn < day);
        T.check("昼夜项不改变「湿度越高递减率越小」的结论",
                TerrainTransfer.effectiveLapseRate(0.20D, WeatherType.CLEAR, 1500.0D, 13.0D)
                        > TerrainTransfer.effectiveLapseRate(0.90D, WeatherType.CLEAR, 1500.0D, 13.0D));
    }

    private static void hangzhouToPlateau() {
        TerrainProfile plateau = new TerrainProfile(1500.0D, 300.0D, 0.75D, false, false, false);
        // 当地 14:00（白天，递减率取白天分支）
        long noon = java.time.ZonedDateTime.of(2026, 9, 13, 14, 0, 0, 0,
                java.time.ZoneOffset.ofHours(8)).toInstant().toEpochMilli();
        long midnight = java.time.ZonedDateTime.of(2026, 9, 13, 2, 0, 0, 0,
                java.time.ZoneOffset.ofHours(8)).toInstant().toEpochMilli();
        TerrainTransfer.Result r = TerrainTransfer.transfer(hangzhou(), hangzhouProfile(), plateau,
                noon, 28800);
        WeatherObservation out = r.observation();

        double drop = 27.0D - out.temperatureC();
        double expected = r.lapseRate() * 1.487D;   // Δh = 1487 m
        T.near("高原降温 = 递减率 × 1.487km（" + fmt(drop) + "°C）", expected, drop, 0.6D);
        T.check("白天：1500m 高原比杭州平原低 8~13°C（实际 " + fmt(drop) + "°C）", drop > 8.0D && drop < 13.0D);
        // 同一个地点、同一个海拔差，夜间降温明显更小（边界层稳定）
        double nightDrop = 27.0D - TerrainTransfer.transfer(hangzhou(), hangzhouProfile(), plateau,
                midnight, 28800).observation().temperatureC();
        T.check("夜间同样的高差降温更小（" + fmt(nightDrop) + " < " + fmt(drop) + "）", nightDrop < drop);
        T.check("夜间降温仍在合理量级（3~9°C，实际 " + fmt(nightDrop) + "°C）",
                nightDrop > 3.0D && nightDrop < 9.0D);

        // 露点守恒 → 相对湿度升高（山地云雾的物理原因）
        T.check("相对湿度随海拔升高（" + fmt(hangzhou().humidityPercent())
                + "% → " + fmt(out.humidityPercent()) + "%）",
                out.humidityPercent() > hangzhou().humidityPercent());
        T.check("订正后相对湿度不超过 100%", out.humidityPercent() <= 100.0D + 1.0E-6D);

        // 气压下降
        T.check("气压随海拔下降（1006 → " + fmt(out.pressureHpa()) + " hPa）",
                out.pressureHpa() < 900.0D && out.pressureHpa() > 800.0D);

        // 解释串必须能说清每一项
        String why = r.explanation();
        T.check("解释串包含参考/目标海拔", why.contains("13m") && why.contains("1500m"));
        T.check("解释串包含递减率", why.contains("递减率"));
        T.check("解释串包含露点", why.contains("露点"));

        // 反方向：高原 → 平原 应升温
        TerrainTransfer.Result back = TerrainTransfer.transfer(out, hangzhouProfile(), plateau,
                noon, 28800);
        T.check("从高原推回平原会升温（负号生效）",
                back.observation().temperatureC() < out.temperatureC());

        // 雨 → 雪：把杭州的雨搬到零下的高山
        WeatherObservation rainy = WeatherObservation.builder("test")
                .weatherType(WeatherType.RAIN).conditionText("中雨")
                .temperatureC(6.0D).humidityPercent(90.0D).windScale(2).build();
        TerrainProfile highPeak = new TerrainProfile(3200.0D, 600.0D, 0.95D, false, false, false);
        WeatherObservation snow = TerrainTransfer.transfer(rainy, hangzhouProfile(), highPeak,
                noon, 28800).observation();
        T.check("6°C 的雨搬到 3200m 山顶变成雪（实际 " + snow.weatherType().zhName()
                + " " + fmt(snow.temperatureC()) + "°C）",
                snow.weatherType().isFrozen() || snow.temperatureC() < 0.0D);

        // 中性地形不做任何改动
        TerrainTransfer.Result none = TerrainTransfer.transfer(hangzhou(), hangzhouProfile(),
                TerrainProfile.NONE, noon, 28800);
        T.near("地形未知时温度不变", 27.0D, none.observation().temperatureC(), 1.0E-9D);
        T.check("地形未知时解释串为空（不产生噪声）", none.explanation().isEmpty());
    }

    private static void terrainEffects() {
        long night = 1789300681445L - 6L * 3600L * 1000L;  // 当地 14:00 往前 6 小时 → 08:00
        long dusk = 1789300681445L + 6L * 3600L * 1000L;   // 20:00
        TerrainProfile valley = new TerrainProfile(400.0D, 120.0D, 0.1D, true, false, false);
        WeatherObservation calm = WeatherObservation.builder("test")
                .weatherType(WeatherType.CLEAR).conditionText("晴")
                .temperatureC(5.0D).humidityPercent(70.0D).windScale(1).build();
        WeatherObservation windy = WeatherObservation.builder(calm).windScale(6).build();

        T.check("夜间 + 谷底 + 静风 → 有冷池",
                TerrainTransfer.coldPoolPenaltyC(valley, calm, dusk, 28800) > 0.5D);
        T.check("白天 → 无冷池",
                TerrainTransfer.coldPoolPenaltyC(valley, calm, night, 28800) == 0.0D);
        T.check("风大 → 冷池被混合掉",
                TerrainTransfer.coldPoolPenaltyC(valley, windy, dusk, 28800) == 0.0D);
        T.check("水面 → 无冷池", TerrainTransfer.coldPoolPenaltyC(
                new TerrainProfile(400.0D, 120.0D, 0.1D, true, false, true), calm, dusk, 28800) == 0.0D);
        T.check("冷池不超过设计上限 4K",
                TerrainTransfer.coldPoolPenaltyC(valley, calm, dusk, 28800)
                        <= TerrainTransfer.COLD_POOL_MAX_C + 1.0E-9D);

        // 风：山脊 > 平地 > 山谷
        TerrainProfile ridge = new TerrainProfile(1500.0D, 400.0D, 1.0D, false, false, false);
        TerrainProfile flat = new TerrainProfile(100.0D, 10.0D, 0.5D, false, false, false);
        double ridgeFactor = TerrainTransfer.windFactor(ridge, calm, 0.0D);
        double flatFactor = TerrainTransfer.windFactor(flat, calm, 0.0D);
        double valleyFactor = TerrainTransfer.windFactor(valley, calm, 0.0D);
        T.check("山脊加速 > 平地 > 山谷遮蔽（" + fmt(ridgeFactor) + " / " + fmt(flatFactor)
                + " / " + fmt(valleyFactor) + "）",
                ridgeFactor > flatFactor && flatFactor > valleyFactor);
        T.check("山谷倍率不低于设计下限", valleyFactor >= TerrainTransfer.WIND_VALLEY_MIN - 1.0E-9D);

        // 地形抬升降水
        double up = TerrainTransfer.orographicFactor(ridge, 900.0D, 0.7D);
        double down = TerrainTransfer.orographicFactor(ridge, -900.0D, 0.7D);
        T.check("迎风抬升增强降水（×" + fmt(up) + "）", up > 1.2D);
        T.check("下沉侧衰减降水（×" + fmt(down) + "）", down < 1.0D);
        T.check("增强倍率不超过上限", up <= TerrainTransfer.OROGRAPHIC_MAX_FACTOR + 1.0E-9D);
        T.check("衰减倍率不低于下限", down >= TerrainTransfer.OROGRAPHIC_MIN_FACTOR - 1.0E-9D);
        T.check("干燥气团的抬升增强明显弱于湿气团",
                TerrainTransfer.orographicFactor(ridge, 900.0D, 0.2D) < up);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}

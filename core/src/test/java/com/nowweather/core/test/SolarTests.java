package com.nowweather.core.test;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.climate.TempRange;
import com.nowweather.core.forecast.OfflineWeatherSynthesizer;
import com.nowweather.core.time.SolarDaylight;

import java.util.List;

/**
 * 太阳位置 / 昼长 / 昼夜温度曲线测试。
 *
 * <p>这一组测试针对一个具体的质量缺陷：早期实现用「一条以 15:00 为峰值的完整余弦」
 * 表示昼夜温度，等价于断言<b>最低温永远出现在 03:00</b>。真实世界的最低温在<b>日出</b>前后，
 * 中纬度冬季日出约 08:00，于是夜间分支有 5 小时的相位差。</p>
 *
 * <p>因此这里对照的是<b>可独立查证的天文事实</b>（赤纬、昼长、极昼极夜），
 * 而不是实现自己的输出 —— 否则测试只会固化错误。</p>
 */
final class SolarTests {

    /** 夏至（北半球），2026 年 6 月 21 日。 */
    private static final double SUMMER_SOLSTICE_DOY = 172.0D;
    /** 冬至（北半球），2026 年 12 月 21 日。 */
    private static final double WINTER_SOLSTICE_DOY = 355.0D;
    /** 春分。 */
    private static final double EQUINOX_DOY = 80.0D;

    private SolarTests() {
    }

    static void run() {
        T.section("太阳赤纬（Spencer 1971）");
        declination();

        T.section("昼长与极昼极夜");
        daylight();

        T.section("昼夜温度曲线：最低温在日出而不是 03:00");
        diurnalShape();

        T.section("离线温度曲线的天文输入");
        offlineIntegration();
    }

    private static void declination() {
        // 夏至赤纬 ≈ +23.44°（地轴倾角），冬至 ≈ -23.44°，春分 ≈ 0°
        T.near("夏至赤纬 = +23.44°", 23.44D,
                SolarDaylight.declinationDegrees(SUMMER_SOLSTICE_DOY), 0.35D);
        T.near("冬至赤纬 = -23.44°", -23.44D,
                SolarDaylight.declinationDegrees(WINTER_SOLSTICE_DOY), 0.35D);
        T.near("春分赤纬 ≈ 0°", 0.0D,
                SolarDaylight.declinationDegrees(EQUINOX_DOY), 1.0D);

        // 全年赤纬必须落在 ±23.5° 内
        double maxAbs = 0.0D;
        for (int day = 1; day <= 365; day++) {
            maxAbs = Math.max(maxAbs, Math.abs(SolarDaylight.declinationDegrees(day)));
        }
        T.check("全年赤纬绝对值 ≤ 23.6°", maxAbs <= 23.6D);
        T.check("全年赤纬极值确实接近 23.44°", maxAbs > 23.3D);

        // 时差方程全年在 ±17 分钟内
        double maxEot = 0.0D;
        for (int day = 1; day <= 365; day++) {
            maxEot = Math.max(maxEot, Math.abs(SolarDaylight.equationOfTimeMinutes(day)));
        }
        T.check("时差方程 |EoT| ≤ 17 分钟（实际极值约 ±16.4）", maxEot <= 17.0D);
        T.check("时差方程不是恒为 0（确实实现了）", maxEot > 10.0D);
    }

    private static void daylight() {
        // 赤道：全年昼长 ≈ 12 小时
        double maxEquatorDeviation = 0.0D;
        for (int day = 1; day <= 365; day += 7) {
            maxEquatorDeviation = Math.max(maxEquatorDeviation,
                    Math.abs(SolarDaylight.daylightHours(0.0D, day) - 12.0D));
        }
        T.check("赤道昼长全年 12h ± 0.3h", maxEquatorDeviation <= 0.3D);

        // 中纬度：夏至昼长查表值（不含大气折射修正，几何昼长）
        // 40°N 夏至几何昼长约 14.85h，冬至约 9.15h
        T.near("40°N 夏至昼长 ≈ 14.85h", 14.85D,
                SolarDaylight.daylightHours(40.0D, SUMMER_SOLSTICE_DOY), 0.15D);
        T.near("40°N 冬至昼长 ≈ 9.15h", 9.15D,
                SolarDaylight.daylightHours(40.0D, WINTER_SOLSTICE_DOY), 0.15D);
        // 南半球相位相反
        T.near("40°S 夏至昼长 = 40°N 冬至昼长", SolarDaylight.daylightHours(40.0D, WINTER_SOLSTICE_DOY),
                SolarDaylight.daylightHours(-40.0D, SUMMER_SOLSTICE_DOY), 0.05D);

        // 极昼 / 极夜
        T.near("80°N 夏至 = 极昼 24h", 24.0D,
                SolarDaylight.daylightHours(80.0D, SUMMER_SOLSTICE_DOY), 0.001D);
        T.near("80°N 冬至 = 极夜 0h", 0.0D,
                SolarDaylight.daylightHours(80.0D, WINTER_SOLSTICE_DOY), 0.001D);
        T.check("极点附近不会返回 NaN", !Double.isNaN(SolarDaylight.daylightHours(90.0D, 172.0D)));

        // 太阳正午：杭州（120.155°E，UTC+8）中央经线 120°E，偏差极小
        double noonHangzhou = SolarDaylight.solarNoonLocalHour(120.155D, 28800, WINTER_SOLSTICE_DOY);
        T.check("杭州太阳正午落在 11:40~12:20", noonHangzhou > 11.66D && noonHangzhou < 12.33D);
        // 乌鲁木齐（87.6°E 却用 UTC+8）：太阳正午应明显晚于 12:00（约 14:10）
        double noonUrumqi = SolarDaylight.solarNoonLocalHour(87.6D, 28800, WINTER_SOLSTICE_DOY);
        T.check("乌鲁木齐太阳正午晚于 13:30（经度修正生效）", noonUrumqi > 13.5D);
        // 去掉经度修正就会得到接近 12:00 的错误结果，这里确认差异确实存在
        T.check("经度修正量 ≈ 2.17 小时", Math.abs((noonUrumqi - noonHangzhou) - 2.17D) < 0.1D);
    }

    private static void diurnalShape() {
        // 用杭州冬季：日出约 06:50，太阳正午约 12:00，峰值 14:30
        double latitude = 30.274D;
        double longitude = 120.155D;
        int offset = 28800;
        double doy = WINTER_SOLSTICE_DOY;
        double noon = SolarDaylight.solarNoonLocalHour(longitude, offset, doy);
        double sunrise = noon - SolarDaylight.daylightHours(latitude, doy) / 2.0D;
        double peak = noon + SolarDaylight.PEAK_LAG_HOURS;

        T.check("杭州冬至日出在 06:00~07:30（实际约 06:50）", sunrise > 6.0D && sunrise < 7.5D);
        T.near("日出时刻形状 = -1（最低温）", -1.0D,
                SolarDaylight.shape(sunrise, sunrise, peak), 1.0E-6D);
        T.near("峰值时刻形状 = +1（最高温）", 1.0D,
                SolarDaylight.shape(peak, sunrise, peak), 1.0E-6D);
        T.near("次日日出形状回到 -1", -1.0D,
                SolarDaylight.shape(sunrise + 24.0D, sunrise, peak), 1.0E-6D);

        // 关键回归：03:00 的形状必须显著高于「完整余弦」给出的 -1
        double shapeAt3 = SolarDaylight.shape(3.0D, sunrise, peak);
        double oldCosineAt3 = SolarDaylight.fallbackShape(3.0D, 15.0D);
        T.check("03:00 不再是最低点（旧实现的最低点）", shapeAt3 > -0.75D);
        T.check("03:00 的新旧形状差异确实很大（> 0.25）", shapeAt3 - oldCosineAt3 > 0.25D);
        // 日出前一刻仍在降温，日出后开始升温
        T.check("日出前 0.5h 仍在降温（形状 < -0.99）",
                SolarDaylight.shape(sunrise - 0.5D, sunrise, peak) < -0.99D);
        T.check("日出后 0.5h 已开始升温", SolarDaylight.shape(sunrise + 0.5D, sunrise, peak) > -0.99D);

        // 单调性：升温段单调增、降温段单调减
        boolean risingMonotone = true;
        double previous = -1.0D;
        for (double h = sunrise; h <= peak; h += 0.25D) {
            double value = SolarDaylight.shape(h, sunrise, peak);
            if (value < previous - 1.0E-9D) {
                risingMonotone = false;
            }
            previous = value;
        }
        T.check("日出→峰值 单调升温", risingMonotone);

        boolean fallingMonotone = true;
        previous = 1.0D;
        for (double h = peak; h <= sunrise + 24.0D; h += 0.25D) {
            double value = SolarDaylight.shape(h, sunrise, peak);
            if (value > previous + 1.0E-9D) {
                fallingMonotone = false;
            }
            previous = value;
        }
        T.check("峰值→次日日出 单调降温", fallingMonotone);

        // 形状必须处处落在 [-1, 1]
        double minShape = 1.0D;
        double maxShape = -1.0D;
        for (double h = 0.0D; h < 24.0D; h += 0.1D) {
            double value = SolarDaylight.shape(h, sunrise, peak);
            minShape = Math.min(minShape, value);
            maxShape = Math.max(maxShape, value);
        }
        T.check("形状函数处处在 [-1,1] 内", minShape >= -1.0D - 1.0E-9D && maxShape <= 1.0D + 1.0E-9D);
        T.check("形状函数确实覆盖了整个 [-1,1]（没有截断）",
                minShape < -0.999D && maxShape > 0.999D);

        // 跨日连续：23:59 与 00:01 的形状必须接近（不能跳变）
        double beforeMidnight = SolarDaylight.shape(23.9833D, sunrise, peak);
        double afterMidnight = SolarDaylight.shape(0.0167D, sunrise, peak);
        T.check("跨午夜连续（跳变 < 0.01）", Math.abs(beforeMidnight - afterMidnight) < 0.01D);
    }

    private static void offlineIntegration() {
        // 杭州、冬季：温度曲线的最低值必须落在日出附近，而不是 03:00
        LocationProfile hangzhou = LocationProfile.empty()
                .withCoordinates(30.274D, 120.155D)
                .withUtcOffset(28800)
                .withElevation(13.0D);
        BiomeClimate plains = BiomeClimate.builder("minecraft:plains")
                .climateClass(ClimateClass.TEMPERATE)
                .tempRange(-12.0D, 38.0D)
                .humidity(0.55D)
                .precipitation(PrecipitationType.RAIN)
                .source("builtin:test")
                .build();
        double doy = 355.0D;

        double bestHour = -1.0D;
        double bestShape = 2.0D;
        for (double h = 0.0D; h < 24.0D; h += 0.05D) {
            double shape = OfflineWeatherSynthesizer.diurnalShape(hangzhou, 28800, doy, h);
            if (shape < bestShape) {
                bestShape = shape;
                bestHour = h;
            }
        }
        double sunrise = SolarDaylight.solarNoonLocalHour(120.155D, 28800, doy)
                - SolarDaylight.daylightHours(30.274D, doy) / 2.0D;
        T.check("离线推算出最低温的时刻落在日出 ±1 小时内（得到的 " + String.format("%.2f", bestHour)
                + " 时，日出 " + String.format("%.2f", sunrise) + " 时）",
                Math.abs(bestHour - sunrise) <= 1.0D);

        // 无坐标 / 无时区时必须降级而不是抛异常：峰值固定 15:00
        LocationProfile nowhere = LocationProfile.empty();
        double atPeak = OfflineWeatherSynthesizer.diurnalShape(nowhere, -1, doy, 15.0D);
        T.near("无坐标时降级：15:00 形状 = +1（固定峰值余弦）", 1.0D, atPeak, 1.0E-9D);
        T.near("无坐标时降级：03:00 形状 = -1（老行为，降级路径的已知代价）", -1.0D,
                OfflineWeatherSynthesizer.diurnalShape(nowhere, -1, doy, 3.0D), 1.0E-9D);
        T.check("无坐标时不抛异常", !Double.isNaN(atPeak));

        // 极夜降级：80°N 冬至昼长为 0，必须走 fallback 分支且不返回 NaN
        LocationProfile svalbard = LocationProfile.empty()
                .withCoordinates(80.0D, 20.0D)
                .withUtcOffset(3600);
        double polarShape = OfflineWeatherSynthesizer.diurnalShape(svalbard, 3600, 355.0D, 12.0D);
        T.check("极夜不返回 NaN", !Double.isNaN(polarShape));
        T.check("极夜形状仍在 [-1,1] 内", polarShape >= -1.001D && polarShape <= 1.001D);

        // 日较差经 ERA5 偏差修正后应接近站点平年值
        // 迈阿密：ERA5 网格湿度约 90% → 18.10 − 12.58 + 1.5 = 7.02K，站点平年值 7.5K
        double miamiDtr = OfflineWeatherSynthesizer.diurnalAmplitudeC(0.90D, null) * 2.0D;
        T.check("迈阿密类气候日较差落在 6.5~8.0K（站点平年值 7.5K）",
                miamiDtr > 6.5D && miamiDtr < 8.0D);
        // 干旱气候（RH 25%）日较差应明显更大
        double aridDtr = OfflineWeatherSynthesizer.diurnalAmplitudeC(0.25D, null) * 2.0D;
        T.check("干旱气候日较差 > 12K", aridDtr > 12.0D);
        T.check("干旱日较差 > 湿润日较差的 1.6 倍", aridDtr > miamiDtr * 1.6D);
        // 极端值仍在物理范围内
        T.check("湿度 100% 时日较差 ≥ 4K（物理下限）",
                OfflineWeatherSynthesizer.diurnalAmplitudeC(1.0D, null) * 2.0D >= 4.0D);
        T.check("湿度 0% 时日较差 ≤ 20K（物理上限）",
                OfflineWeatherSynthesizer.diurnalAmplitudeC(0.0D, null) * 2.0D <= 20.0D);
    }
}

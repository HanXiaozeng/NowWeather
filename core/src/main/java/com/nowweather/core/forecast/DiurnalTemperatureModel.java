package com.nowweather.core.forecast;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.util.MathUtil;

/**
 * 昼夜温度模型。
 *
 * <p>预报的温度不能是随机数，也不能是一条直线：真实世界的日变化大致是
 * 「14 时最高、日出前最低」的正弦曲线，且干旱地区昼夜温差大、湿润地区温差小。</p>
 *
 * <p>MC 当日时刻与钟点的换算：{@code dayTime = 0} 对应清晨 6:00，6000 正午，12000 傍晚 18:00，18000 午夜。</p>
 */
public final class DiurnalTemperatureModel {

    /** 一天中温度最高的时刻（24 小时制）。 */
    public static final double PEAK_HOUR = 14.0D;

    /** 振幅下限 / 上限（摄氏度）。 */
    private static final double MIN_AMPLITUDE = 2.5D;
    private static final double MAX_AMPLITUDE = 11.0D;

    /**
     * 湿度/云量对日较差的阻尼下限。
     *
     * <p>Dai, Trenberth &amp; Karl (1999) 实测：云相对晴空只削减日较差 25%~50%，
     * 所以阻尼系数不该低于 0.50。</p>
     */
    private static final double MIN_DAMPING_FACTOR = 0.50D;

    private DiurnalTemperatureModel() {
    }

    /** MC 当日时刻 → 24 小时制钟点。 */
    public static double hourOfDay(long dayTime) {
        double normalized = MathUtil.floorMod(dayTime, 24000L) / 1000.0D;
        double hour = normalized + 6.0D;
        return hour % 24.0D;
    }

    /**
     * 昼夜振幅：干旱 / 高原大，海洋 / 湿润小。
     */
    public static double amplitude(BiomeClimate climate) {
        double span = climate.tempRange().span();
        double base = MathUtil.clamp(span * 0.16D, MIN_AMPLITUDE, MAX_AMPLITUDE);
        double dampingFactor = 1.15D - MathUtil.clamp(climate.humidity(), 0.0D, 1.0D) * 0.55D;
        if (climate.climateClass() == com.nowweather.core.climate.ClimateClass.OCEANIC) {
            dampingFactor *= 0.55D;
        }
        if (climate.climateClass() == com.nowweather.core.climate.ClimateClass.ARID
                || climate.climateClass() == com.nowweather.core.climate.ClimateClass.VOLCANIC) {
            dampingFactor *= 1.35D;
        }
        // 云与湿度会削弱昼夜温差，但削弱是有限的：Dai, Trenberth & Karl (1999) 实测云量
        // 相对晴空只削减日较差的 25%~50%（并解释了最多 80% 的日较差方差），
        // 因此阻尼系数不得低于 0.50 —— 再低就等于把云当成「没有昼夜温差」了。
        dampingFactor = Math.max(dampingFactor, MIN_DAMPING_FACTOR);
        return MathUtil.clamp(base * dampingFactor, MIN_AMPLITUDE * 0.6D, MAX_AMPLITUDE * 1.4D);
    }

    /**
     * 计算某个时刻的温度。
     *
     * <p>如果给了锚点观测（真实天气的当前温度），则以它为基准反推当天的平均温度，
     * 从而让预报曲线与「此刻的真实温度」无缝衔接。</p>
     *
     * @param climate       生物群系气候
     * @param anchorTempC   锚点温度（可 NaN）
     * @param anchorDayTime 锚点对应的 MC 时刻
     * @param targetDayTime 目标 MC 时刻
     */
    public static double temperatureAt(BiomeClimate climate, double anchorTempC, long anchorDayTime,
                                       long targetDayTime) {
        double amplitude = amplitude(climate);
        double targetHour = hourOfDay(targetDayTime);
        double phaseTarget = Math.cos(2.0D * Math.PI * (targetHour - PEAK_HOUR) / 24.0D);

        double dailyMean;
        if (!Double.isNaN(anchorTempC)) {
            double anchorHour = hourOfDay(anchorDayTime);
            double phaseAnchor = Math.cos(2.0D * Math.PI * (anchorHour - PEAK_HOUR) / 24.0D);
            dailyMean = anchorTempC - amplitude * phaseAnchor;
            // 锚点若把日均值推到气候范围之外，说明外部数据与本地气候冲突：折中
            double rangeMean = climate.tempRange().midpoint();
            dailyMean = MathUtil.lerp(dailyMean, rangeMean, 0.35D);
        } else {
            dailyMean = climate.tempRange().midpoint();
        }
        return dailyMean + amplitude * phaseTarget;
    }

    /** 当天的最低 / 最高温度（用于 HUD 显示「今日 X~Y°C」）。 */
    public static double[] dailyExtremes(BiomeClimate climate, double anchorTempC, long anchorDayTime) {
        double amplitude = amplitude(climate);
        double low = temperatureAt(climate, anchorTempC, anchorDayTime, 20000L);
        double high = temperatureAt(climate, anchorTempC, anchorDayTime, 8000L);
        double[] result = {Math.min(low, high), Math.max(low, high)};
        return result;
    }
}

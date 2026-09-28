package com.nowweather.core.terrain;

/**
 * 标准大气公式集合（纯函数，零依赖）。
 *
 * <p>这些公式都是气象/航空领域的标准件，单独成类是为了能被单元测试逐条钉住 ——
 * 地形订正的一切误差最终都会落到这几个式子上。</p>
 *
 * <h2>系数出处</h2>
 * <ul>
 *   <li>饱和水汽压 Magnus 形式：Alduchov &amp; Eskridge (1996) 推荐系数
 *       {@code 6.1094·exp(17.625·t/(t+243.04))}，−40~50°C 内误差 &lt; 0.4%，是目前最常用的形式；</li>
 *   <li>气压高度：国际标准大气 ISA（对流层，Γ = 6.5 K/km，海平面 288.15 K、1013.25 hPa）；</li>
 *   <li>露点反算：Magnus 的解析反函数。</li>
 * </ul>
 */
public final class AtmosphericFormulas {

    /** 海平面标准气压（hPa）。 */
    public static final double SEA_LEVEL_PRESSURE_HPA = 1013.25D;

    /** 海平面标准温度（K）。 */
    public static final double SEA_LEVEL_TEMPERATURE_K = 288.15D;

    /** 环境递减率（K/km）：国际标准大气。 */
    public static final double LAPSE_ENVIRONMENTAL = 6.5D;

    /** 干绝热递减率（K/km）。 */
    public static final double LAPSE_DRY_ADIABATIC = 9.8D;

    /** 饱和（湿）绝热递减率典型值（K/km）。 */
    public static final double LAPSE_MOIST_TYPICAL = 5.0D;

    /** 露点随高度的平均递减率（K/km）：约等于水汽压随高度的衰减，远小于气温递减率。 */
    public static final double LAPSE_DEWPOINT = 1.8D;

    private AtmosphericFormulas() {
    }

    /** 饱和水汽压（hPa），输入摄氏温度。 */
    public static double saturationVapourPressureHpa(double celsius) {
        return 6.1094D * Math.exp(17.625D * celsius / (celsius + 243.04D));
    }

    /** 水汽压（hPa）：温度 + 相对湿度（0~1）。 */
    public static double vapourPressureHpa(double celsius, double relativeHumidity01) {
        double rh = clamp01(relativeHumidity01);
        return saturationVapourPressureHpa(celsius) * rh;
    }

    /**
     * 露点温度（°C）：由温度与相对湿度反算（Magnus 反函数）。
     *
     * <p>露点是「气团属性」，随高度几乎守恒（本类按 {@link #LAPSE_DEWPOINT} 缓慢递减），
     * 所以它是地形订正里最可靠的中介量 —— 比直接外推相对湿度靠谱得多。</p>
     */
    public static double dewPointC(double celsius, double relativeHumidity01) {
        double rh = clamp01(relativeHumidity01);
        if (rh <= 0.0D) {
            return celsius - 60.0D;
        }
        double gamma = Math.log(rh) + 17.625D * celsius / (celsius + 243.04D);
        return 243.04D * gamma / (17.625D - gamma);
    }

    /** 相对湿度（0~1）：由温度与露点反算。 */
    public static double relativeHumidity(double celsius, double dewPointCelsius) {
        double vapour = saturationVapourPressureHpa(dewPointCelsius);
        double saturation = saturationVapourPressureHpa(celsius);
        if (saturation <= 0.0D) {
            return 1.0D;
        }
        return clamp01(vapour / saturation);
    }

    /**
     * 气压高度公式（ISA 对流层）：给定海平面气压与高度，返回该高度的气压。
     *
     * <p>也可以当作「比值」用：把参考点的实测气压当作 P₀、参考高度当作 h₀ 即可，
     * 不需要真实海平面气压。</p>
     */
    public static double pressureAtHpa(double seaLevelPressureHpa, double heightMeters) {
        double ratio = 1.0D - 0.0065D * heightMeters / SEA_LEVEL_TEMPERATURE_K;
        if (ratio <= 0.0D) {
            // 11 km 以上不适用等温层以下的公式；这里只用于地表，给个下限保护
            return seaLevelPressureHpa * 0.22D;
        }
        return seaLevelPressureHpa * Math.pow(ratio, 5.255D);
    }

    /**
     * 已知参考高度 h₀ 处的气压 P₀，求高度 h 处的气压（hPa）。
     *
     * <p>做法是先反推出等效海平面气压，再用标准公式下推 —— 这样两张曲线严格一致。</p>
     */
    /**
     * 已知参考高度处的气压与气温，求另一高度的气压（hPa）。
     *
     * <p>用的是 Open-Meteo 上游源码里的同一形式：先由地面气温反推<b>等效海平面温度</b>
     * {@code t0 = T + 0.0065·z}，再套气压公式。硬编码 ISA 的 288.15 K 会假设一个固定的
     * 海平面温度，实测在 1000 m 处偏差 −3.7 hPa、5000 m 处 −12.7 hPa；
     * 换成实际地面温度后误差降到 0.12 hPa 以内。</p>
     *
     * @param referenceTemperatureC 参考高度的实测气温（NaN 时退回 ISA 常数）
     */
    public static double pressureAtHeightHpa(double referencePressureHpa, double referenceHeightMeters,
                                             double heightMeters, double referenceTemperatureC) {
        if (referencePressureHpa <= 0.0D || !Double.isFinite(referencePressureHpa)) {
            return pressureAtHpa(SEA_LEVEL_PRESSURE_HPA, heightMeters);
        }
        double seaLevelTemperatureK = Double.isFinite(referenceTemperatureC)
                ? referenceTemperatureC + 273.15D + 0.0065D * referenceHeightMeters
                : SEA_LEVEL_TEMPERATURE_K;
        return pressureAtHpaWith(referencePressureHpa, referenceHeightMeters, heightMeters,
                seaLevelTemperatureK);
    }

    /** 旧的 ISA 形式（保留给拿不到实测气温的场景）。 */
    public static double pressureAtHeightHpa(double referencePressureHpa, double referenceHeightMeters,
                                             double heightMeters) {
        return pressureAtHeightHpa(referencePressureHpa, referenceHeightMeters, heightMeters, Double.NaN);
    }

    private static double pressureAtHpaWith(double referencePressureHpa, double referenceHeightMeters,
                                            double heightMeters, double seaLevelTemperatureK) {
        double ratio0 = 1.0D - 0.0065D * referenceHeightMeters / seaLevelTemperatureK;
        double ratio1 = 1.0D - 0.0065D * heightMeters / seaLevelTemperatureK;
        if (ratio0 <= 0.0D || ratio1 <= 0.0D) {
            return referencePressureHpa * 0.5D;
        }
        return referencePressureHpa * Math.pow(ratio1 / ratio0, 5.255D);
    }

    private static double pressureRatio(double heightMeters) {
        double ratio = 1.0D - 0.0065D * heightMeters / SEA_LEVEL_TEMPERATURE_K;
        return ratio <= 0.0D ? 0.22D : Math.pow(ratio, 5.255D);
    }

    /** 把摄氏度压到露点以上（露点不能高于气温）。 */
    public static double clampDewPoint(double celsius, double dewPointCelsius) {
        return Math.min(dewPointCelsius, celsius);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : value > 1.0D ? 1.0D : value;
    }
}

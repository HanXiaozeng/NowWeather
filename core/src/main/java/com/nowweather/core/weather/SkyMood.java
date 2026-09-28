package com.nowweather.core.weather;

import com.nowweather.core.util.MathUtil;

/**
 * 天色情绪 —— 「现实天气该长什么样的天色」。
 *
 * <h2>设计原则：只做偏移，不做替换</h2>
 * 原版雾色/天色已经包含了生物群系、昼夜、水下等大量正确信息，直接覆盖会把这些全丢掉。
 * 所以这里的输出是<b>相对原版基色的乘性偏移</b>：模组只叠加「天气造成的那部分差异」，
 * 生物群系之间该有的区别原样保留。
 *
 * <h2>能表达的天色</h2>
 * <ul>
 *   <li><b>暴晒</b>：云量极低 + 太阳高 → 提亮、轻微偏暖、雾推远；</li>
 *   <li><b>阴沉沉</b>：云量高 → 去饱和变灰、压暗；</li>
 *   <li><b>雨</b>：再暗一档、偏冷；<b>雷暴</b>：明显压暗、偏青灰；</li>
 *   <li><b>雾 / 霾</b>：雾拉近、偏白/偏黄；</li>
 *   <li><b>沙尘暴</b>：黄褐色、雾显著拉近；</li>
 *   <li><b>雪</b>：偏白偏冷、雾拉近（雪幕）。</li>
 * </ul>
 *
 * <p>这些系数都刻意保守（多数在 ±15% 以内）：天色是"氛围"，一个偏 30% 的色调
 * 玩家会立刻觉得"这游戏坏了"，而不是"今天阴天"。</p>
 */
public record SkyMood(double red, double green, double blue, double fogDensityFactor, String label) {

    /** 中性：完全等于原版。 */
    public static final SkyMood NEUTRAL = new SkyMood(1.0D, 1.0D, 1.0D, 1.0D, "原版");

    /**
     * 由天气类型 + 太阳高度推出天色。
     *
     * @param type      当前天气类型（可来自实况，也可来自预报）
     * @param sunHeight {@code RealTimeSyncModel#sunHeight}，-1（午夜）~ 1（正午）
     */
    public static SkyMood of(WeatherType type, double sunHeight) {
        return of(type, sunHeight, Double.NaN);
    }

    /**
     * 同上，但云量用<b>真实预报</b>的值。
     *
     * <p>「多云」这个天气类型只能给出 0.70 这种常量，而真实云量可能从 30% 一路飘到 95% ——
     * 天色只有跟着真实云量走，才会出现「云慢慢压过来」的过程感。
     * 传 NaN 时退回按天气类型推算。</p>
     *
     * @param realCloudCover01 真实预报云量 0~1；NaN = 用天气类型的值
     */
    public static SkyMood of(WeatherType type, double sunHeight, double realCloudCover01) {
        if (type == null || type == WeatherType.UNKNOWN) {
            return NEUTRAL;
        }
        double cloud = Double.isNaN(realCloudCover01)
                ? MathUtil.clamp(type.cloudCover(), 0.0D, 1.0D)
                : MathUtil.clamp(realCloudCover01, 0.0D, 1.0D);
        double precip = MathUtil.clamp(type.precipitation(), 0.0D, 1.0D);
        double thunder = MathUtil.clamp(type.thunder(), 0.0D, 1.0D);
        double wind = MathUtil.clamp(type.wind(), 0.0D, 1.0D);
        // 「暴晒」只在白天且晴空时成立 —— 夜里的晴空不该提亮
        double sun = MathUtil.clamp(sunHeight, 0.0D, 1.0D);
        double clearSky = 1.0D - cloud;

        String label;
        double r = 1.0D;
        double g = 1.0D;
        double b = 1.0D;
        double density = 1.0D;

        if (type.category() == WeatherCategory.DUST) {
            // 沙尘：黄褐色，雾显著拉近
            r = 1.14D;
            g = 1.02D;
            b = 0.78D;
            density = 0.45D;
            label = "沙尘天色";
        } else if (type.reducesVisibility()) {
            // 雾 / 霾：偏白（雾）或偏灰黄（霾），雾拉近
            // ★ 不能再用 zhName().contains("霾") 判断：展示名现在会跟着玩家语言变，
            // 英文环境下那句判断永远为假，霾会被当成雾。直接按枚举判，与语言无关。
            boolean haze = type == WeatherType.HAZE || type == WeatherType.SMOKE;
            r = haze ? 1.08D : 1.06D;
            g = haze ? 1.04D : 1.07D;
            b = haze ? 0.92D : 1.08D;
            density = haze ? 0.45D : 0.35D;
            label = haze ? "霾天色" : "雾天色";
        } else {
            // 通用路径：云量 → 去饱和 + 压暗；降水 → 更暗更冷；雷暴 → 最暗偏青
            double grey = cloud * 0.35D + precip * 0.18D + thunder * 0.12D;
            r = 1.0D - grey;
            g = 1.0D - grey * 0.92D;
            b = 1.0D - grey * 0.80D;             // 蓝通道留得多 → 偏冷/偏青
            double dim = 1.0D - (cloud * 0.10D + precip * 0.14D + thunder * 0.18D);
            r *= dim;
            g *= dim;
            b *= dim;

            // 暴晒：晴空 + 太阳高 → 提亮 + 轻微偏暖
            double glare = clearSky * clearSky * sun;
            r *= 1.0D + glare * 0.10D;
            g *= 1.0D + glare * 0.07D;
            b *= 1.0D + glare * 0.02D;

            // 降水时雾拉近（雪幕最明显）
            density = 1.0D - precip * (type.isFrozen() ? 0.45D : 0.30D) - thunder * 0.10D;
            // 大风把雾吹散一点
            density *= 1.0D + wind * 0.10D;

            if (glare > 0.55D) {
                label = "暴晒天色";
            } else if (thunder > 0.3D) {
                label = "雷暴天色";
            } else if (precip > 0.0D) {
                label = type.isFrozen() ? "雪幕天色" : "雨天色";
            } else if (cloud > 0.75D) {
                label = "阴沉天色";
            } else if (cloud > 0.4D) {
                label = "多云天色";
            } else {
                label = "晴好天色";
            }
        }

        // 下限刻意压到 0.18：之前写 0.55 把「阴天 / 雨 / 雷暴」全钳成了同一个值，
        // 结果阴天比暴雨还暗 —— 严重度顺序被钳位压平了。测试抓到过这个 bug。
        double clampLow = 0.18D;
        double clampHigh = 1.30D;
        return new SkyMood(
                MathUtil.clamp(r, clampLow, clampHigh),
                MathUtil.clamp(g, clampLow, clampHigh),
                MathUtil.clamp(b, clampLow, clampHigh),
                MathUtil.clamp(density, 0.20D, 1.40D),
                label);
    }

    /** 是否是「什么都不改」的中性天色。 */
    public boolean isNeutral() {
        return Math.abs(red - 1.0D) < 1.0E-6D && Math.abs(green - 1.0D) < 1.0E-6D
                && Math.abs(blue - 1.0D) < 1.0E-6D && Math.abs(fogDensityFactor - 1.0D) < 1.0E-6D;
    }

    /** 把偏移应用到原版雾色上（结果仍夹在 0~1）。 */
    public float[] applyTo(float baseR, float baseG, float baseB) {
        return new float[]{
                (float) MathUtil.clamp(baseR * red, 0.0D, 1.0D),
                (float) MathUtil.clamp(baseG * green, 0.0D, 1.0D),
                (float) MathUtil.clamp(baseB * blue, 0.0D, 1.0D),
        };
    }

    /** 云量区间的上界（默认值，需与光影包里的 NW_CLOUD_BAND 一致）。 */
    public static final double CLOUD_BAND_MAX = 0.60D;
    /** 降水区间的下界（默认值，需与光影包里的 NW_RAIN_THRESHOLD 一致）。 */
    public static final double RAIN_BAND_MIN = 0.75D;

    /**
     * 光影的 {@code rainStrength} —— <b>把云量与降水一起翻译给光影的唯一通道</b>。
     *
     * <h2>双区间编码（必须与光影包里的 NowWeather.glsl 一致）</h2>
     * Iris / OptiFine 给光影的天气输入只有 {@code rainStrength} / {@code wetness} /
     * {@code thunderStrength}。原版把它们当成「下不下雨」，而我们要表达的是两个<b>互相独立</b>的量：
     * <b>云量</b>（阴天要多云、但不下雨）与<b>降水</b>（真的在下）。一个数值承载两个量，就按区间切分：
     * <pre>
     *   rainStrength  0.00 ~ 0.60   云量 0 → 1     （阴天 / 多云走这里：云多，但不下雨）
     *   rainStrength  0.75 ~ 1.00   降水 0 → 1     （真降水走这里）
     *   0.60 ~ 0.75 是缓冲区，两边都不落 —— 保证「阴天」和「小雨」之间有可分辨的界线
     * </pre>
     * 光影包据此就能：云量取 {@code rainStrength / 0.60}，并且只在 {@code > 0.75} 时才画雨。
     *
     * <p><b>不做这个改动会怎样</b>：阴天为了压暗天色必须抬高 rainStrength，
     * 而光影只要看到 rainStrength 就画雨 —— 于是「外面阴天，光影里在下雨」。</p>
     *
     * <p><b>没装模组的人不受影响</b>：原版 {@code rainLevel} 只可能是 0 或 1，
     * 落到 {@code rainStrength / 0.60} 会被钳成 0 或 1，也就是回到光影原本的表现。</p>
     *
     * @param cloudCover01 真实云量 0~1
     * @param type         当前天气类型（可为 null）
     * @param cloudBand    云量区间上界（默认 {@link #CLOUD_BAND_MAX}，需与光影包一致）
     */
    public static float shaderRainStrength(double cloudCover01, WeatherType type, double cloudBand) {
        return shaderRainStrength(cloudCover01, type, cloudBand, 1.0D);
    }

    /**
     * 同上，但可以主观缩放<b>降水量</b>——用于「精调雨 / 雪下得多大」。
     *
     * @param amountScale 降水量倍率：1.0 = 原样，可调 0.0 ~ 2.0
     *
     * <p><b>为什么这样缩放是安全的</b>：倍率只作用在降水区间<b>内部</b>的分量上，
     * 编码结果恒为 {@code RAIN_BAND_MIN + 分量 × (1-RAIN_BAND_MIN)}，也就是永远 ≥ 0.75。
     * 无论把倍率调到多小都不会掉进 0.60~0.75 的缓冲带 ——
     * 所以不会出现「模组说在下雨、光影一滴都不画」那类事故。</p>
     */
    public static float shaderRainStrength(double cloudCover01, WeatherType type, double cloudBand,
                                           double amountScale) {
        boolean precipitating = type != null && type.isPrecipitation();
        if (!precipitating) {
            double band = MathUtil.clamp(cloudBand, 0.0D, RAIN_BAND_MIN - 0.05D);
            double cloud = Double.isNaN(cloudCover01)
                    ? (type == null ? 0.0D : MathUtil.clamp(type.cloudCover(), 0.0D, 1.0D))
                    : MathUtil.clamp(cloudCover01, 0.0D, 1.0D);
            return (float) MathUtil.clamp(cloud * band, 0.0D, 1.0D);
        }
        // 真降水：整个降水区间都归它，云量不再参与（下雨必然有云）
        double precipitation = MathUtil.clamp(type.precipitation(), 0.0D, 1.0D);
        double scale = Double.isNaN(amountScale) ? 1.0D : MathUtil.clamp(amountScale, 0.0D, 2.0D);
        precipitation = MathUtil.clamp(precipitation * scale, 0.0D, 1.0D);
        double strength = RAIN_BAND_MIN + precipitation * (1.0D - RAIN_BAND_MIN);
        if (type.isThundering()) {
            strength = Math.max(strength, 0.95D);
        }
        return (float) MathUtil.clamp(strength, 0.0D, 1.0D);
    }
}

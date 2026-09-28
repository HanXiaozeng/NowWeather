package com.nowweather.core.terrain;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;
import com.nowweather.core.weather.WindScale;

import java.util.Locale;

/**
 * 地形订正：把「参考地点的真实天气」换算到「目标地点的地形」。
 *
 * <h2>为什么需要它</h2>
 * <p>真实天气接口给的是<b>查询坐标</b>的天气（杭州市区、海拔 13 m、平原），
 * 但玩家可能站在游戏世界里的高原或雪山上。把平原的 27°C 直接写进 1500 m 的高原，
 * 既不符合物理，也会被生物群系气候校验大量修正 —— 两边打架。</p>
 *
 * <p>反过来，断网时缓存里只有一份「平原城市」的实况，要推到高原更是必须靠这套订正。</p>
 *
 * <h2>订正什么、依据是什么</h2>
 * <table border="1">
 *   <tr><th>要素</th><th>依据</th><th>实现</th></tr>
 *   <tr><td>气温</td><td>递减率随饱和程度在 5.0（湿绝热）~ 9.8（干绝热）之间过渡，
 *       标准环境值 6.5 K/km</td><td>{@link #effectiveLapseRate}</td></tr>
 *   <tr><td>湿度</td><td><b>露点守恒</b>：露点是气团属性，按 1.8 K/km 缓慢递减，
 *       再由新气温反算相对湿度（比直接外推 RH 靠谱得多）</td><td>{@link AtmosphericFormulas#dewPointC}</td></tr>
 *   <tr><td>气压</td><td>ISA 气压高度公式，用参考点实测气压反推等效海平面气压</td>
 *       <td>{@link AtmosphericFormulas#pressureAtHeightHpa}</td></tr>
 *   <tr><td>风</td><td>暴露度：山脊加速、山谷遮蔽；深谷夜间另有冷池导致的静风</td>
 *       <td>{@link #windFactor}</td></tr>
 *   <tr><td>降水</td><td>地形抬升增强（每 100 m 抬升约 +6%~+12%，随暴露度取值）；
 *       下沉侧只做保守衰减</td><td>{@link #orographicFactor}</td></tr>
 *   <tr><td>相态</td><td>订正后气温跨过雨雪分界线时改雨↔雪</td>
 *       <td>{@link #transfer}</td></tr>
 *   <tr><td>夜间冷池</td><td>谷底 + 弱风 + 晴夜 → 额外降温（山地气象里的经典现象）</td>
 *       <td>{@link #coldPoolPenaltyC}</td></tr>
 * </table>
 *
 * <h2>诚实声明</h2>
 * <p>这是一个<b>单站 → 单点</b>的物理订正，不是数值预报，也不是统计降尺度。
 * 它不会知道「这座山的背风侧正在下雨」这种信息，因为输入里没有那个信息。
 * 真正更准的做法是拉多个邻近点做海拔去趋势插值 —— 那需要数据源支持批量坐标查询，
 * 见 {@code docs/research/terrain-transfer.md} 的落地建议。</p>
 */
public final class TerrainTransfer {

    /**
     * 山脊加速倍率上限。
     *
     * <p>出处：Böhner &amp; Antonić (2009) 给出的地形→风倍率区间
     * {@code H = HL·HW ∈ [0.7（背风）, 1.3（迎风）]}。
     * 早先这个值是我自己拍的 1.55 —— 已按文献收窄。</p>
     */
    public static final double WIND_RIDGE_MAX = 1.3D;

    /** 深谷遮蔽倍率下限（同上，出自 Böhner &amp; Antonić 2009 的 0.7）。 */
    public static final double WIND_VALLEY_MIN = 0.7D;

    /** 地形抬升降水增强：每 100 m 抬升的比例（低端，对应暴露度 0）。 */
    /**
     * ★ 是否启用地形抬升降水增强。默认<b>关闭</b>。
     *
     * <p>Sevruk (1997), <i>Climatic Change</i> 36(3–4):355–369 的结论是：降水随海拔的变化存在
     * <b>large spatial heterogeneity</b>，不存在普适的「每 100 m 增加百分之几」。
     * 也就是说「找不到系数」是文献结论，不是检索失败。
     * 所以在用本站历史数据自标定出系数之前，这项默认不参与计算 ——
     * 留着代码是为了以后能做到「用 Archive API 回归出当地系数再打开」。</p>
     */
    public static final boolean OROGRAPHIC_ENABLED = false;

    public static final double OROGRAPHIC_PER_100M_MIN = 0.06D;

    /**
     * 地形抬升降水增强：每 100 m 抬升的比例（高端，对应暴露度 1）。
     *
     * <p>⚠️ 与 {@link #OROGRAPHIC_PER_100M_MIN} 一样是<b>占位值，不是标定值</b>，
     * 而且实测证明「普适的地形降水梯度」不存在。Kozak et al. (2019),
     * <i>J. Ecological Engineering</i> 20(9):261–266（开放获取，
     * doi:10.12911/22998993/112502）在<b>同一条山脉、同一套雨量计</b>上测到：</p>
     * <ul>
     *   <li>迎风/暴露谷地 <b>+143 mm/100m</b>（r = 0.94，随高度稳定递增）；</li>
     *   <li>相邻雨量计之间跨度 <b>+68.3 ~ +442.3 mm/100m</b>；</li>
     *   <li>背风/雨影谷地 <b>+30.2 mm/100m</b>，而且<b>完全没有规律递增</b>；</li>
     *   <li>按坡向分组：W 8.7、NE 25.0、<b>SE −88.4（反向）</b>、SW −19.4、NNE 43.6、WSW 1.0；</li>
     *   <li>山脊附近梯度还会<b>翻转</b>：909 m 的测点反而比 820 m 的少。</li>
     * </ul>
     * <p>同一条山脉内跨度 <b>−88 ~ +442 mm/100m</b>，仅「遮蔽」一项就让它缩小约 5 倍（143 → 30）。
     * 所以默认必须是<b>关闭</b>（见 {@link #OROGRAPHIC_ENABLED}）—— 这不是「没找到系数」，
     * 而是实测证明了不存在可用系数。</p>
     */
    public static final double OROGRAPHIC_PER_100M_MAX = 0.14D;

    /** 抬升降水增强的上限倍率（避免山顶下出荒谬的雨量）。 */
    public static final double OROGRAPHIC_MAX_FACTOR = 2.5D;

    /** 下沉/遮蔽侧的降水衰减下限。 */
    public static final double OROGRAPHIC_MIN_FACTOR = 0.65D;

    /** 雨雪分界的中心温度（°C）：实测 50% 概率点约 1.2（Dai 2008 陆地样本）。 */
    public static final double RAIN_SNOW_CENTRE_C = 1.2D;

    /** 过渡带半宽（K）：Cui et al. (2020) 实测约 0.9 K 全宽，取半宽 0.5。 */
    public static final double RAIN_SNOW_HALF_WIDTH_C = 0.5D;

    /** 夜间冷池的最大额外降温（K）。 */
    public static final double COLD_POOL_MAX_C = 4.0D;

    /** 冷池生效的风速上限（km/h）：风一大就混合掉了。 */
    public static final double COLD_POOL_MAX_WIND_KMH = 12.0D;

    /** 高原上递减率偏小的起始海拔（m）与折减比例。 */
    public static final double PLATEAU_START_METERS = 2000.0D;

    private TerrainTransfer() {
    }

    /** 订正结果：订正后的观测 + 人话解释（诊断/命令用）。 */
    public record Result(WeatherObservation observation, String explanation, double lapseRate) {

        public boolean changed() {
            return explanation != null && !explanation.isEmpty();
        }
    }

    /**
     * 把参考地点的观测订正到目标地形。
     *
     * @param reference 参考观测（真实天气或缓存实况）
     * @param source    参考地点的画像（提供参考海拔；坐标缺失时按海平面处理）
     * @param terrain   目标地形画像
     * @param epochMillis 目标时刻（判断昼夜，用于冷池）
     * @param utcOffsetSeconds 当地时区偏移（秒）
     */
    public static Result transfer(WeatherObservation reference, LocationProfile source,
                                  TerrainProfile terrain, long epochMillis, int utcOffsetSeconds) {
        if (reference == null) {
            return new Result(null, "", AtmosphericFormulas.LAPSE_ENVIRONMENTAL);
        }
        if (terrain == null || !terrain.hasElevation()) {
            return new Result(reference, "", AtmosphericFormulas.LAPSE_ENVIRONMENTAL);
        }
        double referenceElevation = source != null && source.hasElevation()
                ? source.elevationMeters() : 0.0D;
        double deltaH = terrain.elevationMeters() - referenceElevation;
        if (Math.abs(deltaH) < 5.0D && terrain.exposure() == 0.5D && !terrain.canPoolColdAir()) {
            // 高差可以忽略、又不是特殊地形：不做任何改动，避免凭空引入噪声
            return new Result(reference, "", AtmosphericFormulas.LAPSE_ENVIRONMENTAL);
        }

        StringBuilder why = new StringBuilder();
        why.append(Text.tr("nowweather.terrain.header", fmt0(referenceElevation),
                fmt0(terrain.elevationMeters()), fmtSigned0(deltaH)));

        // ---- 1) 气温 ----
        double temperature = reference.temperatureC();
        double humidity01 = reference.humidity01();
        double localHour = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneOffset.ofTotalSeconds(utcOffsetSeconds)).getHour()
                + java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneOffset.ofTotalSeconds(utcOffsetSeconds)).getMinute() / 60.0D;
        double lapse = effectiveLapseRate(humidity01, reference.weatherType(),
                terrain.elevationMeters(), localHour);
        double lapseDrop = lapse * deltaH / 1000.0D;
        temperature -= lapseDrop;
        why.append(Text.tr("nowweather.terrain.temperature",
                fmtSigned(-lapseDrop), fmt(lapse), fmt0(humidity01 * 100.0D)));

        // ---- 2) 夜间冷池 ----
        double coldPool = coldPoolPenaltyC(terrain, reference, epochMillis, utcOffsetSeconds);
        if (coldPool > 0.0D) {
            temperature -= coldPool;
            why.append(Text.tr("nowweather.terrain.cold_pool", fmt(coldPool)));
        }

        // ---- 3) 湿度：露点守恒 ----
        double referenceDewPoint = AtmosphericFormulas.dewPointC(reference.temperatureC(), humidity01);
        double targetDewPoint = AtmosphericFormulas.clampDewPoint(temperature,
                referenceDewPoint - AtmosphericFormulas.LAPSE_DEWPOINT * deltaH / 1000.0D);
        double targetHumidity = AtmosphericFormulas.relativeHumidity(temperature, targetDewPoint);
        why.append(Text.tr("nowweather.terrain.humidity",
                fmt(referenceDewPoint), fmt(targetDewPoint),
                fmt0(humidity01 * 100.0D), fmt0(targetHumidity * 100.0D)));

        // ---- 4) 气压 ----
        double pressure = AtmosphericFormulas.pressureAtHeightHpa(reference.pressureHpa(),
                referenceElevation, terrain.elevationMeters(), reference.temperatureC());
        if (reference.pressureHpa() > 0.0D && Double.isFinite(reference.pressureHpa())) {
            why.append(Text.tr("nowweather.terrain.pressure",
                    fmt0(reference.pressureHpa()), fmt0(pressure)));
        }

        // ---- 5) 风 ----
        double windFactor = windFactor(terrain, reference, coldPool);
        double referenceKmh = WindScale.toKmh(reference.windScale());
        int windScale = MathUtil.clamp(WindScale.fromKmh(referenceKmh * windFactor), 0, 12);
        if (Math.abs(windFactor - 1.0D) > 0.02D) {
            why.append(Text.tr("nowweather.terrain.wind", fmt2(windFactor), fmt2(terrain.exposure())));
        }

        // ---- 6) 降水（地形抬升）----
        double orographic = OROGRAPHIC_ENABLED ? orographicFactor(terrain, deltaH, humidity01) : 1.0D;
        if (Math.abs(orographic - 1.0D) > 0.02D) {
            why.append(Text.tr("nowweather.terrain.orographic", fmt2(orographic)));
        }

        // ---- 7) 相态 ----
        WeatherType type = reference.weatherType();
        WeatherType adjusted = adjustPhase(type, temperature, orographic);
        if (adjusted != type) {
            why.append(Text.tr("nowweather.terrain.phase", type.zhName(), adjusted.zhName()));
        }

        WeatherObservation result = WeatherObservation.builder(reference)
                .weatherType(adjusted)
                .conditionText(adjusted == type ? reference.conditionText() : adjusted.zhName())
                .temperatureC(temperature)
                .humidityPercent(targetHumidity * 100.0D)
                .pressureHpa(pressure)
                .windScale(windScale)
                .windSpeedKmh(WindScale.toKmh(windScale))
                .build();
        return new Result(result, why.toString(), lapse);
    }

    /**
     * 有效气温递减率（K/km）。
     *
     * <p>判据（这是本模块最需要解释清楚的一处）：</p>
     * <ul>
     *   <li><b>越接近饱和 → 越接近湿绝热</b>。饱和气块抬升时释放潜热，递减率被压到 ~5 K/km；
     *       干燥气块接近干绝热 9.8。</li>
     *   <li>用相对湿度做线性过渡（<b>工程近似</b>：严格做法要判饱和度与 LCL，
     *       但我们手里只有一个地面 RH，没有探空）。</li>
     *   <li>正在降水时直接按饱和处理（降水意味着气柱已饱和）。</li>
     *   <li><b>高原折减</b>：超过 2000 m 后递减率偏小（地表加热使实际梯度变缓）。</li>
     * </ul>
     */
    public static double effectiveLapseRate(double humidity01, WeatherType type, double targetElevation) {
        return effectiveLapseRate(humidity01, type, targetElevation, Double.NaN);
    }

    /**
     * 有效气温递减率（K/km），带昼夜项。
     *
     * <p>Mote, Lundquist &amp; Minder (2009) 在同一个站点实测：<b>夏季白天 ≈ −7、夏季夜间 ≈ −2.5、
     * 其余季节 ≈ −5 K/km</b>。也就是说递减率本身是「随一天中的时刻变化」的，
     * 比「按湿度在干湿绝热之间插值」更能解释山地温度。</p>
     *
     * @param localHour 当地钟点 0~24；NaN = 不启用昼夜项
     */
    public static double effectiveLapseRate(double humidity01, WeatherType type, double targetElevation,
                                            double localHour) {
        double rh = MathUtil.clamp(humidity01, 0.0D, 1.0D);
        boolean precipitating = type != null && type.isPrecipitation();
        double saturated = precipitating ? Math.max(rh, 0.95D) : rh;
        double lapse = AtmosphericFormulas.LAPSE_DRY_ADIABATIC
                + (AtmosphericFormulas.LAPSE_MOIST_TYPICAL - AtmosphericFormulas.LAPSE_DRY_ADIABATIC) * saturated;
        // 先按湿度把递减率夹在湿绝热~干绝热之间
        lapse = MathUtil.clamp(lapse, 4.5D, 9.8D);
        if (Double.isFinite(localHour)) {
            // 昼夜项：白天对流混合强 → 更接近干绝热；夜间边界层稳定 → 被压到约 0.4 倍
            double hour = ((localHour % 24.0D) + 24.0D) % 24.0D;
            double daytime = hour >= 9.0D && hour <= 17.0D ? 1.0D
                    : (hour >= 7.0D && hour <= 20.0D ? 0.6D : 0.0D);
            double nightFactor = 0.40D;
            lapse *= nightFactor + (1.0D - nightFactor) * daytime;
            // ★ 最终下限必须放宽到 1.5：实测的夜间 Tmin 递减率约 2.5 K/km，
            // 若沿用湿绝热的 4.5 下限，昼夜项会被夹回去、等于没做
            //（这个 bug 是测试抓到的：夜间算出来 4.50 = 下限，而不是 2.77）。
            return MathUtil.clamp(lapse, 1.5D, 10.5D);
        }
        return lapse;
    }

    /** 夜间谷底冷池的额外降温（K，正值表示更冷）。 */
    public static double coldPoolPenaltyC(TerrainProfile terrain, WeatherObservation reference,
                                          long epochMillis, int utcOffsetSeconds) {
        if (terrain == null || !terrain.canPoolColdAir()) {
            return 0.0D;
        }
        if (terrain.snowCovered()) {
            // 雪面辐射冷却更强，但积雪也意味着已经冷透了，取中间值
        }
        if (!isNight(epochMillis, utcOffsetSeconds)) {
            return 0.0D;
        }
        double windKmh = reference == null ? 5.0D : WindScale.toKmh(reference.windScale());
        if (windKmh > COLD_POOL_MAX_WIND_KMH) {
            return 0.0D;
        }
        // 云量近似：降水或阴天时辐射冷却弱
        double cloudFactor = 1.0D;
        if (reference != null) {
            WeatherType type = reference.weatherType();
            if (type.isPrecipitation() || type == WeatherType.OVERCAST) {
                cloudFactor = 0.25D;
            } else if (type == WeatherType.CLOUDY || type == WeatherType.PARTLY_CLOUDY) {
                cloudFactor = 0.6D;
            }
        }
        // 静风 + 深谷 + 晴夜 → 最强
        double windFactor = 1.0D - MathUtil.clamp(windKmh / COLD_POOL_MAX_WIND_KMH, 0.0D, 1.0D) * 0.7D;
        double depthFactor = MathUtil.clamp(terrain.reliefMeters() / 80.0D, 0.35D, 1.0D);
        return MathUtil.clamp(COLD_POOL_MAX_C * windFactor * cloudFactor * depthFactor, 0.0D, COLD_POOL_MAX_C);
    }

    /** 风的暴露度倍率。 */
    public static double windFactor(TerrainProfile terrain, WeatherObservation reference, double coldPool) {
        if (terrain == null) {
            return 1.0D;
        }
        // 暴露度 0.5 为中性；>0.5 加速（山脊/山口），<0.5 遮蔽（谷地/背风）
        double factor = 1.0D + (terrain.exposure() - 0.5D) * 2.0D * (WIND_RIDGE_MAX - 1.0D);
        // 海拔越高、风越大（摩擦层变薄）：每 1000 m 约 +8%【工程近似】
        factor *= 1.0D + MathUtil.clamp(terrain.elevationMeters() / 1000.0D, 0.0D, 4.0D) * 0.08D;
        if (coldPool > 0.0D) {
            // 冷池意味着近地面层结稳定、风被压住
            factor *= 0.75D;
        }
        return MathUtil.clamp(factor, WIND_VALLEY_MIN, WIND_RIDGE_MAX * 1.3D);
    }

    /**
     * 地形抬升对降水的增强倍率。
     *
     * <p><b>【工程近似】</b>：真正的 orographic precipitation 需要风场 + 山体几何
     * （Smith &amp; Barstad 2004 那类线性模型）。我们只有「相对参考点的抬升量」与
     * 「暴露度」，所以用一个线性上坡近似：每抬升 100 m 增强 6%~14%（暴露度越高越多），
     * 上限 {@link #OROGRAPHIC_MAX_FACTOR}；下降/遮蔽侧衰减到
     * {@link #OROGRAPHIC_MIN_FACTOR}（雨影的保守近似）。</p>
     */
    public static double orographicFactor(TerrainProfile terrain, double deltaH, double humidity01) {
        if (terrain == null) {
            return 1.0D;
        }
        double per100 = OROGRAPHIC_PER_100M_MIN
                + (OROGRAPHIC_PER_100M_MAX - OROGRAPHIC_PER_100M_MIN) * terrain.exposure();
        // 干燥气团抬升不出多少雨：按湿度打折
        per100 *= MathUtil.clamp(humidity01 / 0.6D, 0.25D, 1.2D);
        if (deltaH >= 0.0D) {
            double factor = 1.0D + per100 * (deltaH / 100.0D);
            return MathUtil.clamp(factor, 1.0D, OROGRAPHIC_MAX_FACTOR);
        }
        double factor = 1.0D + per100 * (deltaH / 100.0D) * 0.6D;
        return MathUtil.clamp(factor, OROGRAPHIC_MIN_FACTOR, 1.0D);
    }

    /** 定点格式化（数字不随语言变化）。 */
    private static String fmt0(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /** 带正负号的定点格式化。 */
    private static String fmtSigned0(double value) {
        return String.format(Locale.ROOT, "%+.0f", value);
    }

    /** 一位小数的定点格式化。 */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 带正负号的一位小数格式化。 */
    private static String fmtSigned(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    /** 两位小数的定点格式化。 */
    private static String fmt2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 订正后气温跨过雨雪分界线时改相态。 */
    private static WeatherType adjustPhase(WeatherType type, double temperature, double orographic) {
        if (type == null || !type.isPrecipitation()) {
            return type;
        }
        // ★ 过渡带宽度按实测收窄。
        // Cui et al. (2020) 在山区 76 场风暴、178 个测点上实测：湿球温度从 0 升到 1°C
        // 只跨 180±40 m，按他们测的湿球递减率 −5°C/km 折算就是约 0.9 K —— 而不是我早先写的 6 K。
        // 那个 6 K 是把 USACE 的<b>干球</b>过渡带 (−1,+3)°C 当成同一件事换算了，两种度量不能混。
        if (temperature <= RAIN_SNOW_CENTRE_C - RAIN_SNOW_HALF_WIDTH_C) {
            return type.asFrozen(true, temperature);
        }
        if (temperature >= RAIN_SNOW_CENTRE_C + RAIN_SNOW_HALF_WIDTH_C) {
            return type.asFrozen(false, temperature);
        }
        return type;
    }

    /** 目标时刻当地是否夜间（19:00~06:00 视为夜间，冷池主要发生在此时段）。 */
    public static boolean isNight(long epochMillis, int utcOffsetSeconds) {
        java.time.ZonedDateTime local = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneOffset.ofTotalSeconds(utcOffsetSeconds));
        int hour = local.getHour();
        return hour >= 19 || hour < 6;
    }

    /** 风向不随地形订正（我们无法从单站数据推出绕流/渠道效应）。 */
    public static WindDirection windDirection(WeatherObservation reference) {
        return reference == null ? WindDirection.UNKNOWN : reference.windDirection();
    }
}

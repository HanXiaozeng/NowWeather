package com.nowweather.core.forecast;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.CoastDistance;
import com.nowweather.core.text.Text;
import com.nowweather.core.time.RealTimeSyncModel;
import com.nowweather.core.time.SolarDaylight;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;
import com.nowweather.core.weather.WindScale;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * <b>离线天气推算器</b> —— 断网 / 数据过期时，凭「地点画像 + 当前时刻」推出接近真实的天气。
 *
 * <p>它刻意只使用<b>可解释、可核对</b>的气象常识，而不是黑箱模型：</p>
 *
 * <ol>
 *   <li><b>纬向年平均气温</b>：用 10° 一档的纬向平均表线性插值（赤道约 26.5°C，60° 约 0°C，极地约 −25°C）；</li>
 *   <li><b>季节正弦</b>：{@code T = 年均 + A(lat)·cos(2π(doy − peak)/365.25)}，
 *       振幅随纬度增大（热带年较差极小、高纬极大），<b>南半球相位平移约 182.6 天</b>；</li>
 *   <li><b>海拔递减率</b>：标准大气约 <b>6.5°C/km</b>；</li>
 *   <li><b>昼夜曲线</b>：最低温出现在日出前、最高温出现在午后，日较差由湿度调节（干燥地区昼夜温差大）；</li>
 *   <li><b>降水气候带</b>：赤道辐合带多雨、副热带（20°~35°）干旱、中纬西风带（40°~60°）较湿、极地干燥；</li>
 *   <li><b>风向带</b>：信风带（0°~30°）偏东、西风带（30°~60°）偏西、极地东风带（60°~90°）偏东；</li>
 *   <li><b>持续性融合</b>：如果缓存里还有不太旧的实况，就用它当锚点
 *       （天气预报里「明天和今天一样」本身就是很强的基线），并按年龄逐渐降低权重。</li>
 * </ol>
 *
 * <p>最重要的是<b>诚实</b>：结果里的 {@code confidence} 会明确反映依据有多弱
 * （只有地点画像 ≈ 0.35，有当天实况锚点 ≈ 0.7），HUD 与 {@code /nowweather status} 都会显示出来，
 * 不会假装这是真实数据。</p>
 */
public final class OfflineWeatherSynthesizer {

    /** 10° 一档的纬向年平均气温（°C），索引 0 = 赤道，索引 9 = 极点附近。 */
    private static final double[] ZONAL_MEAN_C = {
            26.5D, 26.0D, 24.0D, 20.0D, 14.0D, 8.0D, 0.0D, -10.0D, -18.0D, -25.0D
    };

    /** 标准大气温度递减率（°C / km）。 */
    public static final double LAPSE_RATE_C_PER_KM = 6.5D;

    /**
     * 北半球最热的一天（年内第几天）。
     *
     * <p>实测值：对 8 个中纬度站点的十年 ERA5 日均温做最小二乘拟合，峰值出现在第 194~210 天，
     * 均值 <b>202.2</b> —— 注意这<b>不是</b>夏至（第 171.5 天），而是滞后约 31 天（南半球滞后约 26 天）。
     * 详细的拟合过程见 {@code docs/research/offline-weather-synthesis.md}。</p>
     */
    public static final double NORTHERN_PEAK_DAY = 202.2D;

    /** 雨雪相态的分界温度（°C）：降水日为雪的概率达到 50% 的温度。 */
    public static final double RAIN_SNOW_THRESHOLD_C = 1.2D;

    /**
     * 雨雪过渡带的半宽（K）。
     *
     * <p>Cui et al. (2020) 实测全宽约 0.9K（湿球 0→1°C 跨 180±40 m），故取半宽 0.5。
     * 早先用的是 -2~+4 的 6K 宽带 —— 那是把 USACE 的干球过渡带当成同一件事换算了。</p>
     */
    public static final double RAIN_SNOW_BAND_C = 0.5D;

    /** 融合实测：距平的融合权重 {@code w(h) = 1/(1+(h/2.10)^1.20)}（h 单位为天）。 */
    public static final double BLEND_HALF_LIFE_DAYS = 2.10D;

    public static final double BLEND_EXPONENT = 1.20D;

    /** 距平融合的截止年龄（天）：实测表明持续性预报超过约 3 天后不如气候平均。 */
    public static final double BLEND_MAX_AGE_DAYS = 3.0D;

    /** ERA5 网格平滑导致的日较差系统偏差（K），与站点平年值比对得出。 */
    public static final double DTR_ERA5_LOW_BIAS_C = 1.5D;

    /** 昼长短于这个值（小时）就没有可用的日出时刻，昼夜曲线退回固定峰值。 */
    public static final double MIN_USABLE_DAYLIGHT_HOURS = 4.0D;

    /** 实测降水持续性：P(雨|前一天雨)。 */
    public static final double WET_PERSISTENCE_PROBABILITY = 0.679D;

    /** 实测降水持续性：P(雨|前一天晴)。 */
    public static final double DRY_TO_WET_PROBABILITY = 0.187D;

    /** 回春点：缓存实况超过这个年龄就不再参与「持续性融合」。 */
    public static final long PERSISTENCE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L;

    private OfflineWeatherSynthesizer() {
    }

    /** 一次推算的输入。 */
    public record Request(LocationProfile profile,
                          long epochMillis,
                          int utcOffsetSeconds,
                          BiomeClimate biomeClimate,
                          WeatherObservation cachedObservation,
                          long seed) {

        public static Request of(LocationProfile profile, long epochMillis, int utcOffsetSeconds) {
            return new Request(profile, epochMillis, utcOffsetSeconds, null, null,
                    MathUtil.seedOf(profile.latitude(), profile.longitude(), epochMillis / 3_600_000L));
        }
    }

    /** 推算结果（观测 + 说明，便于诊断）。 */
    public record Result(WeatherObservation observation, String explanation) {
    }

    /** 推算出当前天气。永远返回可用结果（最差情况是「温带多云」级别的保守估计）。 */
    public static Result synthesize(Request request) {
        LocationProfile profile = request.profile() == null ? LocationProfile.empty() : request.profile();
        long now = request.epochMillis();
        int offset = request.utcOffsetSeconds();

        ZonedDateTime local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.ofTotalSeconds(offset));
        double dayOfYear = local.getDayOfYear();
        double localHour = local.getHour() + local.getMinute() / 60.0D;

        StringBuilder why = new StringBuilder();

        // ---- 1. 温度：先算「气候期望值」，再把实况距平搬过来 ----
        double temperature = climatologyC(profile, request.biomeClimate(), now, offset);
        why.append(describeClimatology(profile, request.biomeClimate(), now, offset));
        double humidity01 = estimateHumidity(profile, request.biomeClimate());

        // ★ 与缓存实况锚定：必须融合「距平」而不是绝对值。
        //
        // 反例：早上实测 10°C（当时的气候期望 8°C，距平 +2），到傍晚气候期望已是 18°C；
        // 若把绝对值往 10°C 拉，就会把「今天偏暖 2 度」错当成「傍晚只有 10 度」，
        // 跨昼夜边界能引入最多 ±7K 的系统误差。正确做法只搬运距平：
        //     T = T_clim(now) + w · (T_obs − T_clim(观测时刻))
        // 权重用实测拟合的 w(h) = 1/(1+(h/2.10)^1.20)（h 为距今天数）。
        WeatherObservation cached = request.cachedObservation();
        double anchorWeight = 0.0D;
        if (cached != null && cached.hasTemperature()) {
            long age = now - cached.observedAtEpochMillis();
            double ageDays = age / 86_400_000.0D;
            if (age >= 0L && ageDays < BLEND_MAX_AGE_DAYS) {
                double observedClimatology = climatologyC(profile, request.biomeClimate(),
                        cached.observedAtEpochMillis(), offset);
                double anomaly = cached.temperatureC() - observedClimatology;
                anchorWeight = blendWeight(ageDays);
                temperature += anomaly * anchorWeight;
                why.append(Text.tr("nowweather.offline.blend",
                        fmt(ageDays), fmtSigned(anomaly), fmt0(anchorWeight * 100.0D)));
            }
        }

        // ---- 2. 天气类型 ----
        double wetChance = precipitationProbability(profile, dayOfYear, request.biomeClimate());
        WeatherType type;
        String typeWhy;
        String typeWhyPrefix = "";
        long seed = request.seed();

        Boolean allowedRain = request.biomeClimate() == null ? null : request.biomeClimate().canPrecipitate();
        // 实测的降水持续性（n = 51002 站·日）：P(雨|前一天雨)=0.679，P(雨|前一天晴)=0.187。
        // 把它当作「有前一日实况时」的先验，再与当地气候概率取平均。
        boolean recentObservation = false;
        boolean previousWet = false;
        if (cached != null && now - cached.observedAtEpochMillis() < PERSISTENCE_MAX_AGE_MILLIS) {
            recentObservation = true;
            previousWet = cached.weatherType().isPrecipitation();
        }
        if (recentObservation) {
            double markov = previousWet ? WET_PERSISTENCE_PROBABILITY : DRY_TO_WET_PROBABILITY;
            wetChance = MathUtil.clamp((markov + wetChance) / 2.0D, 0.0D, 0.95D);
            typeWhyPrefix = Text.tr("nowweather.offline.markov",
                    Text.tr(previousWet ? "nowweather.offline.markov_wet" : "nowweather.offline.markov_dry"),
                    fmt0(wetChance * 100.0D));
        }
        boolean persist = recentObservation && MathUtil.chance(seed, 101L, anchorWeight * 0.9D);
        if (persist) {
            type = cached.weatherType();
            typeWhy = Text.tr("nowweather.offline.persist", type.zhName(),
                    Long.toString(Math.round(anchorWeight * 100.0D)));
        } else if (Boolean.FALSE.equals(allowedRain)) {
            type = humidity01 > 0.5D ? WeatherType.CLOUDY : WeatherType.CLEAR;
            typeWhy = Text.tr("nowweather.offline.no_precip", type.zhName());
        } else {
            double roll = MathUtil.random01(seed, 102L);
            if (roll < wetChance) {
                double intensity = MathUtil.clamp(0.35D + humidity01 * 0.5D
                        + MathUtil.randomRange(seed, 103L, -0.15D, 0.25D), 0.1D, 1.0D);
                // 相态分界不是 0°C：实测（Dai 2008）降水日为雪的概率在约 1.2°C 达到 50%。
                // ★ 过渡带宽度按 Cui et al. (2020) 实测收窄到约 1K：
                // 他们测的湿球 0→1°C 只跨 180±40 m，按其湿球递减率 -5K/km 折合约 0.9K。
                // 早先写的 -2~+4（6K）是把 USACE 的<b>干球</b>过渡带混进来了，两种度量不能混用。
                if (temperature <= RAIN_SNOW_THRESHOLD_C - RAIN_SNOW_BAND_C) {
                    type = WeatherType.snowOf(intensity);
                } else if (temperature < RAIN_SNOW_THRESHOLD_C + RAIN_SNOW_BAND_C) {
                    type = WeatherType.SLEET;
                } else {
                    type = WeatherType.rainOf(intensity);
                }
                typeWhy = Text.tr("nowweather.offline.wet_hit", fmt0(wetChance * 100.0D), type.zhName());
            } else {
                double cloudRoll = MathUtil.random01(seed, 104L);
                if (cloudRoll < 0.30D) {
                    type = WeatherType.CLEAR;
                } else if (cloudRoll < 0.55D) {
                    type = WeatherType.MAINLY_CLEAR;
                } else if (cloudRoll < 0.80D) {
                    type = WeatherType.PARTLY_CLOUDY;
                } else {
                    type = WeatherType.CLOUDY;
                }
                typeWhy = Text.tr("nowweather.offline.no_precip_hit",
                        Long.toString(Math.round(wetChance * 100.0D)), type.zhName());
            }
        }

        // 温度与形态一致性（例如推算出的类型是雨但温度在冰点以下）
        if (type.isPrecipitation()) {
            type = type.asFrozen(temperature <= RAIN_SNOW_THRESHOLD_C, temperature);
        }

        // ---- 3. 风 ----
        WindDirection windDirection = windDirectionFor(profile.latitude(), request.seed());
        int windScale = windScaleFor(profile, request.seed());
        double windKmh = WindScale.toKmh(windScale);

        WeatherObservation observation = WeatherObservation.builder("offline")
                .weatherType(type)
                .conditionText(Text.tr("nowweather.offline.condition", type.zhName()))
                .temperatureC(MathUtil.round(temperature, 1))
                .humidityPercent(Math.round(humidity01 * 100.0D))
                .windDirection(windDirection)
                .windScale(windScale)
                .windSpeedKmh(windKmh)
                .location("", profile.city(), profile.adcode())
                .reportTimeText(Text.tr("nowweather.offline.report_time"))
                .observedAt(now)
                .fetchedAt(now)
                .confidence(confidence(profile, cached, now))
                .utcOffsetSeconds(offset)
                .build();

        why.append("；").append(typeWhyPrefix).append(typeWhy);
        return new Result(observation, why.toString());
    }

    // ------------------------------------------------------------------ 气候期望值（距平融合的基准）

    /**
     * 给定时刻的<b>气候期望温度</b>：纬向年均（或群系温度中值）+ 季节正弦 + 海拔递减 + 昼夜曲线。
     *
     * <p>它是距平融合的基准 —— 有了它才能只搬运「实况相对期望的偏离」，
     * 而不是把绝对值硬拉过去（后者跨昼夜边界会引入最多 ±7K 的系统误差）。</p>
     */
    public static double climatologyC(LocationProfile profile, BiomeClimate climate,
                                      long epochMillis, int utcOffsetSeconds) {
        ZonedDateTime local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis),
                ZoneOffset.ofTotalSeconds(utcOffsetSeconds));
        double dayOfYear = local.getDayOfYear();
        double localHour = local.getHour() + local.getMinute() / 60.0D;

        double mean = meanAnnualC(profile, climate);
        double seasonal = seasonalAmplitudeC(profile)
                * Math.cos(2.0D * Math.PI * (dayOfYear - peakDayOf(profile)) / 365.25D);
        double altitudeDrop = profile.hasElevation()
                ? LAPSE_RATE_C_PER_KM * profile.elevationMeters() / 1000.0D : 0.0D;
        double diurnal = diurnalAmplitudeC(estimateHumidity(profile, climate), climate)
                * diurnalShape(profile, utcOffsetSeconds, dayOfYear, localHour);
        return mean + seasonal - altitudeDrop + diurnal;
    }

    /**
     * 昼夜温度曲线的形状（-1 最低温 … +1 最高温）。
     *
     * <p>最低温落在<b>日出</b>、最高温落在<b>太阳正午后 2.5 小时</b>，
     * 两段各自是半余弦。老实现用一条以 15:00 为峰值的完整余弦，等价于强行把最低温
     * 钉在 03:00 —— 中纬度冬季日出约 08:00，夜间的相位差 5 小时、温度差 1~3 K。</p>
     *
     * <p>降级路径：没有坐标 / 没有时区 / 极夜（昼长 &lt; 4 小时）时退回固定峰值的完整余弦
     * —— 这些情况下本来就没有可用的日出时刻。</p>
     */
    public static double diurnalShape(LocationProfile profile, int utcOffsetSeconds,
                                      double dayOfYear, double localHour) {
        if (profile.hasCoordinates() && profile.hasUtcOffset()) {
            double daylight = SolarDaylight.daylightHours(profile.latitude(), dayOfYear);
            if (daylight >= MIN_USABLE_DAYLIGHT_HOURS) {
                double noon = SolarDaylight.solarNoonLocalHour(profile.longitude(), utcOffsetSeconds,
                        dayOfYear);
                double sunrise = noon - daylight / 2.0D;
                return SolarDaylight.shape(localHour, sunrise, noon + SolarDaylight.PEAK_LAG_HOURS);
            }
            double noon = SolarDaylight.solarNoonLocalHour(profile.longitude(), utcOffsetSeconds,
                    dayOfYear);
            return SolarDaylight.fallbackShape(localHour, noon + SolarDaylight.PEAK_LAG_HOURS);
        }
        return SolarDaylight.fallbackShape(localHour, 15.0D);
    }

    /** 昼夜曲线的一句话说明（日出/峰值钟点），用于诊断输出。 */
    public static String daylightNote(LocationProfile profile, int utcOffsetSeconds,
                                      double dayOfYear, double localHour) {
        if (!profile.hasCoordinates() || !profile.hasUtcOffset()) {
            return Text.tr("nowweather.offline.daylight.none");
        }
        double daylight = SolarDaylight.daylightHours(profile.latitude(), dayOfYear);
        double noon = SolarDaylight.solarNoonLocalHour(profile.longitude(), utcOffsetSeconds, dayOfYear);
        if (daylight < MIN_USABLE_DAYLIGHT_HOURS) {
            return Text.tr("nowweather.offline.daylight.polar", fmt(daylight),
                    fmt(noon + SolarDaylight.PEAK_LAG_HOURS));
        }
        double sunrise = noon - daylight / 2.0D;
        double sunset = noon + daylight / 2.0D;
        String marker = localHour >= sunrise && localHour < noon + SolarDaylight.PEAK_LAG_HOURS
                ? Text.tr("nowweather.offline.daylight.warming")
                : Text.tr("nowweather.offline.daylight.cooling");
        return Text.tr("nowweather.offline.daylight.normal",
                fmt(daylight), clock(sunrise), clock(sunset),
                clock(noon + SolarDaylight.PEAK_LAG_HOURS), marker);
    }

    /** 小时数 → {@code H:MM}（可跨 24 小时，加减 24 归一）。 */
    private static String clock(double hour) {
        double h = hour % 24.0D;
        if (h < 0.0D) {
            h += 24.0D;
        }
        int hh = (int) Math.floor(h);
        int mm = (int) Math.round((h - hh) * 60.0D);
        if (mm == 60) {
            hh = (hh + 1) % 24;
            mm = 0;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hh, mm);
    }

    /** 纬向年均（没有坐标时退回生物群系温度中值）。 */
    public static double meanAnnualC(LocationProfile profile, BiomeClimate climate) {
        if (profile.hasCoordinates()) {
            return zonalMeanC(profile.latitude());
        }
        return climate == null ? 12.0D : climate.tempRange().midpoint();
    }

    /** 当地最热的一天（南半球相位平移 182.6 天）。 */
    public static double peakDayOf(LocationProfile profile) {
        return NORTHERN_PEAK_DAY + (profile.hemisphere() == LocationProfile.Hemisphere.SOUTH
                ? LocationProfile.Hemisphere.SOUTH.seasonPhaseOffsetDays() : 0.0D);
    }

    /**
     * 距平融合权重（实测拟合）：{@code w(h) = 1/(1+(h/2.10)^1.20)}，h 为距今天数。
     *
     * <p>实测含义：1 天前 0.71、2 天 0.50、3 天 0.39、7 天 0.19 ——
     * 与「持续性预报只在 1~2 天内优于气候平均」这一经典结论一致。</p>
     */
    public static double blendWeight(double ageDays) {
        double h = Math.max(0.0D, ageDays);
        return 1.0D / (1.0D + Math.pow(h / BLEND_HALF_LIFE_DAYS, BLEND_EXPONENT));
    }

    /** 把气候期望值的构成拼成人话（诊断/命令用）。 */
    public static String describeClimatology(LocationProfile profile, BiomeClimate climate,
                                             long epochMillis, int utcOffsetSeconds) {
        ZonedDateTime local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis),
                ZoneOffset.ofTotalSeconds(utcOffsetSeconds));
        double dayOfYear = local.getDayOfYear();
        double localHour = local.getHour() + local.getMinute() / 60.0D;
        double mean = meanAnnualC(profile, climate);
        double seasonal = seasonalAmplitudeC(profile)
                * Math.cos(2.0D * Math.PI * (dayOfYear - peakDayOf(profile)) / 365.25D);
        double humidity = estimateHumidity(profile, climate);
        double amplitude = diurnalAmplitudeC(humidity, climate);
        double diurnal = amplitude * diurnalShape(profile, utcOffsetSeconds, dayOfYear, localHour);
        StringBuilder sb = new StringBuilder();
        sb.append(profile.hasCoordinates()
                ? Text.tr("nowweather.offline.climatology.zonal", fmt(mean))
                : Text.tr("nowweather.offline.climatology.biome_mid", fmt(mean)));
        sb.append(Text.tr("nowweather.offline.climatology.season",
                fmtSigned(seasonal), profile.hemisphere().zhName(),
                fmt0(dayOfYear), fmt0(peakDayOf(profile))));
        if (profile.hasElevation()) {
            sb.append(Text.tr("nowweather.offline.climatology.elevation",
                    fmt0(profile.elevationMeters()),
                    fmt(LAPSE_RATE_C_PER_KM * profile.elevationMeters() / 1000.0D)));
        }
        sb.append(Text.tr("nowweather.offline.climatology.diurnal",
                fmtSigned(diurnal), fmt0(humidity * 100.0D), fmt(amplitude * 2.0D),
                daylightNote(profile, utcOffsetSeconds, dayOfYear, localHour)));
        return sb.toString();
    }

    // ------------------------------------------------------------------ 各分量

    /** 纬向年平均气温（线性插值）。 */
    public static double zonalMeanC(double latitude) {
        double lat = MathUtil.clamp(Math.abs(latitude), 0.0D, 90.0D);
        double position = lat / 10.0D;
        int low = (int) Math.floor(position);
        if (low >= ZONAL_MEAN_C.length - 1) {
            return ZONAL_MEAN_C[ZONAL_MEAN_C.length - 1];
        }
        double fraction = position - low;
        return ZONAL_MEAN_C[low] + (ZONAL_MEAN_C[low + 1] - ZONAL_MEAN_C[low]) * fraction;
    }

    /**
     * 季节振幅（半振幅，K）：热带几乎没有年较差，高纬极大。
     *
     * <p>优先用「距海岸距离」模型（{@link CoastDistance}）：同纬度上海洋性与大陆性的
     * 年较差可以差三倍（西雅图 47.6°N 距海 75km 振幅 7.4K vs 法戈 46.9°N 距海 1210km 振幅 17.1K）。
     * 只按纬度估振幅实测 R² 仅 0.53，加入距海距离后 0.84、留一交叉验证 RMSE 从 4.45K 降到 2.5K。</p>
     *
     * <p><b>降级路径</b>：没有坐标，或掩码资源不可用时，退回只按纬度的老公式
     * {@code A ≈ 1.5 + 0.30·|lat|}。该公式在海洋性西岸会显著高估（里斯本实测 5.5K、
     * 老公式给 13.1K），只作兜底，不要当成等价精度。</p>
     *
     * <p><b>已知失效区</b>：东亚季风型东岸（东京 +4.7K、哈利法克斯 +4.8K）、
     * 极端大陆性（雅库茨克 +10.4K）、热带高原（巴西利亚 −4.5K）。详见 {@link CoastDistance}。</p>
     */
    public static double seasonalAmplitudeC(LocationProfile profile) {
        if (!profile.hasCoordinates()) {
            return 8.0D;
        }
        double distance = CoastDistance.distanceToOceanKm(profile.latitude(), profile.longitude());
        if (Double.isNaN(distance)) {
            return LATITUDE_ONLY_AMPLITUDE_BASE
                    + LATITUDE_ONLY_AMPLITUDE_SLOPE * profile.absoluteLatitude();
        }
        return CoastDistance.seasonalAmplitudeC(profile.absoluteLatitude(), distance);
    }

    /** 只按纬度的兜底振幅系数（掩码不可用时使用）。 */
    public static final double LATITUDE_ONLY_AMPLITUDE_BASE = 1.5D;
    public static final double LATITUDE_ONLY_AMPLITUDE_SLOPE = 0.30D;

    /**
     * 昼夜温差的一半（振幅）：干燥 → 大，湿润 → 小。
     *
     * <p>这是有观测支撑的经验规律：沙漠日较差常达 15~20°C，而热带雨林只有 5~8°C。</p>
     */
    public static double diurnalAmplitudeC(double humidity01, BiomeClimate climate) {
        double humidity = MathUtil.clamp(humidity01, 0.0D, 1.0D);
        // 实测关系式（14 站十年日均数据拟合）：日较差 DTR ≈ 18.10 − 0.1398 × 相对湿度%
        //（R² = 0.797，是本报告里拟合最好的一条经验关系）。振幅 = DTR / 2。
        // 但 ERA5 是 ~30km 网格平均，会平滑掉极值，绝对日较差系统性偏低约 1.5K
        //（与 NOAA 1991-2020 站点平年值比对：迈阿密站点 7.5K vs ERA5 拟合 5.4K）。
        // 相对关系与 R² 不受影响，只做常数修正。
        double dtr = 18.10D - 0.1398D * humidity * 100.0D + DTR_ERA5_LOW_BIAS_C;
        double amplitude = dtr / 2.0D;
        if (climate != null) {
            // 干旱气候再放大一点，海洋性气候压小一点
            switch (climate.climateClass()) {
                case ARID, VOLCANIC -> amplitude *= 1.25D;
                case OCEANIC, COASTAL -> amplitude *= 0.75D;
                case WETLAND, TROPICAL -> amplitude *= 0.85D;
                default -> {
                }
            }
        }
        // 物理上下限：日较差 4K（持续阴雨的海洋性气候）~ 20K（干旱沙漠）
        return MathUtil.clamp(amplitude, 2.0D, 10.0D);
    }

    /** 由气候带与生物群系估计湿度 0~1。 */
    public static double estimateHumidity(LocationProfile profile, BiomeClimate climate) {
        if (climate != null) {
            // 群系气候的湿度是最可信的本地信息
            return MathUtil.clamp(climate.humidity(), 0.05D, 0.98D);
        }
        if (!profile.hasCoordinates()) {
            return 0.55D;
        }
        double lat = profile.absoluteLatitude();
        if (lat < 10.0D) {
            return 0.80D;          // 赤道辐合带
        }
        if (lat < 23.5D) {
            return 0.65D;
        }
        if (lat < 35.0D) {
            // 副热带：注意这里不能默认「干旱」—— 大陆西岸是地中海气候（夏干），
            // 而东亚是季风气候（夏湿、湿度常年 70% 以上）。没有海陆数据时取中性偏湿，
            // 真正的干湿交给玩家所在生物群系的湿度（可用时优先级更高）。
            return 0.50D;
        }
        if (lat < 60.0D) {
            return 0.62D;          // 西风带
        }
        return 0.50D;              // 极地干燥但低温
    }

    /**
     * 降水概率 0~1：纬度带 + 季节摆动 + 群系湿度的综合。
     */
    public static double precipitationProbability(LocationProfile profile, double dayOfYear,
                                                 BiomeClimate climate) {
        double base;
        if (profile.hasCoordinates()) {
            double lat = profile.absoluteLatitude();
            boolean summer = isLocalSummer(profile, dayOfYear);
            if (lat < 10.0D) {
                base = 0.45D;                                  // 赤道全年多雨
            } else if (lat < 23.5D) {
                base = summer ? 0.42D : 0.20D;                 // 季风/信风带
            } else if (lat < 35.0D) {
                // 同样不做「地中海式夏干」的默认假设（那只适用于大陆西岸），取较为中性的值
                base = summer ? 0.28D : 0.26D;
            } else if (lat < 60.0D) {
                base = summer ? 0.30D : 0.36D;                 // 西风带全年较湿
            } else {
                base = 0.22D;                                  // 极地干燥
            }
        } else {
            base = 0.32D;
        }
        if (climate != null) {
            // 群系湿度再修正一半权重
            base = (base + climate.humidity() * 0.7D) / 2.0D;
            if (!climate.canPrecipitate()) {
                base = 0.0D;
            }
        }
        return MathUtil.clamp(base, 0.0D, 0.95D);
    }

    /** 当地是否处于夏半年。 */
    public static boolean isLocalSummer(LocationProfile profile, double dayOfYear) {
        double peak = NORTHERN_PEAK_DAY + (profile.hemisphere() == LocationProfile.Hemisphere.SOUTH
                ? 182.6D : 0.0D);
        double delta = Math.abs(normalizeDayDelta(dayOfYear - peak));
        return delta < 91.3D;
    }

    private static double normalizeDayDelta(double days) {
        double d = days % 365.25D;
        if (d > 182.625D) {
            d -= 365.25D;
        }
        if (d < -182.625D) {
            d += 365.25D;
        }
        return d;
    }

    /**
     * 风向：按行星风带近似。
     *
     * <p>0°~30° 信风带偏东、30°~60° 西风带偏西、60°~90° 极地东风带偏东。
     * 不知道坐标时给一个随机但稳定的方向。</p>
     */
    public static WindDirection windDirectionFor(double latitude, long seed) {
        if (Double.isNaN(latitude)) {
            WindDirection[] all = {WindDirection.N, WindDirection.E, WindDirection.S, WindDirection.W,
                    WindDirection.NE, WindDirection.SE, WindDirection.SW, WindDirection.NW};
            return all[(int) (MathUtil.random01(seed, 201L) * all.length) % all.length];
        }
        double lat = Math.abs(latitude);
        double variation = MathUtil.randomRange(seed, 202L, -0.6D, 0.6D);
        if (lat < 30.0D) {
            // 信风：北半球东北风、南半球东南风
            return latitude >= 0 ? (variation < 0 ? WindDirection.NE : WindDirection.E)
                    : (variation < 0 ? WindDirection.SE : WindDirection.E);
        }
        if (lat < 60.0D) {
            // 西风带：北半球西南风、南半球西北风
            return latitude >= 0 ? (variation < 0 ? WindDirection.SW : WindDirection.W)
                    : (variation < 0 ? WindDirection.NW : WindDirection.W);
        }
        // 极地东风
        return latitude >= 0 ? (variation < 0 ? WindDirection.NE : WindDirection.E)
                : (variation < 0 ? WindDirection.SE : WindDirection.E);
    }

    /** 风级：气候带 + 季节 + 噪声。 */
    public static int windScaleFor(LocationProfile profile, long seed) {
        double base = 2.0D;
        if (profile.hasCoordinates()) {
            double lat = profile.absoluteLatitude();
            if (lat >= 35.0D && lat < 65.0D) {
                base = 4.0D;      // 西风带风大
            } else if (lat >= 65.0D) {
                base = 3.5D;
            } else if (lat < 10.0D) {
                base = 2.5D;      // 赤道无风带
            }
        }
        double noise = MathUtil.randomRange(seed, 203L, -1.2D, 1.2D);
        return MathUtil.clamp((int) Math.round(base + noise), 0, 11);
    }

    /** 可信度：依据越弱越诚实。 */
    public static double confidence(LocationProfile profile, WeatherObservation cached, long now) {
        double value = 0.25D;
        if (profile.isUsable()) {
            value = 0.35D;
        }
        if (profile.hasElevation()) {
            value += 0.03D;
        }
        if (profile.hasUtcOffset()) {
            value += 0.02D;
        }
        if (cached != null) {
            long age = now - cached.observedAtEpochMillis();
            if (age >= 0L && age < PERSISTENCE_MAX_AGE_MILLIS) {
                // 有实况锚点时最高 0.75（刚拿到的实况对「下一刻」确实很有预测力，
                // 但这毕竟是推算而不是实测，不该给出 0.85 这种虚高的可信度）
                value = Math.max(value, 0.75D - 0.30D * ((double) age / PERSISTENCE_MAX_AGE_MILLIS));
            }
        }
        return MathUtil.clamp(value, 0.1D, 0.75D);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 带正负号的定点格式化（{@code %.1f} 加 {@code +}）。 */
    private static String fmtSigned(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    /** 无小数位的定点格式化（{@code %.0f}）。 */
    private static String fmt0(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /** 便于测试与诊断：把推算依据拼成一行。 */
    public static String explain(Result result) {
        return result.explanation();
    }

    /** 用不到但保留：真实时间同步的昼夜相位（与推算器共用同一套时刻定义）。 */
    public static double sunHeight(long ticksOfDay) {
        return RealTimeSyncModel.sunHeight(ticksOfDay);
    }
}

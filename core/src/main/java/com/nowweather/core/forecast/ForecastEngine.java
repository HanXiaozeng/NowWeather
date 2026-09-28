package com.nowweather.core.forecast;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherCategory;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

import java.util.ArrayList;
import java.util.List;

/**
 * 预报引擎 —— 把「真实天气锚点」扩展成一段<b>符合本地气候</b>的天气序列。
 *
 * <h2>为什么不能直接问 API 要预报</h2>
 * UApiPro 只提供实况天气，而且现实世界的城市天气和「玩家所在的生物群系」并不对应 ——
 * 玩家可能站在雪原上，而他真实所在的城市正在 30°C 的晴天。因此正确做法是：
 * <ol>
 *   <li>用真实天气作为<b>锚点</b>（当前时刻的天气倾向）；</li>
 *   <li>用生物群系气候（尤其是<b>正常温度范围</b>）作为<b>约束</b>；</li>
 *   <li>用确定性的马尔可夫链 + 昼夜曲线生成后续序列；</li>
 *   <li>最后交给 {@link ClimateSanityFilter} 判定并修正不合理的数据。</li>
 * </ol>
 *
 * <p>整个生成过程只依赖 {@code seed}，因此服务端生成、客户端也能独立复现同一份预报，
 * 网络同步只需要同步「锚点」，带宽开销极小。</p>
 */
public final class ForecastEngine {

    private final ClimateSanityFilter filter;

    /**
     * 锚点温度向本地气候曲线过渡的「弛豫格数」。
     *
     * <p>第 0 格是真实观测（玩家此刻看到的温度），第 1 格如果直接跳到气候曲线，
     * 两格之间会出现 3~5 ℃ 的硬跳变 —— 在 HUD 上看起来就是「时间没怎么走，温度却变了 4 度」。
     * 用指数弛豫交接：近端以真实观测为准，远端完全交给本地气候。</p>
     */
    private static final double ANCHOR_RELAX_ENTRIES = 3.0D;

    public ForecastEngine() {
        this(new ClimateSanityFilter());
    }

    public ForecastEngine(ClimateSanityFilter filter) {
        this.filter = filter;
    }

    public ClimateSanityFilter filter() {
        return filter;
    }

    public Forecast generate(ForecastRequest request) {
        BiomeClimate climate = request.climate();
        WeatherObservation anchor = request.anchor();
        long seed = request.seed();
        List<ForecastEntry> entries = new ArrayList<>(request.horizon());

        WeatherCategory previous = initialCategory(climate, anchor);
        double anchorTemp = anchor != null && anchor.hasTemperature() ? anchor.temperatureC() : Double.NaN;
        long anchorDayTime = request.dayTime();

        for (int i = 0; i < request.horizon(); i++) {
            long entryStartTick = request.startTick() + i * request.ticksPerEntry();
            long entryDayTime = MathUtil.floorMod(request.dayTime() + i * request.ticksPerEntry(), 24000L);
            // 时间戳必须按真实刻长推进：开启真实时间同步后 1 刻 = 3600 毫秒（一天 = 真实 24 小时），
            // 仍按原版的 50 毫秒换算会让 HUD 上的预报格看起来只差 1 分钟。
            long entryEpoch = request.startEpochMillis()
                    + (long) (i * (double) request.ticksPerEntry() * request.millisPerTick());
            long entrySeed = MathUtil.mix(seed + i * 0x9E3779B97F4A7C15L);

            WeatherCategory category = (i == 0)
                    ? initialCategory(climate, anchor)
                    : nextCategory(climate, previous, entrySeed);
            previous = category;

            double humidity = MathUtil.clamp(
                    climate.humidity() + MathUtil.randomRange(entrySeed, 7L, -0.12D, 0.12D), 0.05D, 1.0D);
            double wind = MathUtil.clamp(
                    climate.windiness() + MathUtil.randomRange(entrySeed, 11L, -0.15D, 0.2D), 0.0D, 1.0D);

            double temperature = entryTemperature(request, climate, anchorTemp, anchorDayTime, entryDayTime, i);
            if (i > 0) {
                temperature = limitStep(climate, request, entries.get(i - 1).temperatureC(), temperature);
            }

            WeatherType type = toWeatherType(category, climate, humidity, wind, temperature, entrySeed);
            // 先按气候白名单收窄：预报只会生成该群系可能出现的天气
            if (!climate.allows(type)) {
                type = climate.nearestAllowed(type);
            }
            SanityReport report = filter.inspect(climate, type, temperature, humidity, wind);

            double confidence = MathUtil.clamp(
                    Math.exp(-i / 9.0D) * climate.confidence().weight() + 0.15D, 0.05D, 0.99D);

            entries.add(new ForecastEntry(i, entryStartTick, entryStartTick + request.ticksPerEntry(),
                    entryEpoch, report.adjustedType(), report.adjustedTempC(), humidity, wind,
                    confidence, report.violations()));
        }

        String provider = anchor == null ? "offline" : anchor.providerId();
        return Forecast.of(climate, provider, request.startEpochMillis(), request.startTick(),
                request.ticksPerEntry(), seed, entries);
    }

    // ------------------------------------------------------------------ 内部算法

    /**
     * 单格温度：第 0 格锚定真实观测，之后按指数弛豫交给本地气候的昼夜曲线。
     */
    private static double entryTemperature(ForecastRequest request, BiomeClimate climate, double anchorTemp,
                                           long anchorDayTime, long entryDayTime, int index) {
        double modeled = DiurnalTemperatureModel.temperatureAt(climate, anchorTemp, anchorDayTime, entryDayTime);
        if (Double.isNaN(anchorTemp)) {
            return modeled;
        }
        if (index == 0) {
            return anchorTemp;
        }
        double blend = 1.0D - Math.exp(-index / ANCHOR_RELAX_ENTRIES);
        return MathUtil.lerp(anchorTemp, modeled, blend);
    }

    /**
     * 限制相邻两格的温差。
     *
     * <p>昼夜曲线的最大变化率是 {@code 2πA/24} 摄氏度每小时（A 为该气候的日振幅），
     * 一格不该超过它的 1.6 倍 —— 否则 HUD 上会出现「一小时变 4 度」这种不像天气预报的数字。</p>
     */
    private static double limitStep(BiomeClimate climate, ForecastRequest request, double previousC,
                                    double currentC) {
        if (Double.isNaN(previousC) || Double.isNaN(currentC)) {
            return currentC;
        }
        double hoursPerEntry = request.ticksPerEntry() / 1000.0D;
        double maxSlopePerHour = 2.0D * Math.PI * DiurnalTemperatureModel.amplitude(climate) / 24.0D;
        double maxDelta = Math.max(0.2D, maxSlopePerHour * hoursPerEntry * 1.6D);
        return MathUtil.clamp(currentC, previousC - maxDelta, previousC + maxDelta);
    }

    private static WeatherCategory initialCategory(BiomeClimate climate, WeatherObservation anchor) {
        if (anchor != null && anchor.weatherType() != WeatherType.UNKNOWN) {
            WeatherCategory category = anchor.weatherType().category();
            if (!climate.canPrecipitate() && category.isPrecipitation()) {
                return WeatherCategory.CLOUDY;
            }
            return category;
        }
        if (!climate.canPrecipitate()) {
            return climate.climateClass() == ClimateClass.ARID ? WeatherCategory.CLEAR : WeatherCategory.CLOUDY;
        }
        return climate.humidity() > 0.6D ? WeatherCategory.CLOUDY : WeatherCategory.CLEAR;
    }

    /**
     * 马尔可夫转移：以一定概率维持当前天气，否则按该气候的权重重新抽样。
     */
    private static WeatherCategory nextCategory(BiomeClimate climate, WeatherCategory previous, long seed) {
        double persistence = MathUtil.clamp(0.38D + climate.humidity() * 0.3D, 0.25D, 0.8D);
        if (MathUtil.random01(seed, 1L) < persistence) {
            return previous;
        }
        double[] weights = weights(climate);
        double total = 0.0D;
        for (double w : weights) {
            total += w;
        }
        double roll = MathUtil.random01(seed, 2L) * total;
        double cumulative = 0.0D;
        WeatherCategory[] categories = WeatherCategory.values();
        for (int i = 0; i < categories.length; i++) {
            cumulative += weights[i];
            if (roll <= cumulative) {
                return categories[i];
            }
        }
        return previous;
    }

    /** 各天气大类在该气候下的出现权重（与 {@link WeatherCategory} 的顺序一一对应）。 */
    private static double[] weights(BiomeClimate climate) {
        double humidity = MathUtil.clamp(climate.humidity(), 0.0D, 1.0D);
        boolean wet = climate.canPrecipitate();
        boolean snowy = climate.precipitation() == PrecipitationType.SNOW;
        double wetness = wet ? humidity : 0.0D;

        double clear = 1.9D - wetness * 1.3D;
        double cloudy = 0.7D + wetness * 0.6D;
        double fog = humidity > 0.65D ? 0.20D * humidity : 0.0D;
        double rain = wetness * (snowy ? 0.25D : 1.5D);
        double snow = wetness * (snowy ? 1.7D : 0.15D);
        double storm = wet
                ? wetness * wetness * 0.8D * climate.thunderBias()
                : 0.0D;
        double dust = climate.climateClass() == ClimateClass.ARID
                ? 0.35D
                : (climate.climateClass() == ClimateClass.VOLCANIC ? 0.6D : 0.03D);
        double wind = 0.15D + climate.windiness() * 0.5D;

        // 顺序：CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM, DUST, WIND
        return new double[]{clear, cloudy, fog, rain, snow, storm, dust, wind};
    }

    private static WeatherType toWeatherType(WeatherCategory category, BiomeClimate climate, double humidity,
                                             double wind, double temperature, long seed) {
        double intensityNoise = MathUtil.randomRange(seed, 3L, -0.15D, 0.2D);
        switch (category) {
            case CLEAR: {
                double roll = MathUtil.random01(seed, 4L);
                if (roll < 0.25D && wind > 0.55D) {
                    return WeatherType.WINDY;
                }
                return roll < 0.4D ? WeatherType.MAINLY_CLEAR : WeatherType.CLEAR;
            }
            case CLOUDY: {
                double roll = MathUtil.random01(seed, 5L);
                if (roll < 0.35D) {
                    return WeatherType.PARTLY_CLOUDY;
                }
                return roll < 0.75D ? WeatherType.CLOUDY : WeatherType.OVERCAST;
            }
            case FOG:
                return MathUtil.random01(seed, 6L) < 0.6D ? WeatherType.FOG : WeatherType.HAZE;
            case RAIN: {
                double intensity = MathUtil.clamp(humidity + intensityNoise, 0.1D, 1.0D);
                WeatherType base = WeatherType.rainOf(intensity);
                if (temperature <= 1.0D) {
                    base = base.asFrozen(temperature <= -2.0D, temperature);
                }
                return base;
            }
            case SNOW: {
                double intensity = MathUtil.clamp(humidity + intensityNoise, 0.1D, 1.0D);
                if (temperature > 3.0D) {
                    return WeatherType.rainOf(intensity);
                }
                WeatherType base = WeatherType.snowOf(intensity);
                if (wind > 0.8D && intensity > 0.7D) {
                    return WeatherType.BLIZZARD;
                }
                return base;
            }
            case STORM: {
                double intensity = MathUtil.clamp(humidity + intensityNoise + 0.15D, 0.2D, 1.0D);
                double storminess = MathUtil.random01(seed, 8L);
                if (temperature <= -1.0D && storminess < 0.4D) {
                    return WeatherType.HAIL;
                }
                if (temperature <= 0.0D) {
                    return WeatherType.HEAVY_SNOW;
                }
                if (climate.thunderBias() < 0.5D) {
                    // 沙漠这类气候不该有雷暴，直接降级
                    return WeatherType.rainOf(intensity);
                }
                return storminess < 0.35D ? WeatherType.SEVERE_THUNDERSTORM : WeatherType.THUNDERSTORM;
            }
            case DUST:
                return wind > 0.6D && climate.humidity() < 0.3D ? WeatherType.SANDSTORM : WeatherType.HAZE;
            case WIND:
                return wind > 0.65D ? WeatherType.WINDY : WeatherType.PARTLY_CLOUDY;
            default:
                return WeatherType.CLOUDY;
        }
    }
}

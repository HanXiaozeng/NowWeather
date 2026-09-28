package com.nowweather.core.forecast;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.climate.TempRange;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 气候合理性过滤器 —— 「预报系统依赖每个生物群系配置好的正常温度范围，
 * 来判断生成的气象数据是否合理」这句话的代码实现。
 *
 * <p>它做两件事：</p>
 * <ol>
 *   <li><b>判定</b>：把每一条生成/外部获取的气象数据与该生物群系的正常温度范围、
 *       降水形态、雷电上限、风力上限做比对，记录所有不合理之处；</li>
 *   <li><b>修正</b>：给出一个「既贴近真实数据、又不违背该群系气候常识」的替代值 ——
 *       例如让沙漠不下雨、让雪原在 15°C 时不飘雪、把沙漠的雷暴降级为多云。</li>
 * </ol>
 *
 * <p>容差（{@code toleranceC}）是刻意保留的：现实世界总有短暂的极端天气，
 * 完全硬夹会让「真实天气」变得不真实。</p>
 */
public final class ClimateSanityFilter {

    private final double toleranceC;
    private final boolean allowExtremeEvents;

    public ClimateSanityFilter() {
        this(6.0D, true);
    }

    public ClimateSanityFilter(double toleranceC, boolean allowExtremeEvents) {
        this.toleranceC = Math.max(0.0D, toleranceC);
        this.allowExtremeEvents = allowExtremeEvents;
    }

    public double toleranceC() {
        return toleranceC;
    }

    /**
     * 校验并修正一条气象数据。
     *
     * @param climate   生物群系气候（提供正常温度范围）
     * @param type      待校验的天气类型
     * @param tempC     待校验的温度
     * @param humidity01 归一化湿度 0~1
     * @param wind01    归一化风力 0~1
     */
    public SanityReport inspect(BiomeClimate climate, WeatherType type, double tempC,
                                double humidity01, double wind01) {
        List<SanityViolation> violations = new ArrayList<>();
        double humidity = MathUtil.clamp(humidity01, 0.0D, 1.0D);
        double wind = MathUtil.clamp(wind01, 0.0D, 1.0D);
        WeatherType adjusted = type == null ? WeatherType.UNKNOWN : type;

        // ---- 1. 温度是否落在正常范围内 ----
        TempRange range = climate.tempRange();
        double clampedTemp = tempC;
        if (!Double.isNaN(tempC)) {
            if (tempC > range.maxC() + toleranceC) {
                violations.add(SanityViolation.TEMPERATURE_ABOVE_RANGE);
                clampedTemp = range.maxC() + (allowExtremeEvents ? toleranceC * 0.5D : 0.0D);
            } else if (tempC < range.minC() - toleranceC) {
                violations.add(SanityViolation.TEMPERATURE_BELOW_RANGE);
                clampedTemp = range.minC() - (allowExtremeEvents ? toleranceC * 0.5D : 0.0D);
            }
        }

        // ---- 2. 干旱气候不该有降水 ----
        if (adjusted.isPrecipitation() && !climate.canPrecipitate()) {
            violations.add(SanityViolation.PRECIPITATION_IN_DRY_CLIMATE);
            adjusted = switch (climate.climateClass()) {
                case ARID, VOLCANIC -> wind >= 0.8D ? WeatherType.SANDSTORM : WeatherType.HAZE;
                case NETHER -> WeatherType.SMOKE;
                default -> WeatherType.CLOUDY;
            };
        }

        // ---- 3. 降水形态要和温度一致 ----
        if (adjusted.isPrecipitation() && !Double.isNaN(clampedTemp)) {
            boolean frozenForm = adjusted.isFrozen();
            if (frozenForm && clampedTemp > 2.0D && !allowExtremeEvents) {
                violations.add(SanityViolation.SNOW_ABOVE_FREEZING);
                adjusted = WeatherType.rainOf(adjusted.precipitation());
            } else if (frozenForm && clampedTemp > 6.0D) {
                violations.add(SanityViolation.SNOW_ABOVE_FREEZING);
                adjusted = WeatherType.rainOf(adjusted.precipitation());
            } else if (!frozenForm && adjusted != WeatherType.FREEZING_RAIN && clampedTemp < -2.0D) {
                violations.add(SanityViolation.RAIN_BELOW_FREEZING);
                adjusted = WeatherType.snowOf(adjusted.precipitation());
            } else if (adjusted == WeatherType.FREEZING_RAIN && clampedTemp > 3.0D) {
                violations.add(SanityViolation.FREEZING_RAIN_IN_WARM_CLIMATE);
                adjusted = WeatherType.rainOf(adjusted.precipitation());
            } else if (clampedTemp > -2.0D && clampedTemp < 2.0D && adjusted.isPrecipitation()
                    && adjusted != WeatherType.SLEET && adjusted.category() == com.nowweather.core.weather.WeatherCategory.RAIN) {
                adjusted = WeatherType.SLEET;
            }
        }
        // 气候本身声明的降水形态（例如雪原群系）优先于温度推断
        if (adjusted.isPrecipitation() && climate.precipitation() == PrecipitationType.SNOW
                && !adjusted.isFrozen() && clampedTemp <= 1.0D) {
            adjusted = WeatherType.snowOf(adjusted.precipitation());
        }

        // ---- 4. 雷电 ----
        // 注意：「有雷电却完全没有降水」这一分支在当前天气表下是**触发不到**的 ——
        // 所有 thunder > 0 的类型降水强度都 > 0，非降水类型的 thunder 全部是 0.00。
        // 这里刻意保留而不是删掉：它是一道防御性闸门，将来一旦加入「干雷暴」这类
        // 有雷电、无降水的天气，就是它在兜底，免得平原上莫名其妙打雷。
        if (adjusted.isThundering()) {
            if (!adjusted.isPrecipitation()) {
                violations.add(SanityViolation.THUNDER_WITHOUT_PRECIPITATION);
                adjusted = WeatherType.HEAVY_RAIN;
            }
            double maxStorm = climate.maxStormIntensity();
            if (adjusted.thunder() > maxStorm + 0.2D) {
                violations.add(SanityViolation.THUNDER_TOO_STRONG_FOR_CLIMATE);
                adjusted = adjusted.thunder() > 0.8D ? WeatherType.THUNDERSTORM : WeatherType.RAIN;
                if (!climate.canPrecipitate()) {
                    adjusted = WeatherType.CLOUDY;
                }
            }
        }

        // ---- 5. 风力 ----
        double maxWind = MathUtil.clamp(climate.windiness() + 0.35D, 0.2D, 1.0D);
        if (wind > maxWind + 0.15D && adjusted == WeatherType.SANDSTORM) {
            violations.add(SanityViolation.DUST_STORM_IN_WET_CLIMATE);
            adjusted = WeatherType.WINDY;
        } else if (wind > maxWind + 0.15D) {
            violations.add(SanityViolation.WIND_TOO_STRONG_FOR_CLIMATE);
            if (adjusted.isPrecipitation() && adjusted != WeatherType.BLIZZARD) {
                adjusted = WeatherType.rainOf(adjusted.precipitation());
            }
        }

        // ---- 6. 雾与干旱 ----
        if ((adjusted == WeatherType.FOG || adjusted == WeatherType.HAZE)
                && climate.humidity() < 0.25D && humidity < 0.3D) {
            violations.add(SanityViolation.FOG_IN_DRY_CLIMATE);
            adjusted = WeatherType.CLEAR;
        }

        // ---- 7. 极端事件开关：配置不允许极端天气时，把台风/强雷暴/沙尘暴/暴风雪/冰雹降级 ----
        if (!allowExtremeEvents && isExtreme(adjusted)) {
            violations.add(SanityViolation.EXTREME_EVENT_NOT_ALLOWED);
            adjusted = downgrade(climate, adjusted);
        }

        // ---- 8. 天气白名单：该群系根本不会出现的天气一律换掉 ----
        // 这是最后一道闸门：前面所有修正都做完后，再确认结果确实属于该生物群系可能的天气。
        if (!climate.allows(adjusted)) {
            violations.add(SanityViolation.WEATHER_NOT_POSSIBLE_IN_CLIMATE);
            adjusted = climate.nearestAllowed(adjusted);
            if (!allowExtremeEvents && isExtreme(adjusted)) {
                adjusted = downgrade(climate, adjusted);
            }
        }

        return new SanityReport(adjusted, clampedTemp, violations);
    }

    /** 是否属于「极端天气事件」。 */
    public static boolean isExtreme(WeatherType type) {
        return type == WeatherType.SEVERE_THUNDERSTORM || type == WeatherType.TYPHOON
                || type == WeatherType.BLIZZARD || type == WeatherType.SANDSTORM
                || type == WeatherType.HAIL;
    }

    /** 把极端天气降级为同一气候下「温和但同类」的天气。 */
    private static WeatherType downgrade(BiomeClimate climate, WeatherType type) {
        WeatherType candidate = switch (type) {
            case SANDSTORM -> WeatherType.HAZE;
            case BLIZZARD -> WeatherType.HEAVY_SNOW;
            case TYPHOON -> WeatherType.HEAVY_RAIN;
            case HAIL -> WeatherType.SLEET;
            case SEVERE_THUNDERSTORM -> WeatherType.THUNDERSTORM;
            default -> WeatherType.PARTLY_CLOUDY;
        };
        if (!climate.allows(candidate)) {
            candidate = climate.nearestAllowed(candidate);
        }
        if (isExtreme(candidate)) {
            candidate = climate.nearestAllowed(WeatherType.PARTLY_CLOUDY);
        }
        if (isExtreme(candidate)) {
            candidate = WeatherType.CLEAR;
        }
        return candidate;
    }

    /** 只判定、不修正（诊断命令用）。 */
    public List<SanityViolation> detect(BiomeClimate climate, WeatherType type, double tempC,
                                       double humidity01, double wind01) {
        return inspect(climate, type, tempC, humidity01, wind01).violations();
    }
}

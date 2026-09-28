package com.nowweather.core.climate;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherType;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「该生物群系可能出现哪些天气」的推导器。
 *
 * <p>这是气候映射的第二条硬约束（第一条是<b>正常温度范围</b>）：
 * 温度范围回答「温度合不合理」，天气白名单回答「这种天气在这里根本会不会发生」。
 * 例如沙漠的温度范围可以容纳 20°C，但沙漠永远不会下雨；下界既不下雨也不下雪。</p>
 *
 * <p>推导结果会被</p>
 * <ul>
 *   <li>{@code ClimateSanityFilter} 用来把不合理的天气改成该群系真实可能出现的天气；</li>
 *   <li>{@code ForecastEngine} 用来限制预报只会生成白名单内的天气；</li>
 *   <li>命令 {@code /NowWeather biome} 用来向玩家展示「本地可能出现的天气」。</li>
 * </ul>
 *
 * <p>推导规则刻意偏「宽容」：宁可多列一种少见天气，也不要因为漏列而让真实天气被误判掉。</p>
 */
public final class WeatherAllowance {

    /** 任何地方都会出现的「没有天气」状态。 */
    private static final List<WeatherType> ALWAYS = List.of(
            WeatherType.CLEAR, WeatherType.MAINLY_CLEAR, WeatherType.PARTLY_CLOUDY,
            WeatherType.CLOUDY, WeatherType.OVERCAST);

    private WeatherAllowance() {
    }

    /**
     * 依据气候画像推导可能的天气集合。
     *
     * @param climate 生物群系气候
     * @return 不可变集合，永远非空
     */
    public static Set<WeatherType> derive(BiomeClimate climate) {
        EnumSet<WeatherType> allowed = EnumSet.noneOf(WeatherType.class);
        ClimateClass climateClass = climate.climateClass();
        TempRange range = climate.tempRange();
        double humidity = climate.humidity();
        double wind = climate.windiness();
        boolean wet = climate.canPrecipitate();

        // --- 无天气环境：下界 / 末地 / 虚空 / 洞穴 ---
        switch (climateClass) {
            case NETHER -> {
                allowed.add(WeatherType.CLEAR);
                allowed.add(WeatherType.HAZE);
                allowed.add(WeatherType.SMOKE);
                allowed.add(WeatherType.WINDY);
                if (humidity < 0.2D) {
                    allowed.add(WeatherType.SANDSTORM);
                }
                return Set.copyOf(allowed);
            }
            case END -> {
                allowed.add(WeatherType.CLEAR);
                allowed.add(WeatherType.PARTLY_CLOUDY);
                allowed.add(WeatherType.CLOUDY);
                allowed.add(WeatherType.HAZE);
                allowed.add(WeatherType.WINDY);
                return Set.copyOf(allowed);
            }
            case VOID -> {
                allowed.add(WeatherType.CLEAR);
                allowed.add(WeatherType.HAZE);
                return Set.copyOf(allowed);
            }
            case CAVE -> {
                allowed.addAll(ALWAYS);
                if (humidity >= 0.85D) {
                    allowed.add(WeatherType.FOG);
                }
                if (range.maxC() >= 30.0D) {
                    allowed.add(WeatherType.HAZE);
                }
                return Set.copyOf(allowed);
            }
            default -> allowed.addAll(ALWAYS);
        }

        // --- 干旱气候：只可能霾 / 浮尘 / 沙尘暴 ---
        if (!wet) {
            allowed.add(WeatherType.HAZE);
            if (climateClass == ClimateClass.ARID || climateClass == ClimateClass.VOLCANIC) {
                allowed.add(WeatherType.SANDSTORM);
            }
            if (climateClass == ClimateClass.POLAR) {
                // 极地虽然「干燥」，但原版雪原依然会下雪，属于特例
                addSnow(allowed, range, wind, climate.precipitation());
            }
            if (wind >= 0.7D) {
                allowed.add(WeatherType.WINDY);
            }
            return Set.copyOf(allowed);
        }

        // --- 气温决定的降水形态 ---
        if (range.maxC() >= 2.0D) {
            allowed.add(WeatherType.DRIZZLE);
            allowed.add(WeatherType.LIGHT_RAIN);
            allowed.add(WeatherType.RAIN);
            if (humidity >= 0.55D) {
                allowed.add(WeatherType.HEAVY_RAIN);
            }
            if (range.minC() <= 1.0D) {
                allowed.add(WeatherType.SLEET);
                allowed.add(WeatherType.FREEZING_RAIN);
            }
        }
        if (range.minC() <= 1.0D) {
            addSnow(allowed, range, wind, climate.precipitation());
        }

        // --- 雷电：由气候的雷暴倾向决定 ---
        //
        // 注：冷群系（雪原、高山雪线）也允许**弱**雷暴，这是刻意行为 ——
        // 「雷打雪」（thundersnow）真实存在，原版雪原也会打雷，所以不能一刀切禁掉；
        // 但强雷暴属于暖湿对流天气，由 thunderBias/humidity 门槛排除。
        // 真机验证 Terralith 时曾怀疑这是 bug（terralith:alpine_grove 范围 -21.7~1.7°C 却列了雷暴），
        // 查证后发现 AllowanceTests「雪原允许雷打雪」正是在断言这个行为，故保持原样。
        double thunderBias = climate.thunderBias();
        if (thunderBias >= 0.45D) {
            allowed.add(WeatherType.THUNDERSTORM);
        }
        if (thunderBias >= 0.95D && humidity >= 0.6D) {
            allowed.add(WeatherType.SEVERE_THUNDERSTORM);
        }
        if (thunderBias >= 0.9D && range.minC() <= 2.0D && range.maxC() >= 6.0D) {
            allowed.add(WeatherType.HAIL);
        }
        // 热带气旋：极少数热带海洋性气候才可能出现
        if (climateClass == ClimateClass.TROPICAL && humidity >= 0.85D && wind >= 0.6D) {
            allowed.add(WeatherType.TYPHOON);
        }

        // --- 干旱 / 火山大类的「沙尘侧」---
        //
        // 即使这个群系能降水，只要它仍归在干旱或火山大类下，就该保留沙尘暴：
        // 荒地是「偶尔下雨的热荒漠」，不是温带草原，沙尘暴正是它的招牌天气。
        // 以前沙尘暴被上面 `if (!wet)` 那条分支独占，两者互斥 ——
        // 于是给超多生物群系的荒地开了雨，就顺带把它的沙尘暴一起弄丢了。
        if ((climateClass == ClimateClass.ARID || climateClass == ClimateClass.VOLCANIC)
                && wind >= 0.5D) {
            allowed.add(WeatherType.SANDSTORM);
        }

        // --- 能见度与风 ---
        if (humidity >= 0.60D) {
            allowed.add(WeatherType.FOG);
        }
        if (humidity < 0.30D) {
            allowed.add(WeatherType.HAZE);
        }
        if (wind >= 0.55D) {
            allowed.add(WeatherType.WINDY);
        }
        return Set.copyOf(allowed);
    }

    private static void addSnow(EnumSet<WeatherType> allowed, TempRange range, double wind,
                                PrecipitationType precipitation) {
        if (precipitation == PrecipitationType.NONE) {
            return;
        }
        allowed.add(WeatherType.LIGHT_SNOW);
        allowed.add(WeatherType.SNOW);
        allowed.add(WeatherType.SLEET);
        if (range.minC() <= -8.0D) {
            allowed.add(WeatherType.HEAVY_SNOW);
        }
        // 暴风雪需要「足够冷 + 足够大风」，两者缺一不可
        if (wind >= 0.55D && range.minC() <= -5.0D) {
            allowed.add(WeatherType.BLIZZARD);
        }
    }

    /**
     * 把扩展天气白名单压成「原版三档天气」白名单 —— 也就是玩家真正会看到的天气。
     *
     * <p>映射规则：雷电强度达到阈值 → 雷暴，有降水 → 降雨，其余为晴朗。
     * 这里用「雷电强度阈值」而不是 {@code thunder > 0}，否则沙尘暴这种带一点点雷电参数的天气
     * 会被错误地算成原版雷暴。</p>
     *
     * <p><b>与 {@code SyncPolicy} 有一处刻意的差异</b>：真正落地时还会要求降水强度达到配置项
     * {@code mapping.rainIntensityThreshold}（默认 0.22），而这里只看「是不是降水类型」。
     * 于是小雨 / 毛毛雨这类低强度降水在<b>这里算降雨、落地时可能被算成晴朗</b>。
     * 这是有意的：本方法回答的是「这个群系<b>可能</b>出现哪些原版天气」，是能力上界，应当宽松；
     * 而落地要对已经发生的那一次观测负责，必须严格。
     * （以前这里的注释写着「与 SyncPolicy 保持一致」，与实现不符，已订正。）</p>
     */
    public static Set<PlatformWeather> toPlatformWeather(Set<WeatherType> allowedWeather) {
        LinkedHashSet<PlatformWeather> result = new LinkedHashSet<>();
        for (WeatherType type : allowedWeather) {
            if (type.thunder() >= THUNDER_THRESHOLD) {
                result.add(PlatformWeather.THUNDER);
            } else if (type.isPrecipitation()) {
                result.add(PlatformWeather.RAIN);
            } else {
                result.add(PlatformWeather.CLEAR);
            }
        }
        if (result.isEmpty()) {
            result.add(PlatformWeather.CLEAR);
        }
        return Set.copyOf(result);
    }

    /** 判定「原版雷暴」所需的雷电强度阈值（与默认配置的 thunderIntensityThreshold 一致）。 */
    public static final double THUNDER_THRESHOLD = 0.55D;

    /** 人类可读的天气白名单描述（命令输出用）。 */
    public static String describe(Set<WeatherType> allowedWeather) {
        StringBuilder sb = new StringBuilder();
        for (WeatherType type : allowedWeather) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(type.zhName());
        }
        return sb.toString();
    }

    /** 原版三档的中文描述。 */
    /**
     * 面向玩家的原版天气描述：把 MC 的 {@code RAIN} 按<b>本地降水形态</b>写成 降雨 / 降雪 / 雨夹雪。
     *
     * <p>MC 的平台天气枚举只有 <b>CLEAR / RAIN / THUNDER</b> 三项，雪是靠
     * 「生物群系温度 &lt; 0.15 且正在下雨」渲染出来的 —— 所以对雪线群系直接显示
     * 「降雨」在逻辑上没错，在界面上却是误导：真机验证 Terralith 时，
     * {@code terralith:alpine_grove}（降水形态雪、正常温度范围 -21.7~1.7°C）
     * 的原版天气被显示成「雷暴/晴朗/降雨」，看的人第一反应是模组判错了。</p>
     */
    public static String describePlatformForPlayer(Set<PlatformWeather> allowedWeather,
                                                   BiomeClimate climate) {
        if (climate == null) {
            return describePlatform(allowedWeather);
        }
        StringBuilder sb = new StringBuilder();
        for (PlatformWeather weather : allowedWeather) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            if (weather == PlatformWeather.RAIN) {
                sb.append(switch (climate.precipitation()) {
                    case SNOW -> Text.tr("nowweather.platform_weather.as_snow");
                    case SLEET -> Text.tr("nowweather.platform_weather.as_sleet");
                    case MIXED -> Text.tr("nowweather.platform_weather.as_rain_snow");
                    default -> weather.zhName();
                });
            } else {
                sb.append(weather.zhName());
            }
        }
        return sb.toString();
    }

    public static String describePlatform(Set<PlatformWeather> allowedWeather) {
        StringBuilder sb = new StringBuilder();
        for (PlatformWeather weather : allowedWeather) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(weather.zhName());
        }
        return sb.toString();
    }
}

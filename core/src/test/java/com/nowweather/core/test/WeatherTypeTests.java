package com.nowweather.core.test;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.sync.SyncPolicy;
import com.nowweather.core.sync.WeatherDecision;
import com.nowweather.core.weather.WeatherCategory;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

import java.util.Set;

/** 天气类型属性、决策阈值、序列化测试。 */
final class WeatherTypeTests {

    private static final BiomeClimateRegistry REGISTRY = new BiomeClimateRegistry(Loggers.noop());

    private WeatherTypeTests() {
    }

    static void run() {
        typeProperties();
        policyThresholds();
        configRoundTrip();
        observationRoundTrip();
        skyMood();
    }

    /**
     * 天色模型：现实天气 → 雾色偏移 / 雾距。
     *
     * <p>要点是「只做偏移、不做替换」，所以这里同时校验方向和量级 ——
     * 天色偏得太狠玩家会以为游戏坏了，偏得太小又等于没做。</p>
     */
    private static void skyMood() {
        T.section("天色（暴晒 / 阴沉 / 雨雷 / 雾霾 / 沙尘）");
        double noon = 1.0D;
        double night = -1.0D;

        com.nowweather.core.weather.SkyMood clear =
                com.nowweather.core.weather.SkyMood.of(WeatherType.CLEAR, noon);
        com.nowweather.core.weather.SkyMood overcast =
                com.nowweather.core.weather.SkyMood.of(WeatherType.OVERCAST, noon);
        com.nowweather.core.weather.SkyMood storm =
                com.nowweather.core.weather.SkyMood.of(WeatherType.SEVERE_THUNDERSTORM, noon);
        com.nowweather.core.weather.SkyMood rain =
                com.nowweather.core.weather.SkyMood.of(WeatherType.RAIN, noon);
        com.nowweather.core.weather.SkyMood fog =
                com.nowweather.core.weather.SkyMood.of(WeatherType.FOG, noon);
        com.nowweather.core.weather.SkyMood sand =
                com.nowweather.core.weather.SkyMood.of(WeatherType.SANDSTORM, noon);
        com.nowweather.core.weather.SkyMood clearNight =
                com.nowweather.core.weather.SkyMood.of(WeatherType.CLEAR, night);

        T.eq("晴天正午 -> 暴晒天色", "暴晒天色", clear.label());
        T.check("暴晒会提亮", clear.red() > 1.0D && clear.green() > 1.0D);
        T.check("暴晒偏暖（红大于蓝）", clear.red() > clear.blue());
        T.check("暴晒把雾推远", clear.fogDensityFactor() > 1.0D);

        T.check("阴天去饱和并压暗",
                overcast.red() < 1.0D && overcast.green() < 1.0D && overcast.blue() < 1.0D);
        T.check("阴天比晴暗、比雷暴亮",
                overcast.red() < clear.red() && overcast.red() > storm.red());

        T.check("雷暴明显压暗", storm.red() < 0.8D);
        T.eq("雷暴天色标签", "雷暴天色", storm.label());
        T.check("雨比阴天更暗", rain.red() < overcast.red());
        T.check("雨偏冷（蓝大于红）", rain.blue() > rain.red());

        T.check("雾把雾距拉到最近", fog.fogDensityFactor() < 0.45D);
        T.check("沙尘明显偏黄（红大于蓝）", sand.red() > sand.blue() * 1.15D);
        T.check("沙尘雾距也拉近", sand.fogDensityFactor() < 0.6D);

        T.check("夜里晴天不暴晒（不提亮）", clearNight.red() <= 1.0D + 1.0E-9D);
        T.check("夜里的晴与正午的晴不同", clearNight.red() < clear.red());

        boolean sane = true;
        for (WeatherType type : WeatherType.values()) {
            for (double sun = -1.0D; sun <= 1.0D; sun += 0.25D) {
                var mood = com.nowweather.core.weather.SkyMood.of(type, sun);
                if (mood.red() < 0.15D || mood.red() > 1.32D
                        || mood.green() < 0.15D || mood.green() > 1.32D
                        || mood.blue() < 0.15D || mood.blue() > 1.32D
                        || mood.fogDensityFactor() < 0.15D || mood.fogDensityFactor() > 1.5D) {
                    sane = false;
                }
            }
        }
        T.check("全部天气 x 全天的天色系数都在安全范围内", sane);

        float[] applied = sand.applyTo(0.9F, 0.9F, 0.9F);
        T.check("叠加到基色后仍夹在 0~1",
                applied[0] >= 0.0F && applied[0] <= 1.0F
                        && applied[1] >= 0.0F && applied[1] <= 1.0F
                        && applied[2] >= 0.0F && applied[2] <= 1.0F);
        T.check("中性天色不变色", com.nowweather.core.weather.SkyMood.NEUTRAL.isNeutral());
        stormRainTier();
        shaderCloudSignal();
    }

    /**
     * 光影的 rainStrength：云量必须能翻译过去。
     *
     * <p>这是「阴天在光影里也是阴沉」的关键 —— 光影只有 rainStrength 一个天气输入，
     * 云量不给它，它就认为万里无云、画一个大太阳。</p>
     */
    /**
     * 暴雨档与白名单「家族规则」。
     *
     * <p>白名单是逐档列举的（250 个群系每个都单独列了 HEAVY_RAIN），加一档强度就要改 250 条数据
     * 不现实；而「同种现象但更强」本来就该被允许 —— 暴雨不是另一种天气，只是更大的大雨。</p>
     */
    private static void stormRainTier() {
        T.section("暴雨档与降水强度家族规则");
        T.eq("暴雨有独立分档", "暴雨", WeatherType.STORM_RAIN.zhName());
        T.check("暴雨强度高于大雨",
                WeatherType.STORM_RAIN.precipitation() > WeatherType.HEAVY_RAIN.precipitation());
        T.check("暴雨仍属降水", WeatherType.STORM_RAIN.isPrecipitation());
        T.check("暴雨不等于打雷（thunder 低于雷暴阈值）",
                WeatherType.STORM_RAIN.thunder() < WeatherType.THUNDERSTORM.thunder());

        var registry = new com.nowweather.core.climate.BiomeClimateRegistry(
                com.nowweather.core.log.Loggers.noop());
        com.nowweather.core.climate.BiomeClimate plains = registry.resolve(
                new com.nowweather.core.climate.BiomeFacts(
                        "minecraft:plains", 0.8F, 0.4F,
                        com.nowweather.core.climate.PrecipitationType.RAIN,
                        "plains", java.util.Set.of(), "minecraft:overworld", false));
        com.nowweather.core.climate.BiomeClimate desert = registry.resolve(
                new com.nowweather.core.climate.BiomeFacts(
                        "minecraft:desert", 2.0F, 0.0F,
                        com.nowweather.core.climate.PrecipitationType.NONE,
                        "desert", java.util.Set.of(), "minecraft:overworld", false));

        T.check("平原能降水", plains.canPrecipitate());
        T.check("沙漠不能降水", !desert.canPrecipitate());

        // ★ 核心要求：能降水的群系里，真实暴雨绝不能被修正成「不下雨」
        WeatherType plainsStorm = plains.nearestAllowed(WeatherType.STORM_RAIN);
        T.check("平原：暴雨仍被修正为某种降水（不会变成多云/晴）",
                plainsStorm.isPrecipitation());
        T.check("平原：修正是同形态的雨（不是雪）",
                !plainsStorm.isFrozen());

        WeatherType plainsSnow = plains.nearestAllowed(WeatherType.HEAVY_SNOW);
        T.check("平原：暴雪也不会被修正成不下雪", plainsSnow.isPrecipitation());

        // 沙漠这类完全不能降水的群系，才允许改成多云/霾
        WeatherType desertStorm = desert.nearestAllowed(WeatherType.STORM_RAIN);
        T.check("沙漠：暴雨被修正为非降水", !desertStorm.isPrecipitation());
        T.check("沙漠：修正结果确实是白名单里的",
                desert.possibleWeather().contains(desertStorm));
    }

    private static void shaderCloudSignal() {
        T.section("光影云量信号（云量 -> rainStrength，双区间编码）");
        // 用模组常量而不是写死数字，避免改了常量忘了改测试
        double scale = com.nowweather.core.weather.SkyMood.CLOUD_BAND_MAX;

        T.near("晴（云量 0）-> 0",
                0.0F, com.nowweather.core.weather.SkyMood.shaderRainStrength(0.0D, WeatherType.CLEAR, scale), 1.0E-6D);
        // 双区间编码：非降水落在云量区间（0~0.60）
        T.near("阴（云量 0.95）-> 0.95 × 0.60 = 0.57",
                0.57F, com.nowweather.core.weather.SkyMood.shaderRainStrength(0.95D, WeatherType.OVERCAST, scale), 1.0E-4D);
        T.check("阴天的信号落在云量区间内（不越过缓冲区）",
                com.nowweather.core.weather.SkyMood.shaderRainStrength(1.0D, WeatherType.OVERCAST, scale)
                        <= (float) com.nowweather.core.weather.SkyMood.CLOUD_BAND_MAX + 1.0E-6F);
        T.check("云越多信号越强",
                com.nowweather.core.weather.SkyMood.shaderRainStrength(0.9D, WeatherType.OVERCAST, scale)
                        > com.nowweather.core.weather.SkyMood.shaderRainStrength(0.4D, WeatherType.PARTLY_CLOUDY, scale));

        // 真降水不能被云量倍率压低
        T.check("中雨不受倍率影响（用真实降水强度）",
                com.nowweather.core.weather.SkyMood.shaderRainStrength(0.1D, WeatherType.RAIN, 0.2D)
                        >= (float) WeatherType.RAIN.precipitation() - 1.0E-6F);
        T.check("雷暴至少给 0.85",
                com.nowweather.core.weather.SkyMood.shaderRainStrength(0.0D, WeatherType.THUNDERSTORM, 0.0D) >= 0.85F);

        // 没有真实云量时退回天气类型自带的云量
        T.check("云量缺失时按天气类型推算",
                com.nowweather.core.weather.SkyMood.shaderRainStrength(Double.NaN, WeatherType.OVERCAST, scale) > 0.5F);

        // 永远夹在 0~1
        boolean sane = true;
        for (WeatherType type : WeatherType.values()) {
            for (double cloud = -0.5D; cloud <= 1.5D; cloud += 0.25D) {
                float value = com.nowweather.core.weather.SkyMood.shaderRainStrength(cloud, type, scale);
                if (value < 0.0F || value > 1.0F) {
                    sane = false;
                }
            }
        }
        T.check("全部天气 x 全部云量的信号都在 0~1", sane);
        // ★ 光影包依赖的不变量：两个区间绝不能重叠，否则「阴天」会被光影当成「在下雨」
        T.check("云量区间上界 < 降水区间下界（缓冲区存在）",
                com.nowweather.core.weather.SkyMood.CLOUD_BAND_MAX
                        < com.nowweather.core.weather.SkyMood.RAIN_BAND_MIN);
        boolean bandOk = true;
        boolean rainOk = true;
        for (WeatherType type : WeatherType.values()) {
            for (double cloud = 0.0D; cloud <= 1.0D; cloud += 0.1D) {
                float v = com.nowweather.core.weather.SkyMood.shaderRainStrength(cloud, type, scale);
                if (type.isPrecipitation()) {
                    if (v < (float) com.nowweather.core.weather.SkyMood.RAIN_BAND_MIN - 1.0E-6F) {
                        rainOk = false;
                    }
                } else if (type != WeatherType.UNKNOWN) {
                    if (v > (float) com.nowweather.core.weather.SkyMood.CLOUD_BAND_MAX + 1.0E-6F) {
                        bandOk = false;
                    }
                }
            }
        }
        T.check("所有非降水天气的信号都不会进入降水区间", bandOk);
        T.check("所有降水天气的信号都不会落在云量区间", rainOk);
        T.check("倍率为 0 且无降水时信号为 0",
                com.nowweather.core.weather.SkyMood.shaderRainStrength(1.0D, WeatherType.OVERCAST, 0.0D) == 0.0F);
    }

    private static void typeProperties() {
        T.section("天气类型属性");
        T.check("晴：无云无雨", WeatherType.CLEAR.cloudCover() == 0.0D && !WeatherType.CLEAR.isPrecipitation());
        T.check("暴雨：降水强度高", WeatherType.HEAVY_RAIN.precipitation() > 0.7D);
        T.check("雷暴：带雷电", WeatherType.THUNDERSTORM.isThundering());
        T.check("暴风雪：既是降水又是冻结", WeatherType.BLIZZARD.isPrecipitation() && WeatherType.BLIZZARD.isFrozen());
        T.check("沙尘暴：非降水但恶劣", !WeatherType.SANDSTORM.isPrecipitation()
                && WeatherType.SANDSTORM.severity() > 0.1D);
        T.check("等级排序：强雷暴 > 小雨",
                WeatherType.SEVERE_THUNDERSTORM.severity() > WeatherType.LIGHT_RAIN.severity());
        T.check("小雨 → 分类为雨", WeatherType.LIGHT_RAIN.category() == WeatherCategory.RAIN);
        T.check("雾会降低能见度", WeatherType.FOG.reducesVisibility());

        T.eq("按强度取雨(0.1) → 毛毛雨", WeatherType.DRIZZLE.name(), WeatherType.rainOf(0.1D).name());
        T.eq("按强度取雨(0.3) → 小雨", WeatherType.LIGHT_RAIN.name(), WeatherType.rainOf(0.3D).name());
        T.eq("按强度取雨(0.6) → 中雨", WeatherType.RAIN.name(), WeatherType.rainOf(0.6D).name());
        T.eq("按强度取雨(0.8) → 大雨", WeatherType.HEAVY_RAIN.name(), WeatherType.rainOf(0.8D).name());
        // ★ 暴雨档必须存在：以前 rainOf 没有 STORM_RAIN，0.95 的暴雨会被塌回大雨，
        //   而 STORM_RAIN 当初就是为了把「暴雨」和「大雨」分开才加的 —— 这条断言钉住它不再退化。
        T.eq("按强度取雨(0.95) → 暴雨", WeatherType.STORM_RAIN.name(), WeatherType.rainOf(0.95D).name());
        T.eq("按强度取雪(0.2) → 小雪", WeatherType.LIGHT_SNOW.name(), WeatherType.snowOf(0.2D).name());
        T.eq("按强度取雪(0.8) → 大雪", WeatherType.HEAVY_SNOW.name(), WeatherType.snowOf(0.8D).name());
        // 雨 / 雪的档位分界必须一一对应，否则「雨转雪」会凭空跳档
        T.eq("0.6 的雨与雪同档", WeatherType.RAIN.name(), WeatherType.rainOf(0.6D).name());
        T.eq("0.6 的雪与雨同档", WeatherType.SNOW.name(), WeatherType.snowOf(0.6D).name());
        T.eq("中雨在 -5°C 下转为雪", WeatherType.SNOW.name(),
                WeatherType.RAIN.asFrozen(true, -5.0D).name());
        T.eq("大雨在 -5°C 下转为大雪", WeatherType.HEAVY_SNOW.name(),
                WeatherType.HEAVY_RAIN.asFrozen(true, -5.0D).name());
        // asFrozen 的冻结档位必须与 snowOf 一致：0.30（小雨级）应为小雪而不是中雪
        T.eq("小雨冻结后是雪档一致的小雪", WeatherType.LIGHT_SNOW.name(),
                WeatherType.LIGHT_RAIN.asFrozen(true, -5.0D).name());
        T.eq("无降水类型保持不变", WeatherType.CLEAR.name(), WeatherType.CLEAR.asFrozen(true, -20.0D).name());

        T.eq("降水形态：-5°C → 雪", PrecipitationType.SNOW.name(),
                PrecipitationType.fromTemperature(-5.0D, PrecipitationType.MIXED).name());
        T.eq("降水形态：0°C → 雨夹雪", PrecipitationType.SLEET.name(),
                PrecipitationType.fromTemperature(0.0D, PrecipitationType.MIXED).name());
        T.eq("降水形态：10°C → 雨", PrecipitationType.RAIN.name(),
                PrecipitationType.fromTemperature(10.0D, PrecipitationType.MIXED).name());
        T.eq("声明无降水时恒为无", PrecipitationType.NONE.name(),
                PrecipitationType.fromTemperature(10.0D, PrecipitationType.NONE).name());
    }

    private static void policyThresholds() {
        T.section("同步策略阈值");
        NowWeatherConfig config = NowWeatherConfig.defaults();
        SyncPolicy policy = new SyncPolicy(config);

        T.eq("晴 → CLEAR", PlatformWeather.CLEAR.name(), policy.toPlatformWeather(WeatherType.CLEAR).name());
        T.eq("阴 → CLEAR（原版阴天不降雨）", PlatformWeather.CLEAR.name(),
                policy.toPlatformWeather(WeatherType.OVERCAST).name());
        T.eq("毛毛雨 → CLEAR（低于阈值）", PlatformWeather.CLEAR.name(),
                policy.toPlatformWeather(WeatherType.DRIZZLE).name());
        T.eq("小雨 → RAIN", PlatformWeather.RAIN.name(), policy.toPlatformWeather(WeatherType.LIGHT_RAIN).name());
        T.eq("雷阵雨 → THUNDER", PlatformWeather.THUNDER.name(),
                policy.toPlatformWeather(WeatherType.THUNDERSTORM).name());
        T.eq("暴风雪 → RAIN", PlatformWeather.RAIN.name(), policy.toPlatformWeather(WeatherType.BLIZZARD).name());
        T.eq("未知 → CLEAR", PlatformWeather.CLEAR.name(), policy.toPlatformWeather(WeatherType.UNKNOWN).name());

        T.check("晴天持续时间为配置值",
                policy.durationFor(WeatherType.CLEAR) == config.clearDurationTicks());
        T.check("暴雨持续时间更长",
                policy.durationFor(WeatherType.SEVERE_THUNDERSTORM) > policy.durationFor(WeatherType.LIGHT_RAIN));
        T.check("持续时间落在配置区间内",
                policy.durationFor(WeatherType.RAIN) >= config.minWeatherDurationTicks()
                        && policy.durationFor(WeatherType.RAIN) <= config.maxWeatherDurationTicks());

        // 关闭气候校验时，沙漠也会下雨（用于对比）
        NowWeatherConfig lax = NowWeatherConfig.defaults().setRespectBiomeClimate(false);
        SyncPolicy laxPolicy = new SyncPolicy(lax);
        BiomeClimate desert = REGISTRY.resolve(new BiomeFacts("minecraft:desert", 2.0F, 0.0F,
                PrecipitationType.NONE, "desert", Set.of(), "minecraft:overworld", false));
        WeatherObservation rain = WeatherObservation.builder("test")
                .weatherType(WeatherType.RAIN).temperatureC(30.0D).humidityPercent(80.0D).windScale(4).build();
        WeatherDecision laxDecision = laxPolicy.decide(rain, desert, 0L, PlatformWeather.CLEAR);
        T.eq("关闭气候校验后沙漠也下雨", PlatformWeather.RAIN.name(), laxDecision.target().name());
        T.check("关闭气候校验时不标修正", !laxDecision.climateAdjusted());

        WeatherDecision strictDecision = policy.decide(rain, desert, 0L, PlatformWeather.CLEAR);
        T.eq("开启气候校验后沙漠不下雨", PlatformWeather.CLEAR.name(), strictDecision.target().name());
        T.check("开启气候校验时标修正", strictDecision.climateAdjusted());

        // 无观测时不改变现状
        WeatherDecision none = policy.decide(null, desert, 0L, PlatformWeather.RAIN);
        T.eq("无数据时保持现状", PlatformWeather.RAIN.name(), none.target().name());
        T.eq("无数据时持续时间为 0", 0, none.durationTicks());
    }

    private static void configRoundTrip() {
        T.section("配置读写");
        NowWeatherConfig config = NowWeatherConfig.defaults()
                .setCity("杭州")
                .setAdcode("330100")
                .setApiKey("uapi-test-key")
                .setRefreshIntervalMillis(120_000L)
                .setRespectBiomeClimate(false)
                .setForecastHorizon(24)
                .setDimensions(java.util.List.of("minecraft:overworld", "minecraft:the_nether"));

        NowWeatherConfig restored = NowWeatherConfig.fromJson(
                com.nowweather.core.json.JsonValue.parse(config.toJson().toJson()).asObject());

        T.eq("城市往返", "杭州", restored.city());
        T.eq("adcode 往返", "330100", restored.adcode());
        T.eq("API Key 往返", "uapi-test-key", restored.apiKey());
        T.eq("刷新间隔往返", 120_000L, restored.refreshIntervalMillis());
        T.eq("气候校验开关往返", false, restored.respectBiomeClimate());
        T.eq("预报格数往返", 24, restored.forecastHorizon());
        T.eq("维度列表往返", 2, restored.dimensions().size());
        T.check("维度判定生效", restored.controlsDimension("minecraft:overworld"));
        T.check("非配置维度被排除", !restored.controlsDimension("minecraft:the_end"));

        // 越界值会被夹取
        NowWeatherConfig clamped = NowWeatherConfig.defaults()
                .setForecastHorizon(9999)
                .setRainIntensityThreshold(5.0D)
                .setRefreshIntervalMillis(1L);
        T.check("预报格数被夹取", clamped.forecastHorizon() <= 96);
        T.check("阈值被夹取到 0~1", clamped.rainIntensityThreshold() <= 1.0D);
        T.check("刷新间隔有下限", clamped.refreshIntervalMillis() >= 30_000L);

        // 空 JSON 不崩溃
        NowWeatherConfig empty = NowWeatherConfig.fromJson(new com.nowweather.core.json.JsonObject());
        T.check("空配置使用默认值", empty.enabled() && empty.forecastHorizon() == 12);
    }

    private static void observationRoundTrip() {
        T.section("观测数据读写");
        WeatherObservation observation = WeatherObservation.builder("uapipro")
                .weatherType(WeatherType.THUNDERSTORM)
                .conditionText("雷阵雨")
                .temperatureC(23.5D)
                .humidityPercent(88.0D)
                .windDirection(com.nowweather.core.weather.WindDirection.SW)
                .windScale(6)
                .city("杭州")
                .province("浙江省")
                .adcode("330100")
                .iconCode("302")
                .reportTimeText("5 分钟前发布")
                .stale(true)
                .confidence(0.75D)
                .build();

        WeatherObservation restored = WeatherObservation.fromJson(
                com.nowweather.core.json.JsonValue.parse(observation.toJson().toJson()).asObject());

        T.eq("天气往返", WeatherType.THUNDERSTORM.name(), restored.weatherType().name());
        T.near("温度往返", 23.5D, restored.temperatureC(), 1.0E-9);
        T.eq("风向往返", "SW", restored.windDirection().name());
        T.eq("过期标记往返", true, restored.stale());
        T.eq("位置展示", "浙江省杭州", restored.locationDisplay());
        T.check("旧数据标记过期后仍可读", restored.withStale(false).stale() == false);

        // 缺温度时用 NaN 表示未知
        WeatherObservation noTemp = WeatherObservation.builder("x").weatherType(WeatherType.CLEAR).build();
        T.check("未设温度时标记未知", !noTemp.hasTemperature());
        T.check("未设湿度时按 0.5 处理", Math.abs(noTemp.humidity01() - 0.5D) < 1.0E-9);
        T.check("风级未知时回退到天气类型风力", noTemp.windiness01() > 0.0D);
    }
}

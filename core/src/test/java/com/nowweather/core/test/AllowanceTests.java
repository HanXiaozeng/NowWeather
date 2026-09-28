package com.nowweather.core.test;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.forecast.ClimateSanityFilter;
import com.nowweather.core.forecast.SanityReport;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.weather.WeatherType;

import java.util.Set;

/**
 * 「该生物群系可能出现哪些天气」白名单的测试。
 *
 * <p>这是继「正常温度范围」之后的第二条气候硬约束：温度范围管「合不合理」，
 * 白名单管「会不会发生」。</p>
 */
final class AllowanceTests {

    private static final BiomeClimateRegistry REGISTRY = new BiomeClimateRegistry(Loggers.noop());

    private AllowanceTests() {
    }

    static void run() {
        perBiomeAllowance();
        filterEnforcesAllowance();
        overrideFromJson();
    }

    private static void perBiomeAllowance() {
        T.section("天气白名单推导");
        BiomeClimate desert = biome("minecraft:desert");
        BiomeClimate snowy = biome("minecraft:snowy_plains");
        BiomeClimate jungle = biome("minecraft:jungle");
        BiomeClimate nether = biome("minecraft:nether_wastes");
        BiomeClimate end = biome("minecraft:the_end");
        BiomeClimate ocean = biome("minecraft:ocean");

        T.check("沙漠允许晴朗", desert.allows(WeatherType.CLEAR));
        T.check("沙漠不允许下雨", !desert.allows(WeatherType.RAIN));
        T.check("沙漠不允许下雪", !desert.allows(WeatherType.SNOW));
        T.check("沙漠不允许雷暴", !desert.allows(WeatherType.THUNDERSTORM));
        T.check("沙漠允许沙尘暴", desert.allows(WeatherType.SANDSTORM));
        T.eq("沙漠的原版天气只有晴朗", "CLEAR", describePlatform(desert));

        T.check("雪原允许下雪", snowy.allows(WeatherType.SNOW));
        T.check("雪原允许暴风雪", snowy.allows(WeatherType.BLIZZARD));
        // 原版雪原确实会出现「雷打雪」（雷电 + 降雪），所以雷阵雨必须允许；
        // 但强雷暴不属于极地气候，必须禁止。
        T.check("雪原允许雷打雪", snowy.allows(WeatherType.THUNDERSTORM));
        T.check("雪原不允许强雷暴", !snowy.allows(WeatherType.SEVERE_THUNDERSTORM));
        T.check("雪原不允许台风", !snowy.allows(WeatherType.TYPHOON));

        T.check("丛林允许雷暴", jungle.allows(WeatherType.THUNDERSTORM));
        T.check("丛林允许强雷暴", jungle.allows(WeatherType.SEVERE_THUNDERSTORM));
        T.check("丛林不允许下雪", !jungle.allows(WeatherType.SNOW));
        T.check("丛林的原版天气包含雷暴", platform(jungle).contains(PlatformWeather.THUNDER));

        T.check("下界不允许下雨", !nether.allows(WeatherType.RAIN));
        T.check("下界不允许下雪", !nether.allows(WeatherType.SNOW));
        T.check("下界允许烟霾", nether.allows(WeatherType.SMOKE));
        T.eq("下界的原版天气只有晴朗", "CLEAR", describePlatform(nether));

        T.check("末地不允许降水", !end.allows(WeatherType.RAIN) && !end.allows(WeatherType.SNOW));
        T.check("末地允许大风", end.allows(WeatherType.WINDY));

        T.check("海洋允许降雨与雷暴",
                ocean.allows(WeatherType.RAIN) && ocean.allows(WeatherType.THUNDERSTORM));
        T.check("海洋允许浓雾", ocean.allows(WeatherType.FOG));

        // 白名单永远非空
        for (BiomeClimate climate : new BiomeClimate[]{desert, snowy, jungle, nether, end, ocean}) {
            T.check(climate.biomeId() + " 白名单非空", !climate.possibleWeather().isEmpty());
        }
    }

    private static void filterEnforcesAllowance() {
        T.section("校验器强制执行白名单");
        ClimateSanityFilter filter = new ClimateSanityFilter(6.0D, true);
        BiomeClimate snowy = biome("minecraft:snowy_plains");
        BiomeClimate desert = biome("minecraft:desert");
        BiomeClimate jungle = biome("minecraft:jungle");

        // 雪原上「中雨」：原版雪原不会下雨 → 应被改成雪
        SanityReport snowyRain = filter.inspect(snowy, WeatherType.RAIN, -3.0D, 0.6D, 0.3D);
        T.check("雪原雨天被修正为冻结形态", snowyRain.adjustedType().isFrozen());

        // 雪原上「强雷暴」：白名单里没有 → 必须降级为允许的天气
        SanityReport snowySevere = filter.inspect(snowy, WeatherType.SEVERE_THUNDERSTORM, -3.0D, 0.6D, 0.3D);
        T.check("雪原强雷暴被降级", snowySevere.adjustedType() != WeatherType.SEVERE_THUNDERSTORM);
        T.check("雪原强雷暴的替代品仍在白名单内", snowy.allows(snowySevere.adjustedType()));

        // 丛林上「台风」：白名单里没有（丛林风太小）
        SanityReport jungleTyphoon = filter.inspect(jungle, WeatherType.TYPHOON, 28.0D, 0.9D, 0.7D);
        T.check("丛林台风被替换", jungle.allows(jungleTyphoon.adjustedType()));
        T.check("丛林台风被记录为「不可能出现的天气」",
                jungleTyphoon.violations().contains(
                        com.nowweather.core.forecast.SanityViolation.WEATHER_NOT_POSSIBLE_IN_CLIMATE));

        // 丛林里下雨是合理的，不应被改
        SanityReport jungleRain = filter.inspect(jungle, WeatherType.THUNDERSTORM, 28.0D, 0.9D, 0.4D);
        T.check("丛林的雷阵雨无需修正", jungleRain.isClean());

        // 关闭极端事件开关后，沙尘暴这类极端天气会被降级
        ClimateSanityFilter calm = new ClimateSanityFilter(6.0D, false);
        SanityReport noExtreme = calm.inspect(desert, WeatherType.SANDSTORM, 35.0D, 0.1D, 0.9D);
        T.check("关闭极端事件后沙尘暴被降级", noExtreme.adjustedType() != WeatherType.SANDSTORM);
        T.check("降级结果仍在白名单内", desert.allows(noExtreme.adjustedType()));
    }

    private static void overrideFromJson() {
        T.section("配置可覆盖天气白名单");
        BiomeClimateRegistry registry = new BiomeClimateRegistry(Loggers.noop());
        registry.loadJson("测试", com.nowweather.core.json.JsonValue.parse("""
                {"biomes":[
                  {"id":"minecraft:desert","climateClass":"ARID","tempMinC":10,"tempMaxC":52,
                   "precipitation":"NONE","possibleMcWeather":["CLEAR","RAIN"]},
                  {"id":"minecraft:plains","climateClass":"TEMPERATE","tempRange":[-16,36],
                   "possibleWeather":["CLEAR","CLOUDY","RAIN","THUNDERSTORM","SANDSTORM"]}
                ]}
                """));

        BiomeClimate desert = registry.resolve(facts("minecraft:desert", 2.0F, 0.0F, PrecipitationType.NONE, "desert"));
        T.check("沙漠配置里允许了降雨（测试用）", desert.allows(WeatherType.RAIN));
        T.check("沙漠配置里未允许雷暴", !desert.allows(WeatherType.THUNDERSTORM));
        T.check("沙漠的原版天气包含降雨", platform(desert).contains(PlatformWeather.RAIN));

        BiomeClimate plains = registry.resolve(facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains"));
        T.eq("显式白名单条目数", 5, plains.possibleWeather().size());
        T.check("平原配置允许沙尘暴", plains.allows(WeatherType.SANDSTORM));
        T.check("平原配置不允许下雪", !plains.allows(WeatherType.SNOW));

        // 序列化往返要保留白名单
        BiomeClimate round = BiomeClimate.fromJson("minecraft:plains",
                com.nowweather.core.json.JsonValue.parse(plains.toJson().toJson()).asObject(), null);
        T.eq("白名单序列化往返条目数", 5, round.possibleWeather().size());
        T.check("白名单序列化往返内容一致", round.allows(WeatherType.SANDSTORM));

        // 未显式配置的群系仍走推导
        BiomeClimate ocean = registry.resolve(facts("minecraft:ocean", 0.5F, 0.5F, PrecipitationType.RAIN, "ocean"));
        T.check("未配置的群系仍有白名单", ocean.possibleWeather().size() > 5);
        T.eq("未配置的群系 source 标记为启发式", true, ocean.source().startsWith("heuristic"));
    }

    // ------------------------------------------------------------------ 辅助

    private static BiomeClimate biome(String id) {
        return REGISTRY.resolve(facts(id, baseTempOf(id), 0.5F, precipitationOf(id), categoryOf(id)));
    }

    private static float baseTempOf(String id) {
        return switch (id) {
            case "minecraft:desert" -> 2.0F;
            case "minecraft:snowy_plains" -> 0.0F;
            case "minecraft:jungle" -> 0.95F;
            case "minecraft:nether_wastes" -> 2.0F;
            case "minecraft:the_end" -> 0.5F;
            default -> 0.5F;
        };
    }

    private static PrecipitationType precipitationOf(String id) {
        return switch (id) {
            case "minecraft:desert", "minecraft:nether_wastes", "minecraft:the_end" -> PrecipitationType.NONE;
            case "minecraft:snowy_plains" -> PrecipitationType.SNOW;
            default -> PrecipitationType.RAIN;
        };
    }

    private static String categoryOf(String id) {
        return switch (id) {
            case "minecraft:desert" -> "desert";
            case "minecraft:snowy_plains" -> "icy";
            case "minecraft:jungle" -> "jungle";
            case "minecraft:nether_wastes" -> "nether";
            case "minecraft:the_end" -> "the_end";
            case "minecraft:ocean" -> "ocean";
            default -> "plains";
        };
    }

    private static BiomeFacts facts(String id, float baseTemperature, float downfall,
                                    PrecipitationType precipitation, String category) {
        return new BiomeFacts(id, baseTemperature, downfall, precipitation, category, Set.of(),
                id.contains("nether") ? "minecraft:the_nether"
                        : id.contains("the_end") ? "minecraft:the_end" : "minecraft:overworld", false);
    }

    private static Set<PlatformWeather> platform(BiomeClimate climate) {
        return climate.possiblePlatformWeather();
    }

    private static String describePlatform(BiomeClimate climate) {
        return climate.possiblePlatformWeather().stream().map(Enum::name).sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}

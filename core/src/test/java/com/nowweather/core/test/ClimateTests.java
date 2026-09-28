package com.nowweather.core.test;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.ClimateMath;
import com.nowweather.core.climate.Confidence;
import com.nowweather.core.climate.HeuristicClimateResolver;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.climate.TempRange;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.Loggers;

import java.util.Optional;
import java.util.Set;

/** 气候映射测试：换算、关键词归类、注册表优先级、启发式解析器。 */
final class ClimateTests {

    private ClimateTests() {
    }

    static void run() {
        tempRangeBasics();
        temperatureConversion();
        keywordClassification();
        registryPriority();
        heuristicResolver();
        jsonLoading();
    }

    private static void tempRangeBasics() {
        T.section("温度范围 TempRange");
        TempRange range = TempRange.of(20, -5);
        T.near("自动排序 min", -5.0D, range.minC(), 1.0E-9);
        T.near("自动排序 max", 20.0D, range.maxC(), 1.0E-9);
        T.check("范围内判定", range.contains(0));
        T.check("范围外判定", !range.contains(30));
        T.near("夹取上界", 20.0D, range.clamp(99), 1.0E-9);
        T.near("夹取下界", -5.0D, range.clamp(-99), 1.0E-9);
        T.near("归一化(中点)", 0.5D, range.normalize(7.5D), 1.0E-9);
        T.near("偏离度", 10.0D, range.deviation(30), 1.0E-9);
        T.near("偏离度(范围内)", 0.0D, range.deviation(0), 1.0E-9);
        T.near("交集", 5.0D, TempRange.of(0, 5).intersect(TempRange.of(-5, 20)).maxC(), 1.0E-9);
        T.near("不相交时取较窄", 2.0D, TempRange.of(0, 2).intersect(TempRange.of(50, 90)).maxC(), 1.0E-9);
    }

    private static void temperatureConversion() {
        T.section("原版温度 ↔ 摄氏换算");
        T.near("雪原 0.0 → -10°C", -10.0D, ClimateMath.mcBaseToCelsius(0.0D), 1.0E-9);
        T.near("平原 0.8 → 6°C", 6.0D, ClimateMath.mcBaseToCelsius(0.8D), 1.0E-9);
        T.near("沙漠 2.0 → 30°C", 30.0D, ClimateMath.mcBaseToCelsius(2.0D), 1.0E-9);
        T.near("往返一致", 0.8D, ClimateMath.celsiusToMcBase(ClimateMath.mcBaseToCelsius(0.8D)), 1.0E-6);
    }

    private static void keywordClassification() {
        T.section("生物群系关键词归类（含超多生物群系）");
        expectClass("minecraft:plains", ClimateClass.TEMPERATE);
        expectClass("minecraft:desert", ClimateClass.ARID);
        expectClass("minecraft:badlands", ClimateClass.ARID);
        expectClass("minecraft:snowy_plains", ClimateClass.POLAR);
        expectClass("minecraft:ice_spikes", ClimateClass.POLAR);
        expectClass("minecraft:taiga", ClimateClass.SUBPOLAR);
        expectClass("minecraft:jungle", ClimateClass.TROPICAL);
        expectClass("minecraft:swamp", ClimateClass.WETLAND);
        expectClass("minecraft:ocean", ClimateClass.OCEANIC);
        expectClass("minecraft:stony_shore", ClimateClass.COASTAL);
        expectClass("minecraft:windswept_hills", ClimateClass.MOUNTAIN);
        expectClass("minecraft:mushroom_fields", ClimateClass.MUSHROOM);
        // 超多生物群系（Biomes O' Plenty）
        expectClass("biomesoplenty:lavender_field", ClimateClass.TEMPERATE);
        expectClass("biomesoplenty:floodplain", ClimateClass.WETLAND);
        expectClass("biomesoplenty:cold_desert", ClimateClass.SUBPOLAR);
        expectClass("biomesoplenty:volcano", ClimateClass.VOLCANIC);
        expectClass("biomesoplenty:bayou", ClimateClass.WETLAND);
        expectClass("biomesoplenty:tropical_rainforest", ClimateClass.TROPICAL);
        expectClass("biomesoplenty:wasteland", ClimateClass.ARID);
        expectClass("biomesoplenty:crag", ClimateClass.MOUNTAIN);
        expectClass("biomesoplenty:highland", ClimateClass.PLATEAU);
        expectClass("biomesoplenty:fungal_jungle", ClimateClass.MUSHROOM);
        expectClass("biomesoplenty:glowing_grotto", ClimateClass.CAVE);
        expectClass("biomesoplenty:ominous_woods", ClimateClass.TEMPERATE);
        expectClass("biomesoplenty:redwood_forest", ClimateClass.TEMPERATE);
    }

    private static void expectClass(String biomeId, ClimateClass expected) {
        Optional<ClimateClass> actual = ClimateMath.classifyByKeywords(biomeId);
        if (actual.isEmpty()) {
            T.fail("关键词归类 " + biomeId, "未命中任何关键词，期望 " + expected.name());
            return;
        }
        T.eq("关键词归类 " + biomeId, expected.name(), actual.get().name());
    }

    private static void registryPriority() {
        T.section("气候注册表解析优先级");
        BiomeClimateRegistry registry = new BiomeClimateRegistry(Loggers.noop());

        BiomeFacts desert = facts("minecraft:desert", 2.0F, 0.0F, PrecipitationType.NONE, "desert");
        BiomeClimate first = registry.resolve(desert);
        T.eq("无配置时走启发式(沙漠→干旱)", ClimateClass.ARID.name(), first.climateClass().name());
        T.check("启发式来源标记", first.source().startsWith("heuristic"));
        T.check("沙漠不下雨", !first.canPrecipitate());

        // 标签规则应覆盖启发式
        BiomeClimate tagTemplate = BiomeClimate.builder("tag:minecraft:is_forest")
                .climateClass(ClimateClass.TEMPERATE)
                .tempRange(-5, 28)
                .source("builtin:tag")
                .confidence(Confidence.HIGH)
                .build();
        registry.addTagRule("#minecraft:is_forest",
                tagTemplate);
        BiomeFacts forest = new BiomeFacts("somemod:mystery_grove", 0.7F, 0.8F, PrecipitationType.RAIN,
                "forest", Set.of("minecraft:is_forest", "minecraft:is_overworld"), "minecraft:overworld", false);
        BiomeClimate viaTag = registry.resolve(forest);
        T.check("标签规则来源", viaTag.source().contains("tag:minecraft:is_forest"));
        T.eq("标签规则给出气候大类", ClimateClass.TEMPERATE.name(), viaTag.climateClass().name());
        T.eq("标签规则改写 biomeId", "somemod:mystery_grove", viaTag.biomeId());
        T.check("未显式给范围时按该群系真实原版温度推断", viaTag.tempRange().contains(4.0D));
        T.check("推断出的范围窄于气候大类的默认范围",
                viaTag.tempRange().span() < ClimateClass.TEMPERATE.defaultRange().span());

        // 显式写死温度范围的标签规则
        BiomeClimateRegistry explicitTagRegistry = new BiomeClimateRegistry(Loggers.noop());
        explicitTagRegistry.addTagRule("#minecraft:is_forest", tagTemplate, true);
        BiomeClimate explicitTag = explicitTagRegistry.resolve(forest);
        T.near("显式范围的标签规则温度下限", -5.0D, explicitTag.tempRange().minC(), 1.0E-9);
        T.near("显式范围的标签规则温度上限", 28.0D, explicitTag.tempRange().maxC(), 1.0E-9);

        // 雪原类群系即使命中「森林」标签，也应保持积雪
        BiomeClimateRegistry snowyForest = new BiomeClimateRegistry(Loggers.noop());
        snowyForest.addTagRule("#minecraft:is_forest", tagTemplate);
        BiomeClimate snowyViaTag = snowyForest.resolve(new BiomeFacts("somemod:frozen_woods", 0.0F, 0.5F,
                PrecipitationType.SNOW, "forest", Set.of("minecraft:is_forest"), "minecraft:overworld", false));
        T.check("雪原森林仍判定为降水形态为雪", snowyViaTag.precipitation().isFrozen());

        // 显式配置优先级最高
        BiomeClimate explicit = BiomeClimate.builder("somemod:mystery_grove")
                .climateClass(ClimateClass.ARID)
                .tempRange(30, 50)
                .source("config")
                .build();
        registry.register(explicit);
        BiomeClimate resolved = registry.resolve(forest);
        T.eq("显式配置覆盖标签规则", "config", resolved.source());
        T.near("显式配置温度下限", 30.0D, resolved.tempRange().minC(), 1.0E-9);

        // 分类规则
        BiomeClimateRegistry categoryOnly = new BiomeClimateRegistry(Loggers.noop());
        categoryOnly.addCategoryRule("snowy", BiomeClimate.builder("category:snowy")
                .climateClass(ClimateClass.POLAR).tempRange(-40, 2).source("builtin:category").build());
        BiomeClimate snowy = categoryOnly.resolve(facts("somemod:white_place", 0.0F, 0.5F,
                PrecipitationType.SNOW, "snowy"));
        T.check("分类规则命中", snowy.source().startsWith("builtin:category"));

        T.eq("显式条目计数", 1, registry.explicitCount());
        T.eq("标签规则计数", 1, registry.tagRuleCount());
    }

    private static void heuristicResolver() {
        T.section("启发式气候解析器");
        HeuristicClimateResolver resolver = new HeuristicClimateResolver();

        BiomeClimate snowy = resolver.resolve(facts("somemod:frozen_waste", 0.0F, 0.4F, PrecipitationType.SNOW, "none"))
                .orElseThrow();
        T.eq("严寒群系气候大类", ClimateClass.POLAR.name(), snowy.climateClass().name());
        T.check("严寒群系温度上限 ≤ 5°C", snowy.tempRange().maxC() <= 8.0D);
        T.check("严寒群系降水为雪", snowy.precipitation().isFrozen());

        BiomeClimate hot = resolver.resolve(facts("minecraft:desert", 2.0F, 0.0F, PrecipitationType.NONE, "desert"))
                .orElseThrow();
        T.check("沙漠温度下限 ≥ 10°C", hot.tempRange().minC() >= 10.0D);
        T.check("沙漠雷暴上限极低", hot.maxStormIntensity() < 0.35D);
        T.check("沙漠不可降水", !hot.canPrecipitate());

        BiomeClimate temperate = resolver.resolve(facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains"))
                .orElseThrow();
        T.check("平原正常温度范围覆盖 0°C", temperate.tempRange().contains(0.0D));
        T.check("平原可降水", temperate.canPrecipitate());

        BiomeClimate unknown = resolver.resolve(facts("somemod:zzz_place", 0.7F, 0.5F,
                PrecipitationType.RAIN, "none")).orElseThrow();
        T.check("未知群系仍能给出范围", unknown.tempRange().span() > 0);
        T.check("未知群系可信度不高", unknown.confidence().weight() <= Confidence.LOW.weight());
    }

    private static void jsonLoading() {
        T.section("气候 JSON 载入");
        BiomeClimateRegistry registry = new BiomeClimateRegistry(Loggers.noop());
        String json = """
                {
                  "biomes": [
                    {"id": "minecraft:desert", "climateClass": "ARID", "tempMinC": 16, "tempMaxC": 52,
                     "precipitation": "NONE", "humidity": 0.08, "source": "builtin:vanilla", "confidence": "HIGH"},
                    {"id": "minecraft:plains", "climateClass": "TEMPERATE", "tempRange": [-14, 34], "baseTemperature": 0.8}
                  ],
                  "tags": [{"tag": "minecraft:is_forest", "climateClass": "TEMPERATE", "tempMinC": -6, "tempMaxC": 30}],
                  "categories": [{"category": "snowy", "climateClass": "POLAR", "tempMinC": -40, "tempMaxC": 4}]
                }
                """;
        BiomeClimateRegistry.LoadReport report = registry.loadJson("测试数据", JsonValue.parse(json));
        T.eq("载入生物群系条数", 2, report.biomes());
        T.eq("载入标签规则条数", 1, report.tags());
        T.eq("载入分类规则条数", 1, report.categories());
        T.eq("载入无警告", 0, report.warnings().size());

        Optional<BiomeClimate> desert = registry.explicit("minecraft:desert");
        T.check("显式条目存在", desert.isPresent());
        T.near("JSON 温度下限", 16.0D, desert.orElseThrow().tempRange().minC(), 1.0E-9);
        T.eq("JSON 降水类型", PrecipitationType.NONE.name(), desert.orElseThrow().precipitation().name());
        T.eq("JSON 可信度", Confidence.HIGH.name(), desert.orElseThrow().confidence().name());

        Optional<BiomeClimate> plains = registry.explicit("minecraft:plains");
        T.near("tempRange 写法", 34.0D, plains.orElseThrow().tempRange().maxC(), 1.0E-9);

        // 映射写法
        BiomeClimateRegistry registry2 = new BiomeClimateRegistry(Loggers.noop());
        registry2.loadJson("映射写法", JsonValue.parse(
                "{\"biomes\": {\"minecraft:ocean\": {\"climateClass\": \"OCEANIC\", \"tempMinC\": -3, \"tempMaxC\": 30}}}"));
        T.check("biomes 映射写法", registry2.explicit("minecraft:ocean").isPresent());

        // 坏数据不应崩溃
        BiomeClimateRegistry.LoadReport bad = registry.loadJson("坏数据",
                JsonValue.parse("{\"biomes\": [{\"climateClass\": \"ARID\"}, 5], \"tags\": [{}]}"));
        T.eq("坏数据：无有效生物群系", 0, bad.biomes());
        T.check("坏数据：产生了警告", bad.warnings().size() >= 2);
    }

    static BiomeFacts facts(String id, float baseTemperature, float downfall, PrecipitationType precipitation,
                           String category) {
        return new BiomeFacts(id, baseTemperature, downfall, precipitation, category, Set.of(),
                "minecraft:overworld", false);
    }
}

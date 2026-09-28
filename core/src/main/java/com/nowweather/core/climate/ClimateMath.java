package com.nowweather.core.climate;

import com.nowweather.core.util.Ids;
import com.nowweather.core.util.MathUtil;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 气候推断数学 —— 「原版温度 ↔ 摄氏」的换算与关键词规则表。
 *
 * <p>为什么需要它：Minecraft 只给出了抽象的 {@code baseTemperature}（雪原 0.0、平原 0.8、沙漠 2.0），
 * 而真实天气 API 给的是摄氏度。要让两者可比、可校验，就必须有一条明确的换算与推断规则。</p>
 */
public final class ClimateMath {

    /** MC 温度每 1.0 对应的摄氏跨度。 */
    public static final double MC_TEMP_SCALE = 20.0D;

    /** MC 温度 0.0 对应的摄氏温度。 */
    public static final double MC_TEMP_OFFSET = -10.0D;

    private ClimateMath() {
    }

    /** MC 量纲 → 摄氏。0.0 → -10°C，0.5 → 0°C，0.8 → 6°C，2.0 → 30°C。 */
    public static double mcBaseToCelsius(double baseTemperature) {
        return MathUtil.clamp(baseTemperature * MC_TEMP_SCALE + MC_TEMP_OFFSET, -60.0D, 90.0D);
    }

    /** 摄氏 → MC 量纲。 */
    public static float celsiusToMcBase(double celsius) {
        return (float) ((celsius - MC_TEMP_OFFSET) / MC_TEMP_SCALE);
    }

    /**
     * 关键词规则表 —— 顺序敏感：越具体的规则越靠前。
     *
     * <p>这张表让 NowWeather 不需要为每个生物群系模组硬编码名单，
     * 也能对「超多生物群系」这类大量新增群系的模组给出合理初值。</p>
     */
    private static final List<Rule> KEYWORD_RULES = List.of(
            // --- 维度 / 极端环境优先 ---
            new Rule(ClimateClass.POLAR, "frozen", "snowy", "snow_", "_snow", "ice_", "_ice", "iced",
                    "glacier", "glacial", "frost", "arctic", "antarctic", "polar", "permafrost",
                    "iceberg", "frozen_ocean", "tundra", "雪"),
            new Rule(ClimateClass.SUBPOLAR, "cold_", "_cold", "boreal", "taiga", "spruce", "fir_", "_fir",
                    "larch", "conifer", "snowless_taiga"),
            new Rule(ClimateClass.VOLCANIC, "volcan", "basalt", "magma", "lava", "ash_", "_ash",
                    "scorched", "inferno", "brimstone", "sulfur", "cinder", "charred", "burnt"),
            new Rule(ClimateClass.NETHER, "nether", "hell", "crimson", "warped", "soulsand", "soul_sand",
                    "quartz_desert"),
            new Rule(ClimateClass.END, "end_", "_end", "endstone", "chorus", "purpur", "the_end", "endisland"),
            new Rule(ClimateClass.VOID, "void", "null_", "emptiness"),
            new Rule(ClimateClass.CAVE, "cave", "cavern", "grotto", "underground", "deep_dark", "abyss_cave"),
            // --- 干旱 ---
            new Rule(ClimateClass.ARID, "desert", "badlands", "mesa", "dune", "oasis", "wasteland", "waste",
                    "xeric", "scrub", "dry_", "_dry", "arid", "barren", "salt_flat", "canyon", "dust"),
            // --- 蘑菇（要先于森林/丛林，否则 fungal_jungle 会被判成丛林）---
            new Rule(ClimateClass.MUSHROOM, "mushroom", "mycelium", "glowshroom", "fungal", "fungi", "spore"),
            // --- 热带 / 亚热带 ---
            new Rule(ClimateClass.TROPICAL, "jungle", "tropical", "rainforest", "bamboo", "palm", "coconut",
                    "banana", "orchid", "monsoon", "mangrove", "rain_forest", "vine"),
            new Rule(ClimateClass.SUBTROPICAL, "savanna", "savannah", "subtropic", "subtropical", "baobab",
                    "acacia"),
            new Rule(ClimateClass.MEDITERRANEAN, "mediterran", "olive", "cypress", "vineyard", "chaparral",
                    "maquis", "garrigue", "karst"),
            // --- 湿地 ---
            new Rule(ClimateClass.WETLAND, "swamp", "marsh", "bayou", "bog", "fen_", "_fen", "mire",
                    "wetland", "peat", "slough", "everglade", "floodplain", "flooded", "delta"),
            // --- 海洋 / 海岸 ---
            new Rule(ClimateClass.OCEANIC, "ocean", "_sea", "sea_", "reef", "coral", "kelp", "atoll",
                    "lagoon", "abyss", "trench", "deep_", "shallow"),
            new Rule(ClimateClass.COASTAL, "beach", "shore", "strand", "coast", "cliff_shore", "stony_shore",
                    "shoreline"),
            // --- 山地 / 高原 ---
            new Rule(ClimateClass.MOUNTAIN, "mountain", "peak", "summit", "alps", "alpine", "slope",
                    "ridge", "crag", "cliff", "massif", "fell", "jagged", "stony_peaks", "windswept"),
            new Rule(ClimateClass.PLATEAU, "plateau", "tableland", "highland", "high_land", "mesa_plateau",
                    "steppe", "moor", "heath"),
            // --- 温带 / 大陆性 ---
            new Rule(ClimateClass.CONTINENTAL, "continental", "prairie_steppe", "pampa", "plains_cold"),
            new Rule(ClimateClass.TEMPERATE, "forest", "plain", "field", "meadow", "prairie", "grass",
                    "garden", "grove", "wood", "woodland", "park", "orchard", "lavender", "sunflower",
                    "tulip", "rose", "clover", "chaparral_gentle", "shrubland", "valley", "patch",
                    "cherry", "maple", "birch", "oak", "willow", "aspen", "hedge", "thicket", "clearing")
    );

    private record Rule(ClimateClass climateClass, String... keywords) {
        boolean matches(String path) {
            for (String keyword : keywords) {
                if (path.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 按资源 id 的路径部分做关键词归类。 */
    public static Optional<ClimateClass> classifyByKeywords(String biomeId) {
        String path = Ids.path(Ids.normalize(biomeId));
        for (Rule rule : KEYWORD_RULES) {
            if (rule.matches(path)) {
                return Optional.of(rule.climateClass());
            }
        }
        return Optional.empty();
    }

    /** 按原版分类（{@code Biome.BiomeCategory}）归类。 */
    public static Optional<ClimateClass> classifyByCategory(String category) {
        if (category == null || category.isBlank() || "none".equalsIgnoreCase(category)) {
            return Optional.empty();
        }
        String key = category.trim().toLowerCase(Locale.ROOT);
        return Optional.of(switch (key) {
            case "ocean", "river" -> ClimateClass.OCEANIC;
            case "beach" -> ClimateClass.COASTAL;
            case "desert" -> ClimateClass.ARID;
            case "forest" -> ClimateClass.TEMPERATE;
            case "taiga" -> ClimateClass.SUBPOLAR;
            case "extreme_hills" -> ClimateClass.MOUNTAIN;
            case "jungle" -> ClimateClass.TROPICAL;
            case "savanna" -> ClimateClass.SUBTROPICAL;
            case "swamp" -> ClimateClass.WETLAND;
            case "icy" -> ClimateClass.POLAR;
            case "mushroom" -> ClimateClass.MUSHROOM;
            case "nether" -> ClimateClass.NETHER;
            case "the_end" -> ClimateClass.END;
            case "underground" -> ClimateClass.CAVE;
            case "mesa" -> ClimateClass.ARID;
            case "plains" -> ClimateClass.TEMPERATE;
            case "mountain" -> ClimateClass.MOUNTAIN;
            default -> ClimateClass.UNKNOWN;
        }).filter(cls -> cls != ClimateClass.UNKNOWN);
    }

    /** 仅凭原版温度与降水做最后兜底判断。 */
    public static ClimateClass classifyByFacts(BiomeFacts facts) {
        if (facts.isNether()) {
            return ClimateClass.NETHER;
        }
        if (facts.isEnd()) {
            return ClimateClass.END;
        }
        if (facts.cave()) {
            return ClimateClass.CAVE;
        }
        double celsius = mcBaseToCelsius(facts.baseTemperature());
        boolean dry = facts.precipitation() == PrecipitationType.NONE || facts.downfall() <= 0.0F;
        if (dry && celsius >= 15.0D) {
            return ClimateClass.ARID;
        }
        if (celsius <= -8.0D) {
            return ClimateClass.POLAR;
        }
        if (celsius <= 2.0D) {
            return ClimateClass.SUBPOLAR;
        }
        if (facts.downfall() >= 0.85F && celsius >= 15.0D) {
            return ClimateClass.TROPICAL;
        }
        if (celsius >= 25.0D) {
            return ClimateClass.SUBTROPICAL;
        }
        return ClimateClass.TEMPERATE;
    }

    /**
     * 综合推断：维度 → 关键词 → 分类 → 原版温度兜底。
     *
     * @return 推断结果与是否命中关键词（命中关键词说明精确度更高）
     */
    public static Classification classify(BiomeFacts facts) {
        if (facts.isNether()) {
            return new Classification(ClimateClass.NETHER, false, "dimension");
        }
        if (facts.isEnd()) {
            return new Classification(ClimateClass.END, false, "dimension");
        }
        Optional<ClimateClass> byKeyword = classifyByKeywords(facts.id());
        if (byKeyword.isPresent()) {
            return new Classification(byKeyword.get(), true, "keyword");
        }
        Optional<ClimateClass> byCategory = classifyByCategory(facts.category());
        if (byCategory.isPresent()) {
            return new Classification(byCategory.get(), false, "category");
        }
        return new Classification(classifyByFacts(facts), false, "baseTemperature");
    }

    /**
     * 由原版事实推导「正常温度范围」。
     *
     * @param climateClass 气候大类
     * @param facts        生物群系事实
     * @param spread       以原版温度为中心的基础半径（摄氏度）
     */
    public static TempRange heuristicRange(ClimateClass climateClass, BiomeFacts facts, double spread) {
        return heuristicRange(climateClass, facts, spread, false);
    }

    /**
     * 由原版事实推导「正常温度范围」。
     *
     * <p>两套信息会打架：一边是「这个群系叫什么」（关键词推出的大类默认温度范围），
     * 一边是「这个群系在游戏里被设成了多少度」（原版 baseTemperature）。</p>
     *
     * <ul>
     *   <li>{@code trustClassRange = true}（名称关键词命中，说明大类判断可信）：
     *       以大类默认范围为准，只按原版温度做有限收窄 —— 例如丛林一定是热的，
     *       不能因为某个模组把温度设得偏低就允许它下雪；</li>
     *   <li>{@code trustClassRange = false}（只靠维度/分类/温度兜底）：
     *       以原版温度为中心，仅当与大类范围有明显交集时才取交集。</li>
     * </ul>
     */
    public static TempRange heuristicRange(ClimateClass climateClass, BiomeFacts facts, double spread,
                                           boolean trustClassRange) {
        double anchor = mcBaseToCelsius(facts.baseTemperature());
        double radius = spread + (climateClass.isExtreme() ? 6.0D : 0.0D);
        // 干旱群系昼夜温差更大
        if (climateClass == ClimateClass.ARID || climateClass == ClimateClass.VOLCANIC) {
            radius += 6.0D;
        }
        // 海洋调温：范围更窄
        if (climateClass == ClimateClass.OCEANIC) {
            radius = Math.max(6.0D, radius - 5.0D);
        }
        TempRange anchored = TempRange.around(anchor, radius);
        TempRange declared = climateClass.defaultRange();

        if (trustClassRange) {
            // 大类范围为主：允许原版温度把范围往外推 12°C，但不允许把大类范围整个推翻
            TempRange merged = declared.intersect(anchored.expand(12.0D));
            if (merged.span() >= 10.0D) {
                return merged;
            }
            // 交集太窄（大类与原版温度差得离谱）：把大类范围整体朝原版温度平移，最多平移 25%
            double shift = MathUtil.clamp(anchor - declared.midpoint(), -declared.span() * 0.25D,
                    declared.span() * 0.25D);
            return new TempRange(declared.minC() + shift, declared.maxC() + shift);
        }

        TempRange merged = declared.intersect(anchored.expand(4.0D));
        // 交集过窄说明两套推断冲突，以原版实际温度为准（更可信）
        return merged.span() >= 8.0D ? merged : anchored;
    }

    /** 气候分类结果。 */
    public record Classification(ClimateClass climateClass, boolean keywordHit, String via) {
    }
}

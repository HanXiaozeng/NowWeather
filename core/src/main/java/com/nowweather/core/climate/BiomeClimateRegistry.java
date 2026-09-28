package com.nowweather.core.climate;

import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.Ids;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 生物群系气候注册表 —— NowWeather 的「生物群系 ↔ 气候」知识库。
 *
 * <p>解析顺序（先命中先用）：</p>
 * <ol>
 *   <li>精确 id 配置（内置数据包 / 用户配置 / 其它模组注册）</li>
 *   <li>标签规则（{@code #minecraft:is_forest} 之类）</li>
 *   <li>分类规则（{@code Biome.BiomeCategory}）</li>
 *   <li>自定义解析器（按 {@link ClimateResolver#priority()} 排序）</li>
 *   <li>{@link HeuristicClimateResolver} 兜底 —— 永远能给出结果</li>
 * </ol>
 */
public final class BiomeClimateRegistry {

    private final Map<String, BiomeClimate> explicit = new ConcurrentHashMap<>();
    private final List<TagRule> tagRules = new CopyOnWriteArrayList<>();
    private final List<CategoryRule> categoryRules = new CopyOnWriteArrayList<>();
    private final List<ClimateResolver> resolvers = new CopyOnWriteArrayList<>();
    private final List<ClimateModifier> modifiers = new CopyOnWriteArrayList<>();
    private final Map<String, BiomeClimate> cache = new ConcurrentHashMap<>();

    private final ClimateResolver fallback;
    private final SyncLogger logger;
    private volatile String loadedSources = "";

    public BiomeClimateRegistry(SyncLogger logger) {
        this.logger = logger == null ? com.nowweather.core.log.Loggers.noop() : logger;
        this.fallback = new HeuristicClimateResolver();
    }

    // ------------------------------------------------------------------ 注册

    /** 注册/覆盖某个生物群系的气候。 */
    public BiomeClimateRegistry register(BiomeClimate climate) {
        explicit.put(Ids.normalize(climate.biomeId()), climate);
        cache.remove(Ids.normalize(climate.biomeId()));
        return this;
    }

    public BiomeClimateRegistry registerAll(Collection<BiomeClimate> climates) {
        climates.forEach(this::register);
        return this;
    }

    /**
     * 批量注册一条标签规则（作用于所有带该标签的生物群系）。
     *
     * <p>默认语义是「只给气候大类」：温度范围仍会按该生物群系的真实原版基础温度推断，
     * 这样其它模组新增的森林/雪原群系不会被一刀切成同一个温度范围。</p>
     */
    public BiomeClimateRegistry addTagRule(String tag, BiomeClimate template) {
        return addTagRule(tag, template, false);
    }

    /**
     * @param explicitRange true 表示连温度范围也一并写死（配置里显式给了 tempMinC/tempMaxC）
     */
    public BiomeClimateRegistry addTagRule(String tag, BiomeClimate template, boolean explicitRange) {
        tagRules.add(new TagRule(normalizeTag(tag), template, explicitRange));
        cache.clear();
        return this;
    }

    /** 批量注册一条原版分类规则（默认同样只给气候大类）。 */
    public BiomeClimateRegistry addCategoryRule(String category, BiomeClimate template) {
        return addCategoryRule(category, template, false);
    }

    public BiomeClimateRegistry addCategoryRule(String category, BiomeClimate template, boolean explicitRange) {
        categoryRules.add(new CategoryRule(category.toLowerCase(Locale.ROOT).trim(), template, explicitRange));
        cache.clear();
        return this;
    }

    /** 注册自定义解析器（优先于启发式，但低于显式配置/标签/分类）。 */
    public BiomeClimateRegistry addResolver(ClimateResolver resolver) {
        resolvers.add(resolver);
        resolvers.sort(Comparator.comparingInt(ClimateResolver::priority));
        cache.clear();
        return this;
    }

    /**
     * 注册气候修正器（<b>后处理</b>：无论基础气候从哪来都会被调用，例如按季节平移温度范围）。
     *
     * <p>注意：一旦注册了修正器，解析结果<b>不再缓存</b> —— 因为修正器的输出可能随时间变化
     * （季节会推进），缓存住就会失效成「永远停在注册那一刻的季节」。解析本身很便宜
     * （几次 map 查找），这点开销换取正确性是划算的。</p>
     */
    public BiomeClimateRegistry addModifier(ClimateModifier modifier) {
        modifiers.add(modifier);
        modifiers.sort(Comparator.comparingInt(ClimateModifier::priority));
        cache.clear();
        return this;
    }

    public boolean removeModifier(ClimateModifier modifier) {
        boolean removed = modifiers.remove(modifier);
        if (removed) {
            cache.clear();
        }
        return removed;
    }

    /** 当前生效的修正器（已被过滤掉 isActive()==false 的）。 */
    public List<ClimateModifier> activeModifiers() {
        return modifiers.stream().filter(ClimateModifier::isActive).toList();
    }

    public boolean removeResolver(ClimateResolver resolver) {
        boolean removed = resolvers.remove(resolver);
        if (removed) {
            cache.clear();
        }
        return removed;
    }

    /** 清空全部数据（重载配置时用）。 */
    public void clear() {
        explicit.clear();
        tagRules.clear();
        categoryRules.clear();
        modifiers.clear();
        cache.clear();
        loadedSources = "";
    }

    public void clearCache() {
        cache.clear();
    }

    // ------------------------------------------------------------------ 查询

    public Optional<BiomeClimate> explicit(String biomeId) {
        return Optional.ofNullable(explicit.get(Ids.normalize(biomeId)));
    }

    /** 解析一个生物群系的气候，永不返回 null。 */
    public BiomeClimate resolve(BiomeFacts facts) {
        String key = Ids.normalize(facts.id());
        List<ClimateModifier> active = activeModifiers();
        // 有修正器时不缓存：修正结果可能随季节/时间变化
        if (active.isEmpty()) {
            BiomeClimate cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        BiomeClimate result = resolveUncached(facts);
        for (ClimateModifier modifier : active) {
            try {
                BiomeClimate modified = modifier.modify(facts, result);
                if (modified != null) {
                    result = modified;
                }
            } catch (RuntimeException e) {
                if (logger != null) {
                    logger.warn(e, "气候修正器 {} 处理 {} 时出错（已忽略该修正器）", modifier.id(), key);
                }
            }
        }
        if (active.isEmpty()) {
            cache.put(key, result);
        }
        return result;
    }

    private BiomeClimate resolveUncached(BiomeFacts facts) {
        String key = Ids.normalize(facts.id());

        BiomeClimate direct = explicit.get(key);
        if (direct != null) {
            return reconcileWithFacts(facts, direct);
        }

        for (TagRule rule : tagRules) {
            if (matchesTag(facts, rule.tag())) {
                return applyTemplate(rule.template(), facts, "tag:" + rule.tag(), rule.explicitRange());
            }
        }

        String category = facts.category() == null ? "none" : facts.category().toLowerCase(Locale.ROOT);
        for (CategoryRule rule : categoryRules) {
            if (rule.category().equals(category)) {
                return applyTemplate(rule.template(), facts, "category:" + category, rule.explicitRange());
            }
        }

        for (ClimateResolver resolver : resolvers) {
            Optional<BiomeClimate> resolved = resolver.resolve(facts);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        return fallback.resolve(facts).orElseGet(() -> BiomeClimate.builder(key)
                .climateClass(ClimateClass.UNKNOWN)
                .tempRange(ClimateClass.UNKNOWN.defaultRange())
                .source("fallback")
                .confidence(Confidence.GUESS)
                .build());
    }

    private static boolean matchesTag(BiomeFacts facts, String tag) {
        for (String raw : facts.tags()) {
            if (normalizeTag(raw).equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 内置数据 vs 真实群系事实的「一致性校验」。
     *
     * <p>现实里数据包会改写原版群系的温度 —— 例如 Terralith 把 {@code minecraft:savanna} 从 2.0 改回 1.2、
     * 把 {@code minecraft:stony_shore} 从 0.2 改成 0.6。如果模组死守内置表，遇到这类整合包就会给出错误的温度范围。</p>
     *
     * <p>做法：当内置表里的基础温度与实际读到的差得较多时，<b>保留气候大类与其它属性，但按实际温度重算温度范围</b>，
     * 并在 source 上标记 {@code |fact-adjusted} 以便诊断。合成事实（诊断命令用）不参与该校验。</p>
     */
    private BiomeClimate reconcileWithFacts(BiomeFacts facts, BiomeClimate direct) {
        if (facts.tags().contains(SYNTHETIC_FACT_TAG) || Float.isNaN(facts.baseTemperature())) {
            return direct;
        }
        // 只对「内置数据表」做一致性修正：玩家/整合包手写的配置是他们的意图，必须尊重
        if (!direct.source().startsWith("builtin:")) {
            return direct;
        }
        float delta = Math.abs(direct.baseTemperature() - facts.baseTemperature());
        if (delta <= FACT_TEMPERATURE_TOLERANCE) {
            return direct;
        }
        TempRange recalculated = ClimateMath.heuristicRange(direct.climateClass(), facts,
                HeuristicClimateResolver.DEFAULT_SPREAD, true);
        if (warnedReconciled.add(Ids.normalize(facts.id())) && logger != null) {
            logger.info("群系 {} 的实际基础温度 {} 与内置气候表 {} 不一致（差 {}），已按实际值重算温度范围：{}",
                    facts.id(), facts.baseTemperature(), direct.baseTemperature(),
                    String.format(java.util.Locale.ROOT, "%.2f", delta), recalculated.describe());
        }
        return BiomeClimate.builder(direct)
                .tempRange(recalculated)
                .baseTemperature(facts.baseTemperature())
                .source(direct.source() + "|fact-adjusted")
                .build();
    }

    /** 合成事实（没有真实群系上下文时构造的）带这个标签，一致性校验会跳过它们。 */
    public static final String SYNTHETIC_FACT_TAG = "nowweather:synthetic";

    /** 基础温度相差多少就认为「数据表过期了」。 */
    private static final float FACT_TEMPERATURE_TOLERANCE = 0.35F;

    /** 一致性修正只提示一次，避免刷屏。 */
    private final java.util.Set<String> warnedReconciled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 把模板套用到具体生物群系上。
     *
     * <p>当规则没有显式给出温度范围时，温度范围改为「按该群系真实原版温度推断」，
     * 这样一条 {@code #minecraft:is_forest} 规则可以安全地覆盖上千个模组群系。</p>
     */
    private static BiomeClimate applyTemplate(BiomeClimate template, BiomeFacts facts, String via,
                                              boolean explicitRange) {
        BiomeClimate.Builder builder = BiomeClimate.builder(template)
                .id(Ids.normalize(facts.id()))
                .source(template.source() + "|" + via);
        if (!explicitRange) {
            builder.tempRange(ClimateMath.heuristicRange(template.climateClass(), facts,
                            HeuristicClimateResolver.DEFAULT_SPREAD))
                    .baseTemperature(facts.baseTemperature())
                    .confidence(Confidence.LOW);
            // 原版事实里明确的降水形态优先（例如雪原群系一定下雪）
            if (template.precipitation() != PrecipitationType.NONE && facts.isSnowy()) {
                builder.precipitation(PrecipitationType.SNOW);
            }
        }
        return builder.build();
    }

    public int explicitCount() {
        return explicit.size();
    }

    public int tagRuleCount() {
        return tagRules.size();
    }

    public int categoryRuleCount() {
        return categoryRules.size();
    }

    public int cachedCount() {
        return cache.size();
    }

    public Collection<BiomeClimate> allExplicit() {
        return List.copyOf(explicit.values());
    }

    public String loadedSources() {
        return loadedSources;
    }

    // ------------------------------------------------------------------ 载入 JSON

    /**
     * 从一份 JSON 数据载入气候配置。支持三种写法混用：
     * <pre>{@code
     * {
     *   "biomes": [ {"id": "minecraft:desert", "climateClass": "ARID", "tempMinC": 15, "tempMaxC": 52} ],
     *   "tags":   [ {"tag": "minecraft:is_forest", "climateClass": "TEMPERATE"} ],
     *   "categories": [ {"category": "snowy", "climateClass": "POLAR"} ]
     * }
     * }</pre>
     * {@code "biomes"} 也可以写成 {@code {"minecraft:desert": {...}}} 的映射形式。
     */
    public LoadReport loadJson(String sourceName, JsonValue document) {
        LoadReport report = new LoadReport(sourceName);
        JsonObject root = document.asObjectOrNull();
        if (root == null) {
            report.warn("根节点不是 JSON 对象，已忽略");
            return report;
        }

        // --- 单个生物群系 ---
        JsonValue biomes = root.get("biomes");
        if (biomes instanceof JsonArray array) {
            for (JsonObject entry : array.objects()) {
                try {
                    register(BiomeClimate.fromJsonObject(entry));
                    report.biomes++;
                } catch (RuntimeException e) {
                    report.warn("跳过一条生物群系配置: " + e.getMessage());
                }
            }
        } else if (biomes instanceof JsonObject map) {
            for (Map.Entry<String, JsonValue> entry : map.entrySet()) {
                JsonObject body = entry.getValue().asObjectOrNull();
                if (body == null) {
                    report.warn("biomes." + entry.getKey() + " 不是对象，已忽略");
                    continue;
                }
                try {
                    register(BiomeClimate.fromJson(entry.getKey(), body, null));
                    report.biomes++;
                } catch (RuntimeException e) {
                    report.warn("解析 biomes." + entry.getKey() + " 失败: " + e.getMessage());
                }
            }
        } else if (biomes != null) {
            report.warn("biomes 字段应为数组或对象");
        }

        // --- 标签规则 ---
        JsonArray tags = root.optArray("tags");
        if (tags != null) {
            for (JsonObject entry : tags.objects()) {
                String tag = entry.optString("tag", entry.optString("id", null));
                if (tag == null || tag.isBlank()) {
                    report.warn("tags 中有一条缺少 tag 字段");
                    continue;
                }
                BiomeClimate template = BiomeClimate.fromJson(tag, entry, null);
                addTagRule(tag, template, hasExplicitRange(entry));
                report.tags++;
            }
        }

        // --- 分类规则 ---
        JsonArray categories = root.optArray("categories");
        if (categories != null) {
            for (JsonObject entry : categories.objects()) {
                String category = entry.optString("category", entry.optString("id", null));
                if (category == null || category.isBlank()) {
                    report.warn("categories 中有一条缺少 category 字段");
                    continue;
                }
                BiomeClimate template = BiomeClimate.fromJson("category:" + category, entry, null);
                addCategoryRule(category, template, hasExplicitRange(entry));
                report.categories++;
            }
        }

        String previous = loadedSources;
        loadedSources = previous.isEmpty() ? sourceName : previous + ", " + sourceName;
        if (!report.warnings.isEmpty() && logger != null) {
            report.warnings.forEach(w -> logger.warn("载入 {} 时: {}", sourceName, w));
        }
        return report;
    }

    /** 导出为 JSON，用于 {@code /NowWeather dump} 与问题排查。 */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray biomes = new JsonArray();
        explicit.values().stream()
                .sorted(Comparator.comparing(BiomeClimate::biomeId))
                .forEach(c -> biomes.add(c.toJson()));
        root.put("biomes", biomes);

        JsonArray tags = new JsonArray();
        for (TagRule rule : tagRules) {
            JsonObject o = rule.template().toJson();
            o.put("tag", rule.tag());
            tags.add(o);
        }
        root.put("tags", tags);

        JsonArray categories = new JsonArray();
        for (CategoryRule rule : categoryRules) {
            JsonObject o = rule.template().toJson();
            o.put("category", rule.category());
            categories.add(o);
        }
        root.put("categories", categories);
        return root;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(Text.tr("nowweather.biome_registry.counts",
                Integer.toString(explicit.size()), Integer.toString(tagRules.size()),
                Integer.toString(categoryRules.size()), Integer.toString(resolvers.size()),
                Integer.toString(cache.size())));
        if (!loadedSources.isEmpty()) {
            lines.add(Text.tr("nowweather.biome_registry.sources", String.valueOf(loadedSources)));
        }
        return lines;
    }

    /** 判断一条标签/分类规则是否显式给出了温度范围。 */
    private static boolean hasExplicitRange(JsonObject entry) {
        return entry.hasNonNull("tempRange")
                || entry.hasNonNull("tempMinC")
                || entry.hasNonNull("tempMaxC")
                || entry.hasNonNull("temp_min_c")
                || entry.hasNonNull("temp_max_c");
    }

    private static String normalizeTag(String tag) {        String t = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (!t.isEmpty() && t.indexOf(':') < 0) {
            t = "minecraft:" + t;
        }
        return t;
    }

    /** 单条「标签 → 气候模板」规则。 */
    public record TagRule(String tag, BiomeClimate template, boolean explicitRange) {
    }

    /** 单条「原版分类 → 气候模板」规则。 */
    public record CategoryRule(String category, BiomeClimate template, boolean explicitRange) {
    }

    /** 载入结果报告。 */
    public static final class LoadReport {
        private final String source;
        private final List<String> warnings = new ArrayList<>();
        private int biomes;
        private int tags;
        private int categories;

        LoadReport(String source) {
            this.source = source;
        }

        void warn(String message) {
            warnings.add(message);
        }

        public String source() {
            return source;
        }

        public int biomes() {
            return biomes;
        }

        public int tags() {
            return tags;
        }

        public int categories() {
            return categories;
        }

        public List<String> warnings() {
            return List.copyOf(warnings);
        }

        public int total() {
            return biomes + tags + categories;
        }

        @Override
        public String toString() {
            return Text.tr("nowweather.load_report.summary", source,
                    Integer.toString(biomes), Integer.toString(tags),
                    Integer.toString(categories), Integer.toString(warnings.size()));
        }
    }

    /** 便于测试与调试：把当前注册表复制成不可变 Map。 */
    public Map<String, BiomeClimate> snapshotExplicit() {
        return Map.copyOf(new LinkedHashMap<>(explicit));
    }
}

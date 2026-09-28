package com.nowweather.tools;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.climate.WeatherAllowance;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.weather.WeatherType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 气候数据集生成器 —— 单一数据源产出「模组数据包 JSON + 人类可读总表」。
 *
 * <p>为什么用生成而不是手写：生物群系条目很快会到几百条，手写 JSON 一定会出现
 * 字段拼错、温度范围写反、群系名打错这类问题；而且「可能出现的天气」白名单
 * 与实际运行时的推导逻辑必须完全一致 —— 生成器直接调用
 * {@link WeatherAllowance#derive(BiomeClimate)}，保证文档与代码永不脱节。</p>
 *
 * <p>用法（在仓库根目录）：</p>
 * <pre>
 * javac -d tools/out/core $(core 全部源码)
 * javac -cp tools/out/core -d tools/out/gen tools/gen/GenerateClimateDatasets.java
 * java  -cp tools/out/core;tools/out/gen com.nowweather.tools.GenerateClimateDatasets
 * </pre>
 */
public final class GenerateClimateDatasets {

    private static final Path RESOURCES = Path.of("versions/forge-common/src/main/resources/data/nowweather/climate");
    private static final Path DOCS = Path.of("docs");
    /** 人工维护的原版数据源（以下划线开头，模组运行时不加载它，只加载生成出来的文件）。 */
    private static final Path VANILLA_INPUT = RESOURCES.resolve("_vanilla_source_1.18.2.json");
    private static final Path BOP_RESEARCH = Path.of("docs/research/bop-biomes-1.18.2.md");

    private GenerateClimateDatasets() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(DOCS);
        List<Row> rows = new ArrayList<>();

        // ---- 1) 原版 1.18 基线：读取人工整理好的表，修正后重新生成 ----
        JsonObject vanillaInput = JsonValue.parse(Files.readString(VANILLA_INPUT, StandardCharsets.UTF_8)).asObject();
        List<Row> vanillaRows = readRows(vanillaInput.optArray("biomes"), "builtin:vanilla", "1.18");
        applyVanillaCorrections(vanillaRows);
        writeDataset(RESOURCES.resolve("vanilla_1.18.1-1.18.2.json"),
                "原版 Minecraft 1.18.x 生物群系气候映射（1.18.1 / 1.18.2 通用）",
                List.of("1.18", "1.18.1", "1.18.2"), vanillaRows, vanillaInput.optArray("tags"),
                vanillaInput.optArray("categories"));
        rows.addAll(vanillaRows);

        // ---- 2) 后续版本新增的原版群系 ----
        List<Row> added = laterVanillaBiomes();
        writeDataset(RESOURCES.resolve("vanilla_later_1.19-26.x.json"),
                "原版 Minecraft 1.19 ~ 26.x 新增生物群系的气候映射。版本依据 docs/research/biomes-by-version.md；"
                        + "在旧版本上不存在的条目永远不会命中，可以安全地一起打包。",
                List.of("1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20", "1.20.1", "1.20.2",
                        "1.20.3", "1.20.4", "1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3",
                        "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
                        "26.1", "26.1.1", "26.1.2", "26.2", "26.3"),
                added, null, null);
        rows.addAll(added);

        // ---- 3) 超多生物群系（Biomes O' Plenty）：来自研究报告的源码级数据 ----
        List<Row> bopRows = readBopResearch();
        if (!bopRows.isEmpty()) {
            writeDataset(RESOURCES.resolve("biomesoplenty_16.0.0.134.json"),
                    "超多生物群系（Biomes O' Plenty）1.18.x 气候映射。数据来源：Glitchfiend/BiomesOPlenty 源码（见 docs/research/bop-biomes-1.18.2.md）",
                    List.of("1.18", "1.18.1", "1.18.2"), bopRows, null, null);
            rows.addAll(bopRows);
        }

        // ---- 4) Terralith / Tectonic：来自调研报告的群系清单 ----
        List<Row> TerralithRows = readThirdPartyResearch();
        if (!TerralithRows.isEmpty()) {
            List<Row> terralith = TerralithRows.stream().filter(r -> r.id().startsWith("terralith:")).toList();
            List<Row> tectonic = TerralithRows.stream().filter(r -> r.id().startsWith("tectonic:")).toList();
            if (!terralith.isEmpty()) {
                writeDataset(RESOURCES.resolve("terralith_2.2.6.json"),
                        "Terralith 1.18.2 群系气候映射。基础温度/降水来自其数据包（见 docs/research/terralith-tectonic-biomes.md），"
                                + "气候大类与温度范围由本模组的启发式规则推导。",
                        List.of("1.18.2"), terralith, null, null);
                rows.addAll(terralith);
            }
            if (!tectonic.isEmpty()) {
                writeDataset(RESOURCES.resolve("tectonic_2.3.5a.json"),
                        "Tectonic 1.18.2 群系气候映射（v1.x 注册的 26 个群系）。来源同上。",
                        List.of("1.18.2"), tectonic, null, null);
                rows.addAll(tectonic);
            }
        }

        // ---- 5) 生成人类可读总表 ----
        writeMarkdownTable(rows, DOCS.resolve("BIOME_CLIMATE_TABLE.md"));

        System.out.println("生成完成：");
        System.out.println("  原版 1.18 基线      : " + vanillaRows.size() + " 条");
        System.out.println("  1.19 ~ 26.x 新增    : " + added.size() + " 条");
        System.out.println("  超多生物群系        : " + bopRows.size() + " 条");
        System.out.println("  合计                : " + rows.size() + " 条");
        System.out.println("  输出目录            : " + RESOURCES.toAbsolutePath());
        System.out.println("  总表                : " + DOCS.resolve("BIOME_CLIMATE_TABLE.md").toAbsolutePath());
    }

    // ------------------------------------------------------------------ 数据行

    /** 一条生物群系气候数据。 */
    private record Row(String id, ClimateClass climateClass, double minC, double maxC,
                       PrecipitationType precipitation, double humidity, double windiness,
                       double thunderBias, float baseTemperature, String since, String source,
                       String confidence, String note) {

        BiomeClimate toClimate() {
            return BiomeClimate.builder(id)
                    .climateClass(climateClass)
                    .tempRange(minC, maxC)
                    .precipitation(precipitation)
                    .humidity(humidity)
                    .windiness(windiness)
                    .thunderBias(thunderBias)
                    .baseTemperature(baseTemperature)
                    .source(source)
                    .confidence(com.nowweather.core.climate.Confidence.parse(confidence,
                            com.nowweather.core.climate.Confidence.MEDIUM))
                    .build();
        }
    }

    private static List<Row> readRows(JsonArray array, String defaultSource, String defaultSince) {
        List<Row> rows = new ArrayList<>();
        if (array == null) {
            return rows;
        }
        for (JsonObject o : array.objects()) {
            rows.add(new Row(
                    o.optString("id", ""),
                    ClimateClass.parse(o.optString("climateClass", "UNKNOWN"), ClimateClass.UNKNOWN),
                    o.optDouble("tempMinC", Double.NaN),
                    o.optDouble("tempMaxC", Double.NaN),
                    PrecipitationType.parse(o.optString("precipitation", null), PrecipitationType.MIXED),
                    o.optDouble("humidity", 0.5D),
                    o.optDouble("windiness", 0.4D),
                    o.optDouble("thunderBias", 1.0D),
                    (float) o.optDouble("baseTemperature", 0.5D),
                    o.optString("since", defaultSince),
                    o.optString("source", defaultSource),
                    o.optString("confidence", "MEDIUM"),
                    o.optString("note", "")));
        }
        return rows;
    }

    /** 依据源码级核对结果修正原版数据。 */
    private static void applyVanillaCorrections(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            // Java 版 1.18.2 源码：savanna 系基础温度均为 2.0，且降水为 none（Java 版稀树草原不下雨）
            switch (r.id()) {
                case "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_savanna" -> rows.set(i,
                        new Row(r.id(), ClimateClass.SUBTROPICAL, r.minC(), r.maxC(), PrecipitationType.NONE,
                                r.humidity(), r.windiness(), r.thunderBias(), 2.0F, r.since(), r.source(),
                                "HIGH", "Java 版稀树草原降水为 none（与原版一致），如需降雨请自行改配置"));
                // 极地气候：原版允许「雷打雪」，但不应出现强雷暴
                case "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:frozen_river",
                     "minecraft:snowy_beach", "minecraft:snowy_plains", "minecraft:ice_spikes",
                     "minecraft:snowy_slopes", "minecraft:frozen_peaks", "minecraft:jagged_peaks" -> rows.set(i,
                        new Row(r.id(), r.climateClass(), r.minC(), r.maxC(), r.precipitation(), r.humidity(),
                                r.windiness(), 0.5D, r.baseTemperature(), r.since(), r.source(), r.confidence(),
                                r.note()));
                default -> {
                }
            }
        }
    }

    /**
     * 1.19 之后新增的原版生物群系（共 6 个，全部在主世界）。
     *
     * <p>版本号依据 docs/research/biomes-by-version.md 的核对结果：</p>
     * <ul>
     *   <li>1.19：deep_dark、mangrove_swamp</li>
     *   <li>cherry_grove：1.19.4 起出现在「Update 1.20」实验数据包里，1.20 转正</li>
     *   <li>pale_garden：1.21.2 起出现在「Winter Drop」实验数据包里，1.21.4 转正</li>
     *   <li>26.2（Chaos Cubed）：sulfur_caves</li>
     *   <li>26.3（Wilderness Bound）：dappled_forest —— 截至核对时尚未正式发布</li>
     * </ul>
     *
     * <p>温度范围与天气白名单按「原版基础温度 + 现实气候常识」给出。</p>
     */
    private static List<Row> laterVanillaBiomes() {
        return List.of(
                new Row("minecraft:deep_dark", ClimateClass.CAVE, 0, 18, PrecipitationType.NONE,
                        0.75D, 0.02D, 0.00D, 0.8F, "1.19", "builtin:vanilla", "HIGH",
                        "深暗之域：地下深处，无天气变化"),
                new Row("minecraft:mangrove_swamp", ClimateClass.WETLAND, 12, 38, PrecipitationType.RAIN,
                        0.95D, 0.20D, 1.30D, 0.8F, "1.19", "builtin:vanilla", "HIGH",
                        "红树林沼泽：炎热潮湿的红树沼泽"),
                new Row("minecraft:cherry_grove", ClimateClass.TEMPERATE, -8, 30, PrecipitationType.RAIN,
                        0.68D, 0.25D, 0.85D, 0.5F, "1.20", "builtin:vanilla", "HIGH",
                        "樱花树林：温和湿润的山地林地（1.19.4 实验数据包内已存在）"),
                new Row("minecraft:pale_garden", ClimateClass.TEMPERATE, -6, 24, PrecipitationType.RAIN,
                        0.80D, 0.15D, 0.70D, 0.7F, "1.21.4", "builtin:vanilla", "HIGH",
                        "苍白之园：阴森湿冷、终年灰暗（1.21.2 实验数据包内已存在）"),
                new Row("minecraft:sulfur_caves", ClimateClass.CAVE, 8, 34, PrecipitationType.NONE,
                        0.40D, 0.03D, 0.00D, 0.8F, "26.2", "builtin:vanilla", "MEDIUM",
                        "硫磺洞穴（26.2 Chaos Cubed 新增）：温暖、干燥、充满硫磺的地下洞穴，无地表天气"),
                new Row("minecraft:dappled_forest", ClimateClass.TEMPERATE, -10, 30, PrecipitationType.RAIN,
                        0.72D, 0.28D, 1.00D, 0.6F, "26.3", "builtin:vanilla", "MEDIUM",
                        "斑驳森林（26.3 Wilderness Bound 新增）：光照斑驳的温带森林"));
    }

    /**
     * 读取 Terralith / Tectonic 的调研结果（{@code docs/research/terralith-tectonic.json}）。
     *
     * <p>关键点：<b>气候大类与温度范围不是手写的</b>，而是把「原版基础温度 + 降水 + 分类」
     * 交给运行时的 {@link com.nowweather.core.climate.HeuristicClimateResolver} 推导 ——
     * 这样生成出来的 JSON 和游戏里遇到未知群系时的行为完全一致，不会出现「表里一种、运行另一种」。</p>
     *
     * <p><b>找不到输入文件时直接抛异常，不再静默跳过。</b>
     * 这里曾经因为路径写错（文件名带了一个早已不存在的版本号后缀）而静默返回空列表，
     * 结果重跑生成器会把数据集从 250 条悄悄缩水成 124 条、并且覆盖掉正确的文档总表 ——
     * 一次「什么都没发生」的失败，比一次报错危险得多。</p>
     */
    private static List<Row> readThirdPartyResearch() throws IOException {
        Path path = Path.of("docs/research/terralith-tectonic.json");
        if (!Files.exists(path)) {
            throw new IOException("缺少 Terralith/Tectonic 调研数据：" + path.toAbsolutePath()
                    + "。它不在的话生成结果会缺 122 条群系，因此这里拒绝静默降级。");
        }
        JsonObject root = JsonValue.parse(Files.readString(path, StandardCharsets.UTF_8)).asObject();
        JsonArray mods = root.optArray("mods");
        List<Row> rows = new ArrayList<>();
        if (mods == null) {
            return rows;
        }
        var resolver = new com.nowweather.core.climate.HeuristicClimateResolver();
        for (JsonObject mod : mods.objects()) {
            String modId = mod.optString("id", "");
            if (modId.isBlank()) {
                continue;
            }
            JsonArray biomes = mod.optArray("biomes");
            if (biomes == null) {
                continue;
            }
            int count = 0;
            for (JsonObject entry : biomes.objects()) {
                String id = entry.optString("id", "");
                if (id.isBlank() || !id.contains(":")) {
                    continue;
                }
                float baseTemperature = (float) entry.optDouble("baseTemperature", 0.5D);
                String precipitation = entry.optString("precipitation", "RAIN");
                String category = entry.optString("category", "none");
                boolean cave = id.contains("cave") || "underground".equalsIgnoreCase(category);

                com.nowweather.core.climate.BiomeFacts facts = new com.nowweather.core.climate.BiomeFacts(
                        id, baseTemperature, 0.5F,
                        PrecipitationType.parse(precipitation, PrecipitationType.MIXED),
                        category, Set.of(), "minecraft:overworld", cave);
                var climate = resolver.resolve(facts).orElse(null);
                if (climate == null) {
                    continue;
                }
                rows.add(new Row(id, climate.climateClass(),
                        climate.tempRange().minC(), climate.tempRange().maxC(),
                        climate.precipitation(), climate.humidity(), climate.windiness(),
                        climate.thunderBias(), baseTemperature, "1.18.2",
                        "builtin:" + modId,
                        "MEDIUM",
                        "基础温度来自 " + modId + " 数据包；温度范围由启发式规则推导"));
                count++;
            }
            System.out.println("  " + modId + "：转换 " + count + " 个群系");
        }
        return rows;
    }

    /** 从研究报告里抽出 JSON 代码块并转换成项目内部格式。 */
    private static List<Row> readBopResearch() throws IOException {
        if (!Files.exists(BOP_RESEARCH)) {
            System.out.println("（跳过 BOP：未找到 " + BOP_RESEARCH + "）");
            return List.of();
        }
        String text = Files.readString(BOP_RESEARCH, StandardCharsets.UTF_8);
        int start = text.indexOf("```json");
        if (start < 0) {
            System.out.println("（跳过 BOP：研究报告里没找到 JSON 代码块）");
            return List.of();
        }
        start = text.indexOf('\n', start) + 1;
        int end = text.indexOf("```", start);
        String json = text.substring(start, end).trim();

        JsonObject root = JsonValue.parse(json).asObject();
        JsonArray biomes = root.optArray("biomes");
        List<Row> rows = new ArrayList<>();
        if (biomes == null) {
            return rows;
        }
        int skipped = 0;
        for (JsonObject o : biomes.objects()) {
            String id = o.optString("id", "");
            if (!id.startsWith("biomesoplenty:")) {
                continue; // 研究报告同时含原版条目，原版走我们自己维护的表
            }
            String rawClass = o.optString("climateClass", "TEMPERATE");
            ClimateClass mapped = mapResearchClass(rawClass);
            if (mapped == ClimateClass.UNKNOWN) {
                skipped++;
            }
            double min = o.optDouble("tempMinC", Double.NaN);
            double max = o.optDouble("tempMaxC", Double.NaN);
            PrecipitationType precipitation = PrecipitationType.parse(o.optString("precipitation", null),
                    PrecipitationType.MIXED);
            double humidity = humidityOf(mapped, precipitation, max);
            double windiness = windinessOf(mapped, id);
            double thunderBias = thunderBiasOf(mapped, humidity);
            rows.add(new Row(id, mapped, min, max, precipitation, humidity, windiness, thunderBias,
                    (float) o.optDouble("baseTemperature", 0.5D), "1.18",
                    "builtin:biomesoplenty", o.optString("confidence", "MEDIUM").toUpperCase(Locale.ROOT), ""));
        }
        if (skipped > 0) {
            System.out.println("（BOP：有 " + skipped + " 条气候大类无法映射，已按 TEMPERATE 处理）");
        }
        rows.sort(Comparator.comparing(Row::id));
        return rows;
    }

    /** 研究报告 §2.4 给出的类名映射表。 */
    private static ClimateClass mapResearchClass(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "TROPICAL" -> ClimateClass.TROPICAL;
            case "TROPICAL_SWAMP", "TEMPERATE_SWAMP" -> ClimateClass.WETLAND;
            case "WARM_TEMPERATE" -> ClimateClass.SUBTROPICAL;
            case "TEMPERATE" -> ClimateClass.TEMPERATE;
            case "COLD" -> ClimateClass.SUBPOLAR;
            case "COLD_SWAMP" -> ClimateClass.SUBPOLAR;
            case "SNOWY", "SNOWY_SWAMP", "OCEAN_FROZEN" -> ClimateClass.POLAR;
            case "ARID" -> ClimateClass.ARID;
            case "SEMI_ARID" -> ClimateClass.SUBTROPICAL;
            case "COLD_ARID" -> ClimateClass.ARID;
            case "MOUNTAIN" -> ClimateClass.MOUNTAIN;
            case "VOLCANIC" -> ClimateClass.VOLCANIC;
            case "COASTAL" -> ClimateClass.COASTAL;
            case "OCEAN_COLD" -> ClimateClass.SUBPOLAR;
            case "OCEAN_TEMPERATE" -> ClimateClass.OCEANIC;
            case "OCEAN_WARM" -> ClimateClass.SUBTROPICAL;
            case "CAVE" -> ClimateClass.CAVE;
            case "NETHER" -> ClimateClass.NETHER;
            case "END" -> ClimateClass.END;
            case "MUSHROOM" -> ClimateClass.MUSHROOM;
            case "OTHER" -> ClimateClass.VOID;
            default -> ClimateClass.parse(raw, ClimateClass.UNKNOWN);
        };
    }

    private static double humidityOf(ClimateClass climateClass, PrecipitationType precipitation, double maxC) {
        if (precipitation == PrecipitationType.NONE) {
            return climateClass == ClimateClass.POLAR ? 0.55D : 0.10D;
        }
        if (precipitation == PrecipitationType.SNOW) {
            return 0.66D;
        }
        return switch (climateClass) {
            case TROPICAL -> 0.90D;
            case WETLAND -> 0.92D;
            case OCEANIC, COASTAL -> 0.82D;
            case SUBTROPICAL -> 0.60D;
            case MEDITERRANEAN -> 0.50D;
            case TEMPERATE -> 0.62D;
            case CONTINENTAL -> 0.55D;
            case SUBPOLAR -> 0.66D;
            case POLAR -> 0.60D;
            case MOUNTAIN -> 0.58D;
            case PLATEAU -> 0.40D;
            case ARID -> 0.10D;
            case VOLCANIC -> 0.05D;
            case MUSHROOM -> 0.85D;
            case CAVE -> 0.85D;
            case NETHER -> 0.05D;
            case END -> 0.05D;
            case VOID -> 0.00D;
            default -> maxC > 28 ? 0.35D : 0.55D;
        };
    }

    private static double windinessOf(ClimateClass climateClass, String id) {
        double base = switch (climateClass) {
            case OCEANIC -> 0.82D;
            case COASTAL -> 0.78D;
            case MOUNTAIN -> 0.86D;
            case PLATEAU -> 0.72D;
            case POLAR -> 0.65D;
            case ARID, VOLCANIC -> 0.58D;
            case WETLAND -> 0.18D;
            case TROPICAL -> 0.24D;
            default -> 0.40D;
        };
        // 名字里带 crag/highland/peak/cliff 的群系多风
        String path = id.substring(id.indexOf(':') + 1);
        if (path.contains("crag") || path.contains("highland") || path.contains("peak") || path.contains("cliff")) {
            base = Math.max(base, 0.82D);
        }
        return base;
    }

    private static double thunderBiasOf(ClimateClass climateClass, double humidity) {
        if (humidity < 0.15D) {
            return 0.15D;
        }
        return switch (climateClass) {
            case TROPICAL -> 1.55D;
            case WETLAND -> 1.35D;
            case SUBTROPICAL, OCEANIC -> 1.15D;
            case POLAR, SUBPOLAR -> 0.50D;
            case CAVE -> 0.30D;
            case ARID, VOLCANIC, NETHER, END, VOID -> 0.15D;
            default -> 1.00D;
        };
    }

    // ------------------------------------------------------------------ 输出

    private static void writeDataset(Path target, String description, List<String> mcVersions,
                                     List<Row> rows, JsonArray tags, JsonArray categories) throws IOException {
        JsonObject root = new JsonObject();
        root.put("schema", 1L);
        root.put("description", description);
        JsonArray versions = new JsonArray();
        mcVersions.forEach(versions::add);
        root.put("mcVersions", versions);
        // 数据集对应的「模组版本」——文件名里也带，但写进内容里才能被程序读到。
        // 模组更新时先看这里：版本对不上的数据集就是该复核的。
        root.put("modVersion", target.getFileName().toString()
                .replaceAll("^_", "").replaceAll("\\.json$", ""));

        JsonArray biomes = new JsonArray();
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Row::id));
        for (Row row : sorted) {
            BiomeClimate climate = row.toClimate();
            JsonObject o = climate.toJson();
            o.put("since", row.since());
            if (!row.note().isEmpty()) {
                o.put("note", row.note());
            }
            biomes.add(o);
        }
        root.put("biomes", biomes);
        if (tags != null) {
            root.put("tags", tags);
        }
        if (categories != null) {
            root.put("categories", categories);
        }

        Files.createDirectories(target.getParent());
        Files.writeString(target, root.toPrettyJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("  写入 " + target + "（" + sorted.size() + " 条）");
    }

    /** 生成人类可读的「生物群系 → 正常温度范围 → 可能出现的天气」总表。 */
    private static void writeMarkdownTable(List<Row> rows, Path target) throws IOException {
        rows.sort(Comparator.comparing(Row::id));

        StringBuilder sb = new StringBuilder();
        sb.append("# NowWeather 生物群系气候总表\n\n");
        sb.append("> 本文件由 `tools/gen/GenerateClimateDatasets.java` 自动生成，请勿手工修改；");
        sb.append("数据源在 `versions/forge-common/src/main/resources/data/nowweather/climate/*.json`。\n\n");
        sb.append("每一条都给出：**正常温度范围**（判定气象数据是否合理的依据）、");
        sb.append("**可能产生的原版天气**（晴 / 雨 / 雷暴）、");
        sb.append("以及**可能出现的扩展天气**（HUD 与预报使用的细分天气）。\n\n");

        sb.append("| 生物群系 | 气候大类 | 正常温度范围 | 降水形态 | 可能出现的原版天气 | 可能出现的扩展天气 | 加入版本 | 数据来源 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            BiomeClimate climate = row.toClimate();
            Set<WeatherType> types = new TreeSet<>(Comparator.comparing(WeatherType::name));
            types.addAll(climate.possibleWeather());
            StringBuilder extended = new StringBuilder();
            for (WeatherType type : types) {
                if (extended.length() > 0) {
                    extended.append('、');
                }
                extended.append(type.zhName());
            }
            sb.append("| `").append(row.id()).append("` | ")
                    .append(climate.climateClass().zhName()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.0f ~ %.0f°C", row.minC(), row.maxC())).append(" | ")
                    .append(row.precipitation().zhName()).append(" | ")
                    .append(WeatherAllowance.describePlatform(climate.possiblePlatformWeather())).append(" | ")
                    .append(extended).append(" | ")
                    .append(row.since()).append(" | ")
                    .append(row.source().replace("builtin:", "")).append(" |\n");
        }

        // 按气候大类汇总，便于快速核对
        sb.append("\n## 按气候大类汇总\n\n");
        sb.append("| 气候大类 | 群系数 | 默认温度范围 | 可能出现的原版天气 |\n");
        sb.append("| --- | --- | --- | --- |\n");
        Map<ClimateClass, List<Row>> grouped = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.climateClass(), k -> new ArrayList<>()).add(row);
        }
        grouped.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ClimateClass, List<Row>> e) -> e.getKey().name()))
                .forEach(e -> {
                    // 汇总行取该气候大类下所有群系天气白名单的并集，避免「恰好第一条是沙漠」这类误导。
                    // ★ 必须用 TreeSet 而不是 LinkedHashSet：白名单来源是 Set.copyOf(...)，
                    // 其迭代顺序取决于每次 JVM 启动的随机 SALT，用它做并集会让生成的文档不可复现。
                    java.util.TreeSet<com.nowweather.core.api.PlatformWeather> union =
                            new java.util.TreeSet<>(Comparator.comparing(Enum::name));
                    for (Row row : e.getValue()) {
                        union.addAll(row.toClimate().possiblePlatformWeather());
                    }
                    sb.append("| ").append(e.getKey().zhName()).append(" | ").append(e.getValue().size())
                            .append(" | ").append(e.getKey().defaultRange().describe()).append(" | ")
                            .append(WeatherAllowance.describePlatform(java.util.Collections.unmodifiableSet(union)))
                            .append(" |\n");
                });

        sb.append("\n合计 **").append(rows.size()).append("** 个生物群系。\n");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("  写入 " + target);
    }
}

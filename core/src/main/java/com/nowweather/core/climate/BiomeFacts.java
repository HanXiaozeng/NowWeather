package com.nowweather.core.climate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生物群系「事实」—— 由平台层从 Minecraft 里读出、交给核心的原始信息。
 *
 * <p>核心不认识 {@code Biome} 类，只认识这一组事实；这样同一套气候推断逻辑
 * 可以跑在 1.18 / 1.21 / 26.x 甚至 Fabric 上。</p>
 *
 * @param id              资源 id，如 {@code minecraft:plains}（已归一化）
 * @param baseTemperature 原版基础温度（MC 量纲，雪原 0.0 / 平原 0.8 / 沙漠 2.0）
 * @param downfall        原版降水量（0~1）
 * @param precipitation   原版降水类型（无 / 雨 / 雪）
 * @param category        原版生物群系分类（如 {@code forest}），可能为 {@code none}
 * @param tags            该生物群系所属的标签（含 {@code #} 前缀可选），如 {@code minecraft:is_forest}
 * @param dimension       所在维度 id，如 {@code minecraft:overworld}
 * @param cave            是否洞穴生物群系
 */
public record BiomeFacts(
        String id,
        float baseTemperature,
        float downfall,
        PrecipitationType precipitation,
        String category,
        Set<String> tags,
        String dimension,
        boolean cave) {

    public BiomeFacts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (precipitation == null) {
            precipitation = PrecipitationType.MIXED;
        }
    }

    public static BiomeFacts of(String id, float baseTemperature, float downfall) {
        return new BiomeFacts(id, baseTemperature, downfall, PrecipitationType.MIXED, "none", Set.of(),
                "minecraft:overworld", false);
    }

    public boolean isNether() {
        return "minecraft:the_nether".equals(dimension);
    }

    public boolean isEnd() {
        return "minecraft:the_end".equals(dimension);
    }

    public boolean isSnowy() {
        return precipitation == PrecipitationType.SNOW || baseTemperature <= 0.05F;
    }

    public BiomeFacts withCategory(String newCategory) {
        return new BiomeFacts(id, baseTemperature, downfall, precipitation, newCategory, tags, dimension, cave);
    }

    public BiomeFacts withTag(String tag) {
        Set<String> next = new LinkedHashSet<>(tags);
        next.add(tag);
        return new BiomeFacts(id, baseTemperature, downfall, precipitation, category, next, dimension, cave);
    }

    public String path() {
        int idx = id.indexOf(':');
        return idx < 0 ? id : id.substring(idx + 1);
    }

    /** 便于日志与诊断展示的一行摘要。 */
    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add(id);
        parts.add(String.format(Locale.ROOT, "base=%.2f", baseTemperature));
        parts.add(String.format(Locale.ROOT, "downfall=%.2f", downfall));
        parts.add("precip=" + precipitation.name());
        parts.add("category=" + category);
        if (cave) {
            parts.add("cave");
        }
        if (!"minecraft:overworld".equals(dimension)) {
            parts.add("dim=" + dimension);
        }
        return String.join(" ", parts);
    }
}

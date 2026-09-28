package com.nowweather.core.util;

import java.util.Locale;

/** 命名空间 id（如 {@code minecraft:plains}）工具。 */
public final class Ids {

    public static final String MINECRAFT = "minecraft";

    private Ids() {
    }

    /**
     * 归一化一个资源 id：转小写、去空格、缺省补 {@code minecraft:} 前缀。
     * {@code "Plains"} → {@code "minecraft:plains"}。
     */
    public static String normalize(String id) {
        if (id == null) {
            return MINECRAFT + ":unknown";
        }
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return MINECRAFT + ":unknown";
        }
        if (trimmed.indexOf(':') < 0) {
            return MINECRAFT + ":" + trimmed;
        }
        return trimmed;
    }

    public static String namespace(String id) {
        String normalized = normalize(id);
        int idx = normalized.indexOf(':');
        return idx < 0 ? MINECRAFT : normalized.substring(0, idx);
    }

    public static String path(String id) {
        String normalized = normalize(id);
        int idx = normalized.indexOf(':');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    public static boolean isVanilla(String id) {
        return MINECRAFT.equals(namespace(id));
    }

    /** 判断 id 是否以给定的「关键词段」结尾或包含（用于无需硬编码名单的启发式匹配）。 */
    public static boolean pathContainsAny(String id, String... needles) {
        String path = path(id);
        for (String needle : needles) {
            if (path.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

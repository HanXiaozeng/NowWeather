package com.nowweather.core.api;

/** 外部模组存在性探测 —— 让核心不去直接调用加载器的 API。 */
public interface ModPresence {

    boolean isLoaded(String modId);

    default String version(String modId) {
        return isLoaded(modId) ? "unknown" : "";
    }

    ModPresence NONE = modId -> false;
}

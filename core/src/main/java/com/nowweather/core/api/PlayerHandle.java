package com.nowweather.core.api;

/**
 * 玩家抽象 —— 核心看到的「玩家」只有这些信息，不需要认识 {@code ServerPlayer}。
 *
 * <p>所有坐标/群系查询都由平台层实现，因此核心逻辑可以跨版本复用。</p>
 */
public interface PlayerHandle {

    java.util.UUID id();

    String name();

    /** 所在维度 id，如 {@code minecraft:overworld}。 */
    String dimensionId();

    /** 所在生物群系的资源 id，如 {@code biomesoplenty:lavender_field}。 */
    String biomeId();

    double x();

    double y();

    double z();

    /** 是否处在能看见天空的位置（洞穴里不该看到「晴天」）。 */
    default boolean canSeeSky() {
        return true;
    }

    /** 是否有权限执行管理命令。 */
    default boolean hasPermission(int level) {
        return false;
    }

    /** 给玩家发一条消息（支持 § 颜色代码）。 */
    void sendMessage(String text);

    /** 玩家语言，用于本地化（默认 zh_cn）。 */
    default String language() {
        return "zh_cn";
    }
}

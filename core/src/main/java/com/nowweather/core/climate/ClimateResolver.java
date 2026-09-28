package com.nowweather.core.climate;

import java.util.Optional;

/**
 * 气候解析器：把「生物群系事实」变成「气候画像」。
 *
 * <p>这是留给其它模组/数据包的第一个扩展点：只要注册一个解析器，
 * 就能接管某类生物群系的气候判定（例如「季节模组」按季节改写温度范围）。</p>
 */
public interface ClimateResolver {

    /** 解析器 id，用于日志与诊断。 */
    String id();

    /** 优先级，数字越小越先被询问。 */
    default int priority() {
        return 1000;
    }

    Optional<BiomeClimate> resolve(BiomeFacts facts);
}

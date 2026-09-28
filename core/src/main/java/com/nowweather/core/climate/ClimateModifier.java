package com.nowweather.core.climate;

/**
 * 气候修正器 —— 在「基础气候已经解析出来」之后再动手的扩展点。
 *
 * <h2>它和 {@link ClimateResolver} 有什么区别</h2>
 * <ul>
 *   <li>{@link ClimateResolver}：<b>参与解析</b>，只有在「显式配置 / 标签 / 分类都没命中」时才会被问到，
 *       属于兜底路径；</li>
 *   <li>{@link ClimateModifier}：<b>后处理</b>，无论基础气候从哪来（内置数据、数据包、配置、启发式），
 *       都会在最后被调用一次。</li>
 * </ul>
 *
 * <p>这就是「季节」这类需求的正确位置：不论某个群系的温度范围是内置数据给的还是玩家改的，
 * 冬天都应该整体下移 —— 用解析器做不到这一点，因为内置数据已经把解析链截断了。</p>
 *
 * <p>修正器可以叠加（按 {@link #priority()} 从小到大依次调用），返回 {@code null} 表示本次不干预。</p>
 */
public interface ClimateModifier {

    /** 唯一 id，用于日志与诊断。 */
    String id();

    /** 优先级：数字小的先执行。 */
    default int priority() {
        return 1000;
    }

    /**
     * 修正某个生物群系当前的气候。
     *
     * @param facts 该生物群系的原始事实（维度、基础温度……）
     * @param base  基础解析结果（可能是内置数据、玩家数据包或启发式推断）
     * @return 修正后的气候；返回 {@code null} 或原对象表示不干预
     */
    BiomeClimate modify(BiomeFacts facts, BiomeClimate base);

    /** 该修正器当前是否可用（例如依赖的模组没装就返回 false）。 */
    default boolean isActive() {
        return true;
    }
}

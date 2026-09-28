package com.nowweather.core.api;

/**
 * 平台天气读写桥 —— 加载器实现它，核心就能驱动 Minecraft 的天气。
 *
 * <p>把它做成接口而不是直接调用 {@code ServerLevel#setWeatherParameters}，
 * 是为了让同一套同步逻辑能落在 Forge / NeoForge / Fabric 以及未来的「扩展天气模组」上。</p>
 */
public interface PlatformWeatherAccess {

    /** 读取某个维度当前的天气。 */
    PlatformWeather currentWeather(String dimensionId);

    /** 当前降雨剩余时间（刻），负数表示未知。 */
    default long rainTimeRemaining(String dimensionId) {
        return -1L;
    }

    /** 当前晴天剩余时间（刻），负数表示未知。 */
    default long clearTimeRemaining(String dimensionId) {
        return -1L;
    }

    /**
     * 设置某维度天气。
     *
     * @param target            目标天气
     * @param durationTicks     该天气持续刻数
     * @param clearDurationTicks 切换回晴天前的刻数（原版在同一接口里要求）
     * @return 是否成功落地
     */
    boolean applyWeather(String dimensionId, PlatformWeather target, int durationTicks, int clearDurationTicks);

    /** 世界总时间（刻）。 */
    long totalWorldTime(String dimensionId);

    /**
     * 世界总「天数时间」（含天数，{@code ServerLevel#getDayTime()}）。
     *
     * <p>真实时间同步需要它来保持「世界第几天」连续；取不到时返回 -1。</p>
     */
    default long totalDayTime(String dimensionId) {
        return -1L;
    }

    /**
     * 写入世界总天数时间（{@code ServerLevel#setDayTime}）。
     *
     * @return 是否写入成功
     */
    default boolean setDayTime(String dimensionId, long dayTime) {
        return false;
    }

    /**
     * 写入<b>连续的</b>雨 / 雷强度（0~1），而不是「下 / 不下」的开关。
     *
     * <h2>为什么这是光影联动的唯一通道</h2>
     * Iris / OptiFine 给光影的天气类 uniform 只有三个
     * （见 Iris 官方文档「World and Weather Uniforms」）：
     * <pre>
     *   rainStrength     ← Level#getRainLevel(partialTick)
     *   wetness          ← 同一来源，过渡更慢（wetnessHalfLife）
     *   thunderStrength  ← Level#getThunderLevel(partialTick)
     * </pre>
     * 它们<b>全部来自原版世界的 rainLevel / thunderLevel</b>。Iris 的自定义 uniform 只能在
     * 光影包自己的 {@code shaders.properties} 里声明、由内置 uniform 组合而成，
     * <b>没有给第三方模组注入 uniform 的 API</b>。
     *
     * <p>所以模组能做的、也是真正有效的一件事：把原版那个「要么 0 要么 1」的值
     * 变成随真实天气连续变化的值 —— 光影的雨、云、雾、湿地反射就都跟着连续变化，
     * 毛毛雨和大暴雨在光影里不再是同一个画面。</p>
     *
     * <p><b>注意</b>：本方法只应写入<b>降水</b>强度。「云量」不能塞进这里 ——
     * 原版 {@code LevelRenderer} 只要 {@code getRainLevel > 0} 就会开启雨致变暗，
     * 把阴天的云量写进来等于凭空下雨。</p>
     *
     * @param rain    0~1，0 = 无降水
     * @param thunder 0~1，0 = 无雷
     * @return 是否写入成功
     */
    default boolean setWeatherIntensity(String dimensionId, float rain, float thunder) {
        return false;
    }

    /** 当天时刻 0~23999（0 = 日出/清晨 6:00 对应的刻）。 */
    long dayTime(String dimensionId);

    /** 世界种子。 */
    long worldSeed();

    /** 该维度是否被允许由 NowWeather 控制天气。 */
    default boolean isNowWeatherAllowed(String dimensionId) {
        return "minecraft:overworld".equals(dimensionId);
    }

    /** 读取某个坐标的生物群系 id。 */
    default String biomeIdAt(String dimensionId, double x, double y, double z) {
        return "";
    }
}

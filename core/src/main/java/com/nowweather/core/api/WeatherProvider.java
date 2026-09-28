package com.nowweather.core.api;

/**
 * 天气数据源扩展点 —— NowWeather 的「接口多」就体现在这里。
 *
 * <h2>怎么接入别的天气模组 / 服务</h2>
 * <ol>
 *   <li>实现本接口，返回一个 {@link ProviderResult}；</li>
 *   <li>通过 {@code NowWeatherApi.registerProvider(...)} 注册；</li>
 *   <li>用 {@link #priority()} 决定优先级（数字小的先被询问，UApiPro 默认为 100）。</li>
 * </ol>
 *
 * <p>{@link #fetch(ProviderContext)} 是<b>阻塞</b>调用，框架会在自己的 IO 线程上执行它，
 * 不需要也不应该在 Minecraft 主线程里直接调用。</p>
 */
public interface WeatherProvider {

    /** 唯一 id，建议形如 {@code uapipro} / {@code myweathermod}。 */
    String id();

    /** 展示名（HUD / 命令输出用）。 */
    String displayName();

    /**
     * 优先级：数字越小越优先。
     * 约定：0~49 本地模组，50~199 在线 API（UApiPro = 100），200+ 兜底数据源。
     */
    default int priority() {
        return 100;
    }

    /** 当前上下文下是否可用（比如需要 API Key、需要某个模组在场）。 */
    default boolean isEnabled(ProviderContext context) {
        return true;
    }

    /** 阻塞式查询。实现里应自行处理异常并返回合适的 {@link ProviderStatus}。 */
    ProviderResult fetch(ProviderContext context);

    /**
     * 最小查询间隔（毫秒）。框架据此做缓存，避免把免费 API 刷爆。
     */
    default long minIntervalMillis() {
        return 10L * 60L * 1000L;
    }

    /** 数据过期多久后必须重新拉取（毫秒）。 */
    default long stalenessMillis() {
        return 30L * 60L * 1000L;
    }

    /** 该 provider 是否自带位置信息（如按玩家 IP 定位），从而不需要服务器配置城市。 */
    default boolean providesLocation() {
        return false;
    }

    /** 该 provider 提供的是否为「预报」而非实时观测。 */
    default boolean isForecastProvider() {
        return false;
    }

    /** 该 provider 是否需要联网（用于离线模式判断）。 */
    default boolean requiresNetwork() {
        return true;
    }
}

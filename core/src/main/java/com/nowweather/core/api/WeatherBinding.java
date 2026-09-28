package com.nowweather.core.api;

import com.nowweather.core.weather.WeatherObservation;

/**
 * 出站联动扩展点：把 NowWeather 决定好的天气推给其它天气模组。
 *
 * <p>典型用法（未来规划，见 docs/ROADMAP.md）：</p>
 * <ul>
 *   <li>{@code projectatmosphere} —— 真实气压 / 云量 / 锋面；</li>
 *   <li>{@code sereneseasons} —— 季节与真实气温对齐；</li>
 *   <li>{@code weather2} / {@code dynamicweatheraa} —— 局部天气与风暴。</li>
 * </ul>
 *
 * <p>实现方只需要关心「怎么把观测推过去」，不需要知道数据从哪来。</p>
 */
public interface WeatherBinding {

    String id();

    /** 目标模组的 modId，框架用它判断是否需要初始化。 */
    String targetModId();

    /** 优先级：数字小的先执行。 */
    default int priority() {
        return 100;
    }

    /** 目标模组在场时才生效。 */
    default boolean isAvailable(ModPresence presence) {
        return presence != null && presence.isLoaded(targetModId());
    }

    /** 首次可用时调用一次（初始化 API、注册监听等）。 */
    default void onActivate(BindingContext context) {
    }

    /** NowWeather 决定并落地了新天气。 */
    void onWeatherChanged(BindingContext context, PlatformWeather applied, WeatherObservation observation);

    /** 每个服务端 tick（可选，用于平滑过渡）。 */
    default void onServerTick(BindingContext context, PlatformWeather applied) {
    }

    /** 目标模组卸载 / 服务器停止。 */
    default void onDeactivate(BindingContext context) {
    }
}

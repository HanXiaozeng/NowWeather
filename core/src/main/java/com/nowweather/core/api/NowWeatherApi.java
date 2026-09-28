package com.nowweather.core.api;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.ClimateResolver;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.core.sync.WeatherDecision;
import com.nowweather.core.weather.WeatherObservation;

import java.util.Optional;

/**
 * NowWeather 对外公开 API —— 其它模组只需要依赖这一个类。
 *
 * <h2>典型接入示例</h2>
 * <pre>{@code
 * // 1) 注册一个天气数据源（把别的天气模组的天气喂给 NowWeather）
 * NowWeatherApi.registerProvider(new MyWeatherProvider());
 *
 * // 2) 注册一个出站联动（让别的模组的云层跟着真实天气走）
 * NowWeatherApi.registerBinding(new MyAtmosphereBinding());
 *
 * // 3) 注册一个气候解析器（例如按季节改写生物群系的正常温度范围）
 * NowWeatherApi.registerClimateResolver(new SeasonalClimateResolver());
 *
 * // 4) 读取当前真实天气
 * NowWeatherApi.currentObservation().ifPresent(obs -> ...);
 * }</pre>
 *
 * <p>所有方法在 NowWeather 尚未初始化时都不会抛异常，而是安全地返回空 / false。</p>
 */
public final class NowWeatherApi {

    private static volatile NowWeatherRuntime runtime;

    private NowWeatherApi() {
    }

    /** 由加载器在模组初始化时调用。 */
    public static void install(NowWeatherRuntime newRuntime) {
        runtime = newRuntime;
    }

    public static void uninstall() {
        runtime = null;
    }

    public static boolean isAvailable() {
        return runtime != null;
    }

    public static Optional<NowWeatherRuntime> runtime() {
        return Optional.ofNullable(runtime);
    }

    // ------------------------------------------------------------------ 扩展点

    /**
     * 注册天气数据源。
     *
     * @return 是否注册成功（NowWeather 未安装时返回 false）
     */
    public static boolean registerProvider(WeatherProvider provider) {
        NowWeatherRuntime r = runtime;
        if (r == null || provider == null) {
            return false;
        }
        r.providers().register(provider);
        r.logger().info("已注册天气数据源: {}（{}，优先级 {}）",
                provider.id(), provider.displayName(), provider.priority());
        return true;
    }

    public static boolean unregisterProvider(String providerId) {
        NowWeatherRuntime r = runtime;
        return r != null && r.providers().unregister(providerId);
    }

    /** 注册出站联动。 */
    public static boolean registerBinding(WeatherBinding binding) {
        NowWeatherRuntime r = runtime;
        if (r == null || binding == null) {
            return false;
        }
        r.bindings().register(binding);
        r.logger().info("已注册天气联动: {} -> {}", binding.id(), binding.targetModId());
        return true;
    }

    public static boolean unregisterBinding(String bindingId) {
        NowWeatherRuntime r = runtime;
        return r != null && r.bindings().unregister(bindingId);
    }

    /** 注册气候解析器（比启发式优先，比显式配置低）。 */
    public static boolean registerClimateResolver(ClimateResolver resolver) {
        NowWeatherRuntime r = runtime;
        if (r == null || resolver == null) {
            return false;
        }
        r.climates().addResolver(resolver);
        return true;
    }

    /** 直接注册/覆盖某个生物群系的气候。 */
    public static boolean registerBiomeClimate(BiomeClimate climate) {
        NowWeatherRuntime r = runtime;
        if (r == null || climate == null) {
            return false;
        }
        r.climates().register(climate);
        return true;
    }

    // ------------------------------------------------------------------ 数据读取

    public static Optional<WeatherObservation> currentObservation() {
        NowWeatherRuntime r = runtime;
        return r == null ? Optional.empty() : Optional.ofNullable(r.controller().observation());
    }

    public static Optional<WeatherDecision> lastDecision() {
        NowWeatherRuntime r = runtime;
        return r == null ? Optional.empty() : Optional.ofNullable(r.controller().lastDecision());
    }

    public static Optional<BiomeClimate> climateOf(BiomeFacts facts) {
        NowWeatherRuntime r = runtime;
        return r == null || facts == null ? Optional.empty() : Optional.of(r.climates().resolve(facts));
    }

    public static Optional<BiomeClimateRegistry> climates() {
        NowWeatherRuntime r = runtime;
        return r == null ? Optional.empty() : Optional.of(r.climates());
    }

    public static Optional<BiomeClimate> climateOf(String biomeId) {
        NowWeatherRuntime r = runtime;
        return r == null ? Optional.empty() : r.climates().explicit(biomeId);
    }
}

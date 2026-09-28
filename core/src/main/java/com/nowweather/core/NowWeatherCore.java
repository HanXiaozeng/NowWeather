package com.nowweather.core;

/**
 * NowWeather 核心的全局常量。
 *
 * <p>本模块（{@code com.nowweather.core}）刻意不依赖任何 Minecraft 类，
 * 以便：1) 离线编译与自测；2) 被 Forge / NeoForge / Fabric 乃至独立工具复用。</p>
 */
public final class NowWeatherCore {

    /** 模组 id，与 mods.toml / fabric.mod.json 保持一致。 */
    public static final String MOD_ID = "nowweather";

    /**
     * 兜底版本号。正式版本号只看 {@code gradle.properties} 的 {@code mod_version}；
     * Forge 侧优先读 mods.toml 里的构建版本（{@code NowWeatherMod#displayVersion()}），
     * 这里仅在读不到时使用。命名规则：&lt;年份后两位&gt;b&lt;当年第几周&gt;&lt;本周第几次修改&gt;，b = build（构建）；如 26b39i。
     */
    public static final String VERSION = "26b39i";

    /** 一次「世界刻」对应的毫秒数（20 TPS 的理想值）。 */
    public static final long MILLIS_PER_TICK = 50L;

    /** Minecraft 一天的刻数。 */
    public static final long TICKS_PER_DAY = 24000L;

    /** UApiPro 天气接口默认地址。 */
    public static final String DEFAULT_WEATHER_ENDPOINT = "https://uapis.cn/api/v1/misc/weather";

    private NowWeatherCore() {
    }
}

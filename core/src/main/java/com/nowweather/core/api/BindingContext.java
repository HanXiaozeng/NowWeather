package com.nowweather.core.api;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.log.SyncLogger;

/**
 * 天气落地（联动其它模组）时的上下文。
 *
 * <p>「联动」有两个方向，都在这里体现：</p>
 * <ul>
 *   <li><b>入</b>：别的天气模组通过 {@link WeatherProvider} 把它的天气交给 NowWeather；</li>
 *   <li><b>出</b>：NowWeather 通过 {@link WeatherBinding} 把最终天气推给别的模组
 *       （例如让 Project Atmosphere 的云层/气压跟着真实天气走）。</li>
 * </ul>
 */
public final class BindingContext {

    private final String dimensionId;
    private final String biomeId;
    private final long totalWorldTime;
    private final long dayTime;
    private final ModPresence presence;
    private final JsonObject settings;
    private final SyncLogger logger;
    private final PlatformWeatherAccess platform;
    private final BiomeClimate climate;

    public BindingContext(String dimensionId, String biomeId, long totalWorldTime, long dayTime,
                          ModPresence presence, JsonObject settings, SyncLogger logger,
                          PlatformWeatherAccess platform, BiomeClimate climate) {
        this.dimensionId = dimensionId;
        this.biomeId = biomeId;
        this.totalWorldTime = totalWorldTime;
        this.dayTime = dayTime;
        this.presence = presence == null ? ModPresence.NONE : presence;
        this.settings = settings == null ? new JsonObject() : settings;
        this.logger = logger == null ? com.nowweather.core.log.Loggers.noop() : logger;
        this.platform = platform;
        this.climate = climate;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String biomeId() {
        return biomeId;
    }

    public long totalWorldTime() {
        return totalWorldTime;
    }

    public long dayTime() {
        return dayTime;
    }

    public ModPresence presence() {
        return presence;
    }

    public JsonObject settings() {
        return settings;
    }

    public SyncLogger logger() {
        return logger;
    }

    public PlatformWeatherAccess platform() {
        return platform;
    }

    /** 当前生物群系的气候画像，可能为 null（维度没有群系时）。 */
    public BiomeClimate climate() {
        return climate;
    }

    public BindingContext withBiome(String newBiomeId, BiomeClimate newClimate) {
        return new BindingContext(dimensionId, newBiomeId, totalWorldTime, dayTime, presence, settings,
                logger, platform, newClimate);
    }
}

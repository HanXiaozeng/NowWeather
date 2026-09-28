package com.nowweather.forge.config;

import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;

import java.util.List;

/**
 * Forge 配置 ↔ 核心配置的桥接。
 *
 * <p>核心（{@code com.nowweather.core}）不认识 {@code ForgeConfigSpec}，也不应该认识：
 * 它只吃一个普通的 {@link NowWeatherConfig}。这个类负责把 Forge 侧的值搬过去，
 * 于是「游戏内改配置 → 立即生效」只需要在 {@code ModConfigEvent.Reloading} 里重新调一次即可。</p>
 */
public final class ConfigBridge {

    private ConfigBridge() {
    }

    /**
     * 把当前 Forge 配置读成一个核心配置对象。
     *
     * <p>配置尚未加载完成时（例如构造期被调用）会退回到默认值，绝不抛异常 ——
     * 模组加载阶段最忌讳因为读配置直接崩溃。</p>
     */
    public static NowWeatherConfig toCoreConfig() {
        NowWeatherConfig config = NowWeatherConfig.defaults();
        try {
            var common = ForgeConfig.COMMON;
            config.setEnabled(common.enabled.get());

            config.setCity(common.city.get());
            config.setAdcode(common.adcode.get());
            config.setUseFirstPlayerLocation(common.useIpLocation.get());

            config.setProviderId(common.providerId.get());
            config.setApiKey(common.apiKey.get());
            config.setEndpoint(common.endpoint.get());
            config.setOpenMeteoEndpoint(common.openMeteoEndpoint.get());
            config.setOpenMeteoGeocoding(common.openMeteoGeocoding.get());
            // 坐标写成「纬度,经度」文本，解析失败就当没填
            var parsedCoordinates = com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder
                    .tryParseCoordinates(common.coordinates.get());
            if (parsedCoordinates != null && parsedCoordinates.isValid()) {
                config.setLatitude(parsedCoordinates.latitude());
                config.setLongitude(parsedCoordinates.longitude());
            }
            config.setRequestTimeoutMillis(common.requestTimeoutMillis.get());
            config.setRefreshIntervalMillis(common.refreshIntervalSeconds.get() * 1000L);
            config.setStaleAfterMillis(common.staleAfterSeconds.get() * 1000L);

            config.setRespectBiomeClimate(common.respectBiomeClimate.get());
            config.setRainIntensityThreshold(common.rainIntensityThreshold.get());
            config.setThunderIntensityThreshold(common.thunderIntensityThreshold.get());
            config.setMinWeatherDurationTicks(common.minWeatherDurationTicks.get());
            config.setMaxWeatherDurationTicks(common.maxWeatherDurationTicks.get());
            config.setClearDurationTicks(common.clearDurationTicks.get());
            config.setThunderRequiresPrecipitation(common.thunderRequiresPrecipitation.get());
            config.setLeaveNetherAndEndAlone(common.leaveNetherAndEndAlone.get());
        config.setTerrainTransfer(common.terrainTransfer.get());
        com.nowweather.forge.platform.ForgeTerrainSampler.metersPerBlock = common.metersPerBlock.get();
        config.setVanillaWeatherMaxMillis(common.vanillaWeatherMaxMinutes.get() * 60_000L);
            config.setSeasonIntegration(common.seasonIntegration.get());
            config.setSeasonAmplitude(common.seasonAmplitude.get());
            config.setTimeSyncEnabled(common.timeSyncEnabled.get());
            config.setTimezoneOffsetMinutes(common.timezoneOffsetMinutes.get());
            config.setTimeSyncIntervalTicks(common.timeSyncIntervalTicks.get());
            config.setMoonPhaseSync(common.moonPhaseSync.get());
            config.setShaderIntensitySync(common.shaderIntensitySync.get());
            config.setCaiyunToken(common.caiyunToken.get());
            config.setAnnounceLocationChange(common.announceLocationChange.get());
            config.setOfflineFallback(common.offineFallback.get());
            config.setOfflineDefaultWeather(common.offlineDefaultWeather.get());

            config.setForecastHorizon(common.forecastHorizon.get());
            config.setForecastTicksPerEntry(common.forecastTicksPerEntry.get());
            config.setTemperatureToleranceC(common.temperatureToleranceC.get());
            config.setAllowExtremeEvents(common.allowExtremeEvents.get());
            // ★ 天色预报开关：以前 Forge 侧根本没有这一项，core 的 skyForecastEnabled() 永远是默认 true，
            //   于是 /nowweather sky 提示玩家去开的那个开关无处可开、天色预报也关不掉。现在接上了。
            config.setSkyForecastEnabled(common.skyForecastEnabled.get());
            warnIfCloudRainScaleDrifts(common.cloudRainScale.get());

            config.setSyncToClients(common.syncToClients.get());
            config.setDimensions(asStringList(common.dimensions.get()));
            config.setFixedBiomeId(common.fixedBiomeId.get());

            config.setLogProviderDiagnostics(common.logProviderDiagnostics.get());
            config.setAnnounceWeatherChanges(common.announceWeatherChanges.get());

            var client = ForgeConfig.CLIENT;
            config.setHudEnabled(client.hudEnabled.get());
        } catch (IllegalStateException | NullPointerException e) {
            // 配置尚未加载：用默认值，等 ModConfigEvent.Loading 之后再同步一次
            return NowWeatherConfig.defaults();
        }
        return config;
    }

    /** 云量区间上界偏离光影适配契约值（0.60）时，是否已经提醒过（保证每条进程只打一次）。 */
    private static boolean cloudRainScaleWarned;

    /**
     * 提醒「云量区间上界被改过」—— 装了适配光影包的话云量会失真。
     *
     * <p>{@code shaders.cloudRainScale} 的上界同时是光影侧写死的契约值
     * （{@code Lib/IndividualFounctions/NowWeather.glsl} 里的 {@code NW_CLOUD_BAND} = 0.60）。
     * 两边一旦不一致，阴天要么永远到不了满云、要么云量曲线被压缩，而界面上看不出任何异常，
     * 所以这里显式警告一次。</p>
     *
     * <p><b>只打一次</b>：本方法会被配置热重载反复调用（每次改配置都会重新桥接），
     * 不加这个标志就会刷屏。日志面向开发者，恒为中文，不翻译（见 {@code Text} 的类注释）。</p>
     */
    private static void warnIfCloudRainScaleDrifts(double scale) {
        if (cloudRainScaleWarned || Math.abs(scale - 0.60D) < 1.0E-9D) {
            return;
        }
        cloudRainScaleWarned = true;
        com.nowweather.forge.NowWeatherLog.LOGGER.warn(
                "shaders.cloudRainScale = {} 已偏离光影适配契约值 0.60："
                        + "适配过的光影包把 NW_CLOUD_BAND 写死在 Lib/IndividualFounctions/NowWeather.glsl 里，"
                        + "两边对不上时云量会失真（调小则阴天到不了满云，调大则云量曲线被压缩）。"
                        + "除非同步改了光影包的那个宏，否则请改回 0.60。", scale);
    }

    /** 给「出站联动」用的附加设置。 */
    public static JsonObject bindingSettings() {
        JsonObject json = new JsonObject();
        try {
            json.put("yieldToOtherWeatherMods", ForgeConfig.COMMON.yieldToOtherWeatherMods.get());
            JsonArray mods = new JsonArray();
            ForgeConfig.COMMON.yieldToModIds.get().forEach(m -> mods.add(String.valueOf(m)));
            json.put("yieldToModIds", mods);
        } catch (IllegalStateException | NullPointerException e) {
            json.put("yieldToOtherWeatherMods", true);
            json.put("yieldToModIds", new JsonArray());
        }
        return json;
    }

    /** LLM 配置（供命令层构造客户端）。 */
    public static com.nowweather.core.llm.LlmClient.Options llmOptions() {
        ForgeConfig.Common c = ForgeConfig.COMMON;
        return new com.nowweather.core.llm.LlmClient.Options(
                c.llmEndpoint.get(), c.llmApiKey.get(), c.llmModel.get(), 0.2D,
                c.llmMaxTokens.get(), c.llmThinking.get(), 30_000L);
    }

    /**
     * 当前配置快照（供 AI 阅读）：逐项 key = value，**密钥只留前 4 位**。
     *
     * <p>这是「让 AI 读取本地配置」的入口 —— 不带这个上下文，模型只能凭空猜，
     * 连「我现在刷新间隔多少」都答不了。</p>
     */
    public static String llmConfigSnapshot() {
        StringBuilder sb = new StringBuilder();
        for (ConfigEntries.Entry entry : ConfigEntries.all()) {
            if (entry.isClient()) {
                continue;
            }
            String value = entry.display();
            if (entry.key().toLowerCase(java.util.Locale.ROOT).contains("apikey")) {
                value = value.isBlank() ? "(未设置)"
                        : value.substring(0, Math.min(4, value.length())) + "***（已打码）";
            }
            sb.append(entry.key()).append(" = ").append(value).append('\n');
        }
        return sb.toString();
    }

    public static boolean llmEnabled() {
        return ForgeConfig.COMMON.llmEnabled.get();
    }

    public static boolean llmWeatherDaily() {
        return ForgeConfig.COMMON.llmWeatherDaily.get();
    }

    public static boolean llmAllowConfigEdits() {
        return ForgeConfig.COMMON.llmAllowConfigEdits.get();
    }

    /** 需要「让出天气控制权」的模组 id：配置为空时用内置列表。 */
    public static List<String> yieldToModIds() {
        JsonObject settings = bindingSettings();
        JsonArray array = settings.optArray("yieldToModIds");
        if (array != null && !array.isEmpty()) {
            return array.asStringList();
        }
        return List.of();
    }

    private static List<String> asStringList(List<? extends String> raw) {
        if (raw == null) {
            return List.of("minecraft:overworld");
        }
        return raw.stream().map(String::valueOf).toList();
    }
}

package com.nowweather.core.config;

import com.nowweather.core.NowWeatherCore;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * NowWeather 的运行时配置（POJO）。
 *
 * <p>核心不依赖任何加载器的配置系统：Forge 层负责把 {@code ForgeConfigSpec} 或
 * {@code config/NowWeather/config.json} 读进来，填到这个对象里。</p>
 */
public final class NowWeatherConfig {

    // ---- 总开关 ----
    private boolean enabled = true;

    /**
     * 是否真的把天气写进游戏。
     *
     * <p>关掉之后 NowWeather 进入「观察模式」：依然拉取真实天气、依然生成预报、
     * 依然同步给客户端，但<b>不会修改游戏天气</b>。用于「检测到别的天气模组在管天气，我们让权」的场景。</p>
     */
    private boolean applyWeather = true;

    // ---- 位置 ----
    private String city = "";
    private String adcode = "";
    private boolean useFirstPlayerLocation = true;

    // ---- 数据源偏好 ----
    /**
     * 指定「优先尝试」的数据源 id；<b>留空表示按注册表里的优先级顺序走</b>
     * （数字小者先试：彩云 80 → Open-Meteo 90 → UApiPro 100）。
     *
     * <p>核心把默认值设成 {@code "uapipro"} 只是为了让<b>核心自测</b>在没有网络、没有
     * Forge 配置层的情况下有一个确定的、可断言的来源；<b>真正的发布形态由 Forge 层决定</b>：
     * {@code ConfigBridge} 会把这里设成 {@code ForgeConfig} 里的 {@code api.providerId}
     * （默认空串），于是线上走的是注册表优先级 —— 也就是彩云优先。
     * 所以这个默认值不会让发布版变成「UApiPro 优先」。</p>
     */
    private String providerId = "uapipro";
    private String apiKey = "";
    private String endpoint = NowWeatherCore.DEFAULT_WEATHER_ENDPOINT;
    private long requestTimeoutMillis = 10_000L;
    /** 刷新间隔：默认 20 分钟 —— Open-Meteo 上游数据本身每 15 分钟更新一次，刷太快只是浪费配额。 */
    private long refreshIntervalMillis = 20L * 60L * 1000L;
    private long staleAfterMillis = 30L * 60L * 1000L;

    // ---- 坐标（Open-Meteo 等按经纬度查询的数据源用；留空则用城市名地理编码）----
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private String openMeteoEndpoint = "https://api.open-meteo.com/v1/forecast";
    private boolean openMeteoGeocoding = true;

    // ---- 离线兜底 ----
    private boolean offlineFallback = true;
    private String offlineDefaultWeather = "AUTO";

    // ---- 天气映射策略 ----
    private boolean respectBiomeClimate = true;
    /** 是否做地形订正（把真实天气按物理关系搬到玩家所在地形）。 */
    private boolean terrainTransfer = true;
    /**
     * 原版 /weather 指令的效果最多持续多少毫秒。
     *
     * <p>原版指令仍然可用（不去禁用玩家熟悉的命令），但它写进世界的天气只被允许保留这么久；
     * 到期后模组会重新接管。默认 20 分钟。</p>
     */
    private long vanillaWeatherMaxMillis = 20L * 60L * 1000L;
    private double rainIntensityThreshold = 0.22D;
    private double thunderIntensityThreshold = 0.55D;
    private int minWeatherDurationTicks = 3_000;
    private int maxWeatherDurationTicks = 18_000;
    private int clearDurationTicks = 6_000;
    private boolean thunderRequiresPrecipitation = true;
    /**
     * 下界 / 末地 / 虚空是否完全保持原版：开启时天气决策对这些群系直接返回「不改动」。
     *
     * <p>默认开启。原版这些维度本来就没有天气系统，动它们只会让别的模组/玩家困惑。</p>
     */
    private boolean leaveNetherAndEndAlone = true;
    /** 是否与季节模组（Serene Seasons）联动：按季节平移各群系的正常温度范围。 */
    private boolean seasonIntegration = true;
    /** 季节幅度倍率（1.0 = 默认：冬 -14°C ~ 夏 +8°C；0 = 只收窄不平移）。 */
    private double seasonAmplitude = 1.0D;

    // ---- 真实时间同步 ----
    /** 是否让游戏内时间与所在地的真实时间一致（一天 = 真实 24 小时）。 */
    private boolean timeSyncEnabled = true;
    /** 时区偏移（分钟）；{@code Integer.MIN_VALUE} = 不手动指定，按「天气接口 → 系统时区」解析。 */
    private int timezoneOffsetMinutes = Integer.MIN_VALUE;
    /** 每多少刻把时间校正一次（已废弃：服务端现在每刻都写）。 */
    private int timeSyncIntervalTicks = 20;
    /** 是否让「世界天数 % 8」对齐真实月相（MC 的月相就是天数 % 8，0 = 满月）。 */
    private boolean moonPhaseSync = true;
    /**
     * 是否把「连续的」雨/雷强度写进世界，供光影（Iris / OptiFine）读取。
     *
     * <p>原版只有 0/1，导致毛毛雨与暴雨在光影里是同一个画面；开启后会按
     * {@code WeatherType.precipitation()} 连续变化。</p>
     */
    private boolean shaderIntensitySync = true;
    /** 是否取「真实逐小时天色预报」（云量 / 能见度 / 降水概率）来驱动天色。 */
    private boolean skyForecastEnabled = true;
    /** 玩家跨城/跨省/跨国时是否在聊天框播报（同城只静默更新坐标）。 */
    private boolean announceLocationChange = true;
    /** 彩云天气接口基址（token 走 [api] apiKey）。 */
    private String caiyunEndpoint = "https://api.caiyunapp.com/v2.6";
    /** 彩云天气 token；留空时退回用 [api] apiKey。 */
    private String caiyunToken = "";

    // ---- 预报 ----
    private int forecastHorizon = 12;
    private long forecastTicksPerEntry = 1_000L;
    private double temperatureToleranceC = 6.0D;
    private boolean allowExtremeEvents = true;

    // ---- 同步与客户端 ----
    private boolean syncToClients = true;
    private boolean hudEnabled = true;
    private boolean clientForecastEnabled = true;

    // ---- 作用范围 ----
    private List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
    private String fixedBiomeId = "";

    // ---- 诊断 ----
    private boolean logProviderDiagnostics = false;
    private boolean announceWeatherChanges = false;

    public NowWeatherConfig() {
    }

    public static NowWeatherConfig defaults() {
        return new NowWeatherConfig();
    }

    // ------------------------------------------------------------------ 访问器

    public boolean enabled() {
        return enabled;
    }

    public NowWeatherConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public boolean applyWeather() {
        return applyWeather;
    }

    public NowWeatherConfig setApplyWeather(boolean applyWeather) {
        this.applyWeather = applyWeather;
        return this;
    }

    public String city() {
        return city;
    }

    public NowWeatherConfig setCity(String city) {
        this.city = city == null ? "" : city.trim();
        return this;
    }

    public String adcode() {
        return adcode;
    }

    public NowWeatherConfig setAdcode(String adcode) {
        this.adcode = adcode == null ? "" : adcode.trim();
        return this;
    }

    public boolean useFirstPlayerLocation() {
        return useFirstPlayerLocation;
    }

    public NowWeatherConfig setUseFirstPlayerLocation(boolean value) {
        this.useFirstPlayerLocation = value;
        return this;
    }

    public String providerId() {
        return providerId;
    }

    public NowWeatherConfig setProviderId(String providerId) {
        this.providerId = providerId == null ? "" : providerId.trim();
        return this;
    }

    public String apiKey() {
        return apiKey;
    }

    public NowWeatherConfig setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        return this;
    }

    public String endpoint() {
        return endpoint;
    }

    public double latitude() {
        return latitude;
    }

    public NowWeatherConfig setLatitude(double latitude) {
        this.latitude = latitude;
        return this;
    }

    public double longitude() {
        return longitude;
    }

    public NowWeatherConfig setLongitude(double longitude) {
        this.longitude = longitude;
        return this;
    }

    /** 是否配置了可用坐标。 */
    public boolean hasCoordinates() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    public String openMeteoEndpoint() {
        return openMeteoEndpoint;
    }

    public NowWeatherConfig setOpenMeteoEndpoint(String endpoint) {
        this.openMeteoEndpoint = endpoint == null || endpoint.isBlank()
                ? "https://api.open-meteo.com/v1/forecast" : endpoint.trim();
        return this;
    }

    public boolean openMeteoGeocoding() {
        return openMeteoGeocoding;
    }

    public NowWeatherConfig setOpenMeteoGeocoding(boolean enabled) {
        this.openMeteoGeocoding = enabled;
        return this;
    }

    public NowWeatherConfig setEndpoint(String endpoint) {
        this.endpoint = endpoint == null || endpoint.isBlank()
                ? NowWeatherCore.DEFAULT_WEATHER_ENDPOINT : endpoint.trim();
        return this;
    }

    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public NowWeatherConfig setRequestTimeoutMillis(long value) {
        this.requestTimeoutMillis = MathUtil.clamp(value, 1_000L, 120_000L);
        return this;
    }

    public long refreshIntervalMillis() {
        return refreshIntervalMillis;
    }

    public NowWeatherConfig setRefreshIntervalMillis(long value) {
        this.refreshIntervalMillis = MathUtil.clamp(value, 30_000L, 24L * 60L * 60L * 1000L);
        return this;
    }

    public long staleAfterMillis() {
        return staleAfterMillis;
    }

    public NowWeatherConfig setStaleAfterMillis(long value) {
        this.staleAfterMillis = MathUtil.clamp(value, 60_000L, 7L * 24L * 60L * 60L * 1000L);
        return this;
    }

    public boolean offlineFallback() {
        return offlineFallback;
    }

    public NowWeatherConfig setOfflineFallback(boolean value) {
        this.offlineFallback = value;
        return this;
    }

    public String offlineDefaultWeather() {
        return offlineDefaultWeather;
    }

    public NowWeatherConfig setOfflineDefaultWeather(String value) {
        this.offlineDefaultWeather = value == null ? "AUTO" : value.trim();
        return this;
    }

    public boolean terrainTransfer() {
        return terrainTransfer;
    }

    public long vanillaWeatherMaxMillis() {
        return vanillaWeatherMaxMillis;
    }

    public NowWeatherConfig setVanillaWeatherMaxMillis(long millis) {
        this.vanillaWeatherMaxMillis = millis;
        return this;
    }

    public NowWeatherConfig setTerrainTransfer(boolean value) {
        this.terrainTransfer = value;
        return this;
    }

    public boolean respectBiomeClimate() {
        return respectBiomeClimate;
    }

    public NowWeatherConfig setRespectBiomeClimate(boolean value) {
        this.respectBiomeClimate = value;
        return this;
    }

    public double rainIntensityThreshold() {
        return rainIntensityThreshold;
    }

    public NowWeatherConfig setRainIntensityThreshold(double value) {
        this.rainIntensityThreshold = MathUtil.clamp(value, 0.0D, 1.0D);
        return this;
    }

    public double thunderIntensityThreshold() {
        return thunderIntensityThreshold;
    }

    public NowWeatherConfig setThunderIntensityThreshold(double value) {
        this.thunderIntensityThreshold = MathUtil.clamp(value, 0.0D, 1.0D);
        return this;
    }

    public int minWeatherDurationTicks() {
        return minWeatherDurationTicks;
    }

    public NowWeatherConfig setMinWeatherDurationTicks(int value) {
        this.minWeatherDurationTicks = MathUtil.clamp(value, 100, 240_000);
        return this;
    }

    public int maxWeatherDurationTicks() {
        return maxWeatherDurationTicks;
    }

    public NowWeatherConfig setMaxWeatherDurationTicks(int value) {
        this.maxWeatherDurationTicks = MathUtil.clamp(value, this.minWeatherDurationTicks, 240_000);
        return this;
    }

    public int clearDurationTicks() {
        return clearDurationTicks;
    }

    public NowWeatherConfig setClearDurationTicks(int value) {
        this.clearDurationTicks = MathUtil.clamp(value, 100, 240_000);
        return this;
    }

    public boolean thunderRequiresPrecipitation() {
        return thunderRequiresPrecipitation;
    }

    public boolean leaveNetherAndEndAlone() {
        return leaveNetherAndEndAlone;
    }

    public NowWeatherConfig setLeaveNetherAndEndAlone(boolean leave) {
        this.leaveNetherAndEndAlone = leave;
        return this;
    }

    public boolean seasonIntegration() {
        return seasonIntegration;
    }

    public NowWeatherConfig setSeasonIntegration(boolean enabled) {
        this.seasonIntegration = enabled;
        return this;
    }

    public boolean timeSyncEnabled() {
        return timeSyncEnabled;
    }

    public NowWeatherConfig setTimeSyncEnabled(boolean enabled) {
        this.timeSyncEnabled = enabled;
        return this;
    }

    public int timezoneOffsetMinutes() {
        return timezoneOffsetMinutes;
    }

    public NowWeatherConfig setTimezoneOffsetMinutes(int minutes) {
        this.timezoneOffsetMinutes = minutes;
        return this;
    }

    public int timeSyncIntervalTicks() {
        return timeSyncIntervalTicks;
    }

    public NowWeatherConfig setTimeSyncIntervalTicks(int ticks) {
        this.timeSyncIntervalTicks = MathUtil.clamp(ticks, 1, 24000);
        return this;
    }

    /**
     * 是否让月相跟随真实历法。
     *
     * <p>Minecraft 的月相 = 世界天数 % 8（0 = 满月、4 = 新月），
     * 而天数偏移只保证天数连续；开启后会把偏移平移 0~7 天，让中秋节真的是满月。</p>
     */
    public boolean moonPhaseSync() {
        return moonPhaseSync;
    }

    public NowWeatherConfig setMoonPhaseSync(boolean enabled) {
        this.moonPhaseSync = enabled;
        return this;
    }

    /** 是否把连续的雨/雷强度写进世界（光影联动的总开关）。 */
    public boolean shaderIntensitySync() {
        return shaderIntensitySync;
    }

    public NowWeatherConfig setShaderIntensitySync(boolean enabled) {
        this.shaderIntensitySync = enabled;
        return this;
    }

    /** 是否取真实天色预报来驱动天色。 */
    public boolean skyForecastEnabled() {
        return skyForecastEnabled;
    }

    public NowWeatherConfig setSkyForecastEnabled(boolean enabled) {
        this.skyForecastEnabled = enabled;
        return this;
    }

    /** 跨越行政区划时是否播报。 */
    public boolean announceLocationChange() {
        return announceLocationChange;
    }

    public NowWeatherConfig setAnnounceLocationChange(boolean enabled) {
        this.announceLocationChange = enabled;
        return this;
    }

    /** 彩云天气 token（留空则用 apiKey）。 */
    public String caiyunToken() {
        return caiyunToken;
    }

    public NowWeatherConfig setCaiyunToken(String token) {
        this.caiyunToken = token == null ? "" : token.trim();
        return this;
    }

    /** 彩云天气接口基址。 */
    public String caiyunEndpoint() {
        return caiyunEndpoint;
    }

    public NowWeatherConfig setCaiyunEndpoint(String endpoint) {
        this.caiyunEndpoint = endpoint == null || endpoint.isBlank()
                ? "https://api.caiyunapp.com/v2.6" : endpoint;
        return this;
    }

    public double seasonAmplitude() {
        return seasonAmplitude;
    }

    public NowWeatherConfig setSeasonAmplitude(double amplitude) {
        this.seasonAmplitude = MathUtil.clamp(amplitude, 0.0D, 3.0D);
        return this;
    }

    public NowWeatherConfig setThunderRequiresPrecipitation(boolean value) {
        this.thunderRequiresPrecipitation = value;
        return this;
    }

    public int forecastHorizon() {
        return forecastHorizon;
    }

    public NowWeatherConfig setForecastHorizon(int value) {
        this.forecastHorizon = MathUtil.clamp(value, 1, 96);
        return this;
    }

    public long forecastTicksPerEntry() {
        return forecastTicksPerEntry;
    }

    public NowWeatherConfig setForecastTicksPerEntry(long value) {
        this.forecastTicksPerEntry = MathUtil.clamp(value, 100L, 24_000L);
        return this;
    }

    public double temperatureToleranceC() {
        return temperatureToleranceC;
    }

    public NowWeatherConfig setTemperatureToleranceC(double value) {
        this.temperatureToleranceC = MathUtil.clamp(value, 0.0D, 30.0D);
        return this;
    }

    public boolean allowExtremeEvents() {
        return allowExtremeEvents;
    }

    public NowWeatherConfig setAllowExtremeEvents(boolean value) {
        this.allowExtremeEvents = value;
        return this;
    }

    public boolean syncToClients() {
        return syncToClients;
    }

    public NowWeatherConfig setSyncToClients(boolean value) {
        this.syncToClients = value;
        return this;
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public NowWeatherConfig setHudEnabled(boolean value) {
        this.hudEnabled = value;
        return this;
    }

    public boolean clientForecastEnabled() {
        return clientForecastEnabled;
    }

    public NowWeatherConfig setClientForecastEnabled(boolean value) {
        this.clientForecastEnabled = value;
        return this;
    }

    public List<String> dimensions() {
        return List.copyOf(dimensions);
    }

    public NowWeatherConfig setDimensions(List<String> dimensions) {
        this.dimensions = dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions);
        return this;
    }

    public boolean controlsDimension(String dimensionId) {
        if (dimensionId == null) {
            return false;
        }
        return dimensions.stream().anyMatch(d -> d.equalsIgnoreCase(dimensionId));
    }

    public String fixedBiomeId() {
        return fixedBiomeId;
    }

    public NowWeatherConfig setFixedBiomeId(String value) {
        this.fixedBiomeId = value == null ? "" : value.trim();
        return this;
    }

    public boolean logProviderDiagnostics() {
        return logProviderDiagnostics;
    }

    public NowWeatherConfig setLogProviderDiagnostics(boolean value) {
        this.logProviderDiagnostics = value;
        return this;
    }

    public boolean announceWeatherChanges() {
        return announceWeatherChanges;
    }

    public NowWeatherConfig setAnnounceWeatherChanges(boolean value) {
        this.announceWeatherChanges = value;
        return this;
    }

    // ------------------------------------------------------------------ 序列化

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("enabled", enabled);
        json.put("applyWeather", applyWeather);
        json.put("city", city);
        json.put("adcode", adcode);
        json.put("useFirstPlayerLocation", useFirstPlayerLocation);

        JsonObject provider = new JsonObject();
        provider.put("id", providerId);
        provider.put("apiKey", apiKey);
        provider.put("endpoint", endpoint);
        provider.put("openMeteoEndpoint", openMeteoEndpoint);
        provider.put("openMeteoGeocoding", openMeteoGeocoding);
        if (!Double.isNaN(latitude)) {
            provider.put("latitude", latitude);
            provider.put("longitude", longitude);
        }
        provider.put("requestTimeoutMillis", requestTimeoutMillis);
        provider.put("refreshIntervalMillis", refreshIntervalMillis);
        provider.put("staleAfterMillis", staleAfterMillis);
        json.put("provider", provider);

        JsonObject mapping = new JsonObject();
        mapping.put("respectBiomeClimate", respectBiomeClimate);
        mapping.put("rainIntensityThreshold", rainIntensityThreshold);
        mapping.put("thunderIntensityThreshold", thunderIntensityThreshold);
        mapping.put("minWeatherDurationTicks", minWeatherDurationTicks);
        mapping.put("maxWeatherDurationTicks", maxWeatherDurationTicks);
        mapping.put("clearDurationTicks", clearDurationTicks);
        mapping.put("thunderRequiresPrecipitation", thunderRequiresPrecipitation);
        mapping.put("leaveNetherAndEndAlone", leaveNetherAndEndAlone);
        mapping.put("seasonIntegration", seasonIntegration);
        mapping.put("seasonAmplitude", seasonAmplitude);
        mapping.put("timeSyncEnabled", timeSyncEnabled);
        mapping.put("timezoneOffsetMinutes", timezoneOffsetMinutes);
        mapping.put("timeSyncIntervalTicks", timeSyncIntervalTicks);
        mapping.put("moonPhaseSync", moonPhaseSync);
        mapping.put("shaderIntensitySync", shaderIntensitySync);
        mapping.put("skyForecastEnabled", skyForecastEnabled);
        mapping.put("announceLocationChange", announceLocationChange);
        mapping.put("caiyunEndpoint", caiyunEndpoint);
        mapping.put("caiyunToken", caiyunToken);
        mapping.put("offlineFallback", offlineFallback);
        mapping.put("offlineDefaultWeather", offlineDefaultWeather);
        json.put("mapping", mapping);

        JsonObject forecast = new JsonObject();
        forecast.put("horizon", forecastHorizon);
        forecast.put("ticksPerEntry", forecastTicksPerEntry);
        forecast.put("temperatureToleranceC", temperatureToleranceC);
        forecast.put("allowExtremeEvents", allowExtremeEvents);
        json.put("forecast", forecast);

        JsonObject client = new JsonObject();
        client.put("syncToClients", syncToClients);
        client.put("hudEnabled", hudEnabled);
        client.put("clientForecastEnabled", clientForecastEnabled);
        json.put("client", client);

        JsonArray dims = new JsonArray();
        dimensions.forEach(dims::add);
        json.put("dimensions", dims);
        if (!fixedBiomeId.isEmpty()) {
            json.put("fixedBiomeId", fixedBiomeId);
        }

        JsonObject debug = new JsonObject();
        debug.put("logProviderDiagnostics", logProviderDiagnostics);
        debug.put("announceWeatherChanges", announceWeatherChanges);
        json.put("debug", debug);
        return json;
    }

    /** 从 JSON 读入（缺字段保持默认值，保证向后兼容）。 */
    public static NowWeatherConfig fromJson(JsonObject json) {
        NowWeatherConfig c = new NowWeatherConfig();
        if (json == null) {
            return c;
        }
        c.setEnabled(json.optBoolean("enabled", c.enabled));
        c.setApplyWeather(json.optBoolean("applyWeather", c.applyWeather));
        c.setCity(json.optString("city", c.city));
        c.setAdcode(json.optString("adcode", c.adcode));
        c.setUseFirstPlayerLocation(json.optBoolean("useFirstPlayerLocation", c.useFirstPlayerLocation));

        JsonObject provider = json.optObject("provider");
        if (provider != null) {
            c.setProviderId(provider.optString("id", c.providerId));
            c.setApiKey(provider.optString("apiKey", c.apiKey));
            c.setEndpoint(provider.optString("endpoint", c.endpoint));
            c.setOpenMeteoEndpoint(provider.optString("openMeteoEndpoint", c.openMeteoEndpoint));
            c.setOpenMeteoGeocoding(provider.optBoolean("openMeteoGeocoding", c.openMeteoGeocoding));
            if (provider.hasNonNull("latitude")) {
                c.setLatitude(provider.optDouble("latitude", Double.NaN));
                c.setLongitude(provider.optDouble("longitude", Double.NaN));
            }
            c.setRequestTimeoutMillis(provider.optLong("requestTimeoutMillis", c.requestTimeoutMillis));
            c.setRefreshIntervalMillis(provider.optLong("refreshIntervalMillis", c.refreshIntervalMillis));
            c.setStaleAfterMillis(provider.optLong("staleAfterMillis", c.staleAfterMillis));
        }

        JsonObject mapping = json.optObject("mapping");
        if (mapping != null) {
            c.setRespectBiomeClimate(mapping.optBoolean("respectBiomeClimate", c.respectBiomeClimate));
            c.setRainIntensityThreshold(mapping.optDouble("rainIntensityThreshold", c.rainIntensityThreshold));
            c.setThunderIntensityThreshold(mapping.optDouble("thunderIntensityThreshold", c.thunderIntensityThreshold));
            c.setMinWeatherDurationTicks(mapping.optInt("minWeatherDurationTicks", c.minWeatherDurationTicks));
            c.setMaxWeatherDurationTicks(mapping.optInt("maxWeatherDurationTicks", c.maxWeatherDurationTicks));
            c.setClearDurationTicks(mapping.optInt("clearDurationTicks", c.clearDurationTicks));
            c.setThunderRequiresPrecipitation(mapping.optBoolean("thunderRequiresPrecipitation",
                    c.thunderRequiresPrecipitation));
            c.setLeaveNetherAndEndAlone(mapping.optBoolean("leaveNetherAndEndAlone", c.leaveNetherAndEndAlone));
            c.setSeasonIntegration(mapping.optBoolean("seasonIntegration", c.seasonIntegration));
            c.setSeasonAmplitude(mapping.optDouble("seasonAmplitude", c.seasonAmplitude));
            c.setTimeSyncEnabled(mapping.optBoolean("timeSyncEnabled", c.timeSyncEnabled));
            c.setTimezoneOffsetMinutes(mapping.optInt("timezoneOffsetMinutes", c.timezoneOffsetMinutes));
            c.setTimeSyncIntervalTicks(mapping.optInt("timeSyncIntervalTicks", c.timeSyncIntervalTicks));
            c.setMoonPhaseSync(mapping.optBoolean("moonPhaseSync", c.moonPhaseSync));
            c.setShaderIntensitySync(mapping.optBoolean("shaderIntensitySync", c.shaderIntensitySync));
            c.setSkyForecastEnabled(mapping.optBoolean("skyForecastEnabled", c.skyForecastEnabled));
            c.setAnnounceLocationChange(mapping.optBoolean("announceLocationChange", c.announceLocationChange));
            c.setCaiyunEndpoint(mapping.optString("caiyunEndpoint", c.caiyunEndpoint));
            c.setCaiyunToken(mapping.optString("caiyunToken", c.caiyunToken));
            c.setOfflineFallback(mapping.optBoolean("offlineFallback", c.offlineFallback));
            c.setOfflineDefaultWeather(mapping.optString("offlineDefaultWeather", c.offlineDefaultWeather));
        }

        JsonObject forecast = json.optObject("forecast");
        if (forecast != null) {
            c.setForecastHorizon(forecast.optInt("horizon", c.forecastHorizon));
            c.setForecastTicksPerEntry(forecast.optLong("ticksPerEntry", c.forecastTicksPerEntry));
            c.setTemperatureToleranceC(forecast.optDouble("temperatureToleranceC", c.temperatureToleranceC));
            c.setAllowExtremeEvents(forecast.optBoolean("allowExtremeEvents", c.allowExtremeEvents));
        }

        JsonObject client = json.optObject("client");
        if (client != null) {
            c.setSyncToClients(client.optBoolean("syncToClients", c.syncToClients));
            c.setHudEnabled(client.optBoolean("hudEnabled", c.hudEnabled));
            c.setClientForecastEnabled(client.optBoolean("clientForecastEnabled", c.clientForecastEnabled));
        }

        JsonArray dims = json.optArray("dimensions");
        if (dims != null && !dims.isEmpty()) {
            c.setDimensions(dims.asStringList());
        }
        c.setFixedBiomeId(json.optString("fixedBiomeId", c.fixedBiomeId));

        JsonObject debug = json.optObject("debug");
        if (debug != null) {
            c.setLogProviderDiagnostics(debug.optBoolean("logProviderDiagnostics", c.logProviderDiagnostics));
            c.setAnnounceWeatherChanges(debug.optBoolean("announceWeatherChanges", c.announceWeatherChanges));
        }
        return c;
    }

    @Override
    public String toString() {
        return "NowWeatherConfig[enabled=" + enabled + ", city='" + city + "', adcode='" + adcode
                + "', provider=" + providerId + ", refresh=" + refreshIntervalMillis + "ms]";
    }

    /**
     * 把另一份配置的值整体拷进本对象。
     *
     * <p>用于「游戏内改配置后热重载」：控制器、策略、预报引擎都持有同一个实例，
     * 原地替换字段值就能让改动立刻生效，不需要重建整个运行时。</p>
     */
    public NowWeatherConfig copyFrom(NowWeatherConfig other) {
        if (other == null) {
            return this;
        }
        this.enabled = other.enabled;
        this.applyWeather = other.applyWeather;
        this.city = other.city;
        this.adcode = other.adcode;
        this.useFirstPlayerLocation = other.useFirstPlayerLocation;
        this.providerId = other.providerId;
        this.apiKey = other.apiKey;
        this.endpoint = other.endpoint;
        this.latitude = other.latitude;
        this.longitude = other.longitude;
        this.openMeteoEndpoint = other.openMeteoEndpoint;
        this.openMeteoGeocoding = other.openMeteoGeocoding;
        this.requestTimeoutMillis = other.requestTimeoutMillis;
        this.refreshIntervalMillis = other.refreshIntervalMillis;
        this.staleAfterMillis = other.staleAfterMillis;
        this.offlineFallback = other.offlineFallback;
        this.offlineDefaultWeather = other.offlineDefaultWeather;
        this.respectBiomeClimate = other.respectBiomeClimate;
        this.rainIntensityThreshold = other.rainIntensityThreshold;
        this.thunderIntensityThreshold = other.thunderIntensityThreshold;
        this.minWeatherDurationTicks = other.minWeatherDurationTicks;
        this.maxWeatherDurationTicks = other.maxWeatherDurationTicks;
        this.clearDurationTicks = other.clearDurationTicks;
        this.thunderRequiresPrecipitation = other.thunderRequiresPrecipitation;
        this.leaveNetherAndEndAlone = other.leaveNetherAndEndAlone;
        this.seasonIntegration = other.seasonIntegration;
        this.seasonAmplitude = other.seasonAmplitude;
        this.timeSyncEnabled = other.timeSyncEnabled;
        this.timezoneOffsetMinutes = other.timezoneOffsetMinutes;
        this.timeSyncIntervalTicks = other.timeSyncIntervalTicks;
        this.moonPhaseSync = other.moonPhaseSync;
        this.shaderIntensitySync = other.shaderIntensitySync;
        this.skyForecastEnabled = other.skyForecastEnabled;
        this.announceLocationChange = other.announceLocationChange;
        this.caiyunEndpoint = other.caiyunEndpoint;
        this.caiyunToken = other.caiyunToken;
        this.forecastHorizon = other.forecastHorizon;
        this.forecastTicksPerEntry = other.forecastTicksPerEntry;
        this.temperatureToleranceC = other.temperatureToleranceC;
        this.allowExtremeEvents = other.allowExtremeEvents;
        this.syncToClients = other.syncToClients;
        this.hudEnabled = other.hudEnabled;
        this.clientForecastEnabled = other.clientForecastEnabled;
        this.dimensions = new ArrayList<>(other.dimensions);
        this.fixedBiomeId = other.fixedBiomeId;
        this.logProviderDiagnostics = other.logProviderDiagnostics;
        this.announceWeatherChanges = other.announceWeatherChanges;
        return this;
    }
}

package com.nowweather.core.sync;

import com.nowweather.core.api.BindingContext;
import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.api.PlayerHandle;
import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProviderRegistry;
import com.nowweather.core.api.WeatherBindingRegistry;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.forecast.ForecastEngine;
import com.nowweather.core.forecast.ForecastRequest;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 天气同步控制器 —— NowWeather 的大脑。
 *
 * <p>它在一个 tick 内完成：</p>
 * <ol>
 *   <li>判断当前是否需要刷新真实天气（异步、不阻塞主线程）；</li>
 *   <li>解析玩家所在生物群系的气候（含「正常温度范围」）；</li>
 *   <li>生成预报 —— 用真实天气作锚点，用气候作约束；</li>
 *   <li>决策并把天气落地到 Minecraft；</li>
 *   <li>把结果广播给出站联动（其它天气模组）。</li>
 * </ol>
 *
 * <p>整个类不引用任何 Minecraft 类型，只通过 {@link com.nowweather.core.api.PlatformWeatherAccess}
 * 与 {@link PlayerHandle} 与游戏交互。</p>
 */
public final class WeatherController {

    private final NowWeatherConfig config;
    private final WeatherProviderRegistry providers;
    private final BiomeClimateRegistry climates;
    private final WeatherBindingRegistry bindings;
    private final com.nowweather.core.api.PlatformWeatherAccess platform;
    private final SyncLogger logger;
    private final Executor ioExecutor;
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
    private final AtomicReference<WeatherObservation> observation = new AtomicReference<>();
    private final AtomicReference<ProviderResult> lastProviderResult = new AtomicReference<>();
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");

    /** 这两个依赖配置内容，热重载配置时需要重建，所以不是 final。 */
    private volatile SyncPolicy policy;
    private volatile ForecastEngine forecastEngine;

    private volatile Forecast forecast;
    private volatile WeatherDecision lastDecision;
    private volatile BiomeClimate lastClimate;
    /** 被强制指定的群系气候（调试 / 自检 / fixedBiomeId 用）；null 表示按玩家实际所在群系判断。 */
    private volatile BiomeClimate resolvedForcedClimate;
    private volatile String lastBiomeId = "";
    private double lastTerrainElevation = Double.NaN;
    private double lastTerrainExposure = Double.NaN;
    private long lastTerrainObservationAt = -1L;
    private double lastTerrainSourceTemp = Double.NaN;
    private volatile WeatherObservation lastTerrainResult;
    private volatile String lastTerrainExplanation = "";
    /** 最近一次 tick 的地形画像（取数时要用它告诉数据源目标海拔）。 */
    private volatile com.nowweather.core.terrain.TerrainProfile lastTerrain =
            com.nowweather.core.terrain.TerrainProfile.NONE;
    /** 上一次取数用的目标海拔；与当前地形一致时说明数据源已订正过，本地不再重复订正。 */
    private double fetchElevation = Double.NaN;
    /** 上一次由<b>我们</b>写入的天气；用于识别「世界里的天气被别人改了」。 */
    private volatile com.nowweather.core.api.PlatformWeather lastWritten;
    /** 外部改动（原版 /weather、其它模组）的让位截止时间（毫秒）。 */
    private volatile long externalChangeUntil;
    /** 最近一次外部改动的说明（诊断用）。 */
    private volatile String externalChangeNote = "";
    private volatile long lastApplyTick = -1_000_000L;
    private volatile long lastForecastTick = -1_000_000L;
    private volatile long nextFetchAllowedAt = 0L;
    /**
     * 位置刚刚被解析出来（典型场景：首次靠 IP 定位拿到「杭州市西湖区」）。
     *
     * <p>用来安排一次<b>立刻重取</b>：需要经纬度的数据源（Open-Meteo）在第一轮拿不到位置，
     * 所以第一轮只能由 IP 定位源顶上；一旦位置解析出来就该马上让它接手，
     * 而不是等满一个刷新周期（默认 20 分钟）。这个标志会在下一次取数后自动清除。</p>
     */
    private volatile boolean locationJustResolved;
    /**
     * 待播报的位置变更（跨城 / 跨省 / 跨国）。
     *
     * <p>取数在 IO 线程上跑，那里拿不到玩家列表，所以只把消息存下来，
     * 由下一次 {@code tick(ctx)} 在主线程上广播出去。</p>
     */
    private volatile String pendingLocationAnnouncement;
    /** 上一次同步用的时区偏移，用来发现「跨越时区」。 */
    private volatile int lastSyncedOffset = Integer.MIN_VALUE;
    private volatile long sequence = 0L;

    /**
     * 离线推算观测使用的数据源 id。
     *
     * <p>它<b>不是真实数据</b>，因此在「要不要去取真实天气」的判断里必须被当成「没有数据」，
     * 否则它自带的「刚刚生成」时间戳会把真实取数压住一整个刷新周期。</p>
     */
    public static final String OFFLINE_PROVIDER_ID = "offline";
    private volatile boolean manualOverride = false;
    /** 本地缓存（由平台层注入；为空表示无缓存能力）。 */
    private volatile com.nowweather.core.cache.WeatherCache cache;
    /** 地点画像（离线推算的依据）。 */
    private final java.util.concurrent.atomic.AtomicReference<com.nowweather.core.cache.LocationProfile> profile =
            new java.util.concurrent.atomic.AtomicReference<>(com.nowweather.core.cache.LocationProfile.empty());
    /** 真实时间同步：时区解析 + 世界时间换算。 */
    private final com.nowweather.core.time.TimeZoneService timeZoneService = new com.nowweather.core.time.TimeZoneService();
    private volatile com.nowweather.core.time.RealTimeSyncModel timeSyncModel;
    private volatile long lastTimeSyncTick = -1_000_000L;
    private volatile long lastSeenDayTime = -1L;
    private volatile boolean sleepSkipWarned;
    private volatile int consecutiveFailures = 0;
    /** 本进程是否已经记过一次「首次取到真实天气」——见 maybeRefresh 里的说明。 */
    private volatile boolean firstFetchLogged;

    public WeatherController(NowWeatherConfig config,
                             WeatherProviderRegistry providers,
                             BiomeClimateRegistry climates,
                             WeatherBindingRegistry bindings,
                             com.nowweather.core.api.PlatformWeatherAccess platform,
                             SyncLogger logger,
                             Executor ioExecutor) {
        this.config = config;
        this.providers = providers;
        this.climates = climates;
        this.bindings = bindings;
        this.platform = platform;
        this.logger = logger;
        this.ioExecutor = ioExecutor;
        this.policy = new SyncPolicy(config);
        this.forecastEngine = new ForecastEngine(policy.filter());
    }

    /**
     * 接入本地缓存：启动时恢复上次的地点画像 / 实况 / 预报。
     *
     * <p>这一步是「离线也能用」的关键 —— 服务器重启后即使完全没网，
     * 也能立刻知道「我们在哪个城市、什么时区、海拔多少」，从而推算天气。</p>
     */
    public void attachCache(com.nowweather.core.cache.WeatherCache newCache) {
        this.cache = newCache;
        if (newCache == null) {
            return;
        }
        newCache.loadProfile().ifPresent(p -> {
            profile.set(p);
            if (p.hasUtcOffset()) {
                timeZoneService.restoreFromCache(p.utcOffsetSeconds(), null, p.city());
            }
        });
        newCache.loadObservation().ifPresent(cached -> {
            observation.compareAndSet(null, cached.withStale(true));
        });
        newCache.loadForecast().ifPresent(cached -> {
            if (forecast == null) {
                forecast = cached;
            }
        });
        logger.info("已从本地缓存恢复：{}", newCache.describe());
    }

    public com.nowweather.core.cache.LocationProfile locationProfile() {
        return profile.get();
    }

    public com.nowweather.core.cache.WeatherCache cache() {
        return cache;
    }

    /**
     * 配置热重载：重建依赖配置的策略与预报引擎。
     *
     * <p>注意 {@link NowWeatherConfig} 是原地修改的同一个实例，所以这里只要重建
     * 「构造时读了一次配置」的那些对象即可。</p>
     */
    public void reloadConfig() {
        SyncPolicy rebuilt = new SyncPolicy(config);
        this.policy = rebuilt;
        this.forecastEngine = new ForecastEngine(rebuilt.filter());
        // 预报参数可能变了，强制重新生成
        this.lastForecastTick = -1_000_000L;
        this.forecast = null;
        // 让下一次 tick 立刻按新配置重新决策
        this.lastApplyTick = -1_000_000L;
        logger.info("配置已应用到天气控制器：尊重本地气候={}，预报 {} 格，极端天气={}",
                config.respectBiomeClimate(), config.forecastHorizon(), config.allowExtremeEvents());
    }

    // ------------------------------------------------------------------ 主循环

    public void tick(ServerTickContext ctx) {
        if (!config.enabled()) {
            return;
        }
        if (!config.controlsDimension(ctx.dimensionId())) {
            return;
        }

        long now = System.currentTimeMillis();
        // ★ 地形必须在取数之前更新：取数时会读 lastTerrain 决定要告诉数据源的目标海拔，
        // 放到 maybeRefresh 后面会让「第一次取数」拿不到海拔（实测就是这样漏掉的）。
        lastTerrain = ctx.terrain();
        maybeRefresh(ctx, now);
        applyTimeSync(ctx, now);

        BiomeClimate climate = resolvedForcedClimate;
        BiomeFacts facts = ctx.biomeFacts();
        String biomeId;
        if (climate != null) {
            // 被强制指定了群系（/nowweather test biome，或配置里的 fixedBiomeId）
            biomeId = climate.biomeId();
        } else if (facts != null) {
            biomeId = facts.id();
            climate = climates.resolve(facts);
        } else if (!config.fixedBiomeId().isBlank()) {
            climate = resolveBiomeById(config.fixedBiomeId());
            biomeId = climate.biomeId();
        } else {
            biomeId = "";
        }

        WeatherObservation current = observation.get();
        if (current == null && config.offlineFallback()) {
            current = buildOfflineObservation(ctx, climate);
            observation.compareAndSet(null, current);
        }
        if (current != null && current.isOlderThan(config.staleAfterMillis(), now) && !current.stale()) {
            WeatherObservation stale = current.withStale(true);
            observation.set(stale);
            current = stale;
            logger.warn("真实天气数据已过期（{} 分钟未更新），暂时沿用缓存", current.ageMillis(now) / 60_000L);
        }

        // ★ 地形订正：把「参考地点的真实天气」搬到玩家实际所在地的海拔 / 暴露度上。
        //   必须在决策之前做 —— 决策是拿 current 去比对群系气候的，
        //   不订正就等于把杭州平原的 25°C 直接灌到 1500m 高原上。
        //   以前这个方法写好了却<b>从未被调用</b>：配置项 mapping.terrainTransfer 默认开着、
        //   文档也在宣传它，但主循环走的是未订正的观测，等于整个功能是死的。
        //   结果按「地形 + 观测」缓存，所以每 tick 调用不会重复做三角函数与幂运算。
        current = applyTerrainTransfer(ctx, current);

        boolean biomeChanged = biomeId != null && !biomeId.equals(lastBiomeId);
        if (biomeChanged) {
            lastBiomeId = biomeId;
        }
        if (climate != null) {
            lastClimate = climate;
        }
        maybeRegenerateForecast(ctx, climate, current, biomeChanged);

        WeatherDecision decision = policy.decide(current, climate, ctx.totalWorldTime(),
                platform.currentWeather(ctx.dimensionId()));
        lastDecision = decision;

        applyIfNeeded(ctx, climate, current, decision);
        applyWeatherIntensity(ctx, current);
        flushLocationAnnouncement(ctx);
        dispatchTick(ctx, climate);
    }

    // ------------------------------------------------------------------ 光影 / 连续气象强度

    /** 当前正在写入世界的连续雨/雷强度（诊断与光影联动用）。 */
    private volatile float lastRainIntensity;
    private volatile float lastThunderIntensity;

    /** 真实逐小时天色预报（云量 / 能见度 / 降水概率），来自气象服务而不是我们自己合成。 */
    private volatile com.nowweather.core.forecast.SkyForecast skyForecast =
            com.nowweather.core.forecast.SkyForecast.empty();
    /** 天色预报客户端；未注入时不做任何取数（例如核心自测环境）。 */
    private volatile com.nowweather.core.providers.openmeteo.OpenMeteoSkyForecastClient skyClient;

    /** 地图地理编码器；用于「只知道城市名、没有坐标」时补全坐标。 */
    private volatile com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder geocoder;

    /** 注入天色预报客户端（由 runtime 装配时调用）。 */
    public void attachSkyForecastClient(
            com.nowweather.core.providers.openmeteo.OpenMeteoSkyForecastClient client) {
        this.skyClient = client;
    }

    /** 注入地理编码器（由 runtime 装配时调用）。 */
    public void attachGeocoder(com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder geocoder) {
        this.geocoder = geocoder;
    }

    /**
     * 位置补全：只知道城市名 / 行政区划码、但没有坐标时，用地理编码补上坐标。
     *
     * <h2>为什么必须有这一步</h2>
     * 彩云天气的实况接口<b>只返回天气、不返回经纬度</b>；而它优先级最高（80），
     * 于是每次取数都成功、路径永远走不到需要坐标的 Open-Meteo（90），
     * 地点画像里也就<b>永远没有坐标</b>。后果是：
     * <ul>
     *   <li>「真实天色预报」（{@code /nowweather sky}）因为缺坐标而永远不取数；</li>
     *   <li>Open-Meteo 永远拿不到坐标，等于白装。</li>
     * </ul>
     * 所以取数成功后要主动把坐标补齐 —— 一次地理编码，之后写进画像与缓存。
     */
    private void ensureCoordinates() {
        var resolver = geocoder;
        if (resolver == null) {
            return;
        }
        var current = profile.get();
        if (current.hasCoordinates()) {
            return;
        }
        String query = !current.city().isBlank() ? current.city() : current.adcode();
        if (query == null || query.isBlank()) {
            return;
        }
        try {
            var resolved = resolver.resolve(query);
            if (resolved != null && resolved.isValid()) {
                profile.set(current.withCoordinates(resolved.latitude(), resolved.longitude()));
                var cacheRef = cache;
                if (cacheRef != null) {
                    cacheRef.saveProfile(profile.get());
                }
                logger.info("已用地理编码补全坐标：{} -> {}, {}",
                        query, resolved.latitude(), resolved.longitude());
            }
        } catch (RuntimeException e) {
            logger.debug("地理编码补全坐标失败: {}", e.getMessage());
        }
    }

    /** 最近一次取到的真实天色预报；没有数据时返回空预报。 */
    public com.nowweather.core.forecast.SkyForecast skyForecast() {
        return skyForecast;
    }

    /**
     * 取一次真实天色预报。
     *
     * <p>刻意与实况取数<b>分开失败域</b>：天色预报失败只影响视觉，绝不能让天气刷新失败，
     * 所以这里吞掉所有异常并在失败时保留上一次的数据。</p>
     */
    private void refreshSkyForecast(ProviderContext providerContext) {
        var client = skyClient;
        if (client == null || !config.skyForecastEnabled()) {
            return;
        }
        var location = effectiveProfile();
        if (!location.hasCoordinates()) {
            return;
        }
        try {
            var fetched = client.fetch(providerContext, location.latitude(), location.longitude(),
                    com.nowweather.core.providers.openmeteo.OpenMeteoSkyForecastClient.DEFAULT_HOURS);
            if (fetched != null && !fetched.isEmpty()) {
                skyForecast = fetched;
            }
        } catch (RuntimeException e) {
            logger.debug("天色预报取数失败（不影响天气）: {}", e.getMessage());
        }
    }

    /**
     * 把「真实天气的强度」连续地写进世界，供光影（Iris / OptiFine）读取。
     *
     * <h2>为什么必须连续</h2>
     * 原版天气只有一个布尔值：{@code ServerLevel.setWeatherParameters(..., raining, thundering)}，
     * 于是 {@code rainLevel} 只会是 0 或 1。而 Iris / OptiFine 给光影的天气 uniform
     * （{@code rainStrength} / {@code wetness} / {@code thunderStrength}）全部取自
     * {@code Level#getRainLevel} / {@code getThunderLevel} —— 结果就是：
     * <b>毛毛雨、中雨、暴雨、雷阵雨在光影里是同一个画面</b>，雨云、雾、湿地反射完全没有层次。
     *
     * <p>这里改用 {@code WeatherType} 自带的 {@link com.nowweather.core.weather.WeatherType#precipitation()}
     * 与 {@link com.nowweather.core.weather.WeatherType#thunder()}（0~1）作为目标值，
     * 并按每刻固定步长平滑逼近 —— 既让光影有层次，也避免天气切换时画面骤变。</p>
     *
     * <p><b>刻意不写「云量」</b>：原版 {@code LevelRenderer} 只要 {@code getRainLevel > 0}
     * 就会开启雨致变暗与雨粒子，把阴天的云量塞进来等于凭空下雨。云量属于光影包自身的设置，
     * 模组无法修改（Iris 没有给第三方模组注入 uniform 的 API）。</p>
     */
    private void applyWeatherIntensity(ServerTickContext ctx, WeatherObservation observation) {
        if (!config.shaderIntensitySync()) {
            return;
        }
        float targetRain = 0.0F;
        float targetThunder = 0.0F;
        if (observation != null && !observation.stale()) {
            WeatherType type = observation.weatherType();
            if (type != null && type != WeatherType.UNKNOWN) {
                targetRain = (float) MathUtil.clamp(type.precipitation(), 0.0D, 1.0D);
                targetThunder = (float) MathUtil.clamp(type.thunder(), 0.0D, 1.0D);
            }
        }
        // 每刻最多变化 0.01 —— 与原版自身的雨量过渡速率一致（约 5 秒走完 0→1），
        // 这样光影的 wetnessHalflife 也不会被人为打乱。
        float rain = approach(lastRainIntensity, targetRain, 0.01F);
        float thunder = approach(lastThunderIntensity, targetThunder, 0.01F);

        // ★ 即使已经收敛也必须继续写。
        // 以前这里有一句「`rain == lastRainIntensity && thunder == lastThunderIntensity` 就 return」，
        // 而原版 {@code ServerLevel.advanceWeatherCycle()} 每刻都会把 rainLevel 朝 0/1 推 0.01 ——
        // 于是我们收敛停写之后，值会被原版慢慢拉走，服务端侧的权威值守不住
        // （客户端因为 {@code ShaderSkyBridge} 每帧重写而表现正常，所以这个 bug 只在服务端可见）。
        // setWeatherIntensity 只是写 4 个 float 字段，没有网络包，每刻写零成本。
        if (platform.setWeatherIntensity(ctx.dimensionId(), rain, thunder)) {
            lastRainIntensity = rain;
            lastThunderIntensity = thunder;
        }
    }

    private static float approach(float current, float target, float step) {
        if (current < target) {
            return Math.min(target, current + step);
        }
        return current > target ? Math.max(target, current - step) : current;
    }

    /** 当前写入世界的连续雨强度（0~1）。 */
    public float rainIntensity() {
        return lastRainIntensity;
    }

    /** 当前写入世界的连续雷强度（0~1）。 */
    public float thunderIntensity() {
        return lastThunderIntensity;
    }

    // ------------------------------------------------------------------ 真实时间同步

    /**
     * 让游戏内时间与所在地的真实时间一致（一天 = 真实 24 小时）。
     *
     * <p>换算与相位处理见 {@link com.nowweather.core.time.RealTimeSyncModel}；
     * 时区按「手动配置 → 天气接口返回 → 系统时区」解析，并会写进缓存供离线使用。</p>
     */
    private void applyTimeSync(ServerTickContext ctx, long now) {
        if (!config.timeSyncEnabled()) {
            return;
        }
        if (config.timezoneOffsetMinutes() != com.nowweather.core.time.TimeZoneService.UNSET
                && !timeZoneService.hasManualOffset()) {
            timeZoneService.setManualOffsetMinutes(config.timezoneOffsetMinutes());
        }
        long currentTotalDayTime = platform.totalDayTime(ctx.dimensionId());
        if (currentTotalDayTime < 0L) {
            return;
        }
        int offset = timeZoneService.effectiveOffsetSeconds();
        if (timeSyncModel == null) {
            timeSyncModel = com.nowweather.core.time.RealTimeSyncModel.initialize(
                    currentTotalDayTime, now, offset, config.moonPhaseSync());
            logger.info("真实时间同步已启用：{}，世界天数偏移 {}（游戏内一天 = 真实 24 小时）",
                    timeZoneService, timeSyncModel.dayOffset());
            if (config.moonPhaseSync()) {
                int realPhase = com.nowweather.core.time.MoonPhaseModel.phaseIndex(now);
                logger.info("月相已对齐真实历法：真实月相 {}/8（{}），游戏内现在也是 {}",
                        realPhase, com.nowweather.core.time.MoonPhaseModel.zhName(realPhase),
                        com.nowweather.core.time.MoonPhaseModel.zhName(
                                com.nowweather.core.time.RealTimeSyncModel.moonPhaseOf(
                                        timeSyncModel.compute(now, offset))));
            }
            lastSeenDayTime = currentTotalDayTime;
            return;
        }

        // ★ 跨越时区后必须重新初始化：dayOffset 是按旧时区算出来的，
        // 直接把新偏移塞进 compute() 会让「世界第几天」跳一格。
        if (lastSyncedOffset != Integer.MIN_VALUE && offset != lastSyncedOffset) {
            timeSyncModel = com.nowweather.core.time.RealTimeSyncModel.initialize(
                    currentTotalDayTime, now, offset, config.moonPhaseSync());
            logger.info("检测到时区变化（{} 小时），世界时间已重新对齐：{}",
                    (offset - lastSyncedOffset) / 3600.0D, timeZoneService);
        }
        lastSyncedOffset = offset;

        long target = timeSyncModel.compute(now, offset);
        if (lastSeenDayTime >= 0L && currentTotalDayTime - lastSeenDayTime > 20L) {
            // 世界时间被「外力」大幅推进（最典型的是玩家睡觉跳过夜晚）。
            // 本模式的目标是「与真实时间一致」，所以这里会把时间校回真实时刻，并提示一次。
            if (!sleepSkipWarned) {
                sleepSkipWarned = true;
                logger.info("检测到世界时间被跳过（例如玩家睡觉）：真实时间同步模式下会把时间校回所在地的真实时刻。"
                        + "如果想要「睡觉跳过夜晚」，请在配置里关闭 timeSyncEnabled。");
            }
        }
        lastSeenDayTime = currentTotalDayTime;

        // ★ 每刻都写，不做节流。
        // 写的是一个整数、不走网络，成本为零；而只要不是每刻写，服务端时钟就会在两次写入之间
        // 被原版日光循环（每刻 +1）带跑 —— 旧默认 20 刻会漂 14 刻，客户端每秒被拽一次，
        // 季节模组也会把这种「回退」当成跳过 23999 刻而刷屏。
        if (platform.setDayTime(ctx.dimensionId(), target)) {
            lastTimeSyncTick = ctx.totalWorldTime();
        }
    }

    /** 供命令 / 诊断查看时区与时间同步状态。 */
    public com.nowweather.core.time.TimeZoneService timeZoneService() {
        return timeZoneService;
    }

    public com.nowweather.core.time.RealTimeSyncModel timeSyncModel() {
        return timeSyncModel;
    }

    /** 记录地点信息并落盘（每次成功获取实况时调用）。 */
    private void rememberLocation(WeatherObservation fresh, ProviderResult result) {
        long now = System.currentTimeMillis();
        var before = profile.get();
        // ★ 空值不能覆盖已有身份。彩云天气这类「只给坐标、不给行政区划名」的数据源
        // （它的 realtime 接口不返回省/市）如果直接用空串覆盖，
        // 会把画像里已经解析出来的「杭州市」冲掉 —— 那之后 Open-Meteo 地理编码就没名字可用了。
        var current = before
                .withIdentity(
                        fresh.city() == null || fresh.city().isBlank()
                                ? before.city() : fresh.city(),
                        fresh.adcode() == null || fresh.adcode().isBlank()
                                ? before.adcode() : fresh.adcode(),
                        fresh.providerId())
                .touched(now, fresh.providerId());
        if (fresh.hasUtcOffset()) {
            current = current.withUtcOffset(fresh.utcOffsetSeconds());
        }
        if (result != null) {
            current = withDiagnosticCoordinate(current, result, "latitude", "longitude");
            current = withDiagnosticElevation(current, result);
        }
        if (!current.hasCoordinates() && config.hasCoordinates()) {
            current = current.withCoordinates(config.latitude(), config.longitude());
        }
        profile.set(current);
        announceIfMoved(before, current, fresh);
        var cacheRef = cache;
        if (cacheRef != null) {
            cacheRef.saveProfile(current);
            cacheRef.saveObservation(fresh);
        }
    }

    private static com.nowweather.core.cache.LocationProfile withDiagnosticCoordinate(
            com.nowweather.core.cache.LocationProfile current, ProviderResult result,
            String latKey, String lonKey) {
        String latText = result.diagnostics().get(latKey);
        String lonText = result.diagnostics().get(lonKey);
        if (latText == null || lonText == null) {
            return current;
        }
        try {
            return current.withCoordinates(Double.parseDouble(latText), Double.parseDouble(lonText));
        } catch (NumberFormatException e) {
            return current;
        }
    }

    private static com.nowweather.core.cache.LocationProfile withDiagnosticElevation(
            com.nowweather.core.cache.LocationProfile current, ProviderResult result) {
        String text = result.diagnostics().get("elevation");
        if (text == null) {
            return current;
        }
        try {
            return current.withElevation(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return current;
        }
    }

    /** 记录数据源给出的时区（每次成功获取实况时调用）。 */
    public void applyProviderTimeZone(WeatherObservation observation) {
        if (observation == null || !observation.hasUtcOffset()) {
            return;
        }
        timeZoneService.applyFromProvider(observation.utcOffsetSeconds(), null, observation.locationDisplay());
    }

    // ------------------------------------------------------------------ 真实天气刷新

    private void maybeRefresh(ServerTickContext ctx, long now) {
        WeatherObservation current = observation.get();
        // ★ 「离线推算」不是真实数据，绝不能压住取数。
        // 曾经的 bug：离线推算的 fetchedAt 就是当时的时刻，于是 isOlderThan(刷新间隔) 为 false，
        // 一旦首刻没能成功发出请求（例如定位/时区还没就绪、或首刻请求失败），
        // 接下来整整一个刷新周期（默认 20 分钟）都不会再尝试任何一次真实取数，
        // 面板上就一直卡着「来源: offline / xxx（推算）」。
        boolean offlineOnly = current != null && OFFLINE_PROVIDER_ID.equals(current.providerId());
        boolean needsData = current == null
                || offlineOnly
                // ★ 位置刚刚被解析出来（例如首次按 IP 定位成功）：立刻再取一次，
                // 好让精度更高的数据源（Open-Meteo，需要经纬度）马上接手，
                // 而不是等满一个刷新周期。
                || locationJustResolved
                || (!manualOverride && current.isOlderThan(config.refreshIntervalMillis(), now));
        if (!needsData || fetchInFlight.get() || now < nextFetchAllowedAt) {
            return;
        }
        ProviderContext providerContext = buildProviderContext(ctx, now);
        if (!providerContext.hasLocation() && !hasLocationProvider(providerContext)) {
            nextFetchAllowedAt = now + 60_000L;
            lastFailure.set(Text.tr("nowweather.failure.no_location"));
            return;
        }
        if (!fetchInFlight.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                WeatherProviderRegistry.Attempt attempt = fetchWithPreferred(providerContext);
                ProviderResult result = attempt.success() != null
                        ? attempt.success()
                        : attempt.attempts().stream().reduce((a, b) -> b).orElse(null);
                if (result != null) {
                    lastProviderResult.set(result);
                }
                if (attempt.hasObservation()) {
                    WeatherObservation fresh = attempt.observation();
                    observation.set(fresh);
                    applyProviderTimeZone(fresh);
                    offlineExplainLogged.set(false);
                    boolean hadIdentity = hasLocationIdentity(profile.get());
                    rememberLocation(fresh, result);
                    // 这一轮如果第一次解析出了位置，就安排一次立刻重取（见字段注释）。
                    // 下一次取数时 hadIdentity 已为 true，标志自动清掉，不会来回抖。
                    locationJustResolved = !hadIdentity && hasLocationIdentity(profile.get());
                    consecutiveFailures = 0;
                    nextFetchAllowedAt = now + config.refreshIntervalMillis();
                    if (config.logProviderDiagnostics()) {
                        logger.info("真实天气已更新: {}", fresh);
                    } else if (!firstFetchLogged) {
                        // ★ 每个进程至少留一条「确实取到数」的凭证。
                        //   否则把 logProviderDiagnostics 关掉之后，「取数成功」与「压根没发请求」
                        //   在日志里长得一模一样 —— 排查「天气不自动同步」时会完全无从下手。
                        firstFetchLogged = true;
                        logger.info("首次取到真实天气: {}", fresh);
                    }
                    // ★ 实况成功后再补一次「真实天色预报」（云量/能见度/降水概率）。
                    // 放在这里而不是独立定时器：定位信息刚被 rememberLocation 更新完，
                    // 这一轮就能用上正确坐标。
                    // 先补全坐标，再取天色预报 —— 否则「有城市名没坐标」时天色预报永远取不到
                    ensureCoordinates();
                    refreshSkyForecast(providerContext);
                } else {
                    onFetchFailure(attempt.failureSummary(), now);
                }
            } catch (RuntimeException e) {
                onFetchFailure(e.getClass().getSimpleName() + ": " + e.getMessage(), now);
                logger.error(e, "刷新真实天气时发生异常");
            } finally {
                fetchInFlight.set(false);
            }
        });
    }

    private WeatherProviderRegistry.Attempt fetchWithPreferred(ProviderContext providerContext) {
        String preferred = config.providerId();
        if (preferred != null && !preferred.isBlank()) {
            var provider = providers.byId(preferred);
            if (provider.isPresent() && provider.get().isEnabled(providerContext)) {
                ProviderResult result;
                try {
                    result = provider.get().fetch(providerContext);
                } catch (RuntimeException e) {
                    result = ProviderResult.failure(preferred, ProviderStatus.PARSE_ERROR,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                if (result != null && result.isSuccess()) {
                    return new WeatherProviderRegistry.Attempt(result, List.of(result));
                }
                // 首选数据源失败：继续走回退链
                WeatherProviderRegistry.Attempt fallback = providers.attempt(providerContext, logger);
                List<ProviderResult> merged = new ArrayList<>();
                if (result != null) {
                    merged.add(result);
                }
                merged.addAll(fallback.attempts());
                return new WeatherProviderRegistry.Attempt(fallback.success(), merged);
            }
        }
        return providers.attempt(providerContext, logger);
    }

    /**
     * 是否存在「能自己搞清楚位置」的可用数据源。
     *
     * <p>★ 这里<b>刻意不判断 {@code requiresNetwork()}</b>。</p>
     *
     * <p>历史 bug：原来的条件是 {@code providesLocation() && !requiresNetwork()}。
     * 但能凭请求方 IP 定位的数据源（UApiPro）<b>本质上就必须联网</b> ——
     * 于是这个条件永远为假，只要玩家没手填城市/坐标，{@link #maybeRefresh} 就会走
     * 「没有定位源」的提前返回，<b>一次 API 都不会发</b>，
     * 面板上永远显示「来源: offline」，而玩家去测 API 又是一切正常的。</p>
     */
    /** 地点画像里是否已经有「能用来查天气」的位置信息（城市 / 行政区划 / 坐标任一即可）。 */
    private static boolean hasLocationIdentity(com.nowweather.core.cache.LocationProfile profile) {
        if (profile == null) {
            return false;
        }
        return (profile.city() != null && !profile.city().isBlank())
                || (profile.adcode() != null && !profile.adcode().isBlank())
                || profile.hasCoordinates();
    }

    private boolean hasLocationProvider(ProviderContext context) {
        return providers.all().stream()
                .anyMatch(p -> p.providesLocation() && p.isEnabled(context));
    }

    private void onFetchFailure(String reason, long now) {
        consecutiveFailures++;
        lastFailure.set(reason);
        // 上限 5 分钟：以前是 30 分钟，一旦连续失败几次，玩家就要等半小时才可能恢复。
        long backoff = Math.min(5L * 60_000L, 15_000L * (1L << Math.min(consecutiveFailures, 6)));
        nextFetchAllowedAt = now + backoff;
        logger.warn("获取真实天气失败（第 {} 次）: {}，{} 秒后重试", consecutiveFailures, reason, backoff / 1000L);
        if (observation.get() == null && !config.offlineFallback()) {
            logger.error("没有可用的天气数据，且未开启离线兜底：天气将保持原版行为");
        }
    }

    private ProviderContext buildProviderContext(ServerTickContext ctx, long now) {
        PlayerHandle anchor = ctx.anchorPlayer().orElse(null);
        // ★ 位置来源优先级：配置 → 已解析出来的地点画像（缓存 / IP 定位）。
        // 之前这里只读 config，于是「UApiPro 按 IP 定位解析出杭州市西湖区」这个结果
        // 永远喂不进 ProviderContext —— 而 Open-Meteo（精度更高、有云量与 15 分钟临近预报）
        // 需要经纬度，拿不到就每次返回 NO_LOCATION 被跳过，永远轮不到它。
        // 改成一个调用点：配置有就用配置，没有就用已经解析出来的画像。
        var resolved = effectiveProfile();
        var builder = ProviderContext.builder()
                .city(resolved.city())
                .adcode(resolved.adcode())
                .apiKey(config.apiKey())
                .timeoutMillis(config.requestTimeoutMillis())
                .dimensionId(ctx.dimensionId())
                .now(now)
                .settings(config.toJson());
        // 坐标同样先用配置、再用已解析出来的画像；都没有就让支持地理编码/IP 定位的数据源自己解决
        if (resolved.hasCoordinates()) {
            builder.coordinates(resolved.latitude(), resolved.longitude());
        }
        // ★ 目标海拔：把玩家所在地形高度告诉数据源。
        // Open-Meteo 支持 elevation 参数并会自己按 6.5 K/km 平移气温与露点、下推气压，
        // 因此拿到手的就是「已经订正到当地高度」的数据 —— 这时本地地形订正必须让路，
        // 否则会二次递减（把 1500m 高原当 3000m 算）。下面记录本次取数用的海拔，
        // applyTerrainTransfer 会据此跳过重复订正。
        if (config.terrainTransfer()) {
            com.nowweather.core.terrain.TerrainProfile terrain = lastTerrain;
            if (terrain != null && terrain.hasElevation()) {
                builder.elevationMeters(terrain.elevationMeters());
                fetchElevation = terrain.elevationMeters();
            } else {
                fetchElevation = Double.NaN;
            }
        } else {
            fetchElevation = Double.NaN;
        }
        if (anchor != null) {
            builder.biomeId(anchor.biomeId());
        }
        return builder.build();
    }

    /** 离线兜底：没有网络时，用气候 + 种子生成一个合理的天气，保证体验连续。 */
    private WeatherObservation buildOfflineObservation(ServerTickContext ctx, BiomeClimate climate) {
        BiomeClimate effective = climate;
        if (effective == null && ctx.biomeFacts() != null) {
            effective = climates.resolve(ctx.biomeFacts());
        }
        WeatherType configuredType = WeatherType.parse(config.offlineDefaultWeather(), null);
        if (configuredType != null && configuredType != WeatherType.UNKNOWN) {
            // 配置里写死了天气：直接用（给「服务器想要恒定天气」的场景）
            return WeatherObservation.builder("offline")
                    .weatherType(configuredType)
                    .conditionText(Text.tr("nowweather.offline.condition_configured",
                            configuredType.zhName()))
                    .temperatureC(Double.NaN)
                    .confidence(0.3D)
                    .reportTimeText(Text.tr("nowweather.offline.report_time_configured"))
                    .location("", config.city(), config.adcode())
                    .build();
        }

        // ★ 正式路径：按「地点画像 + 当前时刻」推算接近真实的天气
        var request = new com.nowweather.core.forecast.OfflineWeatherSynthesizer.Request(
                effectiveProfile(),
                System.currentTimeMillis(),
                timeZoneService.effectiveOffsetSeconds(),
                effective,
                observation.get(),
                com.nowweather.core.util.MathUtil.seedOf(ctx.worldSeed(), ctx.totalWorldTime() / 6_000L,
                        effective == null ? "none" : effective.biomeId()));
        var result = com.nowweather.core.forecast.OfflineWeatherSynthesizer.synthesize(request);
        if (offlineExplainLogged.compareAndSet(false, true)) {
            logger.info("当前离线：推算天气（可信度 {}%）—— {}",
                    Math.round(result.observation().confidence() * 100.0D), result.explanation());
        }
        return result.observation();
    }

    /** 合并「配置 + 缓存」得到当前可用的地点画像。 */
    private com.nowweather.core.cache.LocationProfile effectiveProfile() {
        var current = profile.get();
        var builder = current;
        if (!builder.hasCoordinates() && config.hasCoordinates()) {
            builder = builder.withCoordinates(config.latitude(), config.longitude());
        }
        if (builder.city().isBlank() && !config.city().isBlank()) {
            builder = builder.withIdentity(config.city(), builder.adcode(), builder.providerId());
        }
        if (builder.adcode().isBlank() && !config.adcode().isBlank()) {
            builder = builder.withIdentity(builder.city(), config.adcode(), builder.providerId());
        }
        if (!builder.hasUtcOffset()) {
            builder = builder.withUtcOffset(timeZoneService.effectiveOffsetSeconds());
        }
        return builder;
    }

    /** 离线推算只需提示一次。 */
    private final java.util.concurrent.atomic.AtomicBoolean offlineExplainLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);


    // ------------------------------------------------------------------ 位置巡检与跨城播报

    /**
     * 玩家换地方了吗？同城静默、跨城播报。
     *
     * <h2>为什么用 adcode 前 4 位判「同城」</h2>
     * UApiPro 返回的 adcode 是标准的行政区划码：{@code 330106} →
     * {@code 33}=浙江省 / {@code 3301}=杭州市 / {@code 330106}=西湖区。
     * 所以「前 4 位相同」就等于「同一个地级市」，比拿城市名字符串比更稳
     * （「杭州市」和「杭州」写法不同但其实是同一个城市）。
     *
     * <p>境外城市通常没有 adcode，这时退回比城市名。判定不出来时<b>宁可不播报</b> ——
     * 误报「已进入 XX」比漏报烦人得多。</p>
     */
    public static boolean sameCity(String adcodeBefore, String cityBefore,
                                   String adcodeAfter, String cityAfter) {
        if (isNumericCode(adcodeBefore) && isNumericCode(adcodeAfter)
                && adcodeBefore.length() >= 4 && adcodeAfter.length() >= 4) {
            return adcodeBefore.regionMatches(0, adcodeAfter, 0, 4);
        }
        String a = cityBefore == null ? "" : cityBefore;
        String b = cityAfter == null ? "" : cityAfter;
        return !a.isBlank() && a.equals(b);
    }

    private static boolean isNumericCode(String value) {
        if (value == null || value.length() < 4) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 位置变了就排队一条播报；首次解析出位置（从无到有）不播报。 */
    private void announceIfMoved(com.nowweather.core.cache.LocationProfile before,
                                 com.nowweather.core.cache.LocationProfile after,
                                 WeatherObservation fresh) {
        if (!config.announceLocationChange() || !hasLocationIdentity(before)) {
            return;
        }
        if (sameCity(before.adcode(), before.city(), after.adcode(), after.city())) {
            // 同城（含跨区县）：静默更新坐标，不打扰玩家
            return;
        }
        String block = describeCrossing(before.adcode(), after.adcode());
        pendingLocationAnnouncement = Text.tr("nowweather.announce.entered",
                after.city().isBlank() ? fresh.locationDisplay() : after.city(),
                block,
                com.nowweather.core.time.TimeZoneService.formatOffset(
                        timeZoneService.effectiveOffsetSeconds()));
        logger.info("检测到玩家跨越行政区划：{} -> {}，已自动调整坐标与时区",
                before.city(), after.city().isBlank() ? fresh.locationDisplay() : after.city());
    }

    /** 「跨省 / 跨国」的说明文字；信息不足时就如实说不知道，不编。 */
    public static String describeCrossing(String adcodeBefore, String adcodeAfter) {
        String aa = adcodeBefore == null ? "" : adcodeBefore;
        String bb = adcodeAfter == null ? "" : adcodeAfter;
        if (isNumericCode(aa) && isNumericCode(bb)) {
            if (!aa.regionMatches(0, bb, 0, 2)) {
                return Text.tr("nowweather.announce.cross_province");
            }
            return Text.tr("nowweather.announce.cross_city");
        }
        if (aa.isBlank() || bb.isBlank()) {
            return Text.tr("nowweather.announce.cross_border");
        }
        return Text.tr("nowweather.announce.cross_region");
    }

    /** 在主线程把排队中的播报发给控制维度内的所有玩家。 */
    private void flushLocationAnnouncement(ServerTickContext ctx) {
        String message = pendingLocationAnnouncement;
        if (message == null) {
            return;
        }
        pendingLocationAnnouncement = null;
        for (var player : ctx.players()) {
            try {
                player.sendMessage(message);
            } catch (RuntimeException e) {
                logger.debug("播报位置变更失败: {}", e.getMessage());
            }
        }
    }
    // ------------------------------------------------------------------ 预报

    private void maybeRegenerateForecast(ServerTickContext ctx, BiomeClimate climate,
                                         WeatherObservation current, boolean biomeChanged) {
        if (climate == null) {
            return;
        }
        boolean missing = forecast == null;
        boolean expired = forecast != null && ctx.totalWorldTime() > forecast.endTick();
        boolean biomeSwitched = biomeChanged && forecast != null && !climate.biomeId().equals(forecast.biomeId());
        boolean timeToRefresh = ctx.totalWorldTime() - lastForecastTick
                > Math.max(2_000L, config.forecastTicksPerEntry() * 2L);
        if (!(missing || expired || biomeSwitched || timeToRefresh)) {
            return;
        }
        long seed = MathUtil.seedOf(ctx.worldSeed(), ctx.totalWorldTime() / 24_000L, climate.biomeId(),
                config.forecastHorizon());
        ForecastRequest request = ForecastRequest.builder(climate)
                .anchor(current)
                .seed(seed)
                .startTick(ctx.totalWorldTime())
                .startEpochMillis(System.currentTimeMillis())
                .dayTime(ctx.dayTime())
                .horizon(config.forecastHorizon())
                .ticksPerEntry(config.forecastTicksPerEntry())
                .millisPerTick(config.timeSyncEnabled()
                        ? (double) com.nowweather.core.time.RealTimeSyncModel.MILLIS_PER_DAY
                                / com.nowweather.core.time.RealTimeSyncModel.TICKS_PER_DAY
                        : ForecastRequest.VANILLA_MILLIS_PER_TICK)
                .toleranceC(config.temperatureToleranceC())
                .allowExtremeEvents(config.allowExtremeEvents())
                .build();
        forecast = forecastEngine.generate(request);
        lastForecastTick = ctx.totalWorldTime();
    }

    // ------------------------------------------------------------------ 落地与联动

    /**
     * 把真实天气按地形订正到玩家所在位置。
     *
     * <p>结果会缓存（同一地形 + 同一观测只算一次），避免每 tick 重复做三角函数与幂运算。</p>
     */
    private WeatherObservation applyTerrainTransfer(ServerTickContext ctx, WeatherObservation source) {
        if (source == null || !config.terrainTransfer()) {
            return source;
        }
        com.nowweather.core.terrain.TerrainProfile terrain = ctx.terrain();
        if (terrain == null || !terrain.hasElevation()) {
            return source;
        }
        com.nowweather.core.cache.LocationProfile profile = locationProfile();
        long key = terrain.elevationMeters() == lastTerrainElevation
                && terrain.exposure() == lastTerrainExposure
                && source.observedAtEpochMillis() == lastTerrainObservationAt
                && source.temperatureC() == lastTerrainSourceTemp
                ? 1L : 0L;
        if (key == 1L && lastTerrainResult != null) {
            return lastTerrainResult;
        }
        com.nowweather.core.terrain.TerrainTransfer.Result result =
                com.nowweather.core.terrain.TerrainTransfer.transfer(source, profile, terrain,
                        System.currentTimeMillis(), source.utcOffsetSeconds());
        if (result.observation() == null) {
            return source;
        }
        if (result.changed()) {
            lastTerrainExplanation = result.explanation();
            logger.debug("地形订正：{}", result.explanation());
        }
        lastTerrainElevation = terrain.elevationMeters();
        lastTerrainExposure = terrain.exposure();
        lastTerrainObservationAt = source.observedAtEpochMillis();
        lastTerrainSourceTemp = source.temperatureC();
        lastTerrainResult = result.observation();
        return result.observation();
    }

    /** 最近一次外部天气改动的说明（诊断用）。 */
    public String externalChangeNote() {
        return externalChangeNote;
    }

    /** 外部改动还要让位多久（毫秒，0 = 已重新接管）。 */
    public long externalChangeRemainingMillis() {
        return Math.max(0L, externalChangeUntil - System.currentTimeMillis());
    }

    public String lastTerrainExplanation() {
        return lastTerrainExplanation;
    }

    private void applyIfNeeded(ServerTickContext ctx, BiomeClimate climate, WeatherObservation current,
                              WeatherDecision decision) {
        // 观察模式（让权给其它天气模组）：照常拉数据、生成预报、同步客户端，但不改游戏天气
        if (!config.applyWeather()) {
            return;
        }
        String dimension = ctx.dimensionId();
        PlatformWeather now = platform.currentWeather(dimension);

        // ★ 原版 /weather（或别的模组）改了天气 → 让它生效一段时间，但最多 vanillaWeatherMaxMillis。
        // 这样做而不是去 mixin 原版指令：既保留了玩家熟悉的命令，也不与其它模组抢写入权，
        // 而且完全不需要碰 Minecraft 内部实现（可离线测试）。
        if (lastWritten != null && now != lastWritten) {
            long maxMillis = Math.max(60_000L, config.vanillaWeatherMaxMillis());
            long until = System.currentTimeMillis() + maxMillis;
            if (until > externalChangeUntil + 5_000L) {
                // 只在「新的外部改动」时刷新窗口与日志，避免每 tick 刷屏
                externalChangeUntil = until;
                externalChangeNote = Text.tr("nowweather.external_change",
                        lastWritten.zhName(), now.zhName(),
                        Long.toString(maxMillis / 60_000L));
                logger.info("{}", externalChangeNote);
            }
            lastWritten = now;
        }
        if (System.currentTimeMillis() < externalChangeUntil) {
            return;
        }
        boolean different = decision.isChangeFrom(now);
        boolean due = ctx.totalWorldTime() - lastApplyTick >= Math.max(100L, decision.durationTicks());
        boolean cooldownPassed = ctx.totalWorldTime() - lastApplyTick >= 100L;

        if (!(cooldownPassed && (different || due))) {
            return;
        }
        if (decision.durationTicks() <= 0) {
            return;
        }
        boolean ok = platform.applyWeather(dimension, decision.target(), decision.durationTicks(),
                decision.clearDurationTicks());
        if (!ok) {
            return;
        }
        lastWritten = decision.target();
        lastApplyTick = ctx.totalWorldTime();
        sequence++;
        logger.info("天气切换 → {}（{}）", decision.target().zhName(), decision.reason());
        if (config.announceWeatherChanges()) {
            String message = Text.tr("nowweather.announce.weather_switch",
                    decision.target().zhName(), decision.reason());
            ctx.players().forEach(p -> p.sendMessage(message));
        }
        BindingContext bindingContext = new BindingContext(dimension,
                climate == null ? lastBiomeId : climate.biomeId(), ctx.totalWorldTime(), ctx.dayTime(),
                ctx.presence(), ctx.bindingSettings(), logger, platform, climate);
        bindings.dispatchWeatherChanged(bindingContext, decision.target(), current, logger);
    }

    private void dispatchTick(ServerTickContext ctx, BiomeClimate climate) {
        List<com.nowweather.core.api.WeatherBinding> available = bindings.available(ctx.presence());
        if (available.isEmpty()) {
            return;
        }
        BindingContext bindingContext = new BindingContext(ctx.dimensionId(),
                climate == null ? lastBiomeId : climate.biomeId(), ctx.totalWorldTime(), ctx.dayTime(),
                ctx.presence(), ctx.bindingSettings(), logger, platform, climate);
        WeatherDecision decision = lastDecision;
        PlatformWeather applied = decision == null ? platform.currentWeather(ctx.dimensionId()) : decision.target();
        bindings.dispatchTick(bindingContext, applied, logger);
    }

    public void onServerStopping(ServerTickContext ctx) {
        BindingContext bindingContext = new BindingContext(ctx.dimensionId(), lastBiomeId,
                ctx.totalWorldTime(), ctx.dayTime(), ctx.presence(), ctx.bindingSettings(), logger, platform, null);
        bindings.deactivateAll(bindingContext, logger);
    }

    // ------------------------------------------------------------------ 调试 / 自检

    /**
     * 强制使用某个生物群系的气候（管理员自检用，传 null 恢复自动判断）。
     *
     * <p>没有这个入口就没法在「没有玩家的专用服务器」上验证各群系的行为 ——
     * 而玩家的所在群系正是本模组全部气候逻辑的输入。</p>
     */
    public void setForcedClimate(BiomeClimate climate) {
        this.resolvedForcedClimate = climate;
        this.lastForecastTick = -1_000_000L;
        this.lastApplyTick = -1_000_000L;
        this.forecast = null;
        if (climate != null) {
            this.lastBiomeId = climate.biomeId();
            this.lastClimate = climate;
        }
    }

    public BiomeClimate forcedClimate() {
        return resolvedForcedClimate;
    }

    /**
     * 按 id 解析群系气候。
     *
     * <p>注意必须走完整的 {@link BiomeClimateRegistry#resolve} 而不是直接取显式条目 ——
     * 否则会绕过 {@link com.nowweather.core.climate.ClimateModifier} 后处理链，
     * 季节联动之类的修正就静默失效了（这个坑是「真机验证季节联动」时抓到的：
     * 诊断命令显示的温度范围没有季节平移）。</p>
     */
    public BiomeClimate resolveBiomeById(String biomeId) {
        return climates().resolve(syntheticFacts(com.nowweather.core.util.Ids.normalize(biomeId)));
    }

    /**
     * 合成一组「生物群系事实」。
     *
     * <p>脱离世界上下文时用它走完整个解析链：显式配置优先，否则由关键词/原版温度兜底。
     * 基础温度取中性值 0.5，这样不会被错误的温度带偏 —— 真正在意精度的是那些有配置的群系。</p>
     */
    public static BiomeFacts syntheticFacts(String biomeId) {
        String id = com.nowweather.core.util.Ids.normalize(biomeId);
        boolean nether = id.contains("nether") || id.contains("crimson") || id.contains("warped")
                || id.contains("basalt") || id.contains("soul_sand");
        boolean end = id.contains("the_end") || id.contains("end_");
        String dimension = nether ? "minecraft:the_nether" : end ? "minecraft:the_end" : "minecraft:overworld";
        com.nowweather.core.climate.PrecipitationType precipitation =
                nether || end ? com.nowweather.core.climate.PrecipitationType.NONE
                        : com.nowweather.core.climate.PrecipitationType.MIXED;
        // 带上「合成」标签：显式气候表的一致性校验会跳过它（合成事实的温度是中性值）
        return new BiomeFacts(id, 0.5F, 0.5F, precipitation, "none",
                java.util.Set.of(BiomeClimateRegistry.SYNTHETIC_FACT_TAG), dimension,
                id.contains("cave") || id.contains("deep_dark"));
    }

    /** 自检用：按给定群系与观测算一次决策（纯函数，不改动任何状态）。 */
    public WeatherDecision decideFor(BiomeClimate climate, WeatherObservation observation, long tick) {
        return policy.decide(observation, climate, tick, platform.currentWeather(primaryDimension()));
    }

    /**
     * 自检用：把天气真正写进游戏并<b>回读验证</b>。
     *
     * @return 写入是否成功且回读结果与目标一致
     */
    public boolean applyAndVerify(String dimensionId, com.nowweather.core.api.PlatformWeather target,
                                  int durationTicks) {
        try {
            boolean applied = platform.applyWeather(dimensionId, target, Math.max(1, durationTicks),
                    config.clearDurationTicks());
            com.nowweather.core.api.PlatformWeather now = platform.currentWeather(dimensionId);
            return applied && now == target;
        } catch (RuntimeException e) {
            logger.warn(e, "自检写入天气失败: {}", target);
            return false;
        }
    }

    /**
     * 自检/诊断用：读取某个维度当前真实的平台天气。
     *
     * <p>命令层需要它来把「回读不一致」说清楚 —— 只报一句「失败」而不给出期望/实际，
     * 排查时毫无信息量。</p>
     */
    public com.nowweather.core.api.PlatformWeather currentWeather(String dimensionId) {
        return platform.currentWeather(dimensionId);
    }

    public BiomeClimateRegistry climates() {
        return climates;
    }

    /** 当前主维度（配置列表里的第一个）。 */
    public String primaryDimension() {
        return config.dimensions().isEmpty() ? "minecraft:overworld" : config.dimensions().get(0);
    }

    // ------------------------------------------------------------------ 外部接口

    /** 手动强制刷新（命令用）。 */
    public void requestRefresh(boolean force) {
        if (force) {
            nextFetchAllowedAt = 0L;
            consecutiveFailures = 0;
        }
        WeatherObservation current = observation.get();
        if (current != null && !force) {
            return;
        }
        nextFetchAllowedAt = 0L;
    }

    /** 手动设定天气（命令用）：把 manualOverride 打开，避免被自动刷新立刻覆盖。 */
    public void applyManualObservation(WeatherObservation manual) {
        observation.set(manual);
        manualOverride = true;
        nextFetchAllowedAt = Long.MAX_VALUE;
    }

    public void clearManualOverride() {
        manualOverride = false;
        nextFetchAllowedAt = 0L;
    }

    public boolean isManualOverride() {
        return manualOverride;
    }

    public WeatherObservation observation() {
        return observation.get();
    }

    public Forecast forecast() {
        return forecast;
    }

    public WeatherDecision lastDecision() {
        return lastDecision;
    }

    public ProviderResult lastProviderResult() {
        return lastProviderResult.get();
    }

    public String lastFailure() {
        return lastFailure.get();
    }

    public boolean isFetchInFlight() {
        return fetchInFlight.get();
    }

    public boolean isManual() {
        return manualOverride;
    }

    public SyncPolicy policy() {
        return policy;
    }

    public BiomeClimate climateFor(BiomeFacts facts) {
        return climates.resolve(facts);
    }

    /** 生成给客户端同步用的快照。 */
    public SyncPayload snapshot(String dimensionId, long serverTick) {
        WeatherDecision decision = lastDecision;
        return new SyncPayload(sequence, serverTick, dimensionId, observation.get(), forecast,
                lastClimate, decision == null ? PlatformWeather.CLEAR : decision.target(),
                decision == null ? "" : decision.reason(),
                decision != null && decision.climateAdjusted(),
                timeAnchor(dimensionId),
                fetchNote(),
                realCloudCover(),
                realVisibility());
    }

    /**
     * 当前时刻的真实云量（0~1）；都没有时返回 NaN。
     *
     * <p>优先级：<b>实况观测自带的云量</b>（彩云天气的 {@code cloudrate}，雷达/卫星反演、分钟级刷新）
     * &gt; 逐小时天色预报的插值值（Open-Meteo）。实况永远比预报更贴近「此刻」，
     * 而阴天仿真吃的就是这个值。</p>
     *
     * <p>公开给命令用：{@code /nowweather info shaders} 要把「真实云量 → 写入 rainLevel →
     * 光影解出的云量 → 天色亮度」整条链路摆出来，玩家才能判断天色对不对，
     * 而不是只能凭感觉猜。</p>
     */
    public double realCloudCover() {
        WeatherObservation current = observation.get();
        if (current != null && !Double.isNaN(current.cloudCover01())) {
            return current.cloudCover01();
        }
        var sky = skyForecast;
        return sky == null || sky.isEmpty() ? Double.NaN : sky.cloudCoverAt(System.currentTimeMillis());
    }

    /** 当前时刻的真实能见度（km）；没有真实预报时返回 NaN。 */
    private double realVisibility() {
        var sky = skyForecast;
        return sky == null || sky.isEmpty() ? Double.NaN : sky.visibilityAt(System.currentTimeMillis());
    }

    /**
     * 给客户端看的取数状态说明：现在是离线推算时，告诉玩家「为什么」以及「还有多久重试」。
     *
     * <p>空字符串表示真实数据一切正常。</p>
     */
    public String fetchNote() {
        WeatherObservation current = observation.get();
        boolean offline = current != null && OFFLINE_PROVIDER_ID.equals(current.providerId());
        String failure = lastFailure.get();
        if (!offline && failure.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (offline) {
            sb.append(Text.tr("nowweather.fetch.offline"));
        }
        if (!failure.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(Text.tr("nowweather.list.separator_semicolon"));
            }
            sb.append(Text.tr("nowweather.fetch.last_failure", failure));
        }
        long retryIn = nextFetchAllowedAt - System.currentTimeMillis();
        if (retryIn > 0L) {
            sb.append(Text.tr("nowweather.list.separator_semicolon"))
                    .append(Text.tr("nowweather.fetch.retry_in", Long.toString(Math.max(1L, retryIn / 1000L))));
        } else if (fetchInFlight.get()) {
            sb.append(Text.tr("nowweather.list.separator_semicolon"))
                    .append(Text.tr("nowweather.fetch.in_flight"));
        }
        return sb.toString();
    }

    /**
     * 构造「真实时间 → 世界刻」锚点。
     *
     * <p>客户端据此每帧自行推算世界刻，从而让天空跟着真实时间平滑走 ——
     * 而不是等每秒一次的校正包、在两次校正之间被原版日光循环带跑再被拉回来。</p>
     */
    private SyncPayload.TimeAnchor timeAnchor(String dimensionId) {
        if (!config.timeSyncEnabled() || timeSyncModel == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return new SyncPayload.TimeAnchor(now,
                timeSyncModel.compute(now, timeZoneService.effectiveOffsetSeconds()));
    }

    /** 最近一次解析出的生物群系气候。 */
    public BiomeClimate lastClimate() {
        return lastClimate;
    }

    // ------------------------------------------------------------------ 诊断

    /** 多行状态报告（{@code /NowWeather status}）。 */
    public String statusReport(String dimensionId, BiomeFacts facts) {
        StringBuilder sb = new StringBuilder();
        sb.append(Text.tr("nowweather.status.header")).append('\n');
        sb.append(Text.tr("nowweather.status.version_line",
                        com.nowweather.core.NowWeatherCore.VERSION,
                        config.enabled() ? Text.tr("nowweather.status.enabled")
                                : Text.tr("nowweather.status.disabled")))
                .append('\n');
        StringBuilder location = new StringBuilder(config.city().isEmpty()
                ? Text.tr("nowweather.status.no_city") : config.city());
        if (!config.adcode().isEmpty()) {
            location.append(" / adcode=").append(config.adcode());
        }
        sb.append(Text.tr("nowweather.status.config_location", location.toString())).append('\n');

        WeatherObservation obs = observation.get();
        if (obs == null) {
            sb.append(Text.tr("nowweather.status.no_data"));
            if (!lastFailure.get().isEmpty()) {
                sb.append(Text.tr("nowweather.status.in_parens", lastFailure.get()));
            }
            sb.append('\n');
        } else {
            sb.append(Text.tr("nowweather.status.actual_weather"))
                    .append(obs.conditionText().isEmpty() ? obs.weatherType().zhName() : obs.conditionText())
                    .append(Text.tr("nowweather.status.observation",
                            String.format(Locale.ROOT, "%.1f", obs.temperatureC()),
                            String.format(Locale.ROOT, "%.0f", obs.humidityPercent()),
                            com.nowweather.core.weather.WindScale.describe(obs.windScale())))
                    .append('\n');
            sb.append(Text.tr("nowweather.status.source_line", obs.providerId(),
                            obs.observedAtDisplay(),
                            obs.stale() ? Text.tr("nowweather.status.yes") : Text.tr("nowweather.status.no"),
                            manualOverride ? Text.tr("nowweather.status.yes") : Text.tr("nowweather.status.no")))
                    .append('\n');
            if (OFFLINE_PROVIDER_ID.equals(obs.providerId())) {
                sb.append(Text.tr("nowweather.status.offline_note",
                        fetchNote().isEmpty() ? Text.tr("nowweather.status.trying") : fetchNote()))
                        .append('\n');
            }
        }

        if (facts != null) {
            BiomeClimate climate = climates.resolve(facts);
            sb.append(Text.tr("nowweather.status.biome", climate.biomeId(),
                            climate.climateClass().zhName()))
                    .append('\n');
            sb.append(Text.tr("nowweather.status.climate_line",
                            climate.tempRange().describe(),
                            climate.precipitation().zhName(),
                            String.format(Locale.ROOT, "%.2f", climate.humidity()),
                            climate.source(),
                            climate.confidence().zhName()))
                    .append('\n');
        }

        WeatherDecision decision = lastDecision;
        if (decision != null) {
            sb.append(Text.tr("nowweather.status.decision", decision.describe())).append('\n');
            sb.append(Text.tr("nowweather.status.decision_reason", decision.reason())).append('\n');
        }

        Forecast fc = forecast;
        if (fc != null && !fc.isEmpty()) {
            sb.append(Text.tr("nowweather.status.forecast_header", fc.climateSummary())).append('\n');
            int limit = Math.min(6, fc.size());
            for (int i = 0; i < limit; i++) {
                sb.append("  §7- §f").append(fc.entries().get(i).describe()).append('\n');
            }
            if (fc.size() > limit) {
                sb.append(Text.tr("nowweather.status.forecast_more", Integer.toString(fc.size() - limit)))
                        .append('\n');
            }
        }
        sb.append(Text.tr("nowweather.status.climate_db",
                        Integer.toString(climates.explicitCount()),
                        Integer.toString(climates.tagRuleCount()),
                        Integer.toString(climates.cachedCount())))
                .append('\n');
        sb.append(Text.tr("nowweather.status.providers"));
        if (providers.isEmpty()) {
            sb.append(Text.tr("nowweather.status.no_providers"));
        } else {
            providers.all().forEach(p -> sb.append(Text.tr("nowweather.status.provider_entry",
                    p.id(), p.displayName(), Integer.toString(p.priority()))));
        }
        return sb.toString();
    }

    /** 生成一份可序列化的诊断包（写日志 / 让玩家上报问题用）。 */
    public com.nowweather.core.json.JsonObject diagnostics(String dimensionId, BiomeFacts facts) {
        var json = new com.nowweather.core.json.JsonObject();
        json.put("version", com.nowweather.core.NowWeatherCore.VERSION);
        json.put("dimension", dimensionId);
        json.put("manualOverride", manualOverride);
        json.put("fetchInFlight", fetchInFlight.get());
        json.put("consecutiveFailures", consecutiveFailures);
        json.put("lastFailure", lastFailure.get());
        if (observation.get() != null) {
            json.put("observation", observation.get().toJson());
        }
        if (lastProviderResult.get() != null) {
            json.put("lastProviderResult", lastProviderResult.get().toJson());
        }
        if (forecast != null) {
            json.put("forecast", forecast.toJson());
        }
        if (facts != null) {
            json.put("climate", climates.resolve(facts).toJson());
            json.put("biomeFacts", facts.describe());
        }
        var providerList = new com.nowweather.core.json.JsonArray();
        providers.all().forEach(p -> {
            var o = new com.nowweather.core.json.JsonObject();
            o.put("id", p.id());
            o.put("name", p.displayName());
            o.put("priority", p.priority());
            o.put("providesLocation", p.providesLocation());
            providerList.add(o);
        });
        json.put("providers", providerList);
        return json;
    }
}

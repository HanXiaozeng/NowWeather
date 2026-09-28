package com.nowweather.core.runtime;

import com.nowweather.core.api.PlatformWeatherAccess;
import com.nowweather.core.api.WeatherBindingRegistry;
import com.nowweather.core.api.WeatherProviderRegistry;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.http.JdkHttpTransport;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.providers.uapipro.UApiProWeatherProvider;
import com.nowweather.core.sync.ServerTickContext;
import com.nowweather.core.sync.SyncPayload;
import com.nowweather.core.sync.WeatherController;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行时装配根 —— 把所有子系统拼起来，加载器只需要构造一次然后在 tick 里调用 {@link #tick}。
 *
 * <pre>{@code
 * NowWeatherRuntime runtime = NowWeatherRuntime.builder()
 *         .config(NowWeatherConfig.defaults().setCity("杭州"))
 *         .platform(myForgePlatformAccess)
 *         .logger(myLogger)
 *         .build();
 * runtime.tick(serverTickContext);
 * }</pre>
 */
public final class NowWeatherRuntime implements AutoCloseable {

    private final NowWeatherConfig config;
    private final SyncLogger logger;
    private final HttpTransport transport;
    private final WeatherProviderRegistry providers;
    private final BiomeClimateRegistry climates;
    private final WeatherBindingRegistry bindings;
    private final PlatformWeatherAccess platform;
    private final WeatherController controller;
    private final ExecutorService ioExecutor;
    private final boolean ownsIoExecutor;

    private NowWeatherRuntime(Builder builder) {
        this.config = builder.config;
        this.logger = builder.logger;
        this.transport = builder.transport != null ? builder.transport : new JdkHttpTransport("NowWeather/"
                + com.nowweather.core.NowWeatherCore.VERSION + " (Minecraft Mod)", java.time.Duration.ofSeconds(10));
        this.providers = new WeatherProviderRegistry();
        this.climates = new BiomeClimateRegistry(logger);
        this.bindings = new WeatherBindingRegistry();
        this.platform = builder.platform;
        this.ownsIoExecutor = builder.ioExecutor == null;
        this.ioExecutor = builder.ioExecutor != null
                ? builder.ioExecutor
                : Executors.newSingleThreadExecutor(new DaemonThreadFactory("NowWeather-IO"));

        builder.providers.forEach(providers::register);
        builder.bindings.forEach(bindings::register);

        com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder builtGeocoder = null;
        if (builder.registerDefaultProviders) {
            // 默认注册三个数据源，按优先级形成回退链。
            // ★ 排序是升序（sort(comparingInt(priority))），所以「数字小的先试」：
            //   彩云       ( 80) 最先 —— 雷达实测降水、1km 分辨率、自带云量与时区；需要坐标
            //   Open-Meteo ( 90) 其次 —— 全球覆盖、WMO 标准代码；需要城市名或坐标
            //   UApiPro    (100) 兜底 —— 能按 IP 自动定位，是「完全没有位置信息时」的最后一道
            // 别把数字写反：数字大的是最后才试的。
            providers.register(new com.nowweather.core.providers.caiyun.CaiyunWeatherProvider(
                    transport, config.caiyunEndpoint()));
            com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder geocoder =
                    new com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder(transport);
            providers.register(new com.nowweather.core.providers.openmeteo.OpenMeteoWeatherProvider(
                    transport, config.openMeteoEndpoint(), config.openMeteoGeocoding(),
                    com.nowweather.core.providers.openmeteo.OpenMeteoWeatherProvider.DEFAULT_REFRESH_MILLIS,
                    geocoder));
            builtGeocoder = geocoder;
            providers.register(new UApiProWeatherProvider(transport, config.endpoint(), true, true));
        }

        this.controller = new WeatherController(config, providers, climates, bindings, platform, logger, ioExecutor);
        if (builtGeocoder != null) {
            this.controller.attachGeocoder(builtGeocoder);
        }
        // 真实天色预报（逐小时云量 / 能见度）用独立客户端：失败只影响视觉，不影响天气主链路
        this.controller.attachSkyForecastClient(
                new com.nowweather.core.providers.openmeteo.OpenMeteoSkyForecastClient(
                        transport, config.openMeteoEndpoint()));
        if (builder.cacheDirectory != null) {
            this.controller.attachCache(new com.nowweather.core.cache.WeatherCache(builder.cacheDirectory, logger));
        }
        this.logger.info("NowWeather 核心已装配：{} 个数据源，{} 个联动，城市={}",
                providers.all().size(), bindings.all().size(), config.city().isEmpty() ? "（按 IP 定位）" : config.city());
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ 访问器

    public NowWeatherConfig config() {
        return config;
    }

    public SyncLogger logger() {
        return logger;
    }

    public HttpTransport transport() {
        return transport;
    }

    public WeatherProviderRegistry providers() {
        return providers;
    }

    public BiomeClimateRegistry climates() {
        return climates;
    }

    public WeatherBindingRegistry bindings() {
        return bindings;
    }

    public PlatformWeatherAccess platform() {
        return platform;
    }

    public WeatherController controller() {
        return controller;
    }

    public ExecutorService ioExecutor() {
        return ioExecutor;
    }

    // ------------------------------------------------------------------ 运行

    /** 主循环异常计数与最后一次报错时间（同一类异常不刷屏）。 */
    private int tickErrors;
    private long lastTickErrorLogAt;

    public void tick(ServerTickContext context) {
        try {
            controller.tick(context);
        } catch (RuntimeException e) {
            tickErrors++;
            long now = System.currentTimeMillis();
            // 主循环每 5 刻跑一次，不加节流会把日志刷爆；同类错误每分钟最多报一次
            if (now - lastTickErrorLogAt > 60_000L) {
                lastTickErrorLogAt = now;
                logger.error(e, "NowWeather 主循环发生异常（已忽略，不影响服务端运行；累计 {} 次）", tickErrors);
            }
        }
    }

    public SyncPayload snapshot(String dimensionId, long serverTick) {
        return controller.snapshot(dimensionId, serverTick);
    }

    /**
     * 热重载配置：把新配置的值原地拷进当前配置实例，并重建依赖配置的策略/预报引擎。
     *
     * <p>这样「游戏内改完配置点 Done」就能立即生效，不需要重启服务器。</p>
     */
    public void applyConfig(NowWeatherConfig fresh) {
        if (fresh == null) {
            return;
        }
        config.copyFrom(fresh);
        controller.reloadConfig();
    }

    public void onServerStopping(ServerTickContext context) {
        controller.onServerStopping(context);
    }

    /** 载入内置 / 数据包内的气候数据。 */
    public BiomeClimateRegistry.LoadReport loadClimateJson(String source, String jsonText) {
        var parsed = com.nowweather.core.json.JsonValue.parseOr(jsonText, null);
        if (parsed == null) {
            logger.warn("气候数据 {} 解析失败（不是合法 JSON）", source);
            return null;
        }
        BiomeClimateRegistry.LoadReport report = climates.loadJson(source, parsed);
        logger.info("{}", report);
        return report;
    }

    @Override
    public void close() {
        if (!ownsIoExecutor) {
            // 外部传入的执行器由调用方负责关闭
            return;
        }
        ioExecutor.shutdownNow();
        try {
            ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ Builder

    public static final class Builder {
        private NowWeatherConfig config = NowWeatherConfig.defaults();
        private SyncLogger logger = com.nowweather.core.log.Loggers.console(SyncLogger.Level.INFO);
        private HttpTransport transport;
        private PlatformWeatherAccess platform;
        private ExecutorService ioExecutor;
        private boolean registerDefaultProviders = true;
        private final List<com.nowweather.core.api.WeatherProvider> providers = new java.util.ArrayList<>();
        private final List<com.nowweather.core.api.WeatherBinding> bindings = new java.util.ArrayList<>();

        public Builder config(NowWeatherConfig config) {
            if (config != null) {
                this.config = config;
            }
            return this;
        }

        public Builder logger(SyncLogger logger) {
            if (logger != null) {
                this.logger = logger;
            }
            return this;
        }

        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        private java.nio.file.Path cacheDirectory;

        /**
         * 设置本地缓存目录（客户端建议 {@code .minecraft/weather/nowweather/}，
         * 服务端建议 {@code <服务器目录>/weather/nowweather/}）。
         */
        public Builder cacheDirectory(java.nio.file.Path directory) {
            this.cacheDirectory = directory;
            return this;
        }

        public Builder platform(PlatformWeatherAccess platform) {
            this.platform = platform;
            return this;
        }

        public Builder ioExecutor(ExecutorService executor) {
            this.ioExecutor = executor;
            return this;
        }

        public Builder registerDefaultProviders(boolean register) {
            this.registerDefaultProviders = register;
            return this;
        }

        public Builder provider(com.nowweather.core.api.WeatherProvider provider) {
            providers.add(provider);
            return this;
        }

        public Builder binding(com.nowweather.core.api.WeatherBinding binding) {
            bindings.add(binding);
            return this;
        }

        public NowWeatherRuntime build() {
            if (platform == null) {
                throw new IllegalStateException("必须提供 PlatformWeatherAccess 实现");
            }
            return new NowWeatherRuntime(this);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String name;
        private final AtomicInteger counter = new AtomicInteger();

        private DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

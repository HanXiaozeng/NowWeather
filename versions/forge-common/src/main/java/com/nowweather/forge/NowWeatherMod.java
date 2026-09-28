package com.nowweather.forge;

import com.nowweather.core.api.PlayerHandle;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.core.sync.ServerTickContext;
import com.nowweather.core.sync.SyncPayload;
import com.nowweather.forge.config.ConfigBridge;
import com.nowweather.forge.config.ForgeConfig;
import com.nowweather.forge.data.ClimateDataReloader;
import com.nowweather.forge.platform.ForgeBiomeFactsFactory;
import com.nowweather.forge.platform.ForgeTerrainSampler;
import com.nowweather.forge.platform.ForgeModPresence;
import com.nowweather.forge.platform.ForgePlatformAccess;
import com.nowweather.forge.platform.ForgePlayerHandle;
import com.nowweather.forge.command.NowWeatherCommand;
import com.nowweather.core.NowWeatherCore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * NowWeather 主类 —— Forge 与核心之间的全部接线都在这里。
 *
 * <p>它做四件事：</p>
 * <ol>
 *   <li>注册配置（{@code ForgeConfigSpec}）→ 让玩家能在「模组 → NowWeather → 配置」里改 API Key 与所有功能；</li>
 *   <li>服务端 tick 时把「世界此刻的样子」打包成 {@link ServerTickContext} 交给核心；</li>
 *   <li>把核心产出的真实天气 / 预报同步给客户端；</li>
 *   <li>载入气候数据、注册命令、处理与其它天气模组的共存。</li>
 * </ol>
 */
@Mod(NowWeatherCore.MOD_ID)
public final class NowWeatherMod {

    public static final String MOD_ID = NowWeatherCore.MOD_ID;

    /** 检测到这些模组在控制天气时，默认让出控制权（可在配置里改）。 */
    private static final List<String> DEFAULT_YIELD_MODS = List.of(
            "projectatmosphere", "weather2", "dynamicweather", "dynamic_weather",
            "weatherstormsandtornadoes", "epidemic_weather");

    private static NowWeatherRuntime runtime;

    // ---- 服务端 tick 用的缓存，避免每 tick 都去查注册表 ----
    private static BiomeFacts cachedFacts;
    private static long cachedFactsTick = -10_000L;
    private static BlockPos cachedFactsPos;
    private static String cachedFactsDimension = "";
    private static long lastSentSequence = -1L;
    private static long lastSentTick = -10_000L;
    private static boolean yieldAnnounced;

    public NowWeatherMod() {
        // 0) 安装「翻译缝」：core 侧的 Text.tr(...) 全靠它才能拿到当前语言的译文。
        //    必须放在最前面 —— 下面注册配置、读配置文案都可能立刻用到它。
        com.nowweather.forge.util.ForgeText.install();

        // 1) 配置：COMMON 与 CLIENT 都可以在游戏内直接编辑
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.COMMON_SPEC,
                "nowweather-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ForgeConfig.CLIENT_SPEC,
                "nowweather-client.toml");

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onConfigLoading);
        modBus.addListener(this::onConfigReloading);

        MinecraftForge.EVENT_BUS.register(this);

        // 2) 网络通道
        com.nowweather.forge.net.NowWeatherNetwork.register();

        // 3) 客户端专用初始化（配置界面 / HUD）
        //
        // 注意两件事（Forge 1.18.2 的实际行为，已用 Forge 源码与字节码核对）：
        //   1. 1.18.2 的 Forge **不会**为注册了 ForgeConfigSpec 的模组自动生成配置界面 ——
        //      没有注册 ConfigGuiFactory 时，「Mods → Config」按钮是灰的（ModListScreen.java:400）。
        //      所以下面这个界面不是锦上添花，而是「能在游戏内改配置」的唯一途径。
        //   2. 客户端专用类必须在 DistExecutor 的客户端分支里才加载，
        //      否则专用服务器会直接崩在 NoClassDefFoundError 上。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.nowweather.forge.client.NowWeatherClientSetup.init());

        logVersionCrossCheck();
        NowWeatherLog.LOGGER.info("NowWeather {} 已加载（Minecraft 1.18.x / Forge）", displayVersion());
    }

    /**
     * 显示用的版本号：优先读 {@code mods.toml} 里的构建版本（即 {@code gradle.properties}
     * 的 {@code mod_version}，形如 {@code 26b39a}，其中 b = build），读不到时退回核心里的常量。
     *
     * <p>这样版本号只需要在 {@code gradle.properties} 维护一处，日志 / 聊天面板 / 配置界面
     * 不会因为忘记同步而各说各话。</p>
     */
    public static String displayVersion() {
        // ★ 用编译期常量，而不是 Forge 解析出来的版本对象。
        // 原因：ModList 里的版本是 Forge 用 VersionParser 解析 mods.toml 之后的对象，
        // toString() 未必等于原始字符串（实测面板上显示成了另一个值，导致「明明装的是
        // 26b39a，界面却显示别的版本」这种查不出所以然的怪事）。
        // NowWeatherCore.VERSION 由构建脚本与 gradle.properties 同步，是精确值。
        return NowWeatherCore.VERSION;
    }

    /** Forge 解析出来的版本；与常量不一致时打一条日志，便于发现构建脚本漏改。 */
    private static void logVersionCrossCheck() {
        try {
            String parsed = net.minecraftforge.fml.ModList.get()
                    .getModContainerById(NowWeatherCore.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
            if (!parsed.isEmpty() && !parsed.equals(NowWeatherCore.VERSION)) {
                NowWeatherLog.LOGGER.warn("版本号不一致：Forge 解析为 {}，编译期常量为 {}（请检查构建脚本是否漏改 NowWeatherCore.VERSION）",
                        parsed, NowWeatherCore.VERSION);
            }
        } catch (RuntimeException ignored) {
            // 检查失败无所谓
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        NowWeatherConfig config = ConfigBridge.toCoreConfig();
        applyCompatibilitySwitches(config);

        NowWeatherRuntime previous = runtime;
        if (previous != null) {
            previous.close();
        }
        // 本地缓存目录：客户端在 .minecraft/weather/nowweather/，服务端在 <服务器目录>/weather/nowweather/
        java.nio.file.Path cacheDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("weather").resolve("nowweather");
        runtime = NowWeatherRuntime.builder()
                .config(config)
                .logger(NowWeatherLog.INSTANCE)
                .platform(new ForgePlatformAccess(server))
                .cacheDirectory(cacheDir)
                .build();
        com.nowweather.core.api.NowWeatherApi.install(runtime);

        // 载入气候数据（内置 + 数据包 + 配置目录）
        ClimateDataReloader.reload(runtime, server.getResourceManager());

        // 季节联动：Serene Seasons 在场时把「正常温度范围」按季节平移（走 ClimateModifier 后处理扩展点）
        if (config.seasonIntegration()) {
            var seasonal = com.nowweather.forge.compat.SereneSeasonsClimateModifier.createIfAvailable(
                    ForgeModPresence.INSTANCE, config.seasonAmplitude());
            if (seasonal != null) {
                runtime.climates().addModifier(seasonal);
                NowWeatherLog.LOGGER.info("{}", seasonal.describe());
            }
        }

        NowWeatherLog.LOGGER.info("NowWeather 已启动：{}", describeLocation(config));
        yieldAnnounced = false;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        NowWeatherRuntime current = runtime;
        if (current != null && server != null) {
            current.onServerStopping(new ServerTickContext("minecraft:overworld", 0L, 0L, 0L,
                    List.of(), null, ForgeModPresence.INSTANCE, ConfigBridge.bindingSettings()));
            current.close();
        }
        runtime = null;
        com.nowweather.core.api.NowWeatherApi.uninstall();
    }

    // ------------------------------------------------------------------ 主循环

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        NowWeatherRuntime current = runtime;
        if (current == null) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // 每 5 刻（0.25 秒）跑一次：天气变化远慢于这个频率，没必要每刻都算
        if (server.getTickCount() % 5 != 0) {
            return;
        }

        ServerTickContext context = buildContext(server, current);
        if (context == null) {
            return;
        }
        current.tick(context);
        maybeSyncToClients(server, current, context);
        driveWeather2(server, current, context);
    }

    /**
     * 用真实/推算天气主动指挥 Weather2 生成风暴。
     *
     * <p>只有装了 Weather2、且 {@code compat.weather2Drive} 打开时才做。
     * 判据与冷却都在 {@link com.nowweather.forge.compat.Weather2Intervention} 里，
     * 这里只负责把「当前观测 + 位置 + 群系是否干旱」交给它。</p>
     */
    private void driveWeather2(MinecraftServer server, NowWeatherRuntime current,
                               ServerTickContext context) {
        if (!ForgeConfig.COMMON.weather2Drive.get()
                || !com.nowweather.forge.compat.Weather2Intervention.canDrive()) {
            return;
        }
        com.nowweather.core.weather.WeatherObservation observation = current.controller().observation();
        if (observation == null) {
            return;
        }
        var anchor = context.players().isEmpty() ? null : context.players().get(0);
        ServerLevel level = server.getLevel(ForgeBiomeFactsFactory.dimensionKey(context.dimensionId()));
        if (level == null || anchor == null) {
            return;
        }
        BlockPos pos = null;
        try {
            pos = level.getSharedSpawnPos();
        } catch (RuntimeException ignored) {
            return;
        }
        com.nowweather.core.climate.BiomeClimate climate = current.controller().lastClimate();
        boolean dry = climate != null
                && climate.climateClass() == com.nowweather.core.climate.ClimateClass.ARID;
        boolean canPrecipitate = climate == null || climate.canPrecipitate();
        com.nowweather.forge.compat.Weather2Intervention.maybeSpawn(level, pos, observation,
                canPrecipitate, dry);
    }

    /** 把「世界此刻的样子」打包给核心。 */
    private ServerTickContext buildContext(MinecraftServer server, NowWeatherRuntime current) {
        List<String> dimensions = current.config().dimensions();
        String dimensionId = dimensions.isEmpty() ? "minecraft:overworld" : dimensions.get(0);
        ServerLevel level = server.getLevel(ForgeBiomeFactsFactory.dimensionKey(dimensionId));
        if (level == null) {
            return null;
        }

        List<ServerPlayer> online = new ArrayList<>(server.getPlayerList().getPlayers());
        ServerPlayer anchor = online.stream()
                .filter(p -> p.getLevel() == level)
                .min(Comparator.comparing(p -> p.getName().getString()))
                .orElse(null);
        BiomeFacts facts = anchor == null ? null : factsFor(level, anchor);
        String anchorBiome = facts == null ? "" : facts.id();
        // 地形画像：真实天气是「查询坐标」的，玩家可能站在高原/雪山上，需要订正
        com.nowweather.core.terrain.TerrainProfile terrain = anchor == null
                ? com.nowweather.core.terrain.TerrainProfile.NONE
                : ForgeTerrainSampler.sample(level, anchor.blockPosition());

        List<PlayerHandle> players = online.stream()
                .map(p -> (PlayerHandle) new ForgePlayerHandle(p, p == anchor ? anchorBiome : ""))
                .toList();

        return new ServerTickContext(
                dimensionId,
                level.getGameTime(),
                level.dayTime(),
                level.getSeed(),
                players,
                facts,
                ForgeModPresence.INSTANCE,
                ConfigBridge.bindingSettings(),
                terrain);
    }

    /** 带缓存的生物群系读取：同一位置 1 秒内只查一次注册表。 */
    private BiomeFacts factsFor(ServerLevel level, ServerPlayer player) {
        long now = level.getGameTime();
        BlockPos pos = player.blockPosition();
        String dimension = level.dimension().location().toString();
        if (cachedFacts != null
                && now - cachedFactsTick < 20L
                && dimension.equals(cachedFactsDimension)
                && cachedFactsPos != null
                && cachedFactsPos.distSqr(pos) < 64.0D) {
            return cachedFacts;
        }
        BiomeFacts facts = ForgeBiomeFactsFactory.biomeAt(level, pos);
        if (facts != null) {
            cachedFacts = facts;
            cachedFactsTick = now;
            cachedFactsPos = pos;
            cachedFactsDimension = dimension;
        }
        return facts;
    }

    // ------------------------------------------------------------------ 客户端同步

    private void maybeSyncToClients(MinecraftServer server, NowWeatherRuntime current,
                                    ServerTickContext context) {
        if (!current.config().syncToClients() || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        int tick = server.getTickCount();
        SyncPayload payload = current.snapshot(context.dimensionId(), tick);
        boolean changed = payload.sequence() != lastSentSequence;
        boolean heartbeat = tick - lastSentTick > 400; // 每 20 秒兜底同步一次
        if (!changed && !heartbeat) {
            return;
        }
        com.nowweather.forge.net.NowWeatherNetwork.sendToAll(server, payload);
        lastSentSequence = payload.sequence();
        lastSentTick = tick;
    }

    /** 给某个玩家单独发一份最新快照（进服、或客户端主动请求时用）。 */
    public static void sendSnapshotTo(ServerPlayer player) {
        NowWeatherRuntime current = runtime;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (current == null || server == null || player == null) {
            return;
        }
        String dimensionId = current.config().dimensions().isEmpty()
                ? "minecraft:overworld" : current.config().dimensions().get(0);
        com.nowweather.forge.net.NowWeatherNetwork.sendTo(player,
                current.snapshot(dimensionId, server.getTickCount()));
    }

    // ------------------------------------------------------------------ 事件接线

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NowWeatherCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayer serverPlayer) {
            // 稍微延后一点，避免和登录流程抢带宽
            serverPlayer.getServer().execute(() -> sendSnapshotTo(serverPlayer));
        }
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new com.nowweather.forge.data.ClimateReloadListener());
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(MOD_ID)) {
            NowWeatherLog.LOGGER.debug("配置已加载：{}", event.getConfig().getFileName());
        }
    }

    /**
     * 让模组立刻用上当前的配置值。
     *
     * <p><b>为什么需要它</b>：读 Forge 1.18.2 源码确认，
     * {@code ConfigValue#set()} 只写内存配置、{@code save()} 只把文件写盘，
     * <b>两者都不会发出 {@code ModConfigEvent.Reloading}</b> ——
     * 那个事件由文件监视器在发现「外部改动」时发出，时序不可依赖。
     * 游戏内配置界面与 {@code /nowweather config} 命令都调用本方法，
     * 保证「点了保存/执行了命令就一定生效」。</p>
     */
    public static void reloadConfigNow() {
        NowWeatherRuntime current = runtime;
        if (current == null) {
            return;
        }
        NowWeatherConfig fresh = ConfigBridge.toCoreConfig();
        applyCompatibilitySwitches(fresh);
        current.applyConfig(fresh);
        NowWeatherLog.LOGGER.info("配置已热重载：{}", describeLocation(fresh));
    }

    /** ★ 游戏内改完配置后立即生效，不需要重启。 */
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (!event.getConfig().getModId().equals(MOD_ID)) {
            return;
        }
        reloadConfigNow();
    }

    // ------------------------------------------------------------------ 兼容处理

    /**
     * 与其它天气模组共存：检测到它们在控制天气时，NowWeather 只提供数据、不下场指挥。
     *
     * <p>原则是「谁在管天气就让谁管」—— 硬抢只会让整合包出现两套天气系统互相打架的情况。</p>
     */
    private static void applyCompatibilitySwitches(NowWeatherConfig config) {
        boolean yield = false;
        try {
            if (ForgeConfig.COMMON.yieldToOtherWeatherMods.get()) {
                List<String> ids = new ArrayList<>(ConfigBridge.yieldToModIds());
                if (ids.isEmpty()) {
                    ids.addAll(DEFAULT_YIELD_MODS);
                }
                for (String modId : ids) {
                    if (ForgeModPresence.INSTANCE.isLoaded(modId)) {
                        config.setApplyWeather(false);
                        if (!yieldAnnounced) {
                            NowWeatherLog.LOGGER.info(
                                    "检测到天气模组 {} 正在控制天气：NowWeather 只提供真实天气数据，"
                                            + "不修改游戏天气（可在配置里关闭该让权行为）", modId);
                            yieldAnnounced = true;
                        }
                        yield = true;
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            yield = false;
        }
        if (!yield) {
            config.setApplyWeather(true);
        }
    }

    private static String describeLocation(NowWeatherConfig config) {
        String location = !config.adcode().isBlank() ? "adcode=" + config.adcode()
                : !config.city().isBlank() ? "城市=" + config.city() : "按 IP 定位";
        return String.format(Locale.ROOT, "位置[%s]，数据源[%s]，接管维度%s，天气落地[%s]",
                location, config.providerId(), config.dimensions(),
                config.applyWeather() ? "开启" : "关闭（让权/观察模式）");
    }

    public static NowWeatherRuntime runtime() {
        return runtime;
    }
}

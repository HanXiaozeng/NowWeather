package com.nowweather.core.test;

import com.nowweather.core.api.BindingContext;
import com.nowweather.core.api.ModPresence;
import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.api.PlatformWeatherAccess;
import com.nowweather.core.api.PlayerHandle;
import com.nowweather.core.api.WeatherBinding;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.core.sync.ServerTickContext;
import com.nowweather.core.sync.SyncPayload;
import com.nowweather.core.sync.WeatherController;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 端到端集成测试：真实天气 → 气候校验 → MC 天气落地 → 客户端同步 → 出站联动。 */
final class IntegrationTests {

    private IntegrationTests() {
    }

    static void run() {
        manualPipeline();
        asyncFetchPipeline();
        offlineFallbackIsNotFreshData();
        ipLocationActuallyFetches();
        desertBlocksRain();
        bindingDispatch();
        clientPayloadRoundTrip();
        statusReport();
        disabledSwitch();
        netherAndEndUntouched();
        intensityKeepsBeingWritten();
    }

    /**
     * ★ 回归防线：强度写入「收敛之后仍必须每刻继续写」。
     *
     * <p>曾经的 bug：{@code applyWeatherIntensity} 在「这次算出的值和上次完全一样」时直接
     * {@code return}，而原版 {@code ServerLevel.advanceWeatherCycle()} 每刻都会把
     * {@code rainLevel} 朝 0/1 推 0.01 —— 于是我们收敛停写之后，写进去的值会被原版慢慢拉走。
     * 客户端侧 {@code ShaderSkyBridge} 也曾有同款「只在值变化时才写」的写法，
     * 结果是光影的 {@code rainStrength} 一秒内被拉回 0、云彩消失、天色亮得刺眼，
     * 表现为「天气根本不自动同步」。这条断言就是钉住它不再退化。</p>
     */
    private static void intensityKeepsBeingWritten() {
        T.section("连续强度写入：收敛之后不得停写");
        FakePlatform platform = new FakePlatform();
        try (NowWeatherRuntime runtime = newRuntime(platform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.RAIN).conditionText("中雨")
                    .temperatureC(20.0D).humidityPercent(70.0D).windScale(3).build());
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");

            // 400 刻足够让 0.01/刻 的逼近收敛到目标值（中雨 0.55，约 55 刻）
            for (int i = 1; i <= 400; i++) {
                runtime.tick(context(i, plains));
            }
            int converged = platform.intensityCount.get();
            T.check("收敛前持续写入强度", converged > 0);

            // 收敛之后再跑 20 刻：必须继续写，否则就是旧 bug 回来了
            for (int i = 401; i <= 420; i++) {
                runtime.tick(context(i, plains));
            }
            T.eq("收敛后每刻仍在写（20 刻 = 20 次）", 20, platform.intensityCount.get() - converged);
        }
    }

    /** ★ 下界 / 末地 / 虚空必须完全保持原版：不是「少动」，是根本不写。 */
    private static void netherAndEndUntouched() {
        T.section("下界 / 末地 / 虚空保持原版");

        // 故意让当前天气是「下雨」，再看模组会不会去改它
        FakePlatform platform = new FakePlatform();
        platform.weather = PlatformWeather.RAIN;
        try (NowWeatherRuntime runtime = newRuntime(platform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.THUNDERSTORM).conditionText("雷阵雨")
                    .temperatureC(40.0D).humidityPercent(10.0D).windScale(3).build());

            // 1) 下界群系
            BiomeFacts nether = facts("minecraft:nether_wastes", 2.0F, 0.0F, PrecipitationType.NONE, "nether");
            runtime.tick(context(100L, nether));
            T.eq("下界群系：不写入任何天气", 0, platform.applyCount.get());
            T.eq("下界群系：保持原有天气", PlatformWeather.RAIN.name(), platform.weather.name());
            T.check("下界群系：决策持续时间为 0（表示不改动）",
                    runtime.controller().lastDecision().durationTicks() == 0);
            T.check("下界群系：决策理由里说明了原因",
                    runtime.controller().lastDecision().reason().contains("保持原版"));

            // 2) 末地群系
            BiomeFacts end = facts("minecraft:the_end", 0.5F, 0.0F, PrecipitationType.NONE, "the_end");
            runtime.tick(context(200L, end));
            T.eq("末地群系：不写入任何天气", 0, platform.applyCount.get());

            // 3) 对照组：同样一份观测灌到平原上，必须真的写入
            platform.weather = PlatformWeather.CLEAR;
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(400L, plains));
            T.check("对照组：平原群系会被正常写入", platform.applyCount.get() > 0);
            T.eq("对照组：雷阵雨 → 雷暴", PlatformWeather.THUNDER.name(), platform.weather.name());
        }
    }

    private static void manualPipeline() {
        T.section("端到端：手动观测 → 决策 → 落地");
        FakePlatform platform = new FakePlatform();
        try (NowWeatherRuntime runtime = newRuntime(platform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.HEAVY_RAIN)
                    .conditionText("大雨")
                    .temperatureC(14.0D)
                    .humidityPercent(90.0D)
                    .windScale(5)
                    .city("杭州")
                    .build());

            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(1000L, plains));

            T.eq("平原大雨 → 降雨", PlatformWeather.RAIN.name(), platform.weather.name());
            T.check("已向游戏落地天气", platform.applyCount.get() > 0);
            // 「依据」现在刻意做得短：面板上「真实天气 / 群系气候 / 已落地」已各自成行，
            // 依据只讲面板上没有的东西 —— 为什么被修正、修正成了什么。
            String reason = runtime.controller().lastDecision().reason();
            T.check("决策依据是短句（≤ 40 字，不重复面板已有的信息）",
                    reason.length() <= 40);
            T.check("依据里不再复述「真实天气」", !reason.contains("真实天气"));
            T.check("依据里不再复述「本地气候」", !reason.contains("本地气候"));
            T.check("依据不含长句辩解", !reason.contains("原版只有晴"));
            T.check("生成了预报", runtime.controller().forecast() != null
                    && runtime.controller().forecast().size() > 0);
            T.eq("预报绑定的群系", "minecraft:plains", runtime.controller().forecast().biomeId());
            T.check("手动覆盖标志生效", runtime.controller().isManual());
        }
    }

    private static void asyncFetchPipeline() {
        T.section("端到端：异步拉取 UApiPro → 落地");
        FakePlatform platform = new FakePlatform();
        T.FakeTransport transport = T.fakeTransport(ProviderTests.SAMPLE_RESPONSE);
        try (NowWeatherRuntime runtime = newRuntime(platform, transport)) {
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(0L, plains));

            boolean fetched = await(() -> runtime.controller().observation() != null
                    && "uapipro".equals(runtime.controller().observation().providerId()), 3000L);
            T.check("异步拉取到 UApiPro 数据", fetched);
            T.check("确实发出了 HTTP 请求", transport.callCount() >= 1);
            if (!transport.requestedUrls().isEmpty()) {
                T.check("请求 URL 带上了城市参数",
                        transport.requestedUrls().get(0).contains("city="));
            }

            WeatherObservation obs = runtime.controller().observation();
            T.check("观测数据非空", obs != null);
            if (obs != null) {
                T.eq("观测来源", "uapipro", obs.providerId());
                T.eq("观测天气", WeatherType.CLEAR.name(), obs.weatherType().name());
                T.check("观测温度 30°C", Math.abs(obs.temperatureC() - 30.0D) < 1.0E-9);
            }

            // 晴朗的真实天气在平原上应该落地为晴
            runtime.tick(context(200L, plains));
            T.eq("晴天观测 → 晴朗", PlatformWeather.CLEAR.name(), platform.weather.name());
            // 不再断言「总共只有 1 次请求」——取数成功后还可能多一次「地理编码补全坐标」的请求。
            // 真正要保证的是：缓存没过期之前，后续 tick 不会<b>再新增</b>任何请求。
            int callsAfterFirstFetch = transport.callCount();
            runtime.tick(context(400L, plains));
            runtime.tick(context(600L, plains));
            T.check("缓存过期前不会重复拉取（后续 tick 无新增请求）",
                    transport.callCount() == callsAfterFirstFetch);
        }
    }

    /**
     * 回归用例：离线推算<b>不能</b>被当成「刚取到的真实数据」。
     *
     * <p>曾经的 bug：离线推算的 fetchedAt 就是生成时的时刻，于是
     * {@code isOlderThan(刷新间隔)} 为 false —— 一旦首刻取数失败（或定位尚未就绪），
     * 接下来整整一个刷新周期（默认 20 分钟）都不会再尝试任何一次真实取数，
     * 面板上就永远卡着「来源: offline / xxx（推算）」，而用户去测 API 又是完全正常的。</p>
     */
    private static void offlineFallbackIsNotFreshData() {
        T.section("端到端：离线推算不得压住真实取数");
        FakePlatform platform = new FakePlatform();
        // 让数据源返回 5xx：这次取数必定失败，于是回落到离线推算
        T.FakeTransport transport = T.fakeTransport("{}").status(500, "boom");
        try (NowWeatherRuntime runtime = newRuntime(platform, transport)) {
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(0L, plains));

            WeatherObservation obs = runtime.controller().observation();
            T.check("取数失败时回落到离线推算", obs != null
                    && WeatherController.OFFLINE_PROVIDER_ID.equals(obs.providerId()));
            // 取数在 ioExecutor 上异步执行，必须等一下再断言（原来直接断言是竞态）
            T.check("确实发出了请求", await(() -> transport.callCount() >= 1, 3000L));

            // 离线推算必须给出「为什么」，否则玩家只看到一个 offline 无从判断
            T.check("离线推算会说明原因", !runtime.controller().fetchNote().isEmpty());
            boolean explained = await(() -> runtime.controller().fetchNote().contains("失败"), 3000L);
            T.check("说明里带上上次失败原因", explained);

            // 重试窗口：离线的观测绝不能宣称自己「还新鲜」而跳过取数。
            // 这里直接验证「刷新间隔内的离线观测仍然算需要数据」——
            // 通过再 tick 一次后应很快出现重试迹象（倒计时或请求中）来体现。
            runtime.tick(context(400L, plains));
            String note = runtime.controller().fetchNote();
            T.check("取数失败后仍在重试（倒计时/请求中）",
                    note.contains("重试") || note.contains("请求中"));
        }

        T.section("端到端：真实数据正常时没有取数提示");
        FakePlatform okPlatform = new FakePlatform();
        try (NowWeatherRuntime runtime = newRuntime(okPlatform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(0L, plains));
            boolean fetched = await(() -> runtime.controller().observation() != null
                    && "uapipro".equals(runtime.controller().observation().providerId()), 3000L);
            T.check("真实数据取到了", fetched);
            T.check("真实数据正常时取数提示为空", runtime.controller().fetchNote().isEmpty());
        }
    }

    /**
     * 回归用例：没手填城市/坐标时，「按 IP 定位」必须真的能发请求。
     *
     * <p>历史 bug：{@code hasLocationProvider()} 要求数据源 {@code providesLocation() && !requiresNetwork()}。
     * 可凭请求方 IP 定位的 UApiPro 天然要联网，于是条件恒为假 ——
     * 只要玩家没填城市/坐标，控制器就提前返回，<b>一次 API 都不发</b>，
     * 面板上永远显示 offline，而玩家自己去测 API 又完全正常。</p>
     */
    private static void ipLocationActuallyFetches() {
        T.section("端到端：没填城市时「按 IP 定位」必须真的去请求");
        FakePlatform platform = new FakePlatform();
        T.FakeTransport transport = T.fakeTransport(ProviderTests.SAMPLE_RESPONSE);
        // 完全复刻出问题的配置：城市 / adcode / 坐标都留空
        NowWeatherConfig config = NowWeatherConfig.defaults()
                .setCity("")
                .setAdcode("")
                .setLatitude(Double.NaN)
                .setLongitude(Double.NaN)
                .setUseFirstPlayerLocation(true);
        try (NowWeatherRuntime runtime = newRuntime(platform, transport, config)) {
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(0L, plains));

            boolean requested = await(() -> transport.callCount() >= 1, 3000L);
            T.check("没填城市时仍然发出了天气请求（按 IP 定位）", requested);

            boolean fetched = await(() -> runtime.controller().observation() != null
                    && "uapipro".equals(runtime.controller().observation().providerId()), 3000L);
            T.check("按 IP 定位后拿到真实数据", fetched);
            T.check("拿到真实数据后取数提示为空", runtime.controller().fetchNote().isEmpty());
            // ★ 回归：IP 定位解析出来的位置必须落进「地点画像」——
            // 这是 Open-Meteo（需要经纬度、且精度更高）能接手的位置来源。
            // 之前 buildProviderContext 只读 config，画像里的城市永远喂不进 ProviderContext，
            // 导致 Open-Meteo 每次 NO_LOCATION 被跳过，只能一直用 UApiPro。
            T.check("按 IP 定位后地点画像已填充（Open-Meteo 接手的前提）",
                    !runtime.controller().locationProfile().city().isBlank());
        }
    }

    private static void desertBlocksRain() {
        T.section("端到端：真实下雨 vs 沙漠气候");
        FakePlatform platform = new FakePlatform();
        try (NowWeatherRuntime runtime = newRuntime(platform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.THUNDERSTORM)
                    .conditionText("雷阵雨")
                    .temperatureC(28.0D)
                    .humidityPercent(85.0D)
                    .windScale(6)
                    .city("杭州")
                    .build());

            BiomeFacts desert = facts("minecraft:desert", 2.0F, 0.0F, PrecipitationType.NONE, "desert");
            runtime.tick(context(500L, desert));

            T.eq("沙漠里真实下雨被拦下 → 晴朗", PlatformWeather.CLEAR.name(), platform.weather.name());
            T.check("决策标记为已依气候修正", runtime.controller().lastDecision().climateAdjusted());
            T.check("预报中不含降水", runtime.controller().forecast().entries().stream()
                    .noneMatch(e -> e.weatherType().isPrecipitation()));

            // 同一份真实天气，换成丛林就应该真的下雨
            FakePlatform junglePlatform = new FakePlatform();
            try (NowWeatherRuntime jungleRuntime = newRuntime(junglePlatform,
                    T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
                jungleRuntime.controller().applyManualObservation(WeatherObservation.builder("manual")
                        .weatherType(WeatherType.THUNDERSTORM)
                        .conditionText("雷阵雨")
                        .temperatureC(28.0D)
                        .humidityPercent(85.0D)
                        .windScale(6)
                        .city("杭州")
                        .build());
                BiomeFacts jungle = facts("minecraft:jungle", 0.95F, 0.9F, PrecipitationType.RAIN, "jungle");
                jungleRuntime.tick(context(500L, jungle));
                T.eq("丛林里雷阵雨 → 雷暴", PlatformWeather.THUNDER.name(), junglePlatform.weather.name());
            }
        }
    }

    private static void bindingDispatch() {
        T.section("端到端：出站联动派发");
        FakePlatform platform = new FakePlatform();
        List<String> events = new ArrayList<>();
        WeatherBinding binding = new WeatherBinding() {
            @Override
            public String id() {
                return "test_binding";
            }

            @Override
            public String targetModId() {
                return "testmod";
            }

            @Override
            public boolean isAvailable(ModPresence presence) {
                return true;
            }

            @Override
            public void onWeatherChanged(BindingContext context, PlatformWeather applied,
                                         WeatherObservation observation) {
                events.add("changed:" + applied.name() + ":" + (context.climate() == null
                        ? "no-climate" : context.climate().biomeId()));
            }
        };

        NowWeatherConfig config = NowWeatherConfig.defaults().setCity("杭州");
        try (NowWeatherRuntime runtime = NowWeatherRuntime.builder()
                .config(config)
                .transport(T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))
                .platform(platform)
                .logger(Loggers.noop())
                .binding(binding)
                .build()) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.RAIN).temperatureC(16.0D).humidityPercent(80.0D).build());
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(100L, plains));
            T.eq("联动收到 1 次天气变更", 1, events.size());
            T.check("联动事件包含目标天气", !events.isEmpty() && events.get(0).startsWith("changed:RAIN"));
            T.check("联动上下文带上了群系气候", !events.isEmpty() && events.get(0).contains("minecraft:plains"));
        }
    }

    private static void clientPayloadRoundTrip() {
        T.section("端到端：客户端同步包");
        FakePlatform platform = new FakePlatform();
        try (NowWeatherRuntime runtime = newRuntime(platform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.SNOW).conditionText("中雪").temperatureC(-4.0D)
                    .humidityPercent(80.0D).windScale(4).city("哈尔滨").build());
            BiomeFacts snowy = facts("minecraft:snowy_plains", 0.0F, 0.5F, PrecipitationType.SNOW, "icy");
            runtime.tick(context(3000L, snowy));

            SyncPayload payload = runtime.snapshot("minecraft:overworld", 3000L);
            String json = payload.toJsonString();
            SyncPayload restored = SyncPayload.fromJsonString(json);

            T.eq("同步包序号", payload.sequence(), restored.sequence());
            T.eq("同步包维度", "minecraft:overworld", restored.dimensionId());
            T.eq("同步包天气", payload.applied().name(), restored.applied().name());
            T.check("同步包带观测", restored.observation() != null);
            T.check("同步包带预报", restored.forecast() != null && !restored.forecast().isEmpty());
            T.check("同步包带气候", restored.climate() != null);
            T.eq("同步包气候群系", "minecraft:snowy_plains", restored.climate().biomeId());
            T.eq("雪原种雪 → 降雨档", PlatformWeather.RAIN.name(), platform.weather.name());
            T.check("同步包字节可用", payload.toBytes().length > 100);
        }
    }

    private static void statusReport() {
        T.section("端到端：状态报告与诊断");
        FakePlatform platform = new FakePlatform();
        try (NowWeatherRuntime runtime = newRuntime(platform, T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))) {
            runtime.controller().applyManualObservation(WeatherObservation.builder("manual")
                    .weatherType(WeatherType.CLOUDY).conditionText("多云").temperatureC(20.0D)
                    .humidityPercent(60.0D).windScale(3).build());
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(100L, plains));

            String report = runtime.controller().statusReport("minecraft:overworld", plains);
            T.check("状态报告含版本", report.contains(com.nowweather.core.NowWeatherCore.VERSION));
            T.check("状态报告含正常温度范围", report.contains("正常温度范围"));
            T.check("状态报告含预报", report.contains("预报"));
            T.check("状态报告含数据源", report.contains("uapipro"));

            JsonObject diag = runtime.controller().diagnostics("minecraft:overworld", plains);
            T.check("诊断包含观测", diag.has("observation"));
            T.check("诊断包含气候", diag.has("climate"));
            T.check("诊断包含数据源列表", diag.has("providers"));
            // 默认注册三个数据源：彩云（雷达实况）+ Open-Meteo（全球）+ UApiPro（可按 IP 定位）
            T.eq("诊断数据源数量（彩云 + Open-Meteo + UApiPro）", 3, diag.optArray("providers").size());
            T.check("诊断包含 彩云", diag.toJson().contains("caiyun"));
            T.check("诊断包含 Open-Meteo", diag.toJson().contains("openmeteo"));
            T.check("诊断包含 UApiPro", diag.toJson().contains("uapipro"));

            // ★ 排序是升序，数字小的先试 —— 所以「优先级最高」= 最小数字。
            // 曾经把彩云写成 110（想当然以为数字大 = 优先），结果它被排到最后，与需求正好相反。
            T.check("彩云优先于 Open-Meteo（数字更小）",
                    com.nowweather.core.providers.caiyun.CaiyunWeatherProvider.PRIORITY
                            < com.nowweather.core.providers.openmeteo.OpenMeteoWeatherProvider.PRIORITY);
            T.check("Open-Meteo 优先于 UApiPro（数字更小）",
                    com.nowweather.core.providers.openmeteo.OpenMeteoWeatherProvider.PRIORITY
                            < com.nowweather.core.providers.uapipro.UApiProWeatherProvider.PRIORITY);
        }
    }

    private static void disabledSwitch() {
        T.section("端到端：总开关");
        FakePlatform platform = new FakePlatform();
        NowWeatherConfig config = NowWeatherConfig.defaults().setCity("杭州").setEnabled(false);
        try (NowWeatherRuntime runtime = NowWeatherRuntime.builder()
                .config(config)
                .transport(T.fakeTransport("{}"))
                .platform(platform)
                .logger(Loggers.noop())
                .build()) {
            BiomeFacts plains = facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains");
            runtime.tick(context(0L, plains));
            T.eq("关闭时不动天气", 0, platform.applyCount.get());
            T.check("关闭时无观测", runtime.controller().observation() == null);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static NowWeatherRuntime newRuntime(FakePlatform platform, T.FakeTransport transport) {
        return newRuntime(platform, transport, NowWeatherConfig.defaults().setCity("北京"));
    }

    private static NowWeatherRuntime newRuntime(FakePlatform platform, T.FakeTransport transport,
                                                NowWeatherConfig config) {
        return NowWeatherRuntime.builder()
                .config(config)
                .transport(transport)
                .platform(platform)
                .logger(Loggers.noop())
                .build();
    }

    private static BiomeFacts facts(String id, float baseTemperature, float downfall,
                                    PrecipitationType precipitation, String category) {
        return new BiomeFacts(id, baseTemperature, downfall, precipitation, category, Set.of(),
                "minecraft:overworld", false);
    }

    private static ServerTickContext context(long tick, BiomeFacts facts) {
        return new ServerTickContext("minecraft:overworld", tick, tick % 24000L, 12345L,
                List.of(new FakePlayer("Steve", facts.id())), facts, ModPresence.NONE, new JsonObject());
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 假的游戏天气接口：把「落地」的天气记录下来，便于断言。 */
    static final class FakePlatform implements PlatformWeatherAccess {
        PlatformWeather weather = PlatformWeather.CLEAR;
        final AtomicInteger applyCount = new AtomicInteger();
        int lastDuration = -1;
        /** 连续强度写入的次数与最后一次的值（回归防线用：收敛后不得停写）。 */
        final AtomicInteger intensityCount = new AtomicInteger();
        float lastIntensityRain = Float.NaN;
        float lastIntensityThunder = Float.NaN;

        @Override
        public boolean setWeatherIntensity(String dimensionId, float rain, float thunder) {
            this.lastIntensityRain = rain;
            this.lastIntensityThunder = thunder;
            intensityCount.incrementAndGet();
            return true;
        }

        @Override
        public PlatformWeather currentWeather(String dimensionId) {
            return weather;
        }

        @Override
        public boolean applyWeather(String dimensionId, PlatformWeather target, int durationTicks,
                                   int clearDurationTicks) {
            this.weather = target;
            this.lastDuration = durationTicks;
            applyCount.incrementAndGet();
            return true;
        }

        @Override
        public long totalWorldTime(String dimensionId) {
            return 0L;
        }

        @Override
        public long dayTime(String dimensionId) {
            return 6000L;
        }

        @Override
        public long worldSeed() {
            return 12345L;
        }
    }

    /** 假的玩家。 */
    static final class FakePlayer implements PlayerHandle {
        private final String name;
        private final String biomeId;
        final List<String> messages = new ArrayList<>();

        FakePlayer(String name, String biomeId) {
            this.name = name;
            this.biomeId = biomeId;
        }

        @Override
        public UUID id() {
            return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String dimensionId() {
            return "minecraft:overworld";
        }

        @Override
        public String biomeId() {
            return biomeId;
        }

        @Override
        public double x() {
            return 0;
        }

        @Override
        public double y() {
            return 64;
        }

        @Override
        public double z() {
            return 0;
        }

        @Override
        public void sendMessage(String text) {
            messages.add(text);
        }
    }
}

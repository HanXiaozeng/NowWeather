package com.nowweather.core.test;

import com.nowweather.core.api.ModPresence;
import com.nowweather.core.api.PlatformWeatherAccess;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.core.sync.ServerTickContext;
import com.nowweather.core.sync.SyncPayload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * NowWeather 性能基准 —— <b>只测 core</b>（纯 Java，不含任何 Minecraft 代码）。
 *
 * <p>用法（由 tools/bench.ps1 调用）：
 * <pre>
 *   javac -cp &lt;核心自测输出目录&gt; -d &lt;out&gt; tools/bench/Bench.java
 *   java -cp &lt;out&gt;:&lt;核心自测输出目录&gt; com.nowweather.core.test.Bench
 * </pre>
 *
 * <p>这里<b>不包含</b> Forge 图层的地形采样（读高度图）与网络 IO —— 那两项在报告里单独估算。</p>
 */
public final class Bench {

    private static final int WARMUP_TICKS = 5_000;
    private static final int MEASURE_TICKS = 200_000;

    private Bench() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================ NowWeather 性能基准 ================");
        System.out.printf(Locale.ROOT, "JVM %s / %s %s / %d 核%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());

        long heapBase = usedHeap();
        NowWeatherRuntime runtime = buildRuntime();
        int biomes = loadClimateDataset(runtime);
        long heapAfterDataset = usedHeap();

        System.out.printf(Locale.ROOT, "%n【1】气候数据集%n");
        System.out.printf(Locale.ROOT, "    加载群系条目 : %d 条%n", biomes);
        System.out.printf(Locale.ROOT, "    常驻堆内存   : %.2f MB%n", (heapAfterDataset - heapBase) / 1048576.0D);

        BiomeFacts plains = new BiomeFacts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN,
                "plains", Set.of(), "minecraft:overworld", false);
        ServerTickContext context = new ServerTickContext("minecraft:overworld", 0L, 6000L, 12345L,
                List.of(new IntegrationTests.FakePlayer("Steve", plains.id())), plains,
                ModPresence.NONE, new JsonObject());

        // 先跑一遍确认链路真的在工作（否则基准测的是「提前返回」的空路径）
        runtime.tick(context);
        for (int i = 0; i < 200; i++) {
            runtime.tick(advance(context, i));
        }
        String provider = runtime.controller().observation() == null
                ? "（无）" : runtime.controller().observation().providerId();
        System.out.printf(Locale.ROOT, "%n【2】核心 tick（服务端每 5 刻调用一次）%n");
        System.out.printf(Locale.ROOT, "    观测数据源   : %s%n", provider);
        System.out.printf(Locale.ROOT, "    预报         : %s%n",
                runtime.controller().forecast() == null ? "无" : runtime.controller().forecast().size() + " 格");

        long start = System.nanoTime();
        for (int i = 0; i < WARMUP_TICKS; i++) {
            runtime.tick(advance(context, 1000 + i));
        }
        long warmNanos = System.nanoTime() - start;
        System.out.printf(Locale.ROOT, "    预热 %d 次   : %.1f µs/次%n",
                WARMUP_TICKS, warmNanos / 1000.0D / WARMUP_TICKS);

        start = System.nanoTime();
        for (int i = 0; i < MEASURE_TICKS; i++) {
            runtime.tick(advance(context, 100_000 + i));
        }
        long tickNanos = System.nanoTime() - start;
        double perTickUs = tickNanos / 1000.0D / MEASURE_TICKS;
        System.out.printf(Locale.ROOT, "    实测 %d 次 : %.2f µs/次（中位数量级）%n", MEASURE_TICKS, perTickUs);
        System.out.printf(Locale.ROOT, "    → 折算 1 秒 20 刻的占用：%.3f ms/s（每 5 刻跑一次，故 ×4）%n",
                perTickUs * 4.0D / 1000.0D);
        System.out.printf(Locale.ROOT, "    → 占一个 tick(50ms) 的比例：%.5f%%%n", perTickUs * 4.0D / 50_000.0D * 100.0D);

        // 客户端每帧的「真实时间锚点外推」
        SyncPayload.TimeAnchor anchor = new SyncPayload.TimeAnchor(System.currentTimeMillis(), 18000L);
        long sink = 0L;
        for (int i = 0; i < 100_000; i++) {
            sink += anchor.dayTimeAt(System.currentTimeMillis() + i);
        }
        start = System.nanoTime();
        for (int i = 0; i < 5_000_000; i++) {
            sink += anchor.dayTimeAt(System.currentTimeMillis() + i);
        }
        long anchorNanos = System.nanoTime() - start;
        System.out.printf(Locale.ROOT, "%n【3】客户端每帧的时间锚点外推（含 System.currentTimeMillis）%n");
        System.out.printf(Locale.ROOT, "    实测 : %.1f ns/次（60 FPS 时每秒 60 次）%n", anchorNanos / 5_000_000.0D);

        // 群系气候解析（每次 tick 都会走一次）
        start = System.nanoTime();
        BiomeClimate climate = null;
        for (int i = 0; i < 1_000_000; i++) {
            climate = runtime.climates().resolve(plains);
        }
        long resolveNanos = System.nanoTime() - start;
        System.out.printf(Locale.ROOT, "%n【4】群系气候解析（每次 tick 一次）%n");
        System.out.printf(Locale.ROOT, "    解析结果 : %s / 正常 %s%n",
                climate == null ? "null" : climate.climateClass().zhName(),
                climate == null ? "-" : climate.tempRange().describe());
        System.out.printf(Locale.ROOT, "    实测 : %.1f ns/次%n", resolveNanos / 1_000_000.0D);

        long heapEnd = usedHeap();
        System.out.printf(Locale.ROOT, "%n【5】跑完 %d 次 tick 后的常驻堆%n", WARMUP_TICKS + MEASURE_TICKS);
        System.out.printf(Locale.ROOT, "    相对启动增量 : %.2f MB%n", (heapEnd - heapBase) / 1048576.0D);
        System.out.printf(Locale.ROOT, "    全进程当前   : %.2f MB%n", heapEnd / 1048576.0D);
        System.out.println("====================================================");
        if (sink == Long.MIN_VALUE) {
            System.out.println(sink); // 防止 JIT 把上面的循环优化掉
        }
        runtime.close();
    }

    private static ServerTickContext advance(ServerTickContext base, long tick) {
        return new ServerTickContext(base.dimensionId(), tick, tick % 24000L, base.worldSeed(),
                base.players(), base.biomeFacts(), base.presence(), base.bindingSettings(), base.terrain());
    }

    private static NowWeatherRuntime buildRuntime() {
        NowWeatherConfig config = NowWeatherConfig.defaults().setCity("北京");
        return NowWeatherRuntime.builder()
                .config(config)
                .transport(T.fakeTransport(ProviderTests.SAMPLE_RESPONSE))
                .platform(new IntegrationTests.FakePlatform())
                .logger(Loggers.noop())
                .build();
    }

    /** 把真正打包进模组的 6 份气候 JSON 装进注册表，返回条目总数。 */
    private static int loadClimateDataset(NowWeatherRuntime runtime) throws Exception {
        Path dir = Path.of("versions/forge-common/src/main/resources/data/nowweather/climate");
        if (!Files.isDirectory(dir)) {
            System.out.println("    （找不到气候数据目录，跳过加载：" + dir.toAbsolutePath() + "）");
            return 0;
        }
        int entries = 0;
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                var report = runtime.climates().loadJson(file.getFileName().toString(), JsonValue.parse(text));
                entries += report.biomes();
                System.out.printf(Locale.ROOT, "    %-34s 群系 %3d 标签 %2d 分类 %2d%n",
                        report.source(), report.biomes(), report.tags(), report.categories());
            }
        }
        return entries;
    }

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(30L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}

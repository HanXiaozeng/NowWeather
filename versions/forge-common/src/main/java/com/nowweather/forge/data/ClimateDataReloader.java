package com.nowweather.forge.data;

import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.forge.NowWeatherLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 气候数据载入器 —— 支持三级覆盖，优先级从低到高：
 *
 * <ol>
 *   <li>模组内置数据 {@code data/nowweather/climate/*.json}（打包在 jar 里）</li>
 *   <li>玩家数据包 {@code data/nowweather/climate/*.json}（放在存档的 datapacks 里即可覆盖/追加）</li>
 *   <li>配置目录 {@code config/NowWeather/climate/*.json}（服务器管理员最容易改的位置，优先级最高）</li>
 * </ol>
 *
 * <p>以 {@code _} 开头的文件会被跳过 —— 那是生成器的输入源文件，不是给游戏加载的数据。</p>
 */
public final class ClimateDataReloader {

    private static final String CLIMATE_DIR = "climate";
    private static final SyncLogger LOG = NowWeatherLog.INSTANCE.withPrefix("气候数据");

    private ClimateDataReloader() {
    }

    /**
     * 气候数据的载入结果汇总（命令输出 / 日志用）。
     */
    public record ReloadSummary(int files, int biomes, int tags, int categories) {

        public int total() {
            return biomes + tags + categories;
        }

        @Override
        public String toString() {
            return files + " 个文件 / 生物群系 " + biomes + " 条 / 标签规则 " + tags
                    + " 条 / 分类规则 " + categories + " 条";
        }
    }

    /** 重新载入全部气候数据（会先清空注册表）。 */
    public static ReloadSummary reload(NowWeatherRuntime runtime, ResourceManager manager) {
        if (runtime == null) {
            return null;
        }
        BiomeClimateRegistry registry = runtime.climates();
        registry.clear();

        int files = 0;
        int biomes = 0;
        int tags = 0;
        int categories = 0;

        // ---- 1 & 2：jar 内置 + 玩家数据包（ResourceManager 已经帮我们做了优先级合并）----
        for (ResourceLocation location : listClimateFiles(manager)) {
            String text = readResource(manager, location);
            if (text == null) {
                continue;
            }
            BiomeClimateRegistry.LoadReport report = load(registry, location.toString(), text);
            if (report != null) {
                files++;
                biomes += report.biomes();
                tags += report.tags();
                categories += report.categories();
            }
        }

        // ---- 3：配置目录（优先级最高，最后载入即可覆盖前面的）----
        for (Path path : listConfigFiles()) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                BiomeClimateRegistry.LoadReport report = load(registry, "config/" + path.getFileName(), text);
                if (report != null) {
                    files++;
                    biomes += report.biomes();
                    tags += report.tags();
                    categories += report.categories();
                }
            } catch (IOException e) {
                LOG.warn(e, "读取配置目录气候文件失败: {}", path);
            }
        }

        ReloadSummary summary = new ReloadSummary(files, biomes, tags, categories);
        LOG.info("气候数据载入完成：{}", summary);
        if (biomes == 0) {
            LOG.warn("没有载入到任何生物群系气候数据！所有群系都会走「关键词 + 原版温度」启发式推断。");
        }
        return summary;
    }

    private static BiomeClimateRegistry.LoadReport load(BiomeClimateRegistry registry, String source,
                                                        String text) {
        try {
            JsonValue parsed = JsonValue.parse(text);
            return registry.loadJson(source, parsed);
        } catch (RuntimeException e) {
            LOG.warn(e, "气候数据 {} 解析失败，已跳过", source);
            return null;
        }
    }

    /** 列出数据包 / jar 内 {@code data/nowweather/climate/} 下的全部 json（跳过 {@code _} 开头的文件）。 */
    private static List<ResourceLocation> listClimateFiles(ResourceManager manager) {
        List<ResourceLocation> result = new ArrayList<>();
        try {
            // 注意：1.18.2 的 listResources 第二个参数是「路径字符串」谓词，不是 ResourceLocation 谓词
            Collection<ResourceLocation> found = manager.listResources(CLIMATE_DIR, path -> {
                String name = path.substring(path.lastIndexOf('/') + 1);
                return path.endsWith(".json") && !name.startsWith("_");
            });
            result.addAll(found);
        } catch (RuntimeException e) {
            LOG.warn(e, "枚举内置气候数据失败");
        }
        result.sort(Comparator.comparing(ResourceLocation::toString));
        return result;
    }

    private static String readResource(ResourceManager manager, ResourceLocation location) {
        try {
            java.util.List<Resource> resources = manager.getResources(location);
            if (resources.isEmpty()) {
                return null;
            }
            Resource resource = resources.get(0);
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn(e, "读取气候数据 {} 失败", location);
            return null;
        }
    }

    private static List<Path> listConfigFiles() {
        List<Path> files = new ArrayList<>();
        Path dir = FMLPaths.CONFIGDIR.get().resolve(com.nowweather.core.NowWeatherCore.MOD_ID)
                .resolve(CLIMATE_DIR);
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            LOG.warn(e, "枚举配置目录气候文件失败: {}", dir);
        }
        return files;
    }

    /** 把内置数据释放到配置目录，方便服主直接改（命令 /NowWeather dump 也会用到）。 */
    public static int dumpBuiltinToConfig() {
        int written = 0;
        Path dir = FMLPaths.CONFIGDIR.get().resolve(com.nowweather.core.NowWeatherCore.MOD_ID)
                .resolve(CLIMATE_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn(e, "创建配置目录失败: {}", dir);
            return 0;
        }
        // 导出全部「运行时真正会加载」的内置数据集，口径与 listClimateFiles 完全一致：
        // 除下面这 5 份之外，就只剩生成器输入 _vanilla_source_1.18.2.json，
        // 它以 `_` 开头，既不加载也不导出。
        // 以前这里只列了 3 份（漏了 terralith / tectonic），服主 dump 不到它们的气候数据。
        String[] builtin = {
                "vanilla_1.18.1-1.18.2.json",
                "vanilla_later_1.19-26.x.json",
                "biomesoplenty_16.0.0.134.json",
                "terralith_2.2.6.json",
                "tectonic_2.3.5a.json"
        };
        for (String name : builtin) {
            Path target = dir.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = ClimateDataReloader.class.getResourceAsStream(
                    "/data/nowweather/climate/" + name)) {
                if (in == null) {
                    continue;
                }
                Files.write(target, in.readAllBytes());
                written++;
            } catch (IOException e) {
                LOG.warn(e, "导出 {} 失败", name);
            }
        }
        if (written > 0) {
            LOG.info("已把 {} 个内置气候数据文件导出到 {}，可直接编辑（改完用 /NowWeather reload 生效）",
                    written, dir);
        }
        return written;
    }
}

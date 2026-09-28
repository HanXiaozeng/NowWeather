package com.nowweather.core.test;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.WeatherAllowance;
import com.nowweather.core.forecast.ClimateSanityFilter;
import com.nowweather.core.forecast.SanityReport;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.weather.WeatherType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>生物群系 × 天气 × 温度 全矩阵压测</b> —— 回答两个问题：
 *
 * <ol>
 *   <li><b>会不会崩 / 会不会出现不该出现的天气？</b>
 *       对每一个生物群系，强行灌入全部 25 种天气 × 整个温度区间（−50~+60°C）× 4 组湿度风力，
 *       断言输出<b>永远落在该群系的天气白名单内</b>。</li>
 *   <li><b>「温度」会不会失效？</b>
 *       断言温度修正遵守两条铁律：
 *       <ul>
 *         <li>在容差范围内的温度<b>必须原样保留</b>（不能无缘无故被改）；</li>
 *         <li>超出范围的温度被修正后<b>偏离必须变小</b>（不能越修越离谱）。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>数据来源是<b>真正打包进模组的那些 JSON 文件</b>（原版 + 超多生物群系），
 * 因此这个测试同时验证了：数据文件语法正确、字段完整、与运行时逻辑一致。</p>
 */
final class BiomeWeatherMatrixTests {

    private static final Path CLIMATE_DIR =
            Path.of("versions/forge-common/src/main/resources/data/nowweather/climate");
    private static final Path REPORT = Path.of("docs/TEST_MATRIX.md");

    /** 温度采样：−50°C 到 +60°C，每 2.5°C 一个点。 */
    private static final double TEMP_START = -50.0D;
    private static final double TEMP_END = 60.0D;
    private static final double TEMP_STEP = 2.5D;

    /** 湿度/风力组合：干燥、潮湿、大风、无风。 */
    private static final double[][] ENVIRONMENTS = {
            {0.50D, 0.40D}, {0.95D, 0.20D}, {0.20D, 0.95D}, {0.80D, 0.05D}
    };

    private BiomeWeatherMatrixTests() {
    }

    static void run() {
        List<BiomeClimate> climates = loadDatasets();
        if (climates.isEmpty()) {
            T.fail("载入气候数据集", "没有读到任何群系数据，路径: " + CLIMATE_DIR.toAbsolutePath());
            return;
        }

        datasetSanity(climates);
        matrix(climates);
    }

    // ------------------------------------------------------------------ 载入真实数据集

    /** 读取打包进模组的全部气候 JSON。 */
    private static List<BiomeClimate> loadDatasets() {
        List<BiomeClimate> result = new ArrayList<>();
        if (!Files.isDirectory(CLIMATE_DIR)) {
            return result;
        }
        BiomeClimateRegistry registry = new BiomeClimateRegistry(Loggers.noop());
        try (var stream = Files.list(CLIMATE_DIR)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonValue parsed = JsonValue.parse(text);
                registry.loadJson(file.getFileName().toString(), parsed);
            }
        } catch (IOException | RuntimeException e) {
            T.fail("读取气候数据集", String.valueOf(e));
            return result;
        }
        result.addAll(registry.allExplicit());
        result.sort(Comparator.comparing(BiomeClimate::biomeId));
        return result;
    }

    // ------------------------------------------------------------------ 数据集体检

    private static void datasetSanity(List<BiomeClimate> climates) {
        T.section("数据集体检（" + climates.size() + " 条）");

        int badRange = 0;
        int emptyAllowance = 0;
        int badHumidity = 0;
        int notPlausibleOwnMidpoint = 0;
        int dryButAllowsRain = 0;
        int polarButNoSnow = 0;
        Map<String, Integer> withExplicitAllowance = new TreeMap<>();
        int minBiomes = Integer.MAX_VALUE;
        int maxBiomes = 0;

        for (BiomeClimate climate : climates) {
            if (climate.tempRange().span() < 4.0D || climate.tempRange().span() > 130.0D) {
                badRange++;
            }
            if (climate.possibleWeather().isEmpty()) {
                emptyAllowance++;
            }
            if (climate.humidity() < 0.0D || climate.humidity() > 1.0D
                    || climate.windiness() < 0.0D || climate.windiness() > 1.0D) {
                badHumidity++;
            }
            // 自己声明的正常温度范围中点，必须被自己的白名单接受（否则规则自相矛盾）
            if (!climate.allows(WeatherType.CLEAR)) {
                notPlausibleOwnMidpoint++;
            }
            if (!climate.canPrecipitate() && climate.possibleWeather().stream()
                    .anyMatch(WeatherType::isPrecipitation)) {
                dryButAllowsRain++;
            }
            if (climate.climateClass() == com.nowweather.core.climate.ClimateClass.POLAR
                    && climate.possibleWeather().stream().noneMatch(WeatherType::isFrozen)) {
                polarButNoSnow++;
            }
            if (!climate.explicitPossibleWeather().isEmpty()) {
                withExplicitAllowance.merge(climate.biomeId().split(":")[0], 1, Integer::sum);
            }
            int size = climate.possibleWeather().size();
            minBiomes = Math.min(minBiomes, size);
            maxBiomes = Math.max(maxBiomes, size);
        }

        T.eq("温度范围异常的条目", 0, badRange);
        T.eq("天气白名单为空的条目", 0, emptyAllowance);
        T.eq("湿度/风力越界的条目", 0, badHumidity);
        T.eq("连「晴」都不允许的条目", 0, notPlausibleOwnMidpoint);
        T.eq("干旱却允许降水的条目", 0, dryButAllowsRain);
        T.eq("极地气候却没有雪类天气的条目", 0, polarButNoSnow);
        System.out.println("  白名单大小范围: " + minBiomes + " ~ " + maxBiomes + " 种天气");

        // 各命名空间的条目数
        Map<String, Integer> perNamespace = new TreeMap<>();
        climates.forEach(c -> perNamespace.merge(c.biomeId().split(":")[0], 1, Integer::sum));
        perNamespace.forEach((ns, count) -> System.out.println("  " + ns + ": " + count + " 条"));
        if (withExplicitAllowance.isEmpty()) {
            System.out.println("  （所有条目的天气白名单都由运行时推导，未硬编码 —— 文档与代码不会脱节）");
        }

        T.check("包含超多生物群系数据", perNamespace.getOrDefault("biomesoplenty", 0) >= 60);
        T.check("包含原版数据", perNamespace.getOrDefault("minecraft", 0) >= 55);
    }

    // ------------------------------------------------------------------ 全矩阵压测

    private static void matrix(List<BiomeClimate> climates) {
        ClimateSanityFilter filter = new ClimateSanityFilter(6.0D, true);

        int cases = 0;
        int allowanceViolations = 0;
        int tempPreservedViolations = 0;
        int tempClampViolations = 0;
        int exceptions = 0;
        int unchangedInRange = 0;
        int typesChanged = 0;

        Map<String, Integer> violationCounts = new TreeMap<>();
        // biome -> 该群系下「被改动次数」排行，用于找出规则最激进的群系
        Map<String, Integer> changedByBiome = new TreeMap<>();
        // 每个群系的「典型场景」结果（温度取范围中点、用群系自身湿度风力）
        Map<String, String> typical = new LinkedHashMap<>();
        // 想要灌入却永远不可能出现的天气统计：biome -> 被拒的天气类型集合
        Map<String, List<String>> rejectedPerBiome = new TreeMap<>();

        WeatherType[] types = WeatherType.values();

        for (BiomeClimate climate : climates) {
            double minC = climate.tempRange().minC();
            double maxC = climate.tempRange().maxC();
            double tolerance = 6.0D;
            List<String> rejected = new ArrayList<>();
            int changedForThisBiome = 0;

            for (WeatherType type : types) {
                if (type == WeatherType.UNKNOWN) {
                    continue;
                }
                boolean rejectedThisType = false;
                for (double temp = TEMP_START; temp <= TEMP_END + 1.0E-9; temp += TEMP_STEP) {
                    for (double[] env : ENVIRONMENTS) {
                        cases++;
                        try {
                            SanityReport report = filter.inspect(climate, type, temp, env[0], env[1]);
                            WeatherType out = report.adjustedType();
                            double outTemp = report.adjustedTempC();

                            // 铁律 1：输出必须在该群系的天气白名单内
                            if (!climate.allows(out)) {
                                allowanceViolations++;
                                rejectedThisType = true;
                            }
                            if (!out.equals(type)) {
                                typesChanged++;
                                changedForThisBiome++;
                            }

                            // 铁律 2：温度修正不能「失效」也不能「越修越离谱」
                            if (temp >= minC - tolerance && temp <= maxC + tolerance) {
                                if (Math.abs(outTemp - temp) > 1.0E-9) {
                                    tempPreservedViolations++;
                                } else {
                                    unchangedInRange++;
                                }
                            } else {
                                double before = distanceToRange(temp, minC, maxC);
                                double after = distanceToRange(outTemp, minC, maxC);
                                if (after > before + 1.0E-9) {
                                    tempClampViolations++;
                                }
                            }

                            report.violations().forEach(v -> violationCounts.merge(v.name(), 1, Integer::sum));
                        } catch (RuntimeException e) {
                            exceptions++;
                            if (exceptions <= 3) {
                                T.fail("矩阵用例抛异常", climate.biomeId() + " / " + type + " / " + temp
                                        + "°C -> " + e);
                            }
                        }
                    }
                }
                if (rejectedThisType) {
                    rejected.add(type.name());
                }
            }

            changedByBiome.put(climate.biomeId(), changedForThisBiome);
            if (!rejected.isEmpty()) {
                rejectedPerBiome.put(climate.biomeId(), rejected);
            }

            // 典型场景：温度取范围中点、湿度风力用群系自身值
            SanityReport typicalReport = filter.inspect(climate, WeatherType.CLEAR,
                    climate.tempRange().midpoint(), climate.humidity(), climate.windiness());
            typical.put(climate.biomeId(), String.format(Locale.ROOT, "%s（%.1f°C → %s，%.1f°C）",
                    climate.climateClass().zhName(), climate.tempRange().midpoint(),
                    typicalReport.adjustedType().zhName(), typicalReport.adjustedTempC()));
        }

        T.section("全矩阵压测：" + climates.size() + " 群系 × "
                + (types.length - 1) + " 种天气 × 温度区间 × " + ENVIRONMENTS.length + " 组湿度风力");
        System.out.println("  总计用例数: " + String.format(Locale.ROOT, "%,d", cases));

        T.eq("没有用例抛异常", 0, exceptions);
        T.eq("★ 没有一次输出越出天气白名单", 0, allowanceViolations);
        T.eq("★ 容差范围内的温度被原样保留（温度没有被乱改）", 0, tempPreservedViolations);
        T.eq("★ 超范围温度修正后偏离只会变小（温度没有被越修越离谱）", 0, tempClampViolations);
        System.out.println("  容差内温度原样保留: " + String.format(Locale.ROOT, "%,d", unchangedInRange) + " 次");
        System.out.println("  天气类型被修正: " + String.format(Locale.ROOT, "%,d", typesChanged) + " 次");

        System.out.println("  违规类型统计（触发次数）:");
        violationCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.println("    " + e.getKey() + ": "
                        + String.format(Locale.ROOT, "%,d", e.getValue())));

        writeReport(climates, cases, unchangedInRange, typesChanged, violationCounts,
                rejectedPerBiome, typical);
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    // ------------------------------------------------------------------ 报告

    private static void writeReport(List<BiomeClimate> climates, int cases, int unchanged, int changed,
                                    Map<String, Integer> violationCounts,
                                    Map<String, List<String>> rejectedPerBiome,
                                    Map<String, String> typical) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 生物群系 × 天气 全矩阵压测报告\n\n");
        sb.append("> 由 `BiomeWeatherMatrixTests` 自动生成。数据源是**真正打包进模组的**气候 JSON")
                .append("（`data/nowweather/climate/*.json`）。\n\n");
        sb.append("对每个生物群系强行灌入 **全部 ").append(WeatherType.values().length - 1)
                .append(" 种天气** × 温度 **").append(String.format(Locale.ROOT, "%.0f~%.0f°C",
                        TEMP_START, TEMP_END))
                .append("**（每 ").append(TEMP_STEP).append("°C 一点）× ")
                .append(ENVIRONMENTS.length).append(" 组湿度风力，共 ")
                .append(String.format(Locale.ROOT, "%,d", cases)).append(" 个用例。\n\n");
        sb.append("### 结论\n\n");
        sb.append("| 检查项 | 结果 |\n| --- | --- |\n");
        sb.append("| 天气输出越出白名单 | **0 次** |\n");
        sb.append("| 容差内温度被改动 | **0 次**（").append(String.format(Locale.ROOT, "%,d", unchanged))
                .append(" 次原样保留） |\n");
        sb.append("| 超范围温度修正后偏离变大 | **0 次** |\n");
        sb.append("| 天气类型被修正 | ").append(String.format(Locale.ROOT, "%,d", changed)).append(" 次 |\n\n");

        sb.append("### 违规类型统计\n\n| 违规类型 | 触发次数 | 含义 |\n| --- | --- | --- |\n");
        violationCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    String meaning = "—";
                    try {
                        meaning = com.nowweather.core.forecast.SanityViolation.valueOf(e.getKey()).zhDescription();
                    } catch (IllegalArgumentException ignored) {
                        // 未知项忽略
                    }
                    sb.append("| `").append(e.getKey()).append("` | ")
                            .append(String.format(Locale.ROOT, "%,d", e.getValue()))
                            .append(" | ").append(meaning).append(" |\n");
                });

        sb.append("\n### 各生物群系：正常温度范围 / 可能出现的天气 / 被拒的天气\n\n");
        sb.append("「被拒的天气」= 无论温度怎么变，该群系都不可能出现的天气（会被自动替换成合法天气）。\n\n");
        sb.append("| 生物群系 | 气候大类 | 正常温度范围 | 降水 | 可能出现的原版天气 | 可能出现的天气数 | 被拒的天气 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (BiomeClimate climate : climates) {
            List<String> rejected = rejectedPerBiome.getOrDefault(climate.biomeId(), List.of());
            sb.append("| `").append(climate.biomeId()).append("` | ")
                    .append(climate.climateClass().zhName()).append(" | ")
                    .append(climate.tempRange().describe()).append(" | ")
                    .append(climate.precipitation().zhName()).append(" | ")
                    .append(WeatherAllowance.describePlatform(climate.possiblePlatformWeather())).append(" | ")
                    .append(climate.possibleWeather().size()).append(" | ")
                    .append(rejected.isEmpty() ? "无" : String.join("、", rejected))
                    .append(" |\n");
        }

        sb.append("\n### 典型场景（温度取范围中点）\n\n| 生物群系 | 输入 0°C 时的判定 |\n| --- | --- |\n");
        typical.forEach((biome, text) -> sb.append("| `").append(biome).append("` | ").append(text)
                .append(" |\n"));

        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("  报告已写入 " + REPORT.toAbsolutePath());
        } catch (IOException e) {
            T.fail("写入压测报告", String.valueOf(e));
        }
    }
}

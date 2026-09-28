package com.nowweather.forge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nowweather.core.api.PlayerHandle;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.WeatherAllowance;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.core.sync.WeatherController;
import com.nowweather.core.sync.WeatherDecision;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.forge.NowWeatherMod;
import com.nowweather.core.llm.WeatherAssistant;
import com.nowweather.forge.NowWeatherLog;
import com.nowweather.forge.config.ConfigBridge;
import com.nowweather.forge.config.ConfigEntries;
import com.nowweather.forge.config.ForgeConfig;
import com.nowweather.forge.data.ClimateDataReloader;
import com.nowweather.forge.platform.ForgeBiomeFactsFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /nowweather} 命令族。
 *
 * <p>所有输出都用「游戏内文本」而不是日志，方便玩家/服主直接排查问题：
 * 这个模组的数据链路比较长（真实天气 → 气候校验 → 决策 → 落地），
 * 出问题时需要一眼看出是哪一环。</p>
 *
 * <h2>命令树（顶层只有这 9 个字面量）</h2>
 * <pre>
 *   /nowweather                   概览：真实天气 / 本地气候 / 已落地 / 时间
 *   /nowweather status            完整状态报告
 *   /nowweather forecast [sky|ai] 预报（无参 = 合成预报列表）
 *   /nowweather refresh           立刻重新拉取真实天气
 *   /nowweather set &lt;天气&gt;        手动设定天气（暂停自动同步）
 *   /nowweather auto              恢复自动同步
 *   /nowweather config [...]      配置（list/get/set/reset/reload/dump/ask）
 *   /nowweather info &lt;主题&gt;       诊断与联动信息（biome|time|terrain|shaders|...）
 *   /nowweather test [...]        自检
 *   /nowweather help              帮助
 * </pre>
 *
 * <p><b>帮助表必须与这里的命令树逐条对应</b>：{@link #HELP_ROWS} 是唯一的列出点，
 * 新增/删除子命令时同步改它，否则就会出现「帮助里有、实际执行不了」这种最招人烦的漂移。</p>
 *
 * <p>所有给玩家看的文本都走 {@link Text#tr(String, Object...)}（见 core 的键名约定），
 * 日志（{@code NowWeatherLog.LOGGER}）例外 —— 日志面向开发者，恒为中文。</p>
 */
public final class NowWeatherCommand {

    /** {@code /nowweather info} 的主题；同时用于帮助表，保证「帮助里列的主题」都能真的执行。 */
    private static final String[] INFO_TOPICS = {
            "biome", "time", "terrain", "shaders", "providers", "cache",
            "acoustics", "weather2", "llm", "offline", "diagnose", "sky"
    };

    /** 帮助表：{使用方式键, 说明键, 说明占位符参数…}。 */
    private static final String[][] HELP_ROWS = {
            {"nowweather.command.help.overview.usage", "nowweather.command.help.overview.desc"},
            {"nowweather.command.help.status.usage", "nowweather.command.help.status.desc"},
            {"nowweather.command.help.forecast.usage", "nowweather.command.help.forecast.desc"},
            {"nowweather.command.help.forecast_sky.usage", "nowweather.command.help.forecast_sky.desc"},
            {"nowweather.command.help.forecast_ai.usage", "nowweather.command.help.forecast_ai.desc"},
            {"nowweather.command.help.refresh.usage", "nowweather.command.help.refresh.desc"},
            {"nowweather.command.help.set.usage", "nowweather.command.help.set.desc"},
            {"nowweather.command.help.auto.usage", "nowweather.command.help.auto.desc"},
            {"nowweather.command.help.config.usage", "nowweather.command.help.config.desc"},
            {"nowweather.command.help.config_list.usage", "nowweather.command.help.config_list.desc"},
            {"nowweather.command.help.config_get.usage", "nowweather.command.help.config_get.desc"},
            {"nowweather.command.help.config_set.usage", "nowweather.command.help.config_set.desc"},
            {"nowweather.command.help.config_reset.usage", "nowweather.command.help.config_reset.desc"},
            {"nowweather.command.help.config_reload.usage", "nowweather.command.help.config_reload.desc"},
            {"nowweather.command.help.config_dump.usage", "nowweather.command.help.config_dump.desc",
                    climateDirDisplay()},
            {"nowweather.command.help.config_ask.usage", "nowweather.command.help.config_ask.desc"},
            {"nowweather.command.help.info.usage", "nowweather.command.help.info.desc",
                    String.join(" / ", INFO_TOPICS)},
            {"nowweather.command.help.info_cache_clear.usage", "nowweather.command.help.info_cache_clear.desc"},
            {"nowweather.command.help.info_time_sync.usage", "nowweather.command.help.info_time_sync.desc"},
            {"nowweather.command.help.info_weather2_spawn.usage", "nowweather.command.help.info_weather2_spawn.desc"},
            {"nowweather.command.help.test.usage", "nowweather.command.help.test.desc"},
            {"nowweather.command.help.test_biome.usage", "nowweather.command.help.test_biome.desc"},
            {"nowweather.command.help.test_weather.usage", "nowweather.command.help.test_weather.desc"},
            {"nowweather.command.help.test_matrix.usage", "nowweather.command.help.test_matrix.desc"},
            {"nowweather.command.help.test_all.usage", "nowweather.command.help.test_all.desc"},
            {"nowweather.command.help.test_clear.usage", "nowweather.command.help.test_clear.desc"},
            {"nowweather.command.help.help.usage", "nowweather.command.help.help.desc"},
    };

    /** {@code /nowweather test} 不带参数时只列这几条。 */
    private static final String[][] TEST_HELP_ROWS = {
            {"nowweather.command.help.test_biome.usage", "nowweather.command.help.test_biome.desc"},
            {"nowweather.command.help.test_weather.usage", "nowweather.command.help.test_weather.desc"},
            {"nowweather.command.help.test_matrix.usage", "nowweather.command.help.test_matrix.desc"},
            {"nowweather.command.help.test_all.usage", "nowweather.command.help.test_all.desc"},
            {"nowweather.command.help.test_clear.usage", "nowweather.command.help.test_clear.desc"},
    };

    private NowWeatherCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nowweather")
                .requires(source -> source.hasPermission(2))
                .executes(context -> overview(context.getSource()))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("forecast")
                        .executes(context -> forecastList(context.getSource()))
                        .then(Commands.literal("sky").executes(context -> skyForecast(context.getSource())))
                        .then(Commands.literal("ai").executes(context -> aiWeather(context.getSource()))))
                .then(Commands.literal("refresh").executes(context -> refresh(context.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("weather", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        weatherSuggestions(), builder))
                                .executes(context -> set(context.getSource(),
                                        StringArgumentType.getString(context, "weather")))))
                .then(Commands.literal("auto").executes(context -> auto(context.getSource())))
                .then(Commands.literal("config")
                        .executes(context -> configList(context.getSource(), ""))
                        .then(Commands.literal("list")
                                .executes(context -> configList(context.getSource(), ""))
                                .then(Commands.argument("section", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                configSectionNames(), builder))
                                        .executes(context -> configList(context.getSource(),
                                                StringArgumentType.getString(context, "section")))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                configKeys(), builder))
                                        .executes(context -> configGet(context.getSource(),
                                                StringArgumentType.getString(context, "key")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                configKeys(), builder))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> configSet(context.getSource(),
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value"))))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                configKeys(), builder))
                                        .executes(context -> configReset(context.getSource(),
                                                StringArgumentType.getString(context, "key")))))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
                        .then(Commands.literal("dump").executes(context -> dump(context.getSource())))
                        .then(Commands.literal("ask")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> ask(context.getSource(),
                                                StringArgumentType.getString(context, "text"))))))
                .then(Commands.literal("info")
                        .executes(context -> infoTopics(context.getSource()))
                        .then(Commands.literal("biome").executes(context -> biome(context.getSource())))
                        .then(Commands.literal("time")
                                .executes(context -> timeStatus(context.getSource()))
                                .then(Commands.literal("sync")
                                        .executes(context -> timeStatus(context.getSource()))))
                        .then(Commands.literal("terrain").executes(context -> terrain(context.getSource())))
                        .then(Commands.literal("shaders").executes(context -> shaderStatus(context.getSource())))
                        .then(Commands.literal("providers").executes(context -> providers(context.getSource())))
                        .then(Commands.literal("cache")
                                .executes(context -> cacheStatus(context.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(context -> cacheClear(context.getSource()))))
                        .then(Commands.literal("acoustics").executes(context -> acoustics(context.getSource())))
                        .then(Commands.literal("weather2")
                                .executes(context -> weather2Status(context.getSource()))
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        java.util.List.of("SANDSTORM", "SNOWSTORM"), builder))
                                                .executes(context -> weather2Spawn(context.getSource(),
                                                        StringArgumentType.getString(context, "type"))))))
                        .then(Commands.literal("llm").executes(context -> llmStatus(context.getSource())))
                        .then(Commands.literal("offline").executes(context -> offlinePreview(context.getSource())))
                        .then(Commands.literal("diagnose").executes(context -> diagnose(context.getSource())))
                        .then(Commands.literal("sky").executes(context -> skyForecast(context.getSource()))))
                .then(Commands.literal("test")
                        .executes(context -> testHelp(context.getSource()))
                        .then(Commands.literal("biome")
                                .executes(context -> testBiome(context.getSource(), ""))
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                biomeSuggestions(), builder))
                                        .executes(context -> testBiome(context.getSource(),
                                                StringArgumentType.getString(context, "id")))))
                        .then(Commands.literal("weather")
                                .then(Commands.argument("weather", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                weatherSuggestions(), builder))
                                        .executes(context -> testWeather(context.getSource(),
                                                StringArgumentType.getString(context, "weather"), Double.NaN))
                                        .then(Commands.argument("tempC", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-100.0D, 150.0D))
                                                .executes(context -> testWeather(context.getSource(),
                                                        StringArgumentType.getString(context, "weather"),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "tempC"))))))
                        .then(Commands.literal("matrix")
                                .executes(context -> testMatrix(context.getSource(), "minecraft"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(context -> testMatrix(context.getSource(),
                                                StringArgumentType.getString(context, "namespace")))))
                        .then(Commands.literal("all")
                                .executes(context -> testMatrix(context.getSource(), "")))
                        .then(Commands.literal("clear").executes(context -> testClear(context.getSource()))))
                .then(Commands.literal("help").executes(context -> help(context.getSource()))));
    }

    /**
     * {@code /nowweather set <天气>} 与 {@code test weather} 的补全值。
     *
     * <p><b>只给 ASCII 枚举名 + 三个原版别名。</b>这里以前还塞了 {@code type.zhName()}，
     * 于是补全给的是「雷阵雨」、而 {@code parseWeather} 只认枚举名 —— 玩家按 Tab 补全选出来的值
     * 反而解析失败。中文/英文展示名由 core 的 {@link WeatherType#parse} 负责识别，不该出现在补全表里。</p>
     */
    private static List<String> weatherSuggestions() {
        List<String> list = new ArrayList<>();
        for (WeatherType type : WeatherType.values()) {
            if (type != WeatherType.UNKNOWN) {
                list.add(type.name().toLowerCase(Locale.ROOT));
            }
        }
        // 原版三档天气的常用写法，玩家更习惯直接敲它们
        list.add("clear");
        list.add("rain");
        list.add("thunder");
        return list;
    }

    // ------------------------------------------------------------------ 概览

    /**
     * {@code /nowweather}：一句话说清「真实天气 / 本地气候 / 已落地 / 时间」。
     *
     * <p>与 {@code status} 的分工：概览用于一眼确认「现在到底在不在正常工作」，
     * 细节全部留给 {@code status}。</p>
     */
    private static int overview(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        WeatherController controller = runtime.controller();

        WeatherObservation observation = controller.observation();
        String realWeather = observation == null
                ? Text.tr("nowweather.command.overview.real_weather_none")
                : Text.tr("nowweather.command.overview.real_weather",
                        observation.weatherType().zhName(),
                        String.format(Locale.ROOT, "%.1f", observation.temperatureC()));

        BiomeClimate climate = controller.lastClimate();
        String climateText = climate == null
                ? Text.tr("nowweather.command.overview.climate_none")
                : Text.tr("nowweather.command.overview.climate",
                        climate.biomeId(), climate.tempRange().describe());

        WeatherDecision decision = controller.lastDecision();
        String landed = decision == null
                ? Text.tr("nowweather.command.overview.landed_none")
                : Text.tr("nowweather.command.overview.landed", decision.describe());

        return ok(source, Text.tr("nowweather.command.overview.line",
                realWeather, climateText, landed, timeSummary(source, controller)));
    }

    /** 概览里的「时间」片段：所在地真实时刻 → 游戏内钟点。 */
    private static String timeSummary(CommandSourceStack source, WeatherController controller) {
        try {
            long now = System.currentTimeMillis();
            int offset = controller.timeZoneService().effectiveOffsetSeconds();
            int[] hm = com.nowweather.core.time.RealTimeSyncModel.localHourMinute(now, offset);
            long worldDayTime = source.getLevel().getDayTime();
            int inGame = com.nowweather.core.time.RealTimeSyncModel.inGameHour(worldDayTime % 24000L);
            return Text.tr("nowweather.command.overview.time",
                    String.format(Locale.ROOT, "%02d:%02d", hm[0], hm[1]),
                    String.valueOf(inGame));
        } catch (RuntimeException e) {
            return Text.tr("nowweather.command.common.unknown");
        }
    }

    // ------------------------------------------------------------------ 帮助

    /** {@code /nowweather help}：列出全部子命令；内容与 {@link #HELP_ROWS} 一一对应。 */
    private static int help(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.help.header")).append('\n');
        appendHelpRows(sb, HELP_ROWS);
        sb.append(ChatFormatting.DARK_GRAY).append(Text.tr("nowweather.command.help.usage")).append('\n');
        sb.append(ChatFormatting.DARK_GRAY).append(Text.tr("nowweather.command.help.require_op"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    private static void appendHelpRows(StringBuilder sb, String[][] rows) {
        for (String[] row : rows) {
            Object[] args = java.util.Arrays.copyOfRange(row, 2, row.length);
            sb.append(ChatFormatting.GRAY).append("  ")
                    .append(ChatFormatting.WHITE).append(Text.tr(row[0]))
                    .append(ChatFormatting.DARK_GRAY).append(" — ")
                    .append(ChatFormatting.GRAY)
                    .append(args.length == 0 ? Text.tr(row[1]) : Text.tr(row[1], args))
                    .append('\n');
        }
    }

    // ------------------------------------------------------------------ 各子命令

    private static int status(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        BiomeFacts facts = factsOf(source);
        String report = runtime.controller().statusReport(dimensionOf(source), facts);
        source.sendSuccess(new TextComponent(report), false);
        return 1;
    }

    private static int biome(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        BiomeFacts facts = factsOf(source);
        if (facts == null) {
            return fail(source, Text.tr("nowweather.command.info.biome.no_facts"));
        }
        BiomeClimate climate = runtime.climates().resolve(facts);

        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.biome.header")).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.biome")).append(' ')
                .append(ChatFormatting.WHITE).append(climate.biomeId()).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.climate_class")).append(' ')
                .append(ChatFormatting.WHITE).append(climate.climateClass().zhName()).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.temp_range")).append(' ')
                .append(ChatFormatting.YELLOW).append(climate.tempRange().describe()).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.precipitation")).append(' ')
                .append(ChatFormatting.WHITE).append(climate.precipitation().zhName()).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.humidity_wind")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(String.format(Locale.ROOT, "%.2f / %.2f", climate.humidity(), climate.windiness()))
                .append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.platform_weather")).append(' ')
                .append(ChatFormatting.GREEN)
                .append(WeatherAllowance.describePlatformForPlayer(climate.possiblePlatformWeather(), climate)).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.biome.label.possible_weather")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(WeatherAllowance.describe(climate.possibleWeather())).append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.biome.field.source",
                        climate.source(), climate.confidence().zhName()))
                .append('\n');
        sb.append(ChatFormatting.DARK_GRAY).append(Text.tr("nowweather.command.info.biome.label.facts")).append(' ')
                .append(facts.describe());

        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    /** {@code /nowweather info}：没带主题时列出可选主题（与 {@link #INFO_TOPICS} 同源）。 */
    private static int infoTopics(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.header")).append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.topics", String.join(" / ", INFO_TOPICS)))
                .append('\n');
        sb.append(ChatFormatting.DARK_GRAY).append(Text.tr("nowweather.command.info.usage_hint"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    private static int refresh(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        runtime.controller().requestRefresh(true);
        return ok(source, Text.tr("nowweather.command.refresh.done"));
    }

    /** {@code /nowweather config reload}：重新载入数据包/配置里的气候数据。 */
    private static int reload(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        ClimateDataReloader.ReloadSummary summary = ClimateDataReloader.reload(runtime,
                source.getServer().getResourceManager());
        return ok(source, Text.tr("nowweather.command.config.reload.done",
                summary == null ? Text.tr("nowweather.command.config.reload.empty") : summary.toString()));
    }

    /**
     * 配置目录里的气候数据目录，供提示文案使用。
     *
     * <p>{@code ClimateDataReloader} 把内置数据释放到
     * {@code config/<NowWeatherCore.MOD_ID>/climate/}，而模组 id 是<b>全小写</b>的
     * {@code nowweather}。这里以前把提示写成 {@code config/NowWeather/climate/}，
     * 玩家照着去找只会扑空；现在按 {@link com.nowweather.core.NowWeatherCore#MOD_ID} 拼，
     * 大小写不会再跑偏。（{@code ClimateDataReloader} 里的目录名常量是私有的，
     * 所以这里按同一规则拼出显示用的相对路径。）</p>
     */
    private static String climateDirDisplay() {
        return "config/" + com.nowweather.core.NowWeatherCore.MOD_ID + "/climate/";
    }

    /** {@code /nowweather config dump}：把内置气候数据导出到配置目录，方便服主直接改。 */
    private static int dump(CommandSourceStack source) {
        int written = ClimateDataReloader.dumpBuiltinToConfig();
        if (written == 0) {
            return ok(source, Text.tr("nowweather.command.config.dump.already", climateDirDisplay()));
        }
        return ok(source, Text.tr("nowweather.command.config.dump.done",
                String.valueOf(written), climateDirDisplay()));
    }

    private static int auto(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        runtime.controller().clearManualOverride();
        return ok(source, Text.tr("nowweather.command.auto.done"));
    }

    private static int diagnose(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        var json = runtime.controller().diagnostics(dimensionOf(source), factsOf(source));
        com.nowweather.forge.NowWeatherLog.LOGGER.info("=== NowWeather 诊断 ===\n{}", json.toPrettyJson());
        return ok(source, Text.tr("nowweather.command.info.diagnose.done"));
    }

    private static int providers(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.providers.header")).append('\n');
        runtime.providers().all().forEach(p -> sb.append(ChatFormatting.GRAY).append("- ")
                .append(ChatFormatting.WHITE).append(p.id())
                .append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.providers.line",
                        p.displayName(), String.valueOf(p.priority()),
                        p.requiresNetwork()
                                ? Text.tr("nowweather.command.info.providers.network_required")
                                : Text.tr("nowweather.command.info.providers.offline_ok")))
                .append('\n'));
        sb.append(ChatFormatting.DARK_GRAY)
                .append(Text.tr("nowweather.command.info.providers.extension_points"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ LLM 与联动诊断

    /** LLM 配置的状态文件：只记「上次调用时间」，用来实现每天一次的硬预算。 */
    private static java.nio.file.Path llmStateFile() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("weather").resolve("nowweather").resolve("llm.json");
    }

    private static long lastLlmCallEpochMillis() {
        try {
            java.nio.file.Path path = llmStateFile();
            if (!java.nio.file.Files.isRegularFile(path)) {
                return 0L;
            }
            com.nowweather.core.json.JsonObject json = com.nowweather.core.json.JsonValue
                    .parse(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8))
                    .asObjectOrNull();
            return json == null ? 0L : json.optLong("weatherCallAt", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void recordLlmCall(long epochMillis) {
        try {
            java.nio.file.Path path = llmStateFile();
            java.nio.file.Files.createDirectories(path.getParent());
            com.nowweather.core.json.JsonObject json = new com.nowweather.core.json.JsonObject();
            json.put("weatherCallAt", epochMillis);
            json.put("note", "AI 辅助天气推算每天最多一次；这个文件只是成本计数，删掉就会重置预算");
            java.nio.file.Files.writeString(path, json.toPrettyJson(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            NowWeatherLog.LOGGER.warn("写入 LLM 状态失败（不影响功能）: {}", e.getMessage());
        }
    }

    /**
     * {@code /nowweather config ask <自然语言>}：让 AI 把一句话翻译成配置改动。
     *
     * <p>安全边界：模型只能返回<b>白名单内的键</b>（由 {@code ConfigEntries.serverKeys()} 给出，
     * 服务端会滤掉客户端项），最终每一项都要过 {@code ConfigEntries} 的同一套范围校验 ——
     * 也就是说 AI 没有任何绕过校验、写到危险值的能力。</p>
     */
    private static int ask(CommandSourceStack source, String text) {
        if (!ForgeConfig.COMMON.llmEnabled.get()) {
            return fail(source, Text.tr("nowweather.command.config.ask.disabled"));
        }
        if (!ForgeConfig.COMMON.llmAllowConfigEdits.get()) {
            return fail(source, Text.tr("nowweather.command.config.ask.edits_disabled"));
        }
        if (ForgeConfig.COMMON.llmApiKey.get().isBlank()) {
            return fail(source, Text.tr("nowweather.command.config.ask.no_key"));
        }
        List<String> allowed = ConfigEntries.serverKeys();
        source.sendSuccess(new TextComponent(ChatFormatting.GRAY
                + Text.tr("nowweather.command.config.ask.asking", String.valueOf(allowed.size()))), false);

        final String question = text;
        Thread worker = new Thread(() -> {
            com.nowweather.core.llm.WeatherAssistant assistant =
                    new com.nowweather.core.llm.WeatherAssistant(new com.nowweather.core.llm.LlmClient(
                            new com.nowweather.core.http.JdkHttpTransport()));
            com.nowweather.core.llm.WeatherAssistant.ConfigPatch patch = assistant.parseIntent(
                    ConfigBridge.llmOptions(), question, allowed, ConfigBridge.llmConfigSnapshot());
            source.getServer().execute(() -> applyPatch(source, patch));
        }, "NowWeather-Ask");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /** 把 AI 给出的改动逐项落到配置上。每一项都走与配置界面相同的校验。 */
    private static void applyPatch(CommandSourceStack source, WeatherAssistant.ConfigPatch patch) {
        if (!patch.success()) {
            NowWeatherLog.LOGGER.warn("[AI ask 失败] {}", patch.error());
            source.sendFailure(new TextComponent(ChatFormatting.RED
                    + Text.tr("nowweather.command.config.ask.failed", patch.error())));
            return;
        }
        NowWeatherLog.LOGGER.info("[AI ask] 回复：{} | 建议改动 {} 项", patch.reply(), patch.changes().size());
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.config.ask.header")).append('\n');
        if (!patch.reply().isEmpty()) {
            sb.append(ChatFormatting.WHITE).append(patch.reply()).append('\n');
        }
        if (patch.isEmpty()) {
            sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.config.ask.no_changes"));
            source.sendSuccess(new TextComponent(sb.toString()), false);
            return;
        }
        int applied = 0;
        for (WeatherAssistant.Change change : patch.changes()) {
            ConfigEntries.Entry entry = ConfigEntries.byKey(change.key());
            if (entry == null || entry.isClient()) {
                sb.append(ChatFormatting.RED)
                        .append(Text.tr("nowweather.command.config.ask.not_editable", change.key()))
                        .append('\n');
                continue;
            }
            String error = entry.setFromString(change.value());
            if (error != null) {
                sb.append(ChatFormatting.RED)
                        .append(Text.tr("nowweather.command.config.ask.change_failed",
                                entry.key(), change.value(), error))
                        .append('\n');
                continue;
            }
            sb.append(ChatFormatting.GREEN)
                    .append(Text.tr("nowweather.command.config.ask.change_applied",
                            entry.key(), entry.display()))
                    .append('\n');
            applied++;
        }
        if (applied > 0) {
            try {
                ForgeConfig.COMMON_SPEC.save();
                ForgeConfig.CLIENT_SPEC.save();
                NowWeatherMod.reloadConfigNow();
                sb.append(ChatFormatting.GRAY)
                        .append(Text.tr("nowweather.command.config.ask.saved", String.valueOf(applied)));
            } catch (RuntimeException e) {
                sb.append(ChatFormatting.RED)
                        .append(Text.tr("nowweather.command.config.ask.save_failed", e.getMessage()));
            }
        }
        source.sendSuccess(new TextComponent(sb.toString()), false);
    }

    /** {@code /nowweather forecast ai}：用 AI 辅助推算一次天气（每天最多一次）。 */
    private static int aiWeather(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        if (!ForgeConfig.COMMON.llmEnabled.get()) {
            return fail(source, Text.tr("nowweather.command.forecast.ai.disabled"));
        }
        if (!ForgeConfig.COMMON.llmWeatherDaily.get()) {
            return fail(source, Text.tr("nowweather.command.forecast.ai.daily_disabled"));
        }
        long now = System.currentTimeMillis();
        long last = lastLlmCallEpochMillis();
        if (!WeatherAssistant.budgetAvailable(last, now)) {
            long remain = WeatherAssistant.budgetRemainingMillis(last, now);
            return fail(source, Text.tr("nowweather.command.forecast.ai.budget_used",
                    String.format(Locale.ROOT, "%.1f", remain / 3600_000.0D)));
        }
        com.nowweather.core.cache.LocationProfile location = runtime.controller().locationProfile();
        com.nowweather.core.weather.WeatherObservation current = runtime.controller().observation();
        if (location == null || !location.hasCoordinates()) {
            return fail(source, Text.tr("nowweather.command.forecast.ai.no_location"));
        }
        String context = String.format(Locale.ROOT,
                "纬度 %.3f，经度 %.3f，海拔 %s，半球 %s，生物群系气候 %s",
                location.latitude(), location.longitude(),
                location.hasElevation() ? String.format(Locale.ROOT, "%.0fm", location.elevationMeters()) : "未知",
                location.hemisphere().zhName(),
                runtime.controller().lastClimate() == null ? "未知"
                        : runtime.controller().lastClimate().tempRange().describe());
        String local = current == null ? Text.tr("nowweather.command.forecast.ai.no_local_data")
                : String.format(Locale.ROOT, "%s %.1f°C 湿度 %.0f%% 风 %d 级",
                        current.weatherType().zhName(), current.temperatureC(),
                        current.humidityPercent(), current.windScale());
        // local 同时是喂给模型的语境（固定中文，不进 i18n）；给玩家看的那份单独翻译。
        String localDisplay = current == null ? local
                : Text.tr("nowweather.command.forecast.ai.weather_value",
                        current.weatherType().zhName(),
                        String.format(Locale.ROOT, "%.1f", current.temperatureC()),
                        String.format(Locale.ROOT, "%.0f", current.humidityPercent()),
                        String.valueOf(current.windScale()));

        source.sendSuccess(new TextComponent(ChatFormatting.GRAY
                + Text.tr("nowweather.command.forecast.ai.asking")), false);
        Thread worker = new Thread(() -> {
            WeatherAssistant assistant = new WeatherAssistant(
                    new com.nowweather.core.llm.LlmClient(new com.nowweather.core.http.JdkHttpTransport()));
            WeatherAssistant.WeatherSuggestion suggestion =
                    assistant.deriveWeather(ConfigBridge.llmOptions(), local, context);
            if (suggestion.success()) {
                recordLlmCall(System.currentTimeMillis());
            }
            source.getServer().execute(() -> {
                if (!suggestion.success()) {
                    NowWeatherLog.LOGGER.warn("[AI 推算失败] {}", suggestion.error());
                    source.sendFailure(new TextComponent(ChatFormatting.RED
                            + Text.tr("nowweather.command.forecast.ai.failed", suggestion.error())));
                    return;
                }
                // ★ 异步结果必须写日志：RCON 在命令返回时就结束了，拿不到后续异步回复，
                // 没有这行日志就没法真机验证 AI 到底回了什么。
                NowWeatherLog.LOGGER.info("[AI 推算] 本地={} | AI={} {}(±{}°C) 湿度 {}% 风 {} 级 置信度 {}% | 理由：{}",
                        local, suggestion.weather(), String.format(Locale.ROOT, "%.1f", suggestion.temperatureC()),
                        "-", String.format(Locale.ROOT, "%.0f", suggestion.humidityPercent()),
                        suggestion.windScale(),
                        String.format(Locale.ROOT, "%.0f", suggestion.confidence() * 100.0D),
                        suggestion.reason());
                StringBuilder sb = new StringBuilder();
                sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.forecast.ai.header")).append('\n');
                sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.forecast.ai.label.local")).append(' ')
                        .append(ChatFormatting.WHITE).append(localDisplay).append('\n');
                sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.forecast.ai.label.suggestion")).append(' ')
                        .append(ChatFormatting.GREEN)
                        .append(Text.tr("nowweather.command.forecast.ai.weather_value",
                                suggestion.weather().isEmpty() ? "?" : suggestion.weather(),
                                String.format(Locale.ROOT, "%.1f", suggestion.temperatureC()),
                                String.format(Locale.ROOT, "%.0f", suggestion.humidityPercent()),
                                String.valueOf(suggestion.windScale()))).append('\n');
                sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.forecast.ai.label.confidence")).append(' ')
                        .append(ChatFormatting.WHITE)
                        .append(Text.tr("nowweather.command.forecast.ai.confidence_value",
                                String.format(Locale.ROOT, "%.0f", suggestion.confidence() * 100.0D)));
                if (!suggestion.reason().isEmpty()) {
                    sb.append(ChatFormatting.GRAY)
                            .append(Text.tr("nowweather.command.forecast.ai.label.reason")).append(' ')
                            .append(ChatFormatting.WHITE).append(suggestion.reason());
                }
                sb.append('\n').append(ChatFormatting.DARK_GRAY)
                        .append(Text.tr("nowweather.command.forecast.ai.budget_note"));
                source.sendSuccess(new TextComponent(sb.toString()), false);
            });
        }, "NowWeather-AI");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /** {@code /nowweather info llm}：LLM 状态与预算。 */
    private static int llmStatus(CommandSourceStack source) {
        long now = System.currentTimeMillis();
        long last = lastLlmCallEpochMillis();
        long remain = WeatherAssistant.budgetRemainingMillis(last, now);
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.llm.header")).append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.llm.label.enabled")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(ForgeConfig.COMMON.llmEnabled.get()
                        ? Text.tr("nowweather.command.common.on")
                        : Text.tr("nowweather.command.common.off"))
                .append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.llm.field.model",
                        ForgeConfig.COMMON.llmModel.get(),
                        ForgeConfig.COMMON.llmThinking.get()
                                ? Text.tr("nowweather.command.info.llm.thinking_on")
                                : Text.tr("nowweather.command.common.switch_off")))
                .append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.llm.label.api_key")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(ForgeConfig.COMMON.llmApiKey.get().isBlank()
                        ? Text.tr("nowweather.command.info.llm.key_unset")
                        : Text.tr("nowweather.command.info.llm.key_set"))
                .append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.llm.field.daily",
                        ForgeConfig.COMMON.llmWeatherDaily.get()
                                ? Text.tr("nowweather.command.common.switch_on")
                                : Text.tr("nowweather.command.common.switch_off"),
                        remain == 0L
                                ? Text.tr("nowweather.command.info.llm.budget_available")
                                : Text.tr("nowweather.command.info.llm.budget_remaining",
                                        String.format(Locale.ROOT, "%.1f", remain / 3600_000.0D))))
                .append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.llm.field.nl_edit",
                        ForgeConfig.COMMON.llmAllowConfigEdits.get()
                                ? Text.tr("nowweather.command.info.llm.nl_allowed")
                                : Text.tr("nowweather.command.info.llm.nl_denied")))
                .append('\n');
        sb.append(ChatFormatting.DARK_GRAY)
                .append(Text.tr("nowweather.command.info.llm.usage"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    /** {@code /nowweather info weather2}：查看与 Weather, Storms &amp; Tornadoes 的联动探测结果。 */
    private static int weather2Status(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.weather2.header")).append('\n');
        boolean loaded = com.nowweather.forge.platform.ForgeModPresence.INSTANCE.isLoaded("weather2")
                || com.nowweather.forge.platform.ForgeModPresence.INSTANCE.isLoaded("weatherstormsandtornadoes");
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.weather2.label.loaded")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(Text.tr(loaded
                        ? "nowweather.command.common.yes"
                        : "nowweather.command.common.no"))
                .append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.weather2.label.yield")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(ForgeConfig.COMMON.yieldToOtherWeatherMods.get()
                        ? Text.tr("nowweather.command.info.weather2.yield_on")
                        : Text.tr("nowweather.command.common.switch_off"))
                .append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.weather2.field.bridge",
                        com.nowweather.forge.compat.Weather2Bridge.describe()))
                .append('\n');
        if (com.nowweather.forge.compat.Weather2Bridge.available()) {
            var level = source.getLevel();
            var pos = new net.minecraft.core.BlockPos(source.getPosition());
            var all = com.nowweather.forge.compat.Weather2Bridge.storms(level);
            sb.append(ChatFormatting.GRAY)
                    .append(Text.tr("nowweather.command.info.weather2.field.storms",
                            String.valueOf(all.size())))
                    .append('\n');
            for (var storm : all) {
                if (storm.dead()) {
                    continue;
                }
                sb.append(ChatFormatting.GRAY).append("  - ").append(ChatFormatting.WHITE)
                        .append(storm.typeName())
                        .append(ChatFormatting.GRAY)
                        .append(Text.tr("nowweather.command.info.weather2.storm_line",
                                String.format(Locale.ROOT, "%.0f",
                                        storm.horizontalDistanceTo(pos.getX(), pos.getZ())),
                                String.valueOf(storm.size()),
                                String.format(Locale.ROOT, "%.0f", storm.severity() * 100.0D)))
                        .append('\n');
            }
            double wind = com.nowweather.forge.compat.Weather2Bridge.windSpeed(level);
            if (!Double.isNaN(wind)) {
                sb.append(ChatFormatting.GRAY)
                        .append(Text.tr("nowweather.command.info.weather2.field.wind",
                                String.format(Locale.ROOT, "%.2f", wind)))
                        .append('\n');
            }
        }
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.weather2.field.intervention",
                        com.nowweather.forge.compat.Weather2Intervention.describe()))
                .append('\n');
        String note = com.nowweather.forge.compat.Weather2Intervention.lastSpawnNote();
        if (!note.isEmpty()) {
            sb.append(ChatFormatting.GRAY)
                    .append(Text.tr("nowweather.command.info.weather2.field.last_spawn", note))
                    .append('\n');
        }
        sb.append(ChatFormatting.DARK_GRAY)
                .append(Text.tr("nowweather.command.info.weather2.note"))
                .append('\n');
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    /**
     * {@code /nowweather info acoustics}：音频模组联动状态 + 按当前气候算出的混响微调建议。
     *
     * <p>注意：Sound Physics Remastered 的混响参数目前是全局预设，**我们只能读、不能写**，
     * 所以这里给出的是「建议值」而不是已生效的值。写回需要它按改动单暴露接口。</p>
     */
    private static int acoustics(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.acoustics.header")).append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.acoustics.field.detect",
                        com.nowweather.forge.compat.SoundModBridge.describe()))
                .append('\n');
        if (!com.nowweather.forge.compat.SoundModBridge.soundPhysicsPresent()) {
            sb.append(ChatFormatting.DARK_GRAY)
                    .append(Text.tr("nowweather.command.info.acoustics.not_installed"));
            source.sendSuccess(new TextComponent(sb.toString()), false);
            return 1;
        }
        com.nowweather.core.cache.LocationProfile location =
                runtime == null ? null : runtime.controller().locationProfile();
        com.nowweather.core.weather.WeatherObservation observation =
                runtime == null ? null : runtime.controller().observation();
        double temperature = observation == null ? 15.0D : observation.temperatureC();
        double humidity = observation == null ? 0.5D
                : Math.max(0.0D, Math.min(1.0D, observation.humidityPercent() / 100.0D));
        double elevation = location != null && location.hasElevation() ? location.elevationMeters() : 0.0D;
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.acoustics.label.input")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(observation == null
                        ? Text.tr("nowweather.command.info.acoustics.input_default")
                        : Text.tr("nowweather.command.info.acoustics.input_live"))
                .append('\n');
        String advice = com.nowweather.forge.compat.SoundModBridge.acousticAdvice(
                temperature, humidity, elevation, 0.5D);
        if (advice.isEmpty()) {
            sb.append(ChatFormatting.RED).append(Text.tr("nowweather.command.info.acoustics.read_failed"));
        } else {
            for (String line : advice.split("\n")) {
                if (!line.isBlank()) {
                    sb.append(ChatFormatting.WHITE).append(line).append('\n');
                }
            }
            sb.append(ChatFormatting.DARK_GRAY)
                    .append(Text.tr("nowweather.command.info.acoustics.advice_note"));
        }
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    /** {@code /nowweather info weather2 spawn <SANDSTORM|SNOWSTORM>}：手动催生风暴，用于真机验证干预链路。 */
    private static int weather2Spawn(CommandSourceStack source, String type) {
        String upper = type.trim().toUpperCase(Locale.ROOT);
        if (!upper.equals("SANDSTORM") && !upper.equals("SNOWSTORM")) {
            return fail(source, Text.tr("nowweather.command.info.weather2.spawn.unsupported"));
        }
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(source.getPosition());
        boolean ok = com.nowweather.forge.compat.Weather2Intervention.spawn(level, pos, upper);
        String note = com.nowweather.forge.compat.Weather2Intervention.lastSpawnNote();
        return ok
                ? ok(source, Text.tr("nowweather.command.info.weather2.spawn.ok",
                        String.valueOf(pos.getX()), String.valueOf(pos.getZ()), upper, note))
                : fail(source, Text.tr("nowweather.command.info.weather2.spawn.failed", upper, note));
    }

    private static int forecastList(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        com.nowweather.core.forecast.Forecast forecast = runtime.controller().forecast();
        if (forecast == null || forecast.entries().isEmpty()) {
            return fail(source, Text.tr("nowweather.command.forecast.empty"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA)
                .append(Text.tr("nowweather.command.forecast.header",
                        String.valueOf(forecast.entries().size())))
                .append('\n');
        int shown = 0;
        for (com.nowweather.core.forecast.ForecastEntry entry : forecast.entries()) {
            if (shown++ >= 12) {
                sb.append(ChatFormatting.DARK_GRAY).append(Text.tr("nowweather.command.forecast.omitted")).append('\n');
                break;
            }
            sb.append(ChatFormatting.GRAY).append(String.format(Locale.ROOT, "%2d. ", shown))
                    .append(ChatFormatting.WHITE)
                    .append(Text.tr("nowweather.command.forecast.entry",
                            String.format(Locale.ROOT, "%-10s", entry.weatherType().zhName()),
                            String.format(Locale.ROOT, "%.1f", entry.temperatureC()),
                            String.format(Locale.ROOT, "%.0f", entry.confidence() * 100.0D)));
            if (!entry.violations().isEmpty()) {
                sb.append(ChatFormatting.YELLOW)
                        .append(Text.tr("nowweather.command.forecast.violation",
                                String.valueOf(entry.violations().size())));
            }
            sb.append('\n');
        }
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ 配置命令
    //
    // 与游戏内配置界面共用同一份描述表（ConfigEntries），所以「界面上能改的、命令里也能改」，
    // 而且取值范围只有一处定义，不会出现两边不一致。
    // 专用服务端没有客户端配置，因此这里只暴露 COMMON。

    private static List<String> configSectionNames() {
        return ConfigEntries.sectionAliases(false);
    }

    private static List<String> configKeys() {
        return ConfigEntries.serverKeys();
    }

    private static int configList(CommandSourceStack source, String rawSection) {
        ConfigEntries.Section section = ConfigEntries.byName(rawSection);
        if (section != null && section.isClient()) {
            return fail(source, Text.tr("nowweather.command.config.client_only", section.zhName()));
        }
        if (rawSection != null && !rawSection.isBlank() && section == null) {
            return fail(source, Text.tr("nowweather.command.config.section_unknown",
                    rawSection, String.join(" / ", configSectionNames())));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA)
                .append(section == null
                        ? Text.tr("nowweather.command.config.list.header")
                        : Text.tr("nowweather.command.config.list.header_section", section.zhName()))
                .append('\n');
        for (ConfigEntries.Section current
                : ConfigEntries.Section.values()) {
            if (current.isClient() || (section != null && current != section)) {
                continue;
            }
            sb.append(ChatFormatting.YELLOW).append("[").append(current.zhName()).append("]\n");
            for (ConfigEntries.Entry entry
                    : ConfigEntries.of(current)) {
                sb.append(ChatFormatting.GRAY).append("  ").append(entry.key()).append(" = ")
                        .append(ChatFormatting.WHITE).append(entry.display());
                if (!entry.isDefault()) {
                    sb.append(ChatFormatting.GOLD)
                            .append(Text.tr("nowweather.command.config.list.non_default",
                                    entry.displayDefault()));
                }
                sb.append("\n");
            }
        }
        sb.append(ChatFormatting.DARK_GRAY)
                .append(Text.tr("nowweather.command.config.list.hint"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    private static int configGet(CommandSourceStack source, String key) {
        ConfigEntries.Entry entry =
                ConfigEntries.byKey(key);
        if (entry == null) {
            return fail(source, Text.tr("nowweather.command.config.get.unknown", key));
        }
        if (entry.isClient()) {
            return fail(source, Text.tr("nowweather.command.config.client_only", entry.key()));
        }
        return ok(source, Text.tr("nowweather.command.config.get.line",
                entry.key(), entry.display(), entry.displayDefault(),
                entry.label(), entry.hint()));
    }

    private static int configSet(CommandSourceStack source, String key, String rawValue) {
        ConfigEntries.Entry entry =
                ConfigEntries.byKey(key);
        if (entry == null) {
            return fail(source, Text.tr("nowweather.command.config.get.unknown", key));
        }
        if (entry.isClient()) {
            return fail(source, Text.tr("nowweather.command.config.client_only", entry.key()));
        }
        String error = entry.setFromString(rawValue);
        if (error != null) {
            return fail(source, Text.tr("nowweather.command.config.set.invalid", entry.key(), error));
        }
        try {
            ForgeConfig.COMMON_SPEC.save();
        } catch (RuntimeException e) {
            return fail(source, Text.tr("nowweather.command.config.set.save_failed", e.getMessage()));
        }
        NowWeatherMod.reloadConfigNow();
        String warning = validateAfterSet(entry);
        return ok(source, Text.tr("nowweather.command.config.set.done", entry.key(), entry.display())
                + (warning == null
                        ? ""
                        : "\n" + ChatFormatting.YELLOW
                                + Text.tr("nowweather.command.config.set.warning", warning)));
    }

    private static int configReset(CommandSourceStack source, String key) {
        ConfigEntries.Entry entry =
                ConfigEntries.byKey(key);
        if (entry == null) {
            return fail(source, Text.tr("nowweather.command.config.reset.unknown", key));
        }
        if (entry.isClient()) {
            return fail(source, Text.tr("nowweather.command.config.client_only", entry.key()));
        }
        entry.resetToDefault();
        ForgeConfig.COMMON_SPEC.save();
        NowWeatherMod.reloadConfigNow();
        return ok(source, Text.tr("nowweather.command.config.reset.done", entry.key(), entry.display()));
    }

    /**
     * 设置之后的交叉校验：有些组合单看每一项都合法，合起来却不合理。
     *
     * <p>例如把「最短持续刻数」调到比「最长持续刻数」还大 —— 范围校验拦不住，
     * 但结果会是「每次天气都被压到最长值」。这类问题必须当场说出来。</p>
     */
    private static String validateAfterSet(ConfigEntries.Entry entry) {
        try {
            var common = ForgeConfig.COMMON;
            if (entry.key().equals("mapping.minWeatherDurationTicks")
                    || entry.key().equals("mapping.maxWeatherDurationTicks")) {
                int min = common.minWeatherDurationTicks.get();
                int max = common.maxWeatherDurationTicks.get();
                if (min > max) {
                    return Text.tr("nowweather.command.config.warn.duration",
                            String.valueOf(min), String.valueOf(max));
                }
            }
            if (entry.key().equals("api.refreshIntervalSeconds")
                    || entry.key().equals("api.staleAfterSeconds")) {
                int refresh = common.refreshIntervalSeconds.get();
                int stale = common.staleAfterSeconds.get();
                if (stale < refresh) {
                    return Text.tr("nowweather.command.config.warn.stale",
                            String.valueOf(stale), String.valueOf(refresh));
                }
            }
            if (entry.key().equals("sync.dimensions")) {
                List<String> dimensions = new ArrayList<>();
                common.dimensions.get().forEach(d -> dimensions.add(String.valueOf(d)));
                if (dimensions.isEmpty()) {
                    return Text.tr("nowweather.command.config.warn.dimensions_empty");
                }
            }
            if (entry.key().equals("mapping.leaveNetherAndEndAlone")) {
                Boolean alone = common.leaveNetherAndEndAlone.get();
                if (Boolean.FALSE.equals(alone)) {
                    return Text.tr("nowweather.command.config.warn.nether_end");
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * 地形订正演示：显示当前位置的地形画像，以及把真实天气订正过来的结果。
     *
     * <p>这是真机验证地形订正的唯一手段 —— 没有玩家时用
     * {@code /execute positioned X Y Z run nowweather info terrain} 采样任意坐标。</p>
     */
    private static int terrain(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(source.getPosition());
        com.nowweather.core.terrain.TerrainProfile profile =
                com.nowweather.forge.platform.ForgeTerrainSampler.sample(level, pos);
        com.nowweather.core.cache.LocationProfile location = runtime.controller().locationProfile();
        com.nowweather.core.weather.WeatherObservation current = runtime.controller().observation();

        StringBuilder sb = new StringBuilder();
        sb.append(ChatFormatting.AQUA).append(Text.tr("nowweather.command.info.terrain.header")).append('\n');
        sb.append(ChatFormatting.GRAY)
                .append(Text.tr("nowweather.command.info.terrain.field.pos",
                        String.valueOf(pos.getX()), String.valueOf(pos.getY()), String.valueOf(pos.getZ()),
                        String.valueOf(com.nowweather.forge.platform.ForgeTerrainSampler.SEA_LEVEL_Y)))
                .append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.terrain.label.profile")).append(' ')
                .append(ChatFormatting.WHITE).append(profile.describe()).append('\n');
        if (current == null) {
            sb.append(ChatFormatting.YELLOW)
                    .append(Text.tr("nowweather.command.info.terrain.no_weather")).append('\n');
            source.sendSuccess(new TextComponent(sb.toString()), false);
            return 1;
        }
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.terrain.label.location")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(location.hasElevation()
                        ? Text.tr("nowweather.command.info.terrain.elevation",
                                String.format(Locale.ROOT, "%.0f", location.elevationMeters()))
                        : Text.tr("nowweather.command.info.terrain.elevation_unknown"))
                .append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.terrain.label.before")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(Text.tr("nowweather.command.info.terrain.value",
                        String.format(Locale.ROOT, "%.1f", current.temperatureC()),
                        String.format(Locale.ROOT, "%.0f", current.humidityPercent()),
                        String.format(Locale.ROOT, "%.0f", current.pressureHpa()),
                        String.valueOf(current.windScale())))
                .append('\n');

        com.nowweather.core.terrain.TerrainTransfer.Result result =
                com.nowweather.core.terrain.TerrainTransfer.transfer(current, location, profile,
                        System.currentTimeMillis(), current.utcOffsetSeconds());
        com.nowweather.core.weather.WeatherObservation out = result.observation();
        if (out == null) {
            return fail(source, Text.tr("nowweather.command.info.terrain.failed"));
        }
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.terrain.label.after")).append(' ')
                .append(ChatFormatting.GREEN)
                .append(Text.tr("nowweather.command.info.terrain.value_with_weather",
                        String.format(Locale.ROOT, "%.1f", out.temperatureC()),
                        String.format(Locale.ROOT, "%.0f", out.humidityPercent()),
                        String.format(Locale.ROOT, "%.0f", out.pressureHpa()),
                        String.valueOf(out.windScale()),
                        out.weatherType().zhName()))
                .append('\n');
        sb.append(ChatFormatting.GRAY).append(Text.tr("nowweather.command.info.terrain.label.note")).append(' ')
                .append(ChatFormatting.WHITE)
                .append(result.changed() ? result.explanation()
                        : Text.tr("nowweather.command.info.terrain.no_change"))
                .append('\n');
        sb.append(ChatFormatting.DARK_GRAY)
                .append(Text.tr("nowweather.command.info.terrain.hint"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    private static int set(CommandSourceStack source, String raw) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        WeatherType type = parseWeather(raw);
        if (type == null) {
            return fail(source, Text.tr("nowweather.command.set.unknown", raw));
        }
        WeatherObservation manual = WeatherObservation.builder("manual")
                .weatherType(type)
                .conditionText(type.zhName())
                .temperatureC(Double.NaN)
                .confidence(1.0D)
                .reportTimeText("手动设定")
                .location("", "手动设定", "")
                .build();
        runtime.controller().applyManualObservation(manual);
        return ok(source, Text.tr("nowweather.command.set.done", type.zhName()));
    }

    /**
     * 解析玩家输入的天气名。
     *
     * <p>先交给 core 的 {@link WeatherType#parse}（它认识枚举名、去下划线/连字符写法，
     * 以及当前语言的展示名），再兜一层英文别名 —— 这样补全表里的 ASCII 枚举名、
     * 以及 {@code clear / rain / thunder} 这三个原版写法都一定解析得开。</p>
     */
    static WeatherType parseWeather(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        WeatherType byCore = WeatherType.parse(text, null);
        if (byCore != null && byCore != WeatherType.UNKNOWN) {
            return byCore;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "clear", "sunny" -> WeatherType.CLEAR;
            case "rain" -> WeatherType.RAIN;
            case "thunder", "storm" -> WeatherType.THUNDERSTORM;
            case "snow" -> WeatherType.SNOW;
            case "fog" -> WeatherType.FOG;
            case "haze" -> WeatherType.HAZE;
            case "sandstorm" -> WeatherType.SANDSTORM;
            case "blizzard" -> WeatherType.BLIZZARD;
            case "cloudy" -> WeatherType.CLOUDY;
            default -> null;
        };
    }

    /** {@code /nowweather info cache}：显示本地缓存与地点画像。 */
    private static int cacheStatus(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        var cache = runtime.controller().cache();
        StringBuilder sb = new StringBuilder();
        sb.append("§b").append(Text.tr("nowweather.command.info.cache.header")).append('\n');
        if (cache == null) {
            sb.append("§c").append(Text.tr("nowweather.command.info.cache.disabled")).append('\n');
            source.sendSuccess(new TextComponent(sb.toString()), false);
            return 1;
        }
        sb.append("§7").append(Text.tr("nowweather.command.info.cache.field.directory", cache.directory()))
                .append('\n');
        var profile = runtime.controller().locationProfile();
        sb.append("§7").append(Text.tr("nowweather.command.info.cache.field.profile", profile.describe()))
                .append('\n');
        sb.append("§7")
                .append(Text.tr("nowweather.command.info.cache.field.coords",
                        profile.hasCoordinates()
                                ? String.format(java.util.Locale.ROOT, "%.4f, %.4f",
                                        profile.latitude(), profile.longitude())
                                : Text.tr("nowweather.command.common.unknown"),
                        profile.hasElevation()
                                ? String.format(java.util.Locale.ROOT, "%.0f", profile.elevationMeters())
                                : Text.tr("nowweather.command.common.unknown"),
                        profile.hasCoordinates()
                                ? com.nowweather.core.cache.LocationProfile.climateBand(profile.latitude())
                                : Text.tr("nowweather.command.common.unknown")))
                .append('\n');
        sb.append("§7")
                .append(Text.tr("nowweather.command.info.cache.field.source",
                        profile.providerId().isEmpty()
                                ? Text.tr("nowweather.command.common.unknown")
                                : profile.providerId(),
                        profile.hemisphere().zhName()))
                .append('\n');
        var cached = runtime.controller().observation();
        if (cached != null) {
            long ageMinutes = Math.max(0L, (System.currentTimeMillis() - cached.observedAtEpochMillis()) / 60_000L);
            sb.append("§7").append(Text.tr("nowweather.command.info.cache.field.cached",
                    cached.weatherType().zhName(),
                    String.format(java.util.Locale.ROOT, "%.1f", cached.temperatureC()),
                    String.format(java.util.Locale.ROOT, "%.0f", (double) ageMinutes),
                    cached.providerId())).append('\n');
        } else {
            sb.append("§7").append(Text.tr("nowweather.command.info.cache.none")).append('\n');
        }
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    /** {@code /nowweather info cache clear}：清空本地缓存。 */
    private static int cacheClear(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null || runtime.controller().cache() == null) {
            return fail(source, Text.tr("nowweather.command.info.cache.unavailable"));
        }
        runtime.controller().cache().clear();
        return ok(source, Text.tr("nowweather.command.info.cache.cleared"));
    }

    /** {@code /nowweather info offline}：立刻演示一次「离线推算」，并说明推算依据。 */
    private static int offlinePreview(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        var controller = runtime.controller();
        var profile = controller.locationProfile();
        int offset = controller.timeZoneService().effectiveOffsetSeconds();
        long now = System.currentTimeMillis();
        var request = new com.nowweather.core.forecast.OfflineWeatherSynthesizer.Request(
                profile, now, offset, null, controller.observation(), 20260913L);
        var result = com.nowweather.core.forecast.OfflineWeatherSynthesizer.synthesize(request);
        var observation = result.observation();

        StringBuilder sb = new StringBuilder();
        sb.append("§b").append(Text.tr("nowweather.command.info.offline.header")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.offline.field.input", profile.describe()))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.offline.label.result")).append(' ')
                .append("§f").append(Text.tr("nowweather.command.info.offline.value",
                        observation.weatherType().zhName(),
                        String.format(java.util.Locale.ROOT, "%.1f", observation.temperatureC()),
                        String.format(java.util.Locale.ROOT, "%.0f", observation.humidityPercent()),
                        observation.windDirection().zhName(),
                        com.nowweather.core.weather.WindScale.describe(observation.windScale())))
                .append("§7 ").append(Text.tr("nowweather.command.info.offline.label.confidence")).append(' ')
                .append("§e").append(Text.tr("nowweather.command.info.offline.confidence_value",
                        String.format(java.util.Locale.ROOT, "%.0f", observation.confidence() * 100.0D)))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.offline.field.reason", result.explanation()))
                .append('\n');
        sb.append("§8").append(Text.tr("nowweather.command.info.offline.note"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    /** {@code /nowweather info time}：显示时区与真实时间同步状态。 */
    private static int timeStatus(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        WeatherController controller = runtime.controller();
        var zone = controller.timeZoneService();
        long now = System.currentTimeMillis();
        int offset = zone.effectiveOffsetSeconds();
        int[] hm = com.nowweather.core.time.RealTimeSyncModel.localHourMinute(now, offset);
        long target = com.nowweather.core.time.RealTimeSyncModel.ticksOfDay(now, offset);
        long worldDayTime = source.getLevel().getDayTime();
        int inGame = com.nowweather.core.time.RealTimeSyncModel.inGameHour(worldDayTime % 24000L);
        String hhmm = String.format(java.util.Locale.ROOT, "%02d:%02d", hm[0], hm[1]);

        StringBuilder sb = new StringBuilder();
        sb.append("§b").append(Text.tr("nowweather.command.info.time.header")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.enabled")).append(' ')
                .append("§f").append(runtime.config().timeSyncEnabled()
                        ? Text.tr("nowweather.command.common.on")
                        : Text.tr("nowweather.command.common.off"))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.zone")).append(' ')
                .append("§f").append(zone.zoneName()).append("§7")
                .append(Text.tr("nowweather.command.info.time.zone_source",
                        com.nowweather.core.time.TimeZoneService.formatOffset(offset), zone.source()))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.local_time")).append(' ')
                .append("§f").append(hhmm).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.game_hour")).append(' ')
                .append("§f").append(inGame).append("§7")
                .append(Text.tr("nowweather.command.info.time.game_hour_note",
                        String.valueOf(worldDayTime / 24000L),
                        String.valueOf(worldDayTime % 24000L)))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.target_daytime")).append(' ')
                .append("§f").append(target).append("§7")
                .append(Text.tr("nowweather.command.info.time.target_note", hhmm, String.valueOf(hm[0])))
                .append('\n');
        var model = controller.timeSyncModel();
        if (model != null) {
            sb.append("§7").append(Text.tr("nowweather.command.info.time.label.day_offset")).append(' ')
                    .append("§f").append(model.dayOffset()).append('\n');
        }
        // 月相：Minecraft 的月相就是「世界天数 % 8」（0 = 满月、4 = 新月），
        // 所以这里直接把「真实月相」和「游戏内月相」并排显示，对不对一眼就能看出来。
        int realPhase = com.nowweather.core.time.MoonPhaseModel.phaseIndex(now);
        int gamePhase = com.nowweather.core.time.RealTimeSyncModel.moonPhaseOf(worldDayTime);
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.moon_sync")).append(' ')
                .append("§f").append(runtime.config().moonPhaseSync()
                        ? Text.tr("nowweather.command.common.on")
                        : Text.tr("nowweather.command.common.off"))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.real_phase")).append(' ')
                .append("§f").append(com.nowweather.core.time.MoonPhaseModel.zhName(realPhase)).append("§7")
                .append(Text.tr("nowweather.command.info.time.real_phase_note",
                        String.valueOf(realPhase),
                        String.format(java.util.Locale.ROOT, "%.1f",
                                com.nowweather.core.time.MoonPhaseModel.ageDays(now))))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.time.label.game_phase")).append(' ')
                .append("§f").append(com.nowweather.core.time.MoonPhaseModel.zhName(gamePhase)).append("§7")
                .append(Text.tr("nowweather.command.info.time.game_phase_note", String.valueOf(gamePhase)))
                .append(realPhase == gamePhase
                        ? " §a" + Text.tr("nowweather.command.info.time.moon_match")
                        : " §c" + Text.tr("nowweather.command.info.time.moon_mismatch"))
                .append('\n');
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ 光影联动（/nowweather info shaders）

    /**
     * 光影状态：我们在喂什么、光影能吃到什么、以及为什么有些东西控不了。
     *
     * <p>写清楚能力边界比含糊其辞有用 —— 玩家问「为什么云量没变」时，
     * 这里要直接回答「云量是光影包自己的设置，Iris 没有给模组注入 uniform 的 API」。</p>
     */
    private static int shaderStatus(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        com.nowweather.forge.compat.ShaderBridge.invalidate();
        var controller = runtime.controller();
        long now = System.currentTimeMillis();
        int offset = controller.timeZoneService().effectiveOffsetSeconds();
        long worldDayTime = source.getLevel().getDayTime();
        int realPhase = com.nowweather.core.time.MoonPhaseModel.phaseIndex(now);
        int gamePhase = com.nowweather.core.time.RealTimeSyncModel.moonPhaseOf(worldDayTime);
        float rain = controller.rainIntensity();
        float thunder = controller.thunderIntensity();

        StringBuilder sb = new StringBuilder();
        sb.append("§b").append(Text.tr("nowweather.command.info.shaders.header")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.field.loader",
                com.nowweather.forge.compat.ShaderBridge.loader())).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.label.pack")).append(' ')
                .append("§f").append(com.nowweather.forge.compat.ShaderBridge.packName())
                .append(com.nowweather.forge.compat.ShaderBridge.packActive()
                        ? " §a" + Text.tr("nowweather.command.info.shaders.pack_active")
                        : " §8" + Text.tr("nowweather.command.info.shaders.pack_inactive"))
                .append('\n');
        String note = com.nowweather.forge.compat.ShaderBridge.note();
        if (!note.isEmpty()) {
            sb.append("§8  ").append(note).append('\n');
        }
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.label.intensity_sync")).append(' ')
                .append("§f").append(runtime.config().shaderIntensitySync()
                        ? Text.tr("nowweather.command.common.on")
                        : Text.tr("nowweather.command.common.off"))
                .append('\n');
        // ★ 把「原版雨渲染是否被接管」直接摆出来。
        // 这里曾经出过事故：只要探测到光影就接管原版雨渲染，OptiFine 在 shaderPack=OFF 时
        // 也可能报告已加载，结果「面板说在下雨，游戏里一滴都没有」。
        boolean suppressed = com.nowweather.forge.client.ShaderSkyBridge.isWeatherSuppressed();
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.label.vanilla_rain")).append(' ')
                .append(suppressed
                        ? "§e" + Text.tr("nowweather.command.info.shaders.rain_suppressed")
                        : "§a" + Text.tr("nowweather.command.info.shaders.rain_vanilla"))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.field.rain_level",
                Float.isNaN(com.nowweather.forge.client.ShaderSkyBridge.lastRainStrength())
                        ? Text.tr("nowweather.command.info.shaders.not_written")
                        : String.format(java.util.Locale.ROOT, "%.2f",
                                com.nowweather.forge.client.ShaderSkyBridge.lastRainStrength())))
                .append('\n');

        // ★★ 天色推导：把「真实云量 → 编码成 rainStrength → 光影解出的云量 → 天色亮度」
        //    整条链条摆出来。
        //
        //    为什么需要它：云量本身没有客观分界（「多云」和「中云」在数值上是连续的），
        //    光看天空根本没法判断天色对不对。有了这几个数，玩家可以直接对照：
        //    真实云量 22% → 写入 0.13 → 光影解出 22% → 天色 85% 亮度。
        //    任何一环对不上，都能立刻看出是哪一步的问题。
        //
        //    注意 cloudRainScale 属于 **Forge 配置**（客户端桥接直接读它算编码值），
        //    core 的 NowWeatherConfig 里没有这一项 —— 以前这里误写成 runtime.config()，
        //    会直接编译不过。
        double cloudCover = controller.realCloudCover();
        boolean hasCloud = !Double.isNaN(cloudCover);
        var currentObs = controller.observation();
        float writtenStrength = com.nowweather.forge.client.ShaderSkyBridge.lastRainStrength();
        double bandScale = com.nowweather.forge.config.ForgeConfig.COMMON.cloudRainScale.get();
        sb.append("§e").append(Text.tr("nowweather.command.info.shaders.derive.header")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.derive.cloud",
                hasCloud ? String.format(java.util.Locale.ROOT, "%.0f%%", cloudCover * 100.0D)
                        : Text.tr("nowweather.command.info.shaders.derive.cloud_none")))
                .append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.derive.type",
                currentObs == null || currentObs.weatherType() == null
                        ? Text.tr("nowweather.command.info.shaders.derive.type_none")
                        : currentObs.weatherType().zhName())).append('\n');
        if (hasCloud && !Float.isNaN(writtenStrength)) {
            sb.append("§7").append(Text.tr("nowweather.command.info.shaders.derive.rain_level",
                    String.format(java.util.Locale.ROOT, "%.2f", writtenStrength),
                    String.format(java.util.Locale.ROOT, "%.2f", bandScale))).append('\n');
            sb.append("§7").append(Text.tr("nowweather.command.info.shaders.derive.back_cloud",
                    String.format(java.util.Locale.ROOT, "%.0f%%",
                            Math.min(1.0D, writtenStrength / 0.60D) * 100.0D))).append('\n');
            sb.append("§7").append(Text.tr("nowweather.command.info.shaders.derive.brightness",
                    String.format(java.util.Locale.ROOT, "%.0f%%",
                            (1.0D - Math.min(1.0D, writtenStrength / 0.60D) * 0.45D) * 100.0D)))
                    .append('\n');
        } else {
            sb.append("§8").append(Text.tr("nowweather.command.info.shaders.derive.no_chain")).append('\n');
        }
        sb.append("§8").append(Text.tr("nowweather.command.info.shaders.derive.legend")).append('\n');

        sb.append("§e").append(Text.tr("nowweather.command.info.shaders.uniform_header")).append('\n');
        sb.append(String.format(java.util.Locale.ROOT, "§7  rainStrength    = §f%s§8",
                        Float.isNaN(writtenStrength)
                                ? Text.tr("nowweather.command.info.shaders.not_written")
                                : String.format(java.util.Locale.ROOT, "%.2f", writtenStrength)))
                .append(Text.tr("nowweather.command.info.shaders.uniform.rain_note")).append('\n');
        sb.append(String.format(java.util.Locale.ROOT, "§7  thunderStrength = §f%.2f§8", thunder))
                .append(Text.tr("nowweather.command.info.shaders.uniform.thunder_note")).append('\n');
        sb.append("§7  wetness         = §f")
                .append(Text.tr("nowweather.command.info.shaders.uniform.wetness_value"))
                .append("§8").append(Text.tr("nowweather.command.info.shaders.uniform.wetness_note"))
                .append('\n');
        sb.append(String.format(java.util.Locale.ROOT,
                "§7  worldTime/worldDay = §f%d / %d§8", worldDayTime % 24000L, worldDayTime / 24000L))
                .append(Text.tr("nowweather.command.info.shaders.uniform.time_note")).append('\n');
        sb.append("§7  moonPhase       = §f")
                .append(com.nowweather.core.time.MoonPhaseModel.zhName(gamePhase)).append("§7")
                .append(Text.tr("nowweather.command.info.shaders.uniform.moon_note",
                        com.nowweather.core.time.MoonPhaseModel.zhName(realPhase)))
                .append(gamePhase == realPhase ? " §a✔" : " §c✘")
                .append("§8")
                .append(Text.tr("nowweather.command.info.shaders.uniform.moon_note_tail"))
                .append('\n');
        sb.append("§7  sunAngle        = §f")
                .append(String.format(java.util.Locale.ROOT, "%.3f",
                        com.nowweather.core.time.RealTimeSyncModel.ticksOfDay(now, offset) / 24000.0D))
                .append("§8").append(Text.tr("nowweather.command.info.shaders.uniform.sun_note")).append('\n');

        // ★ rainStrength 是「云量 + 降水」共用的一条通道：不讲清区间，玩家会以为它只表示降水强度。
        sb.append("§8").append(Text.tr("nowweather.command.info.shaders.encoding.header")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.encoding.cloud")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.encoding.rain")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.encoding.buffer")).append('\n');

        sb.append("§8──────────────────────────\n");
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.can_control")).append('\n');
        sb.append("§a").append(Text.tr("nowweather.command.info.shaders.can_control_emphasis"))
                .append("§7").append(Text.tr("nowweather.command.info.shaders.can_control_rest"))
                .append('\n');
        sb.append("§e").append(Text.tr("nowweather.command.info.shaders.need_pack")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.adaptation")).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.shaders.pack_unadapted")).append('\n');
        sb.append("§8").append(Text.tr("nowweather.command.info.shaders.why"));
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ 真实天色预报（/nowweather forecast sky）

    /**
     * 真实逐小时天色预报：云量（含低/中/高）、能见度、降水概率。
     *
     * <p>这些数据来自气象服务的<b>真实预报</b>，不是我们自己的马尔可夫合成 ——
     * 所以它能回答「三小时后会不会转晴」这种合成预报回答不了的问题。</p>
     *
     * <p>{@code /nowweather info sky} 是它的别名，两个入口共用这一个实现。</p>
     */
    private static int skyForecast(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        var controller = runtime.controller();
        var sky = controller.skyForecast();
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("§b").append(Text.tr("nowweather.command.info.sky.header")).append('\n');
        if (sky == null || sky.isEmpty()) {
            sb.append("§7").append(Text.tr("nowweather.command.info.sky.no_data")).append('\n');
            sb.append("§8").append(Text.tr("nowweather.command.info.sky.requirement")).append('\n');
            sb.append("§8").append(Text.tr("nowweather.command.info.sky.disabled_hint")).append('\n');
            source.sendSuccess(new TextComponent(sb.toString()), false);
            return 1;
        }
        sb.append("§7").append(Text.tr("nowweather.command.info.sky.field.source",
                sky.providerId(), String.valueOf(sky.size()))).append('\n');
        sb.append("§7").append(Text.tr("nowweather.command.info.sky.field.current",
                String.format(java.util.Locale.ROOT, "%.0f", sky.cloudCoverAt(now) * 100.0D),
                String.format(java.util.Locale.ROOT, "%.1f", sky.visibilityAt(now))))
                .append('\n');
        var currentHour = sky.nearest(now);
        if (currentHour != null) {
            sb.append("§7")
                    .append(Text.tr("nowweather.command.info.sky.field.layers",
                            String.format(java.util.Locale.ROOT, "%.0f", currentHour.cloudLow01() * 100.0D),
                            String.format(java.util.Locale.ROOT, "%.0f", currentHour.cloudMid01() * 100.0D),
                            String.format(java.util.Locale.ROOT, "%.0f", currentHour.cloudHigh01() * 100.0D)))
                    .append('\n');
        }
        sb.append("§8──────────────────────────\n");
        var zone = java.time.ZoneId.systemDefault();
        for (var hour : sky.hours()) {
            String time = java.time.Instant.ofEpochMilli(hour.epochMillis()).atZone(zone)
                    .toLocalTime().toString().substring(0, 5);
            boolean upcomingRain = hour.precipitationProbability01() >= 0.4D;
            sb.append("§7")
                    .append(Text.tr("nowweather.command.info.sky.hour_line",
                            time,
                            String.format(java.util.Locale.ROOT, "%.0f", hour.cloudCover01() * 100.0D),
                            String.format(java.util.Locale.ROOT, "%.1f", hour.visibilityKm()),
                            String.format(java.util.Locale.ROOT, "%.1f", hour.precipitationMm()),
                            String.format(java.util.Locale.ROOT, "%.0f",
                                    hour.precipitationProbability01() * 100.0D)))
                    .append(upcomingRain
                            ? " §e" + Text.tr("nowweather.command.info.sky.rain_flag")
                            : "")
                    .append('\n');
        }
        sb.append("§8").append(Text.tr("nowweather.command.info.sky.footer")).append('\n');
        source.sendSuccess(new TextComponent(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ 自检（/nowweather test）

    private static int testHelp(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        sb.append(Text.tr("nowweather.command.test.help.header")).append('\n');
        appendHelpRows(sb, TEST_HELP_ROWS);
        // 去掉最后一行自带换行造成的空行
        return ok(source, sb.toString().stripTrailing());
    }

    private static java.util.List<String> biomeSuggestions() {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return java.util.List.of("minecraft:desert", "minecraft:snowy_plains");
        }
        return runtime.climates().allExplicit().stream()
                .map(c -> c.biomeId())
                .sorted()
                .limit(200)
                .toList();
    }

    private static int testBiome(CommandSourceStack source, String rawId) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        WeatherController controller = runtime.controller();
        if (rawId == null || rawId.isBlank()) {
            BiomeClimate forced = controller.forcedClimate();
            return ok(source, forced == null
                    ? Text.tr("nowweather.command.test.biome.none")
                    : Text.tr("nowweather.command.test.biome.forced", forced.describe()));
        }
        BiomeClimate climate = controller.resolveBiomeById(rawId.trim());
        controller.setForcedClimate(climate);
        return ok(source, Text.tr("nowweather.command.test.biome.set",
                climate.describe(),
                climate.tempRange().describe(),
                WeatherAllowance.describePlatformForPlayer(climate.possiblePlatformWeather(), climate)));
    }

    private static int testWeather(CommandSourceStack source, String rawWeather, double tempC) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        WeatherType type = parseWeather(rawWeather);
        if (type == null) {
            return fail(source, Text.tr("nowweather.command.test.weather.unknown", rawWeather));
        }
        WeatherController controller = runtime.controller();
        BiomeClimate climate = controller.forcedClimate();
        double temperature = Double.isNaN(tempC)
                ? (climate == null ? 15.0D : climate.tempRange().midpoint())
                : tempC;

        WeatherObservation forced = WeatherObservation.builder("selftest")
                .weatherType(type)
                .conditionText(type.zhName())
                .temperatureC(temperature)
                .humidityPercent(climate == null ? 60.0D : climate.humidity() * 100.0D)
                .windinessFromClimate(climate == null ? 0.4D : climate.windiness())
                .reportTimeText("自检")
                .build();
        controller.applyManualObservation(forced);

        WeatherDecision decision = controller.decideFor(climate, forced,
                source.getServer().getTickCount());
        boolean applied = climate != null
                && controller.applyAndVerify(controller.primaryDimension(), decision.target(),
                        decision.durationTicks());

        // 落地结果要说人话：以前 climate == null 时直接打印「失败或回读不一致」，
        // 而实际原因是「压根没写入（没有群系就无法做气候校验，于是跳过落地验证）」，
        // 这条误导性信息在实机排查时浪费了不少时间。
        String landing;
        if (climate == null) {
            landing = Text.tr("nowweather.command.test.weather.landing_not_written");
        } else if (applied) {
            landing = Text.tr("nowweather.command.test.weather.landing_ok");
        } else {
            com.nowweather.core.api.PlatformWeather actual =
                    controller.currentWeather(controller.primaryDimension());
            landing = Text.tr("nowweather.command.test.weather.landing_mismatch",
                    decision.target().zhName(),
                    actual == null ? Text.tr("nowweather.command.common.unknown") : actual.zhName(),
                    climate.biomeId(), climate.tempRange().describe(),
                    String.format(java.util.Locale.ROOT, "%.1f", decision.temperatureC()));
        }

        return ok(source, Text.tr("nowweather.command.test.weather.done",
                type.zhName(),
                String.format(java.util.Locale.ROOT, "%.1f", temperature),
                climate == null
                        ? Text.tr("nowweather.command.test.weather.no_climate")
                        : climate.biomeId(),
                decision.describe(),
                String.format(java.util.Locale.ROOT, "%.1f", temperature),
                String.format(java.util.Locale.ROOT, "%.1f", decision.temperatureC()),
                landing));
    }

    /**
     * 全群系 × 全天气自检：把「决策 → 写入 → 回读」整条链路跑一遍。
     *
     * <p>这是在没有玩家的专用服务器上验证气候逻辑的唯一办法 ——
     * 玩家的所在群系本来就应该是「被测试的输入」。</p>
     */
    private static int testMatrix(CommandSourceStack source, String namespace) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        WeatherController controller = runtime.controller();
        java.util.List<BiomeClimate> climates = new java.util.ArrayList<>(runtime.climates().allExplicit());
        if (namespace != null && !namespace.isBlank()) {
            climates.removeIf(c -> !c.biomeId().startsWith(namespace + ":"));
        }
        climates.sort(java.util.Comparator.comparing(BiomeClimate::biomeId));
        if (climates.isEmpty()) {
            return fail(source, Text.tr("nowweather.command.test.matrix.no_biomes", namespace));
        }

        WeatherType[] types = {
                WeatherType.CLEAR, WeatherType.CLOUDY, WeatherType.DRIZZLE, WeatherType.LIGHT_RAIN,
                WeatherType.RAIN, WeatherType.HEAVY_RAIN, WeatherType.THUNDERSTORM,
                WeatherType.SEVERE_THUNDERSTORM, WeatherType.SLEET, WeatherType.LIGHT_SNOW,
                WeatherType.SNOW, WeatherType.HEAVY_SNOW, WeatherType.BLIZZARD, WeatherType.HAIL,
                WeatherType.FREEZING_RAIN, WeatherType.FOG, WeatherType.HAZE, WeatherType.SANDSTORM,
                WeatherType.WINDY, WeatherType.TYPHOON
        };

        long tick = source.getServer().getTickCount();
        String dimension = controller.primaryDimension();
        int cases = 0;
        int appliedOk = 0;
        int applyFailed = 0;
        int whitelistViolations = 0;
        int temperatureViolations = 0;
        int unchanged = 0;
        int preserved = 0;
        java.util.Map<String, Integer> violationKinds = new java.util.TreeMap<>();
        StringBuilder failures = new StringBuilder();

        for (BiomeClimate climate : climates) {
            for (WeatherType type : types) {
                double temperature = temperatureFor(climate, type);
                WeatherObservation observation = WeatherObservation.builder("selftest")
                        .weatherType(type)
                        .conditionText(type.zhName())
                        .temperatureC(temperature)
                        .humidityPercent(climate.humidity() * 100.0D)
                        .windinessFromClimate(climate.windiness())
                        .build();
                cases++;
                WeatherDecision decision;
                try {
                    decision = controller.decideFor(climate, observation, tick);
                } catch (RuntimeException e) {
                    applyFailed++;
                    failures.append("  ! 决策异常 ").append(climate.biomeId()).append(" / ")
                            .append(type.name()).append(": ").append(e).append('\n');
                    continue;
                }

                // 「保持原版」的群系（下界/末地/虚空）：决策层刻意不修改天气，
                // 此时 weatherType 就是灌入的原始天气，按白名单校验没有意义。
                if (decision.durationTicks() == 0) {
                    preserved++;
                    continue;
                }

                if (!climate.allows(decision.weatherType())) {
                    whitelistViolations++;
                    failures.append("  ! 越出白名单 ").append(climate.biomeId()).append(" / ")
                            .append(type.name()).append(" -> ").append(decision.weatherType().name())
                            .append('\n');
                }
                if (!climate.tempRange().contains(decision.temperatureC(), runtime.config().temperatureToleranceC())) {
                    temperatureViolations++;
                    failures.append("  ! 温度越界 ").append(climate.biomeId()).append(" / ")
                            .append(type.name()).append(" 输入 ").append(String.format(java.util.Locale.ROOT, "%.1f", temperature))
                            .append(" -> ").append(String.format(java.util.Locale.ROOT, "%.1f", decision.temperatureC()))
                            .append("（正常 ").append(climate.tempRange().describe()).append("）\n");
                }
                if (decision.weatherType() == type) {
                    unchanged++;
                }
                decision.violations().forEach(v -> violationKinds.merge(v.name(), 1, Integer::sum));

                if (controller.applyAndVerify(dimension, decision.target(), decision.durationTicks())) {
                    appliedOk++;
                } else {
                    applyFailed++;
                }
            }
        }

        String summary = Text.tr("nowweather.command.test.matrix.summary",
                String.valueOf(climates.size()), String.valueOf(types.length), String.valueOf(cases),
                dimension,
                String.valueOf(appliedOk), String.valueOf(applyFailed),
                String.valueOf(whitelistViolations), String.valueOf(temperatureViolations),
                String.valueOf(unchanged),
                String.valueOf(Math.max(0, preserved - applyFailed)),
                violationKinds.toString());
        com.nowweather.forge.NowWeatherLog.LOGGER.info("{}{}", summary,
                failures.length() == 0 ? "\n（无任何失败用例）" : "\n问题明细：\n" + failures);
        return ok(source, summary.replace("\n", "\n  "));
    }

    /** 给自检选一个「这种天气真的发生时就该在这个温度」的输入值。 */
    private static double temperatureFor(BiomeClimate climate, WeatherType type) {
        if (type.isFrozen()) {
            return Math.max(-45.0D, climate.tempRange().minC() + 1.0D);
        }
        if (type.isPrecipitation()) {
            return Math.min(50.0D, climate.tempRange().maxC() - 1.0D);
        }
        if (type == WeatherType.SANDSTORM || type == WeatherType.HAZE) {
            return climate.tempRange().maxC() - 2.0D;
        }
        if (type == WeatherType.FOG) {
            return climate.tempRange().minC() + 2.0D;
        }
        return climate.tempRange().midpoint();
    }

    private static int testClear(CommandSourceStack source) {
        NowWeatherRuntime runtime = NowWeatherMod.runtime();
        if (runtime == null) {
            return fail(source, Text.tr("nowweather.command.common.not_initialized"));
        }
        runtime.controller().setForcedClimate(null);
        runtime.controller().clearManualOverride();
        return ok(source, Text.tr("nowweather.command.test.clear.done"));
    }

    // ------------------------------------------------------------------ 小工具

    private static String dimensionOf(CommandSourceStack source) {
        try {
            ServerLevel level = source.getLevel();
            return level.dimension().location().toString();
        } catch (RuntimeException e) {
            return "minecraft:overworld";
        }
    }

    private static BiomeFacts factsOf(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            return ForgeBiomeFactsFactory.biomeAt(player.getLevel(), player.blockPosition());
        } catch (Exception ignored) {
            // 没有玩家（专用服务器控制台）：用「命令源所在的位置」而不是世界出生点。
            // 这样 /execute positioned <x> <y> <z> run nowweather info biome 才能真正采样
            // 指定坐标的群系 —— 真机验证群系模组时这是唯一可用的手段，
            // 否则无论怎么 positioned 都只会返回出生点的群系。
            try {
                ServerLevel level = source.getLevel();
                return ForgeBiomeFactsFactory.biomeAt(level,
                        new net.minecraft.core.BlockPos(source.getPosition()));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** 让命令输出和被同步给客户端的对象保持同一套描述。 */
    public static String describePlayer(PlayerHandle player) {
        return player.name() + "@" + player.biomeId();
    }

    private static int ok(CommandSourceStack source, String message) {
        source.sendSuccess(new TextComponent(ChatFormatting.GREEN + "[NowWeather] " + ChatFormatting.RESET + message), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(new TextComponent(ChatFormatting.RED + "[NowWeather] " + ChatFormatting.RESET + message));
        return 0;
    }
}

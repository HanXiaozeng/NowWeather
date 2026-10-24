package com.nowweather.forge.llm;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.llm.AutoAssistSchedule;
import com.nowweather.core.llm.DailyCallQuota;
import com.nowweather.core.llm.LlmClient;
import com.nowweather.core.llm.LlmTiers;
import com.nowweather.core.llm.WeatherAssistant;
import com.nowweather.core.runtime.NowWeatherRuntime;
import com.nowweather.core.sync.WeatherController;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.http.JdkHttpTransport;
import com.nowweather.forge.NowWeatherLog;
import com.nowweather.forge.config.ConfigBridge;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「自动 AI 辅助」：真实天气缺失或不可信时，让服务器自己问一次模型，把结果垫上去。
 *
 * <h2>它解决的问题</h2>
 * <p>现实里最常见的两条链路是：数据源挂了（或 Key 过期），于是 {@code observation} 变成
 * 「离线推算」或者干脆为空 —— 此时游戏里的天气和外面的真实天气<b>已经没有关系了</b>。
 * 以前唯一的补救办法是玩家自己敲 {@code /nowweather forecast ai}，而没人会一直盯着敲。
 * 这个类把那次「手动问一次」变成自动的：条件满足时后台问一次，结果作为
 * <b>辅助观测</b>写入控制器（{@link WeatherController#applyAssistedObservation}），
 * 真实数据一恢复就被替换掉。</p>
 *
 * <h2>触发判定顺序（严格按这个来，任何一步不满足就整轮跳过）</h2>
 * <ol>
 *   <li><b>三个开关</b>：{@code llm.enabled} + {@code llm.weatherDaily} + {@code llm.autoAssist}
 *       全为 true。任何一个关掉都说明玩家不想要这个功能（也说明他不愿意为它花钱）；</li>
 *   <li><b>没有可用档位就直接不跑</b>：主 / 副两档都没配齐，或者今日配额都用完了 ——
 *       这时候起线程只会得到一个必然失败的请求；</li>
 *   <li><b>节奏</b>：距离上次尝试必须 ≥ {@link AutoAssistSchedule#intervalMinutes} 分钟
 *       （额度即节奏：1440 ÷ 每日次数）。这一次判断是纯算术；</li>
 *   <li><b>观测确实缺失或不可信</b>：见 {@link AutoAssistSchedule#needsAssist}。
 *       有新鲜真实数据时什么都不做 —— 这是「不费钱」的关键；</li>
 *   <li><b>玩家没有手动设过天气</b>：{@code isManualOverride()} 为 false。手动覆盖是玩家
 *       明确的意图表达，自动辅助不该去动它；</li>
 *   <li><b>上一次自动尝试还在进行中</b>：用一个 {@link AtomicBoolean} 防重入
 *       （tick 很密，而一次 AI 请求可能几十秒）。</li>
 * </ol>
 *
 * <h2>异步与配额</h2>
 * <ul>
 *   <li>真正发请求的工作全在 {@code NowWeather-AutoAssist} 守护线程里，
 *       <b>绝不在 tick 线程上等网络</b>；结果回到主线程再写观测（{@code Text.tr} 只在主线程用）；</li>
 *   <li>配额账本与 {@code /nowweather forecast ai} <b>同一份</b>（{@link LlmQuotaStore}），
 *       走的是同一套 {@link DailyCallQuota}；</li>
 *   <li>扣配额沿用现有约定：<b>只有成功的那一档才扣</b>。失败（Key 写错 / 网络不通 / 4xx）
 *       本来也基本不产生费用，扣了只会让玩家当天用不上真正的调用；</li>
 *   <li>无论成败都会更新「上次尝试时间」并留一行 INFO 日志（触发原因 + 用了哪一档 + 关键结果），
 *       这样「为什么 AI 没动静」能在日志里直接看出来。</li>
 * </ul>
 */
public final class AutoAssist {

    /** 正在跑的那一次尝试（防重入）。tick 每 5 刻一次，而一次请求可能几十秒。 */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    /**
     * 上次尝试的时间戳（epoch 毫秒）；{@code 0} = 本进程里从未试过。
     *
     * <p>记的是「<b>尝试</b>」而不是「成功」：失败也要等一个间隔再重试，
     * 否则 Key 写错时会变成每 5 刻发一次请求 —— 那是最糟的一种「不花钱但也没用」。</p>
     */
    private static volatile long lastAttemptMillis;

    private AutoAssist() {
    }

    /** 上一次自动尝试的时间（诊断/日志用）。 */
    public static long lastAttemptMillis() {
        return lastAttemptMillis;
    }

    /**
     * 复位（服务端启动/停止时调用）。
     *
     * <p>不清的话，单机玩家退出世界再进另一个世界，会沿用上一个世界留下的「刚才试过了」，
     * 白白多等一个间隔。防重入标记也一并清掉：上个世界的后台线程到这里必定已经结束。</p>
     */
    public static void reset() {
        lastAttemptMillis = 0L;
        IN_FLIGHT.set(false);
    }

    /**
     * 服务端 tick 调用：判断该不该现在问一次，该就起一个后台线程。
     *
     * <p>本方法必须<b>立刻返回</b>：它运行在服务器主线程上，任何一次网络等待都会卡住整个世界。</p>
     */
    public static void maybeRun(NowWeatherRuntime runtime, MinecraftServer server) {
        if (runtime == null || server == null) {
            return;
        }
        try {
            evaluate(runtime, server);
        } catch (RuntimeException e) {
            // 自动辅助是「锦上添花」，它自己出问题绝不能把服务器 tick 带崩。
            NowWeatherLog.LOGGER.warn("[AI 自动辅助] 判定阶段异常，本轮跳过: {}", e.toString());
        }
    }

    /** 判定 + 起线程。拆出来是为了让 {@link #maybeRun} 的 try/catch 只包一层。 */
    private static void evaluate(NowWeatherRuntime runtime, MinecraftServer server) {
        // ---- ① 三个开关
        if (!ConfigBridge.llmEnabled() || !ConfigBridge.llmWeatherDaily() || !ConfigBridge.llmAutoAssist()) {
            return;
        }
        WeatherController controller = runtime.controller();
        if (controller == null) {
            return;
        }

        // ---- ② 有没有一档真的能用（配齐 + 今天还有额度）
        long now = System.currentTimeMillis();
        int utcOffset = utcOffsetSeconds(controller);
        LlmTiers.Tier primary = ConfigBridge.llmPrimaryTier();
        LlmTiers.Tier secondary = ConfigBridge.llmSecondaryTier();
        DailyCallQuota.Usage usage = LlmQuotaStore.load(now, utcOffset);
        int cadence = cadenceCalls(primary, secondary, usage, now, utcOffset);
        if (cadence <= 0) {
            // 两档都不可用：不是「现在不跑」，而是「跑到下个间隔也不会成功」，直接返回。
            return;
        }

        // ---- ③ 节奏（纯算术）
        int intervalMinutes = AutoAssistSchedule.intervalMinutes(cadence);
        if (!AutoAssistSchedule.isDue(lastAttemptMillis, now, intervalMinutes)) {
            return;
        }

        // ---- ④ 观测确实缺失或不可信
        WeatherObservation current = controller.observation();
        if (!AutoAssistSchedule.needsAssist(current, runtime.config().staleAfterMillis(), now)) {
            return;
        }

        // ---- ⑤ 玩家手动设过天气就不插手
        if (controller.isManualOverride()) {
            return;
        }

        // ---- ⑥ 上一次还在跑
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }

        String reason = describeReason(current, runtime.config().staleAfterMillis(), now);
        // 「上次尝试时间」在发起的那一刻就更新：即使这条线程立刻异常退出，
        // 下一次也要等到下个间隔，绝不会变成刷屏式重试。
        lastAttemptMillis = now;
        final int offset = utcOffset;
        try {
            Thread worker = new Thread(
                    () -> perform(runtime, server, controller, primary, secondary, reason, offset),
                    "NowWeather-AutoAssist");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException e) {
            // 起线程失败（极少见）：必须把防重入的标记放掉，否则这个功能永久失效。
            IN_FLIGHT.set(false);
            NowWeatherLog.LOGGER.warn("[AI 自动辅助] 无法启动后台线程: {}", e.toString());
        }
    }

    /**
     * 该用哪个档位的 dailyCalls 当节奏；{@code <= 0} 表示两档都不可用。
     *
     * <p>用「第一档真能接活的档位」的次数：主模型配齐且有额度就用它的，
     * 否则看副模型 —— 这也正是回落链会做的选择。副模型没启用时它不算可用
     * （与 {@link LlmTiers.Tier#secondary} 的语义一致）。</p>
     */
    private static int cadenceCalls(LlmTiers.Tier primary, LlmTiers.Tier secondary,
                                    DailyCallQuota.Usage usage, long now, int utcOffset) {
        if (primary != null && primary.enabled() && primary.configured()
                && DailyCallQuota.available(usage, false, primary.dailyCalls(), now, utcOffset)) {
            return primary.dailyCalls();
        }
        if (secondary != null && secondary.enabled() && secondary.configured()
                && DailyCallQuota.available(usage, true, secondary.dailyCalls(), now, utcOffset)) {
            return secondary.dailyCalls();
        }
        return 0;
    }

    /** 后台线程：走主→副回落链，成功则把建议转成观测并回主线程应用。 */
    private static void perform(NowWeatherRuntime runtime, MinecraftServer server,
                                WeatherController controller, LlmTiers.Tier primary,
                                LlmTiers.Tier secondary, String reason, int utcOffset) {
        try {
            WeatherObservation current = controller.observation();
            String local = localSummary(current);
            String context = contextSummary(runtime);

            long now = System.currentTimeMillis();
            WeatherAssistant assistant = new WeatherAssistant(new LlmClient(new JdkHttpTransport()));
            // 回落链的回调只回传 Reply（它才是判断「这一档成没成」的依据），
            // 解析结果放在这一格里 —— 链在第一个成功处停下，所以它就是最终生效的那一次。
            WeatherAssistant.WeatherAttempt[] last = new WeatherAssistant.WeatherAttempt[1];
            LlmTiers.Outcome outcome;
            synchronized (LlmQuotaStore.LOCK) {
                DailyCallQuota.Usage snapshot = LlmQuotaStore.load(now, utcOffset);
                outcome = LlmTiers.run(primary, secondary,
                        (isSecondary, dailyCalls) -> DailyCallQuota.available(
                                snapshot, isSecondary, dailyCalls, now, utcOffset),
                        options -> {
                            WeatherAssistant.WeatherAttempt attempt =
                                    assistant.deriveWeatherAttempt(options, local, context);
                            last[0] = attempt;
                            return attempt.reply();
                        });
                // ★ 只有成功的那一档才扣配额（与 /nowweather forecast ai 同一套约定）。
                DailyCallQuota.Usage updated = snapshot;
                boolean charged = false;
                if (outcome.primary() != null && outcome.primary().succeeded()) {
                    updated = DailyCallQuota.consume(updated, false, now, utcOffset);
                    charged = true;
                }
                if (outcome.secondary() != null && outcome.secondary().succeeded()) {
                    updated = DailyCallQuota.consume(updated, true, now, utcOffset);
                    charged = true;
                }
                if (charged) {
                    LlmQuotaStore.save(updated);
                }
            }

            WeatherAssistant.WeatherSuggestion suggestion = last[0] == null
                    ? WeatherAssistant.WeatherSuggestion.failure("没有可用的模型档位")
                    : last[0].suggestion();

            if (!suggestion.success()) {
                NowWeatherLog.LOGGER.info("[AI 自动辅助] 触发原因={} | 未取得结果（{}）| {}",
                        reason, chainSummary(outcome), suggestion.error());
                return;
            }
            // 观测对象的拼装放在主线程：conditionText / reportTimeText 要查语言表，
            // 而语言表不是为跨线程并发设计的（与命令里「Text.tr 一律在主线程做」一致）。
            server.execute(() -> apply(controller, suggestion, reason, outcome, utcOffset));
        } catch (Throwable t) {
            // 任何异常（含 Error 之外的运行时异常）都不许逃出后台线程。
            NowWeatherLog.LOGGER.warn("[AI 自动辅助] 后台任务异常: {}", t.toString());
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    /** 主线程：把建议转成观测并交给控制器（不走手动覆盖）。 */
    private static void apply(WeatherController controller, WeatherAssistant.WeatherSuggestion suggestion,
                              String reason, LlmTiers.Outcome outcome, int utcOffset) {
        try {
            long now = System.currentTimeMillis();
            WeatherObservation assisted = WeatherAssistant.toObservation(suggestion, now, utcOffset);
            if (assisted == null) {
                // weather 字段解析不出来（模型乱写）：放弃应用，但把模型原话留在日志里便于排查。
                NowWeatherLog.LOGGER.warn(
                        "[AI 自动辅助] 触发原因={} | 模型给出的天气类型无法解析（weather={}），已放弃应用",
                        reason, suggestion.weather());
                return;
            }
            controller.applyAssistedObservation(assisted);
            NowWeatherLog.LOGGER.info(
                    "[AI 自动辅助] 触发原因={} | 档位={} | 已应用 {} {}°C 湿度 {}% 风 {} 级 置信度 {}%",
                    reason, outcome.usedSecondary() ? "副模型" : "主模型",
                    assisted.weatherType().name(),
                    String.format(Locale.ROOT, "%.1f", assisted.temperatureC()),
                    String.format(Locale.ROOT, "%.0f", assisted.humidityPercent()),
                    assisted.windScale(),
                    String.format(Locale.ROOT, "%.0f", suggestion.confidence() * 100.0D));
        } catch (RuntimeException e) {
            NowWeatherLog.LOGGER.warn("[AI 自动辅助] 应用观测失败: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 文案（日志恒为中文）

    /** 触发原因（写进日志，面向开发者，不进 i18n）。 */
    private static String describeReason(WeatherObservation observation, long staleAfterMillis, long now) {
        if (observation == null) {
            return "还没有任何观测";
        }
        if (observation.stale()) {
            return "观测已被标记为过期";
        }
        if (observation.isOlderThan(staleAfterMillis, now)) {
            return String.format(Locale.ROOT, "观测超过过期阈值 %d 分钟",
                    staleAfterMillis / 60_000L);
        }
        if (WeatherController.OFFLINE_PROVIDER_ID.equals(observation.providerId())) {
            return "当前是离线推算（不是真实数据）";
        }
        return "未知";
    }

    /** 两档各自的结局，一行说完（日志用）。 */
    private static String chainSummary(LlmTiers.Outcome outcome) {
        return "主：" + statusZh(outcome.primary()) + "；副：" + statusZh(outcome.secondary());
    }

    private static String statusZh(LlmTiers.Attempt attempt) {
        if (attempt == null) {
            return "未尝试";
        }
        return switch (attempt.status()) {
            case SUCCEEDED -> "成功";
            case FAILED -> "失败" + (attempt.error() == null || attempt.error().isBlank()
                    ? "" : "（" + attempt.error() + "）");
            case SKIPPED_DISABLED -> "未启用";
            case SKIPPED_NOT_CONFIGURED -> "未配置";
            case SKIPPED_QUOTA -> "今日配额已用完";
        };
    }

    /**
     * 喂给模型的「本地推算摘要」。
     *
     * <p>★ 与 {@code NowWeatherCommand#aiWeather} 里的拼法保持一致（同一条提示词、
     * 同一份语境），否则手动问一次和自动问一次会得到口径不同的回答。
     * 这一份是<b>给模型看的固定中文</b>，不进 i18n（模型不认识语言键）。</p>
     */
    static String localSummary(WeatherObservation current) {
        if (current == null) {
            return "（当前没有取到任何真实天气数据）";
        }
        return String.format(Locale.ROOT, "%s %.1f°C 湿度 %.0f%% 风 %d 级",
                current.weatherType().zhName(), current.temperatureC(),
                current.humidityPercent(), current.windScale());
    }

    /** 地点与气候语境（同 {@code aiWeather}）。坐标缺失时也给一段可用的描述，而不是直接放弃。 */
    static String contextSummary(NowWeatherRuntime runtime) {
        LocationProfile location = runtime.controller().locationProfile();
        if (location == null || !location.hasCoordinates()) {
            return "（没有坐标信息，按生物群系标签判断气候）";
        }
        return String.format(Locale.ROOT,
                "纬度 %.3f，经度 %.3f，海拔 %s，半球 %s，生物群系气候 %s",
                location.latitude(), location.longitude(),
                location.hasElevation()
                        ? String.format(Locale.ROOT, "%.0fm", location.elevationMeters()) : "未知",
                location.hemisphere().zhName(),
                runtime.controller().lastClimate() == null ? "未知"
                        : runtime.controller().lastClimate().tempRange().describe());
    }

    /** 当前所在地的 UTC 偏移（秒）；取不到就用 UTC，绝不抛异常。 */
    private static int utcOffsetSeconds(WeatherController controller) {
        try {
            return controller.timeZoneService().effectiveOffsetSeconds();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

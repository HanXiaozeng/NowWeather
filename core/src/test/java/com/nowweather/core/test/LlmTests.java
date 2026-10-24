package com.nowweather.core.test;

import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpResult;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.llm.AutoAssistSchedule;
import com.nowweather.core.llm.DailyCallQuota;
import com.nowweather.core.llm.LlmClient;
import com.nowweather.core.llm.LlmTiers;
import com.nowweather.core.llm.WeatherAssistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 接入测试。
 *
 * <p>全部用假传输层，<b>不发真实请求</b>（真实调用要花钱，而且测试不该依赖网络）。
 * 重点验证的是「省成本」与「安全」两件事：思考模式确实被关掉、每日预算生效、
 * 以及模型就算不守规矩也改不到白名单之外的配置。</p>
 */
final class LlmTests {

    private static final String OK_REPLY = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant",
             "content":"{\\"temperatureC\\": 22.5, \\"humidityPercent\\": 65, \\"windScale\\": 3, \\"weather\\": \\"RAIN\\", \\"confidence\\": 0.7, \\"reason\\": \\"梅雨季节偏高\\"}"},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":48}}""";

    private LlmTests() {
    }

    static void run() {
        T.section("LLM 客户端：请求构造与错误处理");
        client();

        T.section("thinking 字段只发给 DeepSeek");
        thinkingGating();

        T.section("每日一次预算（旧机制，已废弃但保留）");
        budget();

        T.section("每日调用配额（每个档位独立、按本地日重置）");
        quota();

        T.section("配额账本的持久化与旧结构兼容");
        quotaState();

        T.section("双档位回落：主失败 → 副成功");
        fallback();

        T.section("AI 辅助天气");
        derive();

        T.section("自然语言改配置：白名单是硬约束");
        intent();

        T.section("自动 AI 辅助：节奏、触发条件、建议→观测");
        autoAssist();
    }

    /** 把一段「模型回复正文」包装成 OpenAI 兼容的响应体。 */
    private static String apiReply(String content) {
        com.nowweather.core.json.JsonObject message = new com.nowweather.core.json.JsonObject();
        message.put("content", content);
        com.nowweather.core.json.JsonObject choice = new com.nowweather.core.json.JsonObject();
        choice.put("message", (com.nowweather.core.json.JsonValue) message);
        com.nowweather.core.json.JsonArray choices = new com.nowweather.core.json.JsonArray();
        choices.add((com.nowweather.core.json.JsonValue) choice);
        com.nowweather.core.json.JsonObject root = new com.nowweather.core.json.JsonObject();
        root.put("choices", (com.nowweather.core.json.JsonValue) choices);
        return root.toJson();
    }

    private static LlmClient.Options options() {
        return LlmClient.Options.of("sk-test-key");
    }

    private static void client() {
        T.FakeTransport transport = T.fakeTransport(OK_REPLY);
        LlmClient client = new LlmClient(transport);
        LlmClient.Reply reply = client.chat(options(), "系统提示", "用户输入");
        T.check("调用成功", reply.success());
        T.check("取到内容", reply.hasContent());
        T.near("统计了 prompt token", 120.0D, reply.promptTokens(), 0.5D);
        T.near("统计了 completion token", 48.0D, reply.completionTokens(), 0.5D);

        String body = transport.lastRequestBody();
        T.check("请求体里带上了模型名", body.contains("deepseek-v4-flash"));
        T.check("★ 关闭了思考模式（省输出 token）", body.contains("disabled"));
        T.check("max_tokens 被限制住（默认 1200）", body.contains("\"max_tokens\": 1200")
                || body.contains("\"max_tokens\":1200"));
        T.check("stream = false", body.contains("\"stream\": false") || body.contains("\"stream\":false"));
        T.check("请求消息里有 system 与 user", body.contains("系统提示") && body.contains("用户输入"));

        // 没有 Key 时不该发请求
        T.FakeTransport empty = T.fakeTransport(OK_REPLY);
        LlmClient.Reply noKey = new LlmClient(empty).chat(
                LlmClient.Options.of(""), "s", "u");
        T.check("没配 Key 时直接失败且不发请求",
                !noKey.success() && empty.callCount() == 0);

        // HTTP 错误要有可读提示
        LlmClient.Reply unauthorized = new LlmClient(
                T.fakeTransport("{}").status(401, "{}")).chat(options(), "s", "u");
        T.check("401 提示 Key 可能无效", !unauthorized.success()
                && unauthorized.error().contains("API Key"));
        LlmClient.Reply limited = new LlmClient(
                T.fakeTransport("{}").status(429, "{}")).chat(options(), "s", "u");
        T.check("429 提示触发限流", !limited.success() && limited.error().contains("限流"));

        // 空 choices 不该崩
        LlmClient.Reply blank = new LlmClient(T.fakeTransport("{\"choices\":[]}"))
                .chat(options(), "s", "u");
        T.check("空 choices 被当成失败而不是抛异常", !blank.success());
        // 非法 JSON 也不该崩
        LlmClient.Reply broken = new LlmClient(T.fakeTransport("not json at all"))
                .chat(options(), "s", "u");
        T.check("非 JSON 响应被兜住", !broken.success());
    }

    /**
     * 缺陷修复 ①：{@code thinking} 是 DeepSeek 专有字段，只能发给 DeepSeek 端点。
     *
     * <p>验证方式分两层：既断言「实际发出去的请求体里有没有这个字段」（用假传输层捕获正文），
     * 也直接单测判定函数本身 —— 光测请求体只能覆盖到两个端点，判定函数的边界（null / 大小写 /
     * 自建反代）还得单独点一下。</p>
     */
    private static void thinkingGating() {
        // 1) DeepSeek 端点：照旧发送 thinking:disabled
        T.FakeTransport deepseek = T.fakeTransport(OK_REPLY);
        new LlmClient(deepseek).chat(options(), "系统提示", "用户输入");
        T.check("DeepSeek 端点仍然带 thinking 字段（省输出 token）",
                deepseek.lastRequestBody().contains("thinking"));

        // 2) 非 DeepSeek 端点：一个字都不能出现 —— 否则 Ollama / vLLM / OneAPI 可能直接 400
        String localEndpoint = "http://127.0.0.1:11434/v1/chat/completions";
        T.FakeTransport local = T.fakeTransport(OK_REPLY);
        LlmClient.Options localOptions = options().withEndpoint(localEndpoint);
        new LlmClient(local).chat(localOptions, "系统提示", "用户输入");
        String body = local.lastRequestBody();
        T.check("★ 非 DeepSeek 端点不再发送 thinking 字段", !body.contains("thinking"));
        T.check("其余字段照常发送（model / max_tokens / stream）",
                body.contains("deepseek-v4-flash") && body.contains("max_tokens")
                        && body.contains("stream"));
        T.check("请求确实打到了那个端点", local.lastUrl().equals(localEndpoint));

        // 3) 思考模式打开时本来就不发 disabled（两种端点都一样）
        T.FakeTransport on = T.fakeTransport(OK_REPLY);
        new LlmClient(on).chat(options().withThinking(true), "s", "u");
        T.check("思考模式打开时不发 disabled", !on.lastRequestBody().contains("thinking"));

        // 4) 判定函数本身
        T.check("判定：DeepSeek 官方端点", LlmClient.supportsThinkingField(
                "https://api.deepseek.com/chat/completions"));
        T.check("判定：大小写不敏感（URL 是玩家手填的）", LlmClient.supportsThinkingField(
                "https://API.DeepSeek.com/v1/chat/completions"));
        T.check("判定：自建反代里带 deepseek 也算", LlmClient.supportsThinkingField(
                "https://llm.example.com/deepseek/v1/chat/completions"));
        T.check("判定：OpenRouter 不算", !LlmClient.supportsThinkingField(
                "https://openrouter.ai/api/v1/chat/completions"));
        T.check("判定：Ollama 不算", !LlmClient.supportsThinkingField(localEndpoint));
        T.check("判定：null 端点不算（不抛异常）", !LlmClient.supportsThinkingField(null));
    }

    private static void budget() {
        long now = 1_789_300_000_000L;
        T.check("从未调用过 → 可用", WeatherAssistant.budgetAvailable(0L, now));
        T.check("刚调用过 → 不可用", !WeatherAssistant.budgetAvailable(now - 1000L, now));
        T.check("23 小时后 → 仍不可用", !WeatherAssistant.budgetAvailable(now - 23L * 3600_000L, now));
        T.check("24 小时后 → 可用", WeatherAssistant.budgetAvailable(now - 24L * 3600_000L, now));
        T.near("刚调用过时剩余 ≈ 24 小时", 24.0D,
                WeatherAssistant.budgetRemainingMillis(now - 1000L, now) / 3600_000.0D, 0.01D);
        T.near("可用时剩余 = 0", 0.0D, WeatherAssistant.budgetRemainingMillis(0L, now), 1.0E-9D);
    }

    /** 东八区的某个时刻（测试统一用它，避免依赖运行机器的时区）。 */
    private static final long NOW = 1_790_500_000_000L;
    private static final int OFFSET_SECONDS = 8 * 3600;

    private static void quota() {
        DailyCallQuota.Usage empty = DailyCallQuota.Usage.EMPTY;
        T.check("空账本 → 主模型可用", DailyCallQuota.available(empty, false, 1, NOW, OFFSET_SECONDS));
        T.check("空账本 → 副模型可用", DailyCallQuota.available(empty, true, 2, NOW, OFFSET_SECONDS));

        DailyCallQuota.Usage one = DailyCallQuota.consume(empty, false, NOW, OFFSET_SECONDS);
        T.eq("主模型用掉 1 次后计数 = 1", 1, DailyCallQuota.used(one, false, NOW, OFFSET_SECONDS));
        T.check("配额 = 1 时用完 → 不可调用",
                !DailyCallQuota.available(one, false, 1, NOW, OFFSET_SECONDS));
        T.check("★ 副档位不受主档位影响（各自独立计数）",
                DailyCallQuota.available(one, true, 2, NOW, OFFSET_SECONDS));

        DailyCallQuota.Usage two = DailyCallQuota.consume(one, false, NOW, OFFSET_SECONDS);
        T.eq("再扣一次 → 2", 2, DailyCallQuota.used(two, false, NOW, OFFSET_SECONDS));
        T.check("配额 = 3 时仍有额度", DailyCallQuota.available(two, false, 3, NOW, OFFSET_SECONDS));
        T.check("配额 = 2 时刚好用完", !DailyCallQuota.available(two, false, 2, NOW, OFFSET_SECONDS));

        // ---- 跨天重置：按「本地日」，不是「距上次 24 小时」
        long dayStartLocal = DailyCallQuota.dayKey(NOW, OFFSET_SECONDS) * 86_400_000L
                - OFFSET_SECONDS * 1000L; // 本地 00:00 对应的 epoch 毫秒
        long lateNight = dayStartLocal + 86_400_000L - 60_000L;   // 本地 23:59
        long nextMidnight = dayStartLocal + 86_400_000L + 60_000L; // 次日 00:01
        DailyCallQuota.Usage usedLate = DailyCallQuota.consume(new DailyCallQuota.Usage(
                (int) DailyCallQuota.dayKey(lateNight, OFFSET_SECONDS), 0, 0, 0L, 0L),
                false, lateNight, OFFSET_SECONDS);
        T.eq("23:59 已用 1 次", 1,
                DailyCallQuota.used(usedLate, false, lateNight, OFFSET_SECONDS));
        T.check("★ 跨过本地零点后即使只过 2 分钟也重置（不是距上次 24 小时）",
                DailyCallQuota.available(usedLate, false, 1, nextMidnight, OFFSET_SECONDS));
        T.eq("跨天后计数读出来是 0", 0,
                DailyCallQuota.used(usedLate, false, nextMidnight, OFFSET_SECONDS));

        // 时区偏移确实参与日键计算：同一时刻在东八区与 UTC 可能不是同一天
        T.check("日键包含时区偏移（UTC 同刻的日键不大于东八区）",
                DailyCallQuota.dayKey(NOW, 0) <= DailyCallQuota.dayKey(NOW, OFFSET_SECONDS));
    }

    private static void quotaState() {
        // ---- 旧结构兼容：只有 weatherCallAt
        String legacyJson = "{\"weatherCallAt\":" + (NOW - 3_600_000L)
                + ",\"note\":\"AI 辅助天气推算每天最多一次\"}";
        DailyCallQuota.Usage legacy = DailyCallQuota.parse(legacyJson, NOW, OFFSET_SECONDS);
        T.eq("★ 旧结构 → 主模型今天已用 1 次", 1,
                DailyCallQuota.used(legacy, false, NOW, OFFSET_SECONDS));
        T.eq("旧结构 → 副模型仍是 0", 0,
                DailyCallQuota.used(legacy, true, NOW, OFFSET_SECONDS));
        T.check("旧结构 + 配额 1 → 今天不可再调",
                !DailyCallQuota.available(legacy, false, 1, NOW, OFFSET_SECONDS));

        // ---- 新结构往返
        DailyCallQuota.Usage used = DailyCallQuota.consume(
                DailyCallQuota.consume(DailyCallQuota.Usage.EMPTY, false, NOW, OFFSET_SECONDS),
                true, NOW, OFFSET_SECONDS);
        String json = DailyCallQuota.toJson(used);
        T.check("写出的 JSON 有新结构字段", json.contains("\"dayKey\"")
                && json.contains("\"primary\"") && json.contains("\"secondary\"")
                && json.contains("\"used\"") && json.contains("\"lastAt\""));
        DailyCallQuota.Usage round = DailyCallQuota.parse(json, NOW, OFFSET_SECONDS);
        T.eq("往返后主模型 1 次", 1, DailyCallQuota.used(round, false, NOW, OFFSET_SECONDS));
        T.eq("往返后副模型 1 次", 1, DailyCallQuota.used(round, true, NOW, OFFSET_SECONDS));

        // ---- 坏输入不能把功能弄挂
        T.check("非 JSON 返回空账本而不是抛异常",
                DailyCallQuota.used(DailyCallQuota.parse("not json at all", NOW, OFFSET_SECONDS),
                        false, NOW, OFFSET_SECONDS) == 0);
        T.check("空文本返回空账本",
                DailyCallQuota.used(DailyCallQuota.parse("", NOW, OFFSET_SECONDS),
                        false, NOW, OFFSET_SECONDS) == 0);
        T.check("null 返回空账本",
                DailyCallQuota.used(DailyCallQuota.parse(null, NOW, OFFSET_SECONDS),
                        false, NOW, OFFSET_SECONDS) == 0);
    }

    /**
     * 缺陷修复 ② 之外的核心：主 → 副回落。
     *
     * <p>用一个「按调用顺序依次返回预设响应」的假传输，把「主失败、副成功」这条最关键的路径
     * 完整跑一遍（连请求 URL 都检查，确认第二次真的打到了副模型的端点）。</p>
     */
    private static void fallback() {
        String secondaryEndpoint = "http://127.0.0.1:11434/v1/chat/completions";
        LlmClient.Options primaryOptions = options();
        LlmClient.Options secondaryOptions = options()
                .withEndpoint(secondaryEndpoint)
                .withModel("qwen2.5:7b");
        LlmTiers.Tier primary = LlmTiers.Tier.primary(primaryOptions, 1);
        LlmTiers.Tier secondary = LlmTiers.Tier.secondary(true, secondaryOptions, 2);

        // 1) 主 401 失败 → 副成功
        ScriptedTransport failThenOk = new ScriptedTransport()
                .thenStatus(401, "{}")
                .thenOk(OK_REPLY);
        LlmClient client = new LlmClient(failThenOk);
        LlmTiers.Outcome outcome = LlmTiers.run(primary, secondary, LlmTiers.UNLIMITED,
                options -> client.chat(options, "s", "u"));
        T.check("★ 主失败 → 副成功，整体算成功", outcome.success());
        T.check("结果标记为「已降级到副模型」", outcome.usedSecondary());
        T.eq("一共发了两次请求", 2, failThenOk.urls.size());
        T.eq("主档位的状态 = 失败", LlmTiers.Status.FAILED, outcome.primary().status());
        T.eq("副档位的状态 = 成功", LlmTiers.Status.SUCCEEDED, outcome.secondary().status());
        T.eq("第二次真的打到了副模型端点", secondaryEndpoint, failThenOk.urls.get(1));

        // 2) 主成功 → 副模型一次都不该花钱
        ScriptedTransport primaryOk = new ScriptedTransport().thenOk(OK_REPLY);
        LlmClient okClient = new LlmClient(primaryOk);
        LlmTiers.Outcome primaryOnly = LlmTiers.run(primary, secondary, LlmTiers.UNLIMITED,
                options -> okClient.chat(options, "s", "u"));
        T.check("主成功 → 整体成功且不算降级",
                primaryOnly.success() && !primaryOnly.usedSecondary());
        T.eq("主成功时只发一次请求", 1, primaryOk.urls.size());
        T.check("主成功时副档位压根没被尝试", primaryOnly.secondary() == null);

        // 3) 主配额用完 → 直接用副模型（也不该因为主档位配额用尽而整体失败）
        ScriptedTransport quotaOnly = new ScriptedTransport().thenOk(OK_REPLY);
        LlmClient quotaClient = new LlmClient(quotaOnly);
        LlmTiers.Outcome fromSecondary = LlmTiers.run(primary, secondary,
                (isSecondary, dailyCalls) -> isSecondary,
                options -> quotaClient.chat(options, "s", "u"));
        T.eq("★ 主档位：配额用完", LlmTiers.Status.SKIPPED_QUOTA, fromSecondary.primary().status());
        T.check("主配额用完时副模型接手", fromSecondary.success() && fromSecondary.usedSecondary());
        T.eq("只发了 1 次请求（主档位被跳过）", 1, quotaOnly.urls.size());

        // 4) 主未配置、副未启用 → 两个都不可用，且原因分得清
        ScriptedTransport unused = new ScriptedTransport().thenOk(OK_REPLY);
        LlmClient unusedClient = new LlmClient(unused);
        LlmTiers.Outcome none = LlmTiers.run(
                LlmTiers.Tier.primary(options().withModel(""), 1),
                LlmTiers.Tier.secondary(false, secondaryOptions, 2),
                LlmTiers.UNLIMITED,
                options -> unusedClient.chat(options, "s", "u"));
        T.check("两个都不可用 → 整体失败", !none.success());
        T.eq("主档位：未配置", LlmTiers.Status.SKIPPED_NOT_CONFIGURED, none.primary().status());
        T.eq("副档位：未启用", LlmTiers.Status.SKIPPED_DISABLED, none.secondary().status());
        T.eq("两次都没发请求", 0, unused.callCount);

        // 5) 全失败：两个档位各发一次，整体失败
        ScriptedTransport allFail = new ScriptedTransport()
                .thenStatus(401, "{}")
                .thenStatus(500, "{}");
        LlmClient allFailClient = new LlmClient(allFail);
        LlmTiers.Outcome both = LlmTiers.run(primary, secondary, LlmTiers.UNLIMITED,
                options -> allFailClient.chat(options, "s", "u"));
        T.check("主副都失败 → 整体失败", !both.success());
        T.eq("主档位失败", LlmTiers.Status.FAILED, both.primary().status());
        T.eq("副档位失败", LlmTiers.Status.FAILED, both.secondary().status());
        T.eq("两次都真的发了请求", 2, allFail.urls.size());

        // 6) 走完整条链也要能拿到副模型的解析结果（回落不能只回一句「成功」）
        ScriptedTransport derive = new ScriptedTransport()
                .thenStatus(500, "{}")
                .thenOk(OK_REPLY);
        WeatherAssistant assistant = new WeatherAssistant(new LlmClient(derive));
        WeatherAssistant.WeatherAttempt[] last = new WeatherAssistant.WeatherAttempt[1];
        LlmTiers.Outcome chain = LlmTiers.run(primary, secondary, LlmTiers.UNLIMITED, options -> {
            last[0] = assistant.deriveWeatherAttempt(options, "晴 27°C", "纬度 30.27");
            return last[0].reply();
        });
        T.check("回落链整体成功", chain.success() && chain.usedSecondary());
        T.check("副模型的结果被正常解析成天气建议",
                last[0] != null && last[0].suggestion().success()
                        && Math.abs(last[0].suggestion().temperatureC() - 22.5D) < 1.0E-9D);
    }

    /** 按顺序返回预设响应的假传输：回落测试必须能「第一次失败、第二次成功」。 */
    private static final class ScriptedTransport implements HttpTransport {

        private final List<HttpResult> script = new ArrayList<>();
        private final List<String> urls = new ArrayList<>();
        private int callCount;

        ScriptedTransport thenOk(String body) {
            script.add(new HttpResult(200, body, Map.of()));
            return this;
        }

        ScriptedTransport thenStatus(int code, String body) {
            script.add(new HttpResult(code, body, Map.of()));
            return this;
        }

        @Override
        public HttpResult execute(HttpRequestSpec spec) throws HttpException {
            callCount++;
            urls.add(spec.url());
            // 脚本用完时就一直重复最后一条，免得测试因为多调了一次而越界
            int index = Math.min(callCount - 1, script.size() - 1);
            return script.get(index);
        }
    }

    private static void derive() {
        T.FakeTransport transport = T.fakeTransport(OK_REPLY);
        WeatherAssistant assistant = new WeatherAssistant(new LlmClient(transport));
        WeatherAssistant.WeatherSuggestion suggestion = assistant.deriveWeather(
                options(), "晴 27.0°C 湿度 60% 西风 2 级", "纬度 30.27，海拔 13m，北半球，夏季，平原");

        T.check("解析成功", suggestion.success());
        T.near("温度", 22.5D, suggestion.temperatureC(), 1.0E-9D);
        T.near("湿度", 65.0D, suggestion.humidityPercent(), 1.0E-9D);
        T.eq("风力", 3, suggestion.windScale());
        T.eq("天气", "RAIN", suggestion.weather());
        T.near("置信度", 0.7D, suggestion.confidence(), 1.0E-9D);
        T.check("给出了理由", suggestion.reason().contains("梅雨"));

        // 模型套了 Markdown 代码块也要能解析。
        // 这里用自己的 JSON 构造器拼响应，避免手写转义出错（上一版就是手写转义写错导致假失败）。
        String inner = "```json\n{\"temperatureC\": 18, \"weather\": \"CLOUDY\"}\n```";
        String fenced = apiReply(inner);
        WeatherAssistant fencedAssistant = new WeatherAssistant(
                new LlmClient(T.fakeTransport(fenced)));
        WeatherAssistant.WeatherSuggestion s2 = fencedAssistant.deriveWeather(options(), "a", "b");
        T.check("模型套 Markdown 代码块时仍能解析出 JSON", s2.success()
                && Math.abs(s2.temperatureC() - 18.0D) < 1.0E-9D);

        // 湿度越界要被夹回去
        String wild = apiReply("{\"temperatureC\": 20, \"humidityPercent\": 250, \"windScale\": 99}");
        WeatherAssistant.WeatherSuggestion s3 = new WeatherAssistant(
                new LlmClient(T.fakeTransport(wild))).deriveWeather(options(), "a", "b");
        T.near("湿度上限被夹到 100", 100.0D, s3.humidityPercent(), 1.0E-9D);
        T.eq("风力上限被夹到 12", 12, s3.windScale());

        // 完全没法解析的输出
        WeatherAssistant.WeatherSuggestion s4 = new WeatherAssistant(
                new LlmClient(T.fakeTransport(apiReply("抱歉我不会"))))
                .deriveWeather(options(), "a", "b");
        T.check("非 JSON 输出被当成失败而不是当成天气", !s4.success());
    }

    private static void intent() {
        List<String> allowed = List.of("api.refreshIntervalSeconds", "location.city",
                "mapping.allowExtremeEvents", "api.apiKey");

        // 模型给了一条合法 + 一条非法（不在白名单）的改动
        String reply = apiReply("{\"changes\":[{\"key\":\"api.refreshIntervalSeconds\",\"value\":\"600\"},"
                + "{\"key\":\"debug.deleteEverything\",\"value\":\"true\"}],"
                + "\"reply\":\"已把刷新间隔改成 10 分钟\"}");
        WeatherAssistant assistant = new WeatherAssistant(
                new LlmClient(T.fakeTransport(reply)));
        WeatherAssistant.ConfigPatch patch = assistant.parseIntent(options(), "刷新快点", allowed);

        T.check("解析成功", patch.success());
        T.eq("★ 非法键被丢弃，只留合法的 1 条", 1, patch.changes().size());
        T.eq("键名", "api.refreshIntervalSeconds", patch.changes().get(0).key());
        T.eq("值", "600", patch.changes().get(0).value());
        T.check("带回了给用户的说明", patch.reply().contains("10 分钟"));

        // 大小写不一致的键要能纠正回白名单里的写法
        String messy = apiReply("{\"changes\":[{\"key\":\"API.RefreshIntervalSeconds\",\"value\":\"900\"}]}");
        WeatherAssistant.ConfigPatch p2 = new WeatherAssistant(
                new LlmClient(T.fakeTransport(messy))).parseIntent(options(), "x", allowed);
        T.eq("大小写被纠正为白名单里的标准写法", "api.refreshIntervalSeconds",
                p2.changes().get(0).key());

        // 纯提问：不改任何东西
        String question = apiReply("{\"changes\":[],\"reply\":\"刷新间隔目前的默认值是 1200 秒\"}");
        WeatherAssistant.ConfigPatch p3 = new WeatherAssistant(
                new LlmClient(T.fakeTransport(question))).parseIntent(options(), "默认刷新间隔是多少", allowed);
        T.check("纯提问不改配置", p3.success() && p3.isEmpty());
        T.check("答案写在 reply 里", p3.reply().contains("1200"));

        // 空输入与空白名单
        T.check("空输入被拒绝", !assistant.parseIntent(options(), "  ", allowed).success());
        T.check("空白名单被拒绝", !assistant.parseIntent(options(), "改点东西", List.of()).success());
    }

    /**
     * 自动 AI 辅助：三件纯计算的事。
     *
     * <ol>
     *   <li><b>节奏</b>（{@link AutoAssistSchedule#intervalMinutes}）：额度即节奏，
     *       1440 ÷ 每日次数，下限 1 分钟；</li>
     *   <li><b>到没到点</b>（{@link AutoAssistSchedule#isDue}）：从未试过立刻算到点，
     *       刚好等于间隔也算；</li>
     *   <li><b>要不要辅助</b>（{@link AutoAssistSchedule#needsAssist}）与
     *       <b>建议→观测</b>（{@link WeatherAssistant#toObservation}）。</li>
     * </ol>
     *
     * <p>这些是「自动辅助会不会乱花钱」的全部判据，所以全部做成纯函数在这里单测；
     * 「读控制器 / 起线程」那部分（Forge 侧的 {@code AutoAssist}）不在 core 里，也不该在这里测。</p>
     */
    private static void autoAssist() {
        // ---- ① 额度即节奏
        T.eq("dailyCalls=144 → 每 10 分钟检查一次", 10, AutoAssistSchedule.intervalMinutes(144));
        T.eq("dailyCalls=24 → 每小时一次", 60, AutoAssistSchedule.intervalMinutes(24));
        T.eq("dailyCalls=2 → 每 12 小时一次", 720, AutoAssistSchedule.intervalMinutes(2));
        T.eq("dailyCalls=1 → 每天一次", 1440, AutoAssistSchedule.intervalMinutes(1));
        T.eq("★ dailyCalls=1000 → 下限 1 分钟（不会退化成每 tick 都问）",
                1, AutoAssistSchedule.intervalMinutes(1000));
        T.eq("dailyCalls=0（非法值）按每天一次保守处理", 1440, AutoAssistSchedule.intervalMinutes(0));
        T.eq("间隔毫秒与分钟一致", 600_000L, AutoAssistSchedule.intervalMillis(144));

        // ---- ② 到没到点
        long now = 1_790_500_000_000L;
        T.check("从未试过（0）→ 立刻到点", AutoAssistSchedule.isDue(0L, now, 10));
        T.check("从未试过（负数）→ 也立刻到点", AutoAssistSchedule.isDue(-1L, now, 10));
        T.check("刚试过 → 没到点", !AutoAssistSchedule.isDue(now - 1000L, now, 10));
        T.check("差 1 毫秒 → 没到点", !AutoAssistSchedule.isDue(now - 599_999L, now, 10));
        T.check("★ 刚好到间隔 → 到点（>= 而不是 >）", AutoAssistSchedule.isDue(now - 600_000L, now, 10));
        T.check("超过间隔 → 到点", AutoAssistSchedule.isDue(now - 20L * 60_000L, now, 10));
        T.check("★ 系统时间被改回去（差值负数）→ 不触发，不会变成每刻都问",
                !AutoAssistSchedule.isDue(now + 3_600_000L, now, 10));

        // ---- ③ 要不要辅助：四种「不可信」都要触发，新鲜真实数据不要
        long staleAfter = 30L * 60_000L;
        T.check("没有观测 → 需要辅助", AutoAssistSchedule.needsAssist(null, staleAfter, now));
        T.check("★ 新鲜的真实观测 → 不需要辅助（这是「不费钱」的关键）",
                !AutoAssistSchedule.needsAssist(observation("uapipro", now, false), staleAfter, now));
        T.check("观测被标记为过期 → 需要辅助",
                AutoAssistSchedule.needsAssist(observation("uapipro", now, true), staleAfter, now));
        T.check("观测比过期阈值还旧 → 需要辅助",
                AutoAssistSchedule.needsAssist(observation("uapipro", now - 40L * 60_000L, false),
                        staleAfter, now));
        T.check("刚好等于过期阈值 → 仍算新鲜（isOlderThan 是 >）",
                !AutoAssistSchedule.needsAssist(observation("uapipro", now - staleAfter, false),
                        staleAfter, now));
        T.check("★ 当前是离线推算 → 需要辅助（离线推算不是真实数据）",
                AutoAssistSchedule.needsAssist(
                        observation(com.nowweather.core.sync.WeatherController.OFFLINE_PROVIDER_ID, now, false),
                        staleAfter, now));

        // ---- ④ 建议 → 观测
        WeatherAssistant.WeatherSuggestion ok = new WeatherAssistant.WeatherSuggestion(
                true, 22.5D, 65.0D, 3, "RAIN", 0.7D, "梅雨季节偏高", "");
        T.check("providerId 常量就是 llm", "llm".equals(WeatherAssistant.ASSIST_PROVIDER_ID));
        com.nowweather.core.weather.WeatherObservation assisted =
                WeatherAssistant.toObservation(ok, now, OFFSET_SECONDS);
        T.check("能转换出观测", assisted != null);
        if (assisted != null) {
            T.eq("来源标记为 AI 辅助", WeatherAssistant.ASSIST_PROVIDER_ID, assisted.providerId());
            T.eq("天气类型", com.nowweather.core.weather.WeatherType.RAIN, assisted.weatherType());
            T.near("温度", 22.5D, assisted.temperatureC(), 1.0E-9D);
            T.near("湿度", 65.0D, assisted.humidityPercent(), 1.0E-9D);
            T.eq("风力", 3, assisted.windScale());
            T.near("置信度", 0.7D, assisted.confidence(), 1.0E-9D);
            T.eq("观测时间 = 传入的 now", now, assisted.observedAtEpochMillis());
            T.eq("取数时间 = 传入的 now", now, assisted.fetchedAtEpochMillis());
            T.eq("时区偏移原样带过来", OFFSET_SECONDS, assisted.utcOffsetSeconds());
            T.check("★ stale=false（它是刚算出来的估计，不是旧数据）", !assisted.stale());
            T.check("conditionText 标注了 AI 辅助",
                    assisted.conditionText().contains("AI 辅助"));
            T.check("reportTimeText 标注了 AI 辅助",
                    assisted.reportTimeText().contains("AI 辅助"));
        }

        // 湿度缺失（NaN）→ 保留 -1「未知」，不能被写成 0%
        WeatherAssistant.WeatherSuggestion noHumidity = new WeatherAssistant.WeatherSuggestion(
                true, 10.0D, Double.NaN, -1, "SNOW", 0.5D, "", "");
        com.nowweather.core.weather.WeatherObservation dry =
                WeatherAssistant.toObservation(noHumidity, now, OFFSET_SECONDS);
        T.check("湿度未知时保留「未知」（-1）而不是 0",
                dry != null && dry.humidityPercent() < 0.0D);

        // weather 乱写 → 返回 null（调用方放弃应用），绝不用 UNKNOWN 兜底覆盖现有天气
        WeatherAssistant.WeatherSuggestion gibberish = new WeatherAssistant.WeatherSuggestion(
                true, 20.0D, 50.0D, 2, "龙卷风夹冰雹", 0.5D, "", "");
        T.check("★ weather 解析不出来 → 返回 null（不覆盖现有观测）",
                WeatherAssistant.toObservation(gibberish, now, OFFSET_SECONDS) == null);
        T.check("失败的建议 → 返回 null",
                WeatherAssistant.toObservation(
                        WeatherAssistant.WeatherSuggestion.failure("没额度"), now, OFFSET_SECONDS) == null);
        T.check("null 建议 → 返回 null（不抛异常）",
                WeatherAssistant.toObservation(null, now, OFFSET_SECONDS) == null);
    }

    /** 造一条观测：{@code provider} 来源、{@code fetchedAt} 时间、是否过期。 */
    private static com.nowweather.core.weather.WeatherObservation observation(
            String provider, long fetchedAt, boolean stale) {
        return com.nowweather.core.weather.WeatherObservation.builder(provider)
                .weatherType(com.nowweather.core.weather.WeatherType.CLEAR)
                .temperatureC(20.0D)
                .humidityPercent(50.0D)
                .windScale(2)
                .fetchedAt(fetchedAt)
                .observedAt(fetchedAt)
                .stale(stale)
                .build();
    }
}

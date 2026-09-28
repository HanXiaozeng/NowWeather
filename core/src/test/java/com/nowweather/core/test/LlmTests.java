package com.nowweather.core.test;

import com.nowweather.core.llm.LlmClient;
import com.nowweather.core.llm.WeatherAssistant;

import java.util.List;

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

        T.section("每日一次预算（省成本）");
        budget();

        T.section("AI 辅助天气");
        derive();

        T.section("自然语言改配置：白名单是硬约束");
        intent();
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
}

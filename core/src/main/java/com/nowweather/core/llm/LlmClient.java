package com.nowweather.core.llm;

import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpResult;
import com.nowweather.core.http.HttpTransport;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonException;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.log.SyncLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 LLM 客户端（OpenAI 兼容的 {@code /chat/completions}）。
 *
 * <p>默认对接 DeepSeek。刻意做成「只会说话」的最小实现：没有流式、没有函数调用、
 * 没有多轮会话状态 —— 提示词由调用方（{@link WeatherAssistant}）拼，会话历史也由它管。</p>
 *
 * <h2>成本控制</h2>
 * <p>这是本项目<b>唯一会产生费用的功能</b>，所以：</p>
 * <ul>
 *   <li>调用次数由 {@link WeatherAssistant} 的「每天一次」预算兜住，本类只负责发请求；</li>
 *   <li>{@code maxTokens} 有上限（默认 {@link #DEFAULT_MAX_TOKENS}），防止模型失控长篇大论；</li>
 *   <li>请求体里的 {@code thinking} 默认关闭（DeepSeek 的思考模式会显著增加输出 token）。</li>
 * </ul>
 *
 * <h2>安全</h2>
 * <p>API Key 只在本类里作为请求头发送，<b>不写日志</b>。异常信息里也不带 Key。</p>
 */
public final class LlmClient {

    /** DeepSeek 官方端点。 */
    public static final String DEEPSEEK_ENDPOINT = "https://api.deepseek.com/chat/completions";

    /** 默认模型：用户指定为 deepseek-v4-flash 且关闭思考。 */
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

    /** 默认输出上限（token）：这是花钱的地方，宁可截断也不要失控。 */
    public static final int DEFAULT_MAX_TOKENS = 1200;

    private final HttpTransport transport;
    private final SyncLogger logger;

    public LlmClient(HttpTransport transport) {
        this(transport, Loggers.console(SyncLogger.Level.WARN));
    }

    public LlmClient(HttpTransport transport, SyncLogger logger) {
        this.transport = transport;
        this.logger = logger;
    }

    /** 一条对话消息。 */
    public record Message(String role, String content) {

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /** 调用参数。 */
    public record Options(String endpoint, String apiKey, String model, double temperature,
                          int maxTokens, boolean thinking, long timeoutMillis) {

        public static Options of(String apiKey) {
            return new Options(DEEPSEEK_ENDPOINT, apiKey, DEFAULT_MODEL, 0.2D,
                    DEFAULT_MAX_TOKENS, false, 30_000L);
        }

        public Options withModel(String newModel) {
            return new Options(endpoint, apiKey, newModel, temperature, maxTokens, thinking, timeoutMillis);
        }

        public Options withEndpoint(String newEndpoint) {
            return new Options(newEndpoint, apiKey, model, temperature, maxTokens, thinking, timeoutMillis);
        }

        public Options withThinking(boolean enabled) {
            return new Options(endpoint, apiKey, model, temperature, maxTokens, enabled, timeoutMillis);
        }

        public Options withMaxTokens(int tokens) {
            return new Options(endpoint, apiKey, model, temperature, Math.max(64, tokens), thinking, timeoutMillis);
        }

        public Options withTimeoutMillis(long millis) {
            return new Options(endpoint, apiKey, model, temperature, maxTokens, thinking,
                    Math.max(3_000L, millis));
        }
    }

    /** 调用结果。 */
    public record Reply(boolean success, String content, String error, int promptTokens,
                        int completionTokens, long elapsedMillis) {

        public static Reply failure(String error) {
            return new Reply(false, "", error, 0, 0, 0L);
        }

        public boolean hasContent() {
            return success && content != null && !content.isBlank();
        }
    }

    /** 发一次对话请求。 */
    public Reply chat(Options options, List<Message> messages) {
        if (options == null || options.apiKey() == null || options.apiKey().isBlank()) {
            return Reply.failure("未配置 API Key");
        }
        if (messages == null || messages.isEmpty()) {
            return Reply.failure("消息为空");
        }
        JsonObject body = new JsonObject();
        body.put("model", options.model());
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.put("role", message.role());
            item.put("content", message.content());
            array.add(item);
        }
        body.put("messages", (JsonValue) array);
        body.put("stream", false);
        body.put("temperature", options.temperature());
        body.put("max_tokens", options.maxTokens());
        if (!options.thinking()) {
            // DeepSeek 的思考模式会显著增加输出 token（= 成本）。关闭方式随版本略有差异，
            // 这里同时给两种写法，服务端不认识的那一个会被忽略。
            JsonObject thinking = new JsonObject();
            thinking.put("type", "disabled");
            body.put("thinking", (JsonValue) thinking);
        }

        long start = System.currentTimeMillis();
        HttpResult response;
        try {
            response = transport.execute(HttpRequestSpec.post(options.endpoint())
                    .header("Content-Type", "application/json")
                    .bearerToken(options.apiKey())
                    .header("Accept", "application/json")
                    .body(body.toJson())
                    .timeout(Duration.ofMillis(options.timeoutMillis()))
                    .build());
        } catch (HttpException e) {
            // 注意：e.getMessage() 由传输层生成，不含请求头
            logger.warn("LLM 请求失败: {}", e.getMessage());
            return Reply.failure((e.isNetworkLevel() ? "网络错误：" : "请求错误：") + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;

        if (!response.isSuccess()) {
            String hint = response.statusCode() == 401 ? "（API Key 可能无效）"
                    : response.statusCode() == 402 ? "（余额不足）"
                    : response.statusCode() == 429 ? "（触发限流）" : "";
            return new Reply(false, "", "HTTP " + response.statusCode() + hint, 0, 0, elapsed);
        }
        try {
            JsonObject root = JsonValue.parse(response.body()).asObjectOrNull();
            if (root == null) {
                return new Reply(false, "", "响应不是 JSON 对象", 0, 0, elapsed);
            }
            JsonArray choices = root.optArray("choices");
            if (choices == null || choices.isEmpty()) {
                String error = root.optString("error", "");
                return new Reply(false, "", error.isEmpty() ? "响应里没有 choices" : error, 0, 0, elapsed);
            }
            JsonObject first = choices.get(0).asObjectOrNull();
            JsonObject message = first == null ? null : first.optObject("message");
            String content = message == null ? "" : message.optString("content", "");
            JsonObject usage = root.optObject("usage");
            int prompt = usage == null ? 0 : usage.optInt("prompt_tokens", 0);
            int completion = usage == null ? 0 : usage.optInt("completion_tokens", 0);
            if (content.isBlank()) {
                return new Reply(false, "", "模型返回了空内容", prompt, completion, elapsed);
            }
            logger.info("LLM 调用成功（{}ms，prompt {} / completion {} token）",
                    elapsed, prompt, completion);
            return new Reply(true, content, "", prompt, completion, elapsed);
        } catch (JsonException e) {
            return new Reply(false, "", "响应解析失败：" + e.getMessage(), 0, 0, elapsed);
        }
    }

    /** 便捷方法：单条 system + 单条 user。 */
    public Reply chat(Options options, String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.add(Message.user(userPrompt));
        return chat(options, messages);
    }
}

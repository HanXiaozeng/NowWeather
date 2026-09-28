package com.nowweather.core.llm;

import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonException;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.log.SyncLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 天气助手：把 LLM 用在两个地方，并且<b>只</b>用在两个地方。
 *
 * <ol>
 *   <li><b>AI 辅助推算天气</b>（{@link #deriveWeather}）：有网络时，用「本地算法的推算结果 + AI 的经验修正」
 *       得到一份更合理的天气。这部分<b>每天最多调用一次</b>（见 {@link #DAILY_BUDGET_MILLIS}），
 *       结果写进缓存当天的剩余时间都用它 —— 这是为了省成本，也是因为天气本来就不会每小时剧变。</li>
 *   <li><b>自然语言改配置</b>（{@link #parseIntent}）：{@code /nowweather ask 帮我调整下api...}，
 *       模型只被允许返回一份<b>白名单内的键值对</b>，最终由平台层用与配置界面完全相同的
 *       {@code ConfigEntries} 校验并写入 —— 也就是说 <b>AI 没有任何绕过范围校验的能力</b>。</li>
 * </ol>
 *
 * <h2>为什么「AI 改配置」必须走白名单</h2>
 * <p>模型可能返回不存在的键、越界的值、甚至一大段解释性文字。如果直接把它的输出当成配置写进去，
 * 一个幻觉就能把模组配置写坏（比如把刷新间隔设成 0 导致疯狂请求接口）。所以这里的约定是：
 * 模型只能「建议」，{@link ConfigPatch} 只承载<b>已解析出来的键值对</b>，
 * 真正生效要交给平台层逐项校验。</p>
 */
public final class WeatherAssistant {

    /** 两次「AI 辅助天气」之间的最小间隔：24 小时。 */
    public static final long DAILY_BUDGET_MILLIS = 24L * 60L * 60L * 1000L;

    private static final String WEATHER_SYSTEM_PROMPT = """
            你是一个气象数据校正助手，服务于一个把真实天气同步进 Minecraft 的模组。
            用户会给你一份「本地算法推算出的天气」，以及该地点的纬度、经度、海拔、半球、季节与生物群系气候。
            你的任务是：结合你的气象常识，指出这份推算里最可能的偏差并给出修正后的数值。

            严格按下面的 JSON 格式回答，不要输出任何解释文字、不要用 Markdown 代码块：
            {"temperatureC": 数字, "humidityPercent": 0-100 的数字, "windScale": 0-12 的整数,
             "weather": "CLEAR|CLOUDY|RAIN|SNOW|THUNDERSTORM|FOG|SLEET 之一",
             "confidence": 0-1 的数字, "reason": "不超过 40 字的理由"}

            约束：
            1. 温度必须落在生物群系的「正常温度范围」内；超范围时向范围内收敛，并在 reason 里说明。
            2. 不要臆造台风、沙尘暴这类需要专门数据支持的极端天气。
            3. 你的修正幅度要保守：本地推算的 1 倍标准差约 3°C，超过这个幅度的调整必须有明确理由。
            4. 只输出 JSON。""";

    private static final String INTENT_SYSTEM_PROMPT = """
            你是一个 Minecraft 模组配置助手。用户用自然语言提出修改配置的请求，
            你要把它翻译成若干条「配置键 = 新值」。

            可用配置键（只能从这里选，键名必须逐字一致）：
            %s

            严格按下面的 JSON 格式回答，不要输出任何解释文字、不要用 Markdown 代码块：
            {"changes": [{"key": "配置键", "value": "新值"}], "reply": "给用户看的一句话说明"}

            约束：
            1. value 一律用字符串表示（数字也写成 "1200"）。
            2. 用户的说法可能对应多个键，按最合理的理解给；拿不准就不要改，宁可少给。
            3. 如果用户只是提问而不是要求修改，changes 返回空数组，把答案写在 reply 里。
            4. 不要建议任何不在上面列表里的键。只输出 JSON。
            5. 用户可能会问「现在是多少」这类问题：这时请依据给出的「当前配置」如实回答，
               changes 留空，把答案写在 reply 里。绝对不要编造配置值。""";

    private final LlmClient client;
    private final SyncLogger logger;

    public WeatherAssistant(LlmClient client) {
        this(client, Loggers.console(SyncLogger.Level.WARN));
    }

    public WeatherAssistant(LlmClient client, SyncLogger logger) {
        this.client = client;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ AI 辅助天气

    /** AI 修正后的天气建议。 */
    public record WeatherSuggestion(boolean success, double temperatureC, double humidityPercent,
                                    int windScale, String weather, double confidence, String reason,
                                    String error) {

        public static WeatherSuggestion failure(String error) {
            return new WeatherSuggestion(false, Double.NaN, Double.NaN, -1, "", 0.0D, "", error);
        }
    }

    /**
     * 用 AI 修正本地推算出的天气。
     *
     * @param localSummary     本地算法的推算摘要（人话，直接喂给模型）
     * @param contextSummary   地点与气候上下文（纬度/海拔/半球/季节/群系温度范围）
     */
    public WeatherSuggestion deriveWeather(LlmClient.Options options, String localSummary,
                                           String contextSummary) {
        String user = "地点与气候：\n" + contextSummary + "\n\n本地算法推算：\n" + localSummary;
        LlmClient.Reply reply = client.chat(options, WEATHER_SYSTEM_PROMPT, user);
        if (!reply.hasContent()) {
            return WeatherSuggestion.failure(reply.error());
        }
        JsonObject json = extractJsonObject(reply.content());
        if (json == null) {
            return WeatherSuggestion.failure("模型输出不是 JSON");
        }
        try {
            double temperature = json.optDouble("temperatureC", Double.NaN);
            double humidity = json.optDouble("humidityPercent", Double.NaN);
            int wind = json.optInt("windScale", -1);
            String weather = json.optString("weather", "").trim().toUpperCase(Locale.ROOT);
            double confidence = json.optDouble("confidence", 0.5D);
            String reason = json.optString("reason", "");
            if (!Double.isFinite(temperature)) {
                return WeatherSuggestion.failure("模型没有给出温度");
            }
            return new WeatherSuggestion(true, temperature,
                    Double.isFinite(humidity) ? Math.max(0.0D, Math.min(100.0D, humidity)) : Double.NaN,
                    wind < 0 ? -1 : Math.min(12, wind), weather,
                    Math.max(0.0D, Math.min(1.0D, confidence)), reason, "");
        } catch (RuntimeException e) {
            return WeatherSuggestion.failure("字段解析失败：" + e.getMessage());
        }
    }

    /** 是否已超过每日预算。 */
    public static boolean budgetAvailable(long lastCallEpochMillis, long nowEpochMillis) {
        if (lastCallEpochMillis <= 0L) {
            return true;
        }
        return nowEpochMillis - lastCallEpochMillis >= DAILY_BUDGET_MILLIS;
    }

    /** 距离下次可用还有多少毫秒（可用时返回 0）。 */
    public static long budgetRemainingMillis(long lastCallEpochMillis, long nowEpochMillis) {
        if (lastCallEpochMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, DAILY_BUDGET_MILLIS - (nowEpochMillis - lastCallEpochMillis));
    }

    // ------------------------------------------------------------------ 自然语言改配置

    /** 一条待应用的配置改动。 */
    public record Change(String key, String value) {
    }

    /** 意图解析结果。 */
    public record ConfigPatch(boolean success, List<Change> changes, String reply, String error) {

        public static ConfigPatch failure(String error) {
            return new ConfigPatch(false, List.of(), "", error);
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    /**
     * 把用户的一句话翻译成配置改动。
     *
     * @param allowedKeys 允许模型改的键（由平台层从配置表里取出，服务端会滤掉客户端项）
     */
    public ConfigPatch parseIntent(LlmClient.Options options, String userText, List<String> allowedKeys) {
        return parseIntent(options, userText, allowedKeys, "");
    }

    /**
     * 带「当前配置快照」的版本：模型因此能回答「我现在刷新间隔是多少」这类问题，
     * 也能基于现状给出更合理的修改建议，而不是凭空猜。
     *
     * @param currentConfig 当前配置的多行文本（形如 {@code key = value}）；密钥由调用方打码
     */
    public ConfigPatch parseIntent(LlmClient.Options options, String userText,
                                   List<String> allowedKeys, String currentConfig) {
        if (userText == null || userText.isBlank()) {
            return ConfigPatch.failure("请输入你想调整的内容");
        }
        if (allowedKeys == null || allowedKeys.isEmpty()) {
            return ConfigPatch.failure("当前没有可修改的配置项");
        }
        String system = INTENT_SYSTEM_PROMPT.replace("%s", String.join("\n", allowedKeys));
        String user = (currentConfig == null || currentConfig.isBlank())
                ? userText
                : "当前配置（节选，密钥已打码）：\n" + currentConfig + "\n\n用户请求：" + userText;
        LlmClient.Reply reply = client.chat(options, system, user);
        if (!reply.hasContent()) {
            return ConfigPatch.failure(reply.error());
        }
        JsonObject json = extractJsonObject(reply.content());
        if (json == null) {
            return ConfigPatch.failure("模型输出不是 JSON");
        }
        List<Change> changes = new ArrayList<>();
        String replyText = json.optString("reply", "");
        JsonArray array = json.optArray("changes");
        if (array != null) {
            for (JsonValue item : array) {
                JsonObject object = item.asObjectOrNull();
                if (object == null) {
                    continue;
                }
                String key = object.optString("key", "").trim();
                String value = object.optString("value", "").trim();
                if (key.isEmpty()) {
                    continue;
                }
                // ★ 白名单在本地再卡一次：不依赖模型「守规矩」
                boolean allowed = false;
                for (String candidate : allowedKeys) {
                    if (candidate.equalsIgnoreCase(key)) {
                        allowed = true;
                        key = candidate;
                        break;
                    }
                }
                if (!allowed) {
                    logger.warn("模型建议了一个不在白名单里的配置键，已忽略：{}", key);
                    continue;
                }
                changes.add(new Change(key, value));
            }
        }
        return new ConfigPatch(true, List.copyOf(changes), replyText, "");
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 从模型输出里抠出 JSON 对象。
     *
     * <p>即便提示词说了「只输出 JSON」，模型偶尔仍会套一层 Markdown 代码块或加一句前言，
     * 所以这里做一次容错提取：先直接解析，失败则找第一个 {@code {} 到最后一个 {@code }}。</p>
     */
    static JsonObject extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        try {
            return JsonValue.parse(text).asObjectOrNull();
        } catch (JsonException ignored) {
            // 落到下面的容错提取
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonValue.parse(text.substring(start, end + 1)).asObjectOrNull();
        } catch (JsonException e) {
            return null;
        }
    }
}

package com.nowweather.core.llm;

/**
 * 主模型 / 副模型两档的自动回落。
 *
 * <h2>为什么需要两档</h2>
 * <p>AI 只是「锦上添花」的功能：主模型端点挂了、Key 余额用完了、或者今天配额用完了，
 * 都不该让玩家看到一条「AI 调用失败」就完事。配一个更便宜（或本地部署）的副模型，
 * 在主档位不可用时接着试一次，成功就照常给出结果 —— 这就是本类唯一的职责。</p>
 *
 * <h2>判定顺序（严格按这个来）</h2>
 * <ol>
 *   <li><b>主档位</b>：未配置（缺 Key / 端点 / 模型名）→ 跳过；今天配额用完 → 跳过；
 *       否则发一次请求：成功就用它，失败（网络 / HTTP 非 2xx / 空内容）→ 记下原因。</li>
 *   <li>主档位没成功才轮到<b>副档位</b>：未启用 → 跳过；未配置 → 跳过；配额用完 → 跳过；
 *       否则发一次请求。</li>
 *   <li>两个都没成功 → 两个原因<b>分别</b>带回去，由平台层逐条告诉玩家
 *       （「主：配额用完；副：未启用」这种信息量远比一句「AI 失败」有用）。</li>
 * </ol>
 *
 * <p>本类只做「顺序 + 为什么跳过」的判断，不认识提示词、不碰配置、不写文件：
 * 真正的调用由调用方以 {@link Call} 传入，配额由 {@link QuotaGate} 提供（见
 * {@link DailyCallQuota}），于是测试可以用假传输层把整条链跑一遍。</p>
 */
public final class LlmTiers {

    /**
     * 一个档位的定义。
     *
     * @param enabled   是否启用（主档位恒为 true —— 它的总开关在外面由 llm.enabled 管）
     * @param options   调用参数（含端点 / Key / 模型名 / 温度 / 上限 / 思考 / 超时）
     * @param dailyCalls 这一档每天最多调用几次
     */
    public record Tier(boolean enabled, LlmClient.Options options, int dailyCalls) {

        public static Tier primary(LlmClient.Options options, int dailyCalls) {
            return new Tier(true, options, dailyCalls);
        }

        public static Tier secondary(boolean enabled, LlmClient.Options options, int dailyCalls) {
            return new Tier(enabled, options, dailyCalls);
        }

        /**
         * 「配齐了没有」：Key、端点、模型名缺一不可。
         *
         * <p>端点与模型名也在检查范围内，因为 LlmClient 不会替调用方补默认值 ——
         * 只填了 Key 就发请求，只会拿到一个看不懂的 HTTP 错误。</p>
         */
        public boolean configured() {
            return options != null
                    && options.apiKey() != null && !options.apiKey().isBlank()
                    && options.endpoint() != null && !options.endpoint().isBlank()
                    && options.model() != null && !options.model().isBlank();
        }
    }

    /** 某一档这次为什么没给出结果。 */
    public enum Status {
        /** 调用成功且有内容。 */
        SUCCEEDED,
        /** 真的发了请求，但失败了（网络 / HTTP / 空内容 / 抛异常）。 */
        FAILED,
        /** 该档位被配置关闭了。 */
        SKIPPED_DISABLED,
        /** 启用但没配齐（缺 Key / 端点 / 模型名）。 */
        SKIPPED_NOT_CONFIGURED,
        /** 今天的配额用完了。 */
        SKIPPED_QUOTA
    }

    /** 单个档位的一次尝试。{@code error} 只在 {@link Status#FAILED} 时有意义。 */
    public record Attempt(Status status, String error) {

        public boolean tried() {
            return status == Status.SUCCEEDED || status == Status.FAILED;
        }

        public boolean succeeded() {
            return status == Status.SUCCEEDED;
        }
    }

    /**
     * 一次完整回落的结果。
     *
     * @param primary   主档位的尝试（一定非 null）
     * @param secondary 副档位的尝试；主档位一次成功时为 {@code null}（压根没碰它）
     * @param reply     最终生效的那次回复；全失败时是最后一次失败回复（可能为 null）
     */
    public record Outcome(Attempt primary, Attempt secondary, LlmClient.Reply reply) {

        public boolean success() {
            return reply != null && reply.hasContent();
        }

        /** 结果是不是来自副模型（提示里要说明「已降级」）。 */
        public boolean usedSecondary() {
            return secondary != null && secondary.status() == Status.SUCCEEDED;
        }
    }

    /** 真正发请求的一步：由调用方决定用哪套提示词、怎么解析。 */
    @FunctionalInterface
    public interface Call {
        LlmClient.Reply call(LlmClient.Options options);
    }

    /**
     * 配额闸门。只看「还能不能用」，<b>不负责扣减</b> ——
     * 扣减由调用方根据 {@link Outcome} 里每一档的 {@link Attempt#tried()} 决定，
     * 因为「失败也算花了一次」这件事属于记账策略，不属于回落顺序。
     */
    @FunctionalInterface
    public interface QuotaGate {
        boolean available(boolean secondary, int dailyCalls);
    }

    /** 不限量闸门：{@code /nowweather config ask} 用它（改配置不扣配额）。 */
    public static final QuotaGate UNLIMITED = (secondary, dailyCalls) -> true;

    private LlmTiers() {
    }

    /** 跑一次「主 → 副」回落链。{@code quota} 传 null 等价于不限量。 */
    public static Outcome run(Tier primary, Tier secondary, QuotaGate quota, Call call) {
        QuotaGate gate = quota == null ? UNLIMITED : quota;
        Step first = attempt(primary, false, gate, call);
        if (first.attempt().succeeded()) {
            // 主档位成功了就到此为止：副档位一次都不该花钱
            return new Outcome(first.attempt(), null, first.reply());
        }
        Step second = attempt(secondary, true, gate, call);
        LlmClient.Reply reply = second.reply() != null ? second.reply() : first.reply();
        return new Outcome(first.attempt(), second.attempt(), reply);
    }

    private record Step(Attempt attempt, LlmClient.Reply reply) {
    }

    private static Step attempt(Tier tier, boolean secondary, QuotaGate gate, Call call) {
        if (tier == null || !tier.enabled()) {
            return new Step(new Attempt(Status.SKIPPED_DISABLED, ""), null);
        }
        if (!tier.configured()) {
            return new Step(new Attempt(Status.SKIPPED_NOT_CONFIGURED, ""), null);
        }
        if (!gate.available(secondary, tier.dailyCalls())) {
            return new Step(new Attempt(Status.SKIPPED_QUOTA, ""), null);
        }
        LlmClient.Reply reply;
        try {
            reply = call.call(tier.options());
        } catch (RuntimeException e) {
            // 调用方给的回调理论上不抛（LlmClient 自己把异常兜成 Reply），
            // 但回落链不该因为一个意外异常就整条断掉 —— 那等于主档位一崩、副档位永远没机会。
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Step(new Attempt(Status.FAILED, message), null);
        }
        if (reply == null) {
            return new Step(new Attempt(Status.FAILED, "没有返回结果"), null);
        }
        if (!reply.hasContent()) {
            return new Step(new Attempt(Status.FAILED, reply.error()), reply);
        }
        return new Step(new Attempt(Status.SUCCEEDED, ""), reply);
    }
}

package com.nowweather.core.llm;

/**
 * 「自动 AI 辅助」的检查节奏 —— 由每日额度直接反推，纯计算、无副作用。
 *
 * <h2>约定：额度即节奏</h2>
 * <p>自动辅助<b>没有</b>独立的「检查间隔」配置项，也不应该有：多一个旋钮就多一组
 * 「间隔 5 分钟、额度 24 次」这种互相矛盾的组合 —— 那等于一天里有 20 次检查注定白跑
 * （要么被配额闸门挡回去，要么把额度一口气烧在前 2 小时）。所以这里只做一件事：
 * 把「每天 N 次」翻译成「每 1440 / N 分钟看一次」。</p>
 * <pre>
 *   dailyCalls = 144 → 每 10 分钟看一次（1440 ÷ 144 = 10）
 *   dailyCalls =  24 → 每 60 分钟看一次
 *   dailyCalls =   2 → 每 720 分钟（半天）看一次
 *   dailyCalls =   1 → 每 1440 分钟（一天）看一次
 *   dailyCalls =1000 → 1 分钟（整数除法的下限，见 {@link #intervalMinutes(int)}）
 * </pre>
 *
 * <p>注意「看一次」不等于「调一次」：绝大多数检查会因为「当前观测是新鲜的真实数据」而
 * 什么都不做（见 Forge 侧的触发条件），只有真的缺数据时才会花掉一次配额。
 * 也就是说配额的消耗速度<b>不超过</b>这里算出来的节奏。</p>
 *
 * <p>本类不碰时钟、不碰配置、不碰文件：时间由调用方传入，于是测试可以任意构造
 * 「刚好到间隔」「刚从未来回来」这类边界。</p>
 */
public final class AutoAssistSchedule {

    /** 一天有多少分钟 —— 节奏换算的分子（本地日与 UTC 日都是 1440，所以这里不需要时区）。 */
    public static final int MINUTES_PER_DAY = 1440;

    private AutoAssistSchedule() {
    }

    /**
     * 由每日额度反推检查间隔（分钟）：{@code 1440 / dailyCalls}，<b>下限 1</b>。
     *
     * <p>为什么下限是 1 而不是 0：{@code dailyCalls} 很大时（比如 1000）整数除法会得到
     * 0 或 1，而「每 0 分钟检查一次」等于每 tick 都去问一次模型 —— 那不是省钱的东西。
     * 1 分钟已经是自动辅助合理的上限频率。</p>
     *
     * <p>{@code dailyCalls <= 0}（理论上被配置校验拦掉了，但这里不依赖它）按 1 处理，
     * 即「一天看一次」，取的是<b>保守</b>的一侧：宁可少花，不可多花。</p>
     */
    public static int intervalMinutes(int dailyCalls) {
        int calls = Math.max(1, dailyCalls);
        return Math.max(1, MINUTES_PER_DAY / calls);
    }

    /** 间隔（毫秒）。给需要直接做算术的调用方用，避免各处重复 {@code * 60_000L}。 */
    public static long intervalMillis(int dailyCalls) {
        return intervalMinutes(dailyCalls) * 60_000L;
    }

    /**
     * 距离上次尝试是否已过间隔。
     *
     * <p>{@code lastAttemptMillis <= 0} 表示<b>从未试过</b> → {@code true}
     * （新装好的服务器应该立刻检查一次，而不是先干等一个间隔）。
     * 刚好等于间隔也算「到点了」（{@code >=}）：否则「每 10 分钟一次」在整点上永远差 1 毫秒。</p>
     *
     * <p>时间倒退（{@code nowMillis < lastAttemptMillis}，比如系统时间被改回去）时按
     * 「不 due」处理：差值会算成负数，而负数 &lt; 间隔，正好落到 {@code false} 这一侧，
     * 于是最多等一个间隔就恢复正常，不会变成「每秒都触发」。</p>
     */
    public static boolean isDue(long lastAttemptMillis, long nowMillis, int intervalMinutes) {
        if (lastAttemptMillis <= 0L) {
            return true;
        }
        long intervalMillis = Math.max(1, intervalMinutes) * 60_000L;
        return nowMillis - lastAttemptMillis >= intervalMillis;
    }

    /**
     * 当前观测是不是「缺失或不可信」—— 自动辅助<b>唯一</b>的触发前提（纯判断，不读配置也不读控制器）。
     *
     * <h2>为什么这四种情况都算「不可信」</h2>
     * <ol>
     *   <li>{@code observation == null}：还没取到过任何数据（刚启动、或者一直在失败）；</li>
     *   <li>{@code stale()}：取数失败后沿用的旧数据，控制器自己已经标记为过期；</li>
     *   <li>{@code isOlderThan(staleAfterMillis, now)}：比配置的「过期阈值」还旧 ——
     *       覆盖 {@code stale()} 没被置位、但数据其实已经放了半天的情形；</li>
     *   <li>来源是离线推算（{@link com.nowweather.core.sync.WeatherController#OFFLINE_PROVIDER_ID}）：
     *       它是本地算法凑出来的，<b>不是真实数据</b>，正是最值得让 AI 修一次的情形。</li>
     * </ol>
     *
     * <p>反过来，「有新鲜的真实观测」时一律返回 {@code false}：AI 辅助是用来补洞的，
     * 不是用来每次都用模型覆盖一遍数据源的 —— 那既费钱，又会让天气在两个来源之间来回跳。</p>
     */
    public static boolean needsAssist(com.nowweather.core.weather.WeatherObservation observation,
                                      long staleAfterMillis, long nowMillis) {
        if (observation == null) {
            return true;
        }
        if (observation.stale()) {
            return true;
        }
        if (observation.isOlderThan(staleAfterMillis, nowMillis)) {
            return true;
        }
        // 离线推算不是真实数据：它自己也只是一个推测，值得让模型按气象常识修一次。
        return com.nowweather.core.sync.WeatherController.OFFLINE_PROVIDER_ID
                .equals(observation.providerId());
    }
}

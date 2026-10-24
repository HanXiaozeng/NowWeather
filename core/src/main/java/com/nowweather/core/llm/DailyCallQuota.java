package com.nowweather.core.llm;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;

/**
 * 「每个档位每天 N 次」的调用配额账本。
 *
 * <h2>为什么不再用「距上次 24 小时」</h2>
 * <p>旧实现（{@link WeatherAssistant#budgetAvailable}，已废弃）只记一个「上次调用时间」，
 * 于是：</p>
 * <ul>
 *   <li>没法表达「今天还剩几次」—— 它只有「能用 / 不能用」两态，配额做成 N 次就表达不了；</li>
 *   <li>没法给主 / 副两个模型分别记账 —— 两个档位的钱是两个账户在花；</li>
 *   <li>语义还很别扭：23:59 调用过一次，第二天 00:01 仍然不能用，要等满 24 小时。
 *       玩家看到的是「今天一次都没用上啊」。</li>
 * </ul>
 * <p>所以改成按<b>本地日</b>计数：日键 = {@code (now + utcOffset) / 86_400_000}，
 * 过了本地零点就自动归零，不需要任何后台任务去重计。</p>
 *
 * <h2>持久化</h2>
 * <p>账本由平台层写进 {@code weather/nowweather/llm.json}，结构：</p>
 * <pre>{@code
 * { "dayKey": 20693, "primary": { "used": 1, "lastAt": 1790500000000 },
 *                    "secondary": { "used": 0, "lastAt": 0 } }
 * }</pre>
 * <p>读取时<b>兼容旧结构</b>（只有 {@code weatherCallAt}）：旧文件按「主模型今天已用 1 次」处理，
 * 见 {@link #parse(String, long, int)} 的注释。</p>
 *
 * <p>本类只做纯计算，不碰文件、不碰时钟 —— 时间与世界时区都由调用方传入，
 * 这样测试可以任意构造「跨天」场景。</p>
 */
public final class DailyCallQuota {

    /** 每个档位每日调用次数的下限（0 会被 Forge 的 defineInRange 与 ConfigEntries 一起拦掉）。 */
    public static final int MIN_DAILY_CALLS = 1;

    /** 每日调用次数上限：再高就不是「省成本的硬上限」了，直接建议用不限额度的账号。 */
    public static final int MAX_DAILY_CALLS = 100;

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * 账本快照（不可变）。
     *
     * @param dayKey        这份计数属于哪一天（见 {@link #dayKey(long, int)}）
     * @param primaryUsed   主模型当天已用次数
     * @param secondaryUsed 副模型当天已用次数
     * @param primaryLastAt 主模型最近一次调用时间（只用于展示，不参与计数）
     * @param secondaryLastAt 副模型最近一次调用时间
     */
    public record Usage(int dayKey, int primaryUsed, int secondaryUsed,
                        long primaryLastAt, long secondaryLastAt) {

        /** 空账本：任何一天、任何档位都还没用过。 */
        public static final Usage EMPTY = new Usage(Integer.MIN_VALUE, 0, 0, 0L, 0L);

        public int used(boolean secondary) {
            return secondary ? secondaryUsed : primaryUsed;
        }

        public long lastAt(boolean secondary) {
            return secondary ? secondaryLastAt : primaryLastAt;
        }
    }

    private DailyCallQuota() {
    }

    /**
     * 本地日键：{@code (now + utcOffset) / 86_400_000}。
     *
     * <p>用 {@link Math#floorDiv} 而不是 {@code /}：1970 年以前的时间戳与负偏移会得到负数，
     * 截断除法和取整除法在跨零点那一刻就是两个不同的答案。</p>
     */
    public static long dayKey(long nowEpochMillis, int utcOffsetSeconds) {
        return Math.floorDiv(nowEpochMillis + utcOffsetSeconds * 1000L, MILLIS_PER_DAY);
    }

    /**
     * 该档位今天已经用了几次（跨天自动视为 0）。
     *
     * <p>「跨天视为 0」是刻意不做写操作的结果：计数不改，只是读出来是 0；
     * 真正的清理由下一次 {@link #consume} 顺带完成（换天时从 1 重新计）。</p>
     */
    public static int used(Usage usage, boolean secondary, long nowEpochMillis, int utcOffsetSeconds) {
        if (usage == null) {
            return 0;
        }
        if (usage.dayKey() != dayKey(nowEpochMillis, utcOffsetSeconds)) {
            return 0;
        }
        return Math.max(0, usage.used(secondary));
    }

    /** 该档位今天还能不能调用。{@code dailyCalls} 由配置给出（各档位独立）。 */
    public static boolean available(Usage usage, boolean secondary, int dailyCalls,
                                    long nowEpochMillis, int utcOffsetSeconds) {
        return used(usage, secondary, nowEpochMillis, utcOffsetSeconds) < Math.max(1, dailyCalls);
    }

    /**
     * 扣掉一次并返回新账本（不可变，所以是「返回新的」而不是「改自己」）。
     *
     * <p>换天时把计数重置为「本档 1 次、另一档 0 次」—— 另一档昨天的次数今天不算数。</p>
     */
    public static Usage consume(Usage usage, boolean secondary, long nowEpochMillis, int utcOffsetSeconds) {
        long day = dayKey(nowEpochMillis, utcOffsetSeconds);
        int primary = 0;
        int secondaryCount = 0;
        long primaryLastAt = 0L;
        long secondaryLastAt = 0L;
        if (usage != null && usage.dayKey() == day) {
            primary = Math.max(0, usage.primaryUsed());
            secondaryCount = Math.max(0, usage.secondaryUsed());
            primaryLastAt = usage.primaryLastAt();
            secondaryLastAt = usage.secondaryLastAt();
        }
        if (secondary) {
            secondaryCount++;
            secondaryLastAt = nowEpochMillis;
        } else {
            primary++;
            primaryLastAt = nowEpochMillis;
        }
        return new Usage((int) day, primary, secondaryCount, primaryLastAt, secondaryLastAt);
    }

    /**
     * 从 {@code llm.json} 的文本解析账本。
     *
     * <p><b>向后兼容</b>：旧文件只有 {@code {"weatherCallAt": 1790500000000}} 一个字段。
     * 读到它时按「主模型今天已用 1 次」处理 —— 旧语义是「24 小时内不重复调用」，
     * 把它读成 1 次是<b>保守</b>的一侧：最多让玩家多等一天，绝不会因为换了记账方式而
     * 突然多花一次钱。同时把 dayKey 设成「今天」，于是这份旧计数明天自然归零。</p>
     *
     * <p>任何解析异常都返回 {@link Usage#EMPTY} 而不是抛出：状态文件只是成本计数，
 * 它坏了不该让整个 AI 功能不可用。</p>
     */
    public static Usage parse(String json, long nowEpochMillis, int utcOffsetSeconds) {
        if (json == null || json.isBlank()) {
            return Usage.EMPTY;
        }
        JsonObject root;
        try {
            root = JsonValue.parse(json).asObjectOrNull();
        } catch (RuntimeException e) {
            // JsonException 是 RuntimeException 的子类，这里一并兜住
            return Usage.EMPTY;
        }
        if (root == null) {
            return Usage.EMPTY;
        }
        long day = dayKey(nowEpochMillis, utcOffsetSeconds);
        JsonObject primary = root.optObject("primary");
        JsonObject secondary = root.optObject("secondary");
        if (primary == null && secondary == null) {
            // 旧结构：只有 weatherCallAt
            long legacyAt = root.optLong("weatherCallAt", 0L);
            if (legacyAt <= 0L) {
                return Usage.EMPTY;
            }
            return new Usage((int) day, 1, 0, legacyAt, 0L);
        }
        int primaryUsed = primary == null ? 0 : Math.max(0, primary.optInt("used", 0));
        int secondaryUsed = secondary == null ? 0 : Math.max(0, secondary.optInt("used", 0));
        long primaryLastAt = primary == null ? 0L : primary.optLong("lastAt", 0L);
        long secondaryLastAt = secondary == null ? 0L : secondary.optLong("lastAt", 0L);
        // 文件里的 dayKey 若缺失（手工编辑过），按今天算 —— 总比整份账本作废好
        int dayKey = root.optInt("dayKey", (int) day);
        return new Usage(dayKey, primaryUsed, secondaryUsed, primaryLastAt, secondaryLastAt);
    }

    /** 账本 → 写回文件用的 JSON 文本。 */
    public static String toJson(Usage usage) {
        Usage value = usage == null ? Usage.EMPTY : usage;
        JsonObject root = new JsonObject();
        root.put("dayKey", (long) value.dayKey());
        JsonObject primary = new JsonObject();
        primary.put("used", (long) value.primaryUsed());
        primary.put("lastAt", value.primaryLastAt());
        root.put("primary", (JsonValue) primary);
        JsonObject secondary = new JsonObject();
        secondary.put("used", (long) value.secondaryUsed());
        secondary.put("lastAt", value.secondaryLastAt());
        root.put("secondary", (JsonValue) secondary);
        return root.toPrettyJson();
    }
}

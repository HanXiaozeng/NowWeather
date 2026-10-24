package com.nowweather.forge.llm;

import com.nowweather.core.llm.DailyCallQuota;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LLM 配额账本的平台侧存储：<b>文件位置 + 串行锁 + 读写</b>，仅此三件事。
 *
 * <h2>为什么要有这个类</h2>
 * <p>账本的格式与计算全在 core 的 {@link DailyCallQuota} 里（纯计算、可单测），
 * 但「写到哪个文件」「用哪把锁」是平台才知道的事。自动 AI 辅助
 * （{@link AutoAssist}）和 {@code /nowweather forecast ai} 必须用
 * <b>同一份账本、同一把锁</b>，否则两条路径各自记账，玩家会在「额度明明还有」和
 * 「额度突然没了」之间反复横跳。所以把它抽出来，两边共用。</p>
 *
 * <h2>锁的粒度</h2>
 * <p>「读账本 → 判断还有没有额度 → 扣减 → 写回」这四步必须成组完成，
 * 否则两个几乎同时发出的请求会各自认为「还有额度」，于是超花一次钱。
 * 代价是 AI 请求彼此排队（最长一个超时时间），但调用方都是异步线程，
 * 不占服务器主线程。</p>
 */
public final class LlmQuotaStore {

    /** 账本的串行化锁：凡是「读-判断-扣-写」都必须持它。 */
    public static final Object LOCK = new Object();

    private LlmQuotaStore() {
    }

    /**
     * 账本文件。
     *
     * <p>文件名沿用旧的 {@code llm.json}（删掉它仍然等于「重置配额」），但结构已升级成
     * 主 / 副两个档位各记一笔 + 一个本地日键，见 {@link DailyCallQuota}。
     * 旧结构（只有 {@code weatherCallAt}）仍能读，读取时按「主模型今天已用 1 次」处理。</p>
     */
    public static Path stateFile() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("weather").resolve("nowweather").resolve("llm.json");
    }

    /** 读账本（含旧结构兼容）。读不到 / 解析失败一律当空账本 —— 状态坏了不该让 AI 功能不可用。 */
    public static DailyCallQuota.Usage load(long nowMillis, int utcOffsetSeconds) {
        try {
            Path path = stateFile();
            if (!Files.isRegularFile(path)) {
                return DailyCallQuota.Usage.EMPTY;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return DailyCallQuota.parse(text, nowMillis, utcOffsetSeconds);
        } catch (Exception e) {
            return DailyCallQuota.Usage.EMPTY;
        }
    }

    /** 写回账本。写不进去只影响「下次还能不能调」，不影响这次已经拿到的结果。 */
    public static void save(DailyCallQuota.Usage usage) {
        try {
            Path path = stateFile();
            Files.createDirectories(path.getParent());
            Files.writeString(path, DailyCallQuota.toJson(usage), StandardCharsets.UTF_8);
        } catch (Exception e) {
            com.nowweather.forge.NowWeatherLog.LOGGER
                    .warn("写入 LLM 配额状态失败（不影响本次调用）: {}", e.getMessage());
        }
    }
}

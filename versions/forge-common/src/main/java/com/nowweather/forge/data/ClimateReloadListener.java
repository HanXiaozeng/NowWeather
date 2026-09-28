package com.nowweather.forge.data;

import com.nowweather.core.NowWeatherCore;
import com.nowweather.forge.NowWeatherLog;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 数据包重载监听器：玩家执行 {@code /reload} 或数据包变化时，重新载入气候数据。
 *
 * <p>这里用 1.18.2 的非弃用接口 {@link PreparableReloadListener} 的完整签名实现
 * （{@code ResourceManagerReloadListener} 从 1.18 起已标记 {@code @Deprecated}），
 * 重活在后台线程里做，再把结果交给 {@code barrier::wait} 汇合。</p>
 */
public final class ClimateReloadListener implements PreparableReloadListener {

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                          ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        return CompletableFuture.<Void>supplyAsync(() -> {
            try {
                var runtime = com.nowweather.forge.NowWeatherMod.runtime();
                if (runtime != null) {
                    ClimateDataReloader.reload(runtime, resourceManager);
                }
            } catch (RuntimeException e) {
                NowWeatherLog.LOGGER.warn("重载气候数据失败（将沿用上一次的数据）", e);
            }
            return null;
        }, backgroundExecutor).thenCompose(barrier::wait);
    }

    @Override
    public String getName() {
        return NowWeatherCore.MOD_ID + ":climate";
    }
}

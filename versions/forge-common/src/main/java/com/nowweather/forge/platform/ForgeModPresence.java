package com.nowweather.forge.platform;

import com.nowweather.core.api.ModPresence;
import net.minecraftforge.fml.ModList;

/**
 * 模组存在性探测（用 Forge 的 {@link ModList}）。
 *
 * <p>注意调用时机：{@code ModList} 在模组构造阶段可能还没准备好，
 * 因此本类只应在服务器启动之后被调用（{@code ServerTickContext} 里就是这么用的）。</p>
 */
public final class ForgeModPresence implements ModPresence {

    public static final ForgeModPresence INSTANCE = new ForgeModPresence();

    private ForgeModPresence() {
    }

    @Override
    public boolean isLoaded(String modId) {
        if (modId == null || modId.isBlank()) {
            return false;
        }
        try {
            return ModList.get().isLoaded(modId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String version(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }
}

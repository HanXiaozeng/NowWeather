package com.nowweather.forge.platform;

import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.PrecipitationType;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;

/**
 * 各 MC 版本<b>共用</b>的生物群系事实拼装逻辑。
 *
 * <p>1.18.1 与 1.18.2 的生物群系 API 差别不小，无法用同一份实现同时满足两边：</p>
 *
 * <table border="1">
 *   <caption>版本差异</caption>
 *   <tr><th></th><th>1.18.1</th><th>1.18.2</th></tr>
 *   <tr><td>{@code LevelReader#getBiome(BlockPos)}</td><td>{@code Biome}</td>
 *       <td>{@code Holder<Biome>}（1.18.2 才引入 Holder/TagKey 这套注册表 API）</td></tr>
 *   <tr><td>{@code Biome#getBiomeCategory()}</td><td>public 实例方法</td>
 *       <td>包私有，须用静态 {@code Biome.getBiomeCategory(Holder)}</td></tr>
 *   <tr><td>标签</td><td>旧 {@code Tag} 体系</td><td>{@code Holder#tags()} / {@code TagKey}</td></tr>
 * </table>
 *
 * <p>所以「怎么读」放在各版本的 {@code ForgeBiomeFactsFactory} 里，
 * 而「怎么解释这些值」放在这里 —— 后者两个版本完全一致。</p>
 */
public final class BiomeFactsSupport {

    private BiomeFactsSupport() {
    }

    /**
     * 原版降水枚举 → 核心的降水形态。
     *
     * <p>注意 1.19.4 起原版移除了 {@code precipitation} 枚举字段，改为布尔 {@code has_precipitation}，
     * 下雪与否只由基础温度是否低于 0.15 决定 —— 到那个版本时这里要改成按温度推导。</p>
     */
    public static PrecipitationType toPrecipitationType(Biome.Precipitation precipitation,
                                                       float baseTemperature) {
        if (precipitation == null) {
            return PrecipitationType.MIXED;
        }
        return switch (precipitation) {
            case NONE -> PrecipitationType.NONE;
            case SNOW -> PrecipitationType.SNOW;
            case RAIN -> baseTemperature < 0.15F ? PrecipitationType.SNOW : PrecipitationType.RAIN;
        };
    }

    /** 原版分类的名字（未知分类走 none）。 */
    public static String categoryName(Biome.BiomeCategory category) {
        if (category == null) {
            return "none";
        }
        String name;
        try {
            name = category.getName();
        } catch (RuntimeException e) {
            return "none";
        }
        return name == null || name.isBlank() ? "none" : name;
    }

    /**
     * 判断是否属于「洞穴类」环境：看不到天空，或者名字里带洞穴特征。
     *
     * <p>看不到天空是最可靠的判据（玩家在洞里就不该看到「晴天」）。</p>
     */
    public static boolean looksLikeCave(String biomeId, boolean canSeeSky) {
        if (!canSeeSky) {
            return true;
        }
        String path = biomeId == null ? "" : biomeId;
        return path.contains("cave") || path.contains("deep_dark") || path.contains("grotto");
    }

    /** 拼装 {@link BiomeFacts}。 */
    public static BiomeFacts build(String biomeId,
                                   float baseTemperature,
                                   float downfall,
                                   Biome.Precipitation precipitation,
                                   Biome.BiomeCategory category,
                                   Set<String> tags,
                                   String dimensionId,
                                   boolean canSeeSky) {
        String id = biomeId == null || biomeId.isBlank() ? "minecraft:plains" : biomeId;
        return new BiomeFacts(
                id,
                baseTemperature,
                downfall,
                toPrecipitationType(precipitation, baseTemperature),
                categoryName(category),
                tags == null ? Set.of() : tags,
                dimensionId == null ? "minecraft:overworld" : dimensionId,
                looksLikeCave(id, canSeeSky));
    }
}

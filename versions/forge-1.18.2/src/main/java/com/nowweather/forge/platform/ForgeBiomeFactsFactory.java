package com.nowweather.forge.platform;

import com.nowweather.core.climate.BiomeFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <b>Minecraft 1.18.2 专用</b>：读取生物群系事实。
 *
 * <p>1.18.2 引入了 {@code Holder<T>} / {@code TagKey<T>} 这套注册表 API，
 * 与 1.18.1 完全不兼容，所以这个类在 1.18.1 工程里有一份对应的实现
 * （见 {@code versions/forge-1.18.1/.../ForgeBiomeFactsFactory.java}）。</p>
 *
 * <p>1.18.2 的两个坑：</p>
 * <ul>
 *   <li>{@code Level#getBiome(BlockPos)} 返回的是 {@code Holder<Biome>}，不是 {@code Biome}；</li>
 *   <li>{@code Biome#getBiomeCategory()} 是<b>包私有</b>的，必须用静态方法
 *       {@code Biome.getBiomeCategory(Holder)}。</li>
 * </ul>
 */
public final class ForgeBiomeFactsFactory {

    private ForgeBiomeFactsFactory() {
    }

    /** 读取某个坐标处的生物群系事实；取不到时返回 null。 */
    public static BiomeFacts biomeAt(ServerLevel level, BlockPos pos) {
        try {
            Holder<Biome> holder = level.getBiome(pos);
            Biome biome = holder.value();

            Registry<Biome> registry = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
            ResourceLocation key = registry.getKey(biome);

            return BiomeFactsSupport.build(
                    key == null ? null : key.toString(),
                    biome.getBaseTemperature(),
                    biome.getDownfall(),
                    biome.getPrecipitation(),
                    Biome.getBiomeCategory(holder),
                    tagsOf(holder),
                    level.dimension().location().toString(),
                    level.canSeeSky(pos));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 读取该生物群系所属的所有标签（形如 {@code minecraft:is_forest}）。 */
    private static Set<String> tagsOf(Holder<Biome> holder) {
        Set<String> tags = new LinkedHashSet<>();
        try {
            holder.tags().forEach((TagKey<Biome> tag) -> tags.add(tag.location().toString()));
        } catch (RuntimeException e) {
            // 标签不可用时留空：气候推断会自动退回「关键词 + 原版温度」路径
        }
        return tags;
    }

    /** 由维度 id 构造资源键（供天气读写使用）。 */
    public static ResourceKey<Level> dimensionKey(String dimensionId) {
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) {
            location = new ResourceLocation("minecraft", "overworld");
        }
        return ResourceKey.create(Registry.DIMENSION_REGISTRY, location);
    }
}

package com.nowweather.forge.platform;

import com.nowweather.core.climate.BiomeFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;

/**
 * <b>Minecraft 1.18.1 专用</b>：读取生物群系事实。
 *
 * <p>与 1.18.2 的差别（都用 javap 对着真实 jar 核对过）：</p>
 * <ul>
 *   <li>1.18.1 <b>没有</b> {@code net.minecraft.core.Holder} 与 {@code net.minecraft.tags.TagKey}
 *       —— 这套 API 是 1.18.2 才引入的；因此 {@code LevelReader#getBiome(BlockPos)} 直接返回
 *       {@code Biome}，而不是 {@code Holder<Biome>}；</li>
 *   <li>1.18.1 的 {@code Biome#getBiomeCategory()} 是 <b>public 实例方法</b>
 *       （1.18.2 改成了包私有 + 静态方法）；</li>
 *   <li>标签体系是旧的 {@code Tag} / {@code TagCollection}，拿「某个群系属于哪些标签」需要绕一大圈，
 *       收益很低 —— 这里直接返回空集合，气候推断会自动走「名称关键词 + 原版基础温度 + 原版分类」
 *       这条路径（对超多生物群系这类命名规范的模组效果很好）。</li>
 * </ul>
 */
public final class ForgeBiomeFactsFactory {

    private ForgeBiomeFactsFactory() {
    }

    /** 读取某个坐标处的生物群系事实；取不到时返回 null。 */
    public static BiomeFacts biomeAt(ServerLevel level, BlockPos pos) {
        try {
            Biome biome = level.getBiome(pos);
            Registry<Biome> registry = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
            ResourceLocation key = registry.getKey(biome);

            return BiomeFactsSupport.build(
                    key == null ? null : key.toString(),
                    biome.getBaseTemperature(),
                    biome.getDownfall(),
                    biome.getPrecipitation(),
                    biome.getBiomeCategory(),
                    Set.of(),
                    level.dimension().location().toString(),
                    level.canSeeSky(pos));
        } catch (RuntimeException e) {
            return null;
        }
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

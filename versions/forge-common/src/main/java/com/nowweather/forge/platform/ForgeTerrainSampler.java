package com.nowweather.forge.platform;

import com.nowweather.core.terrain.TerrainProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 从 Minecraft 世界采样地形画像（{@link TerrainProfile}）。
 *
 * <h2>坐标换算</h2>
 * <p>MC 的海平面在 {@code y = 63}，取「1 格 = 1 米」，于是
 * {@code 海拔(米) = y − 63}。这不是真实的地理高程，但它是玩家能直观理解、
 * 也能和「递减率 6.5 K/km」这种量级对上的唯一合理映射 ——
 * 一座 y=200 的山在游戏里本来就被玩家当作几百米的山。</p>
 *
 * <h2>怎么算暴露度</h2>
 * <p>在中心点周围按 {@link #SAMPLE_STEP} 步长取 {@link #SAMPLE_RADIUS} 半径内的高度图，
 * 用「中心点相对邻域的高度排名」近似暴露度：</p>
 * <ul>
 *   <li>中心显著高于邻域 → 山脊/山顶 → 暴露度趋近 1（风大、抬升降水多）；</li>
 *   <li>中心显著低于邻域 → 谷底/盆地 → 暴露度趋近 0（遮蔽、夜间积冷空气）。</li>
 * </ul>
 * <p>这是【工程近似】。更严谨的做法是算地形遮蔽角（sky view factor）或 TPI，
 * 但对「风该乘 1.4 还是 0.6」这个精度需求，高度排名已经够用。</p>
 *
 * <p>只采样<b>已加载</b>的区块，避免为了问一句天气而生成地形。</p>
 */
public final class ForgeTerrainSampler {

    /** 海平面对应的 y。 */
    public static final int SEA_LEVEL_Y = 63;

    /**
     * ★ 一格等于多少米。默认 8.0，不是 1.0。
     *
     * <p>原因是量级问题：MC 世界总高只有 384 格，而真实地形起伏可达 8848 m。
     * 若按 1 格 = 1 米，一双 300 格的高原也只有 300 m，
     * 按 6.5 K/km 只能算出不到 2 K 的温差 —— 玩家完全感觉不到，
     * 整个地形订正等于没做。取 8.0 后 300 格 ≈ 2400 m，温差约 15 K，
     * 与「高原 vs 平原」的直观感受一致。</p>
     *
     * <p>这个数字必须<b>可配置且对玩家可见</b>（HUD / 命令里显示 z_tgt），
     * 否则玩家看到温度凭空变了 10 K 会以为是 bug。</p>
     */
    public static double metersPerBlock = 8.0D;

    /** 采样半径（格）。 */
    public static final int SAMPLE_RADIUS = 40;

    /** 采样步长（格）：半径 40、步长 8 → 11×11 = 121 个采样点。 */
    public static final int SAMPLE_STEP = 8;

    /** 判定为「谷底」的相对深度阈值（格）。 */
    public static final int VALLEY_DEPTH = 12;

    /** 高度异常 → 暴露度的尺度（格）：比邻域均值高 25 格记作最暴露，低 25 格记作最遮蔽。 */
    public static final double EXPOSURE_SCALE = 25.0D;

    /** 有效采样点下限：少于它就只报海拔，不报起伏与暴露度。 */
    public static final int MIN_SAMPLES = 40;

    private ForgeTerrainSampler() {
    }

    /** 采样指定位置的地形画像。任何异常都退回 {@link TerrainProfile#NONE}（= 不做订正）。 */
    public static TerrainProfile sample(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return TerrainProfile.NONE;
        }
        try {
            // ★ 中心区块没加载就什么都不要报。
            // 真机踩到的坑：对未加载坐标调用 getHeight 会返回一个占位值（实测得到 -127m），
            // 于是「订正」凭空把气压抬到 1034 hPa、气温升高 0.9°C —— 全是编出来的。
            // 没有数据时正确的做法是「不做订正」，而不是拿占位值当观测。
            if (!level.hasChunkAt(pos)) {
                return TerrainProfile.NONE;
            }
            int centerY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
            if (centerY <= level.getMinBuildHeight()) {
                return TerrainProfile.NONE;
            }
            int min = centerY;
            int max = centerY;
            long sum = 0L;
            int count = 0;
            int above = 0;
            for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx += SAMPLE_STEP) {
                for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz += SAMPLE_STEP) {
                    int x = pos.getX() + dx;
                    int z = pos.getZ() + dz;
                    if (!level.hasChunkAt(new BlockPos(x, centerY, z))) {
                        continue;
                    }
                    int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                    sum += h;
                    count++;
                    if (h > centerY + VALLEY_DEPTH) {
                        above++;
                    }
                }
            }

            BlockState state = level.getBlockState(new BlockPos(pos.getX(), centerY, pos.getZ()));
            boolean water = state.getFluidState().is(FluidTags.WATER)
                    || state.is(Blocks.WATER) || state.is(Blocks.ICE);
            boolean snow = state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
                    || state.is(Blocks.POWDER_SNOW);

            if (count < MIN_SAMPLES) {
                // 周围已加载的区块太少（玩家刚传送、或站在加载边界）：
                // 只报海拔，起伏与暴露度取中性值，宁可少订正也不要乱订正
                return new TerrainProfile(toMeters(centerY - SEA_LEVEL_Y), 0.0D, 0.5D, false, snow, water);
            }

            double mean = (double) sum / count;
            double relief = max - min;

            // 暴露度：中心相对邻域<b>平均高度</b>的偏差，按 EXPOSURE_SCALE 折算。
            // 用高度差而不是「相对最低点的排名」——后者在起伏很小的地形上也会饱和到 0 或 1，
            // 实测出生点起伏仅 37 格就给出暴露度 0.00、风被压到 0.45 倍，明显过强。
            double exposure = clamp01(0.5D + (centerY - mean) / EXPOSURE_SCALE);

            boolean valley = centerY < mean - VALLEY_DEPTH * 0.5D && above > count * 0.3D;

            return new TerrainProfile(toMeters(centerY - SEA_LEVEL_Y), toMeters(relief), exposure,
                    valley, snow, water);
        } catch (RuntimeException e) {
            return TerrainProfile.NONE;
        }
    }

    /** 格 → 米（含 {@link #metersPerBlock} 缩放）。 */
    private static double toMeters(double blocks) {
        return blocks * (Double.isFinite(metersPerBlock) && metersPerBlock > 0.0D ? metersPerBlock : 1.0D);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : value > 1.0D ? 1.0D : value;
    }
}

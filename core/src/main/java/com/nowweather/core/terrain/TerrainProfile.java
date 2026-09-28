package com.nowweather.core.terrain;

import com.nowweather.core.text.Text;

/**
 * 目标地点的地形画像：地形订正的<b>输入</b>。
 *
 * <p>由平台层采样 Minecraft 的高度图得到（见 {@code ForgeTerrainSampler}）。
 * core 只认这个不可变值对象，不认识 Minecraft。</p>
 *
 * @param elevationMeters 目标位置海拔（米）。MC 里按「1 格 = 1 米、海平面 y=63 为 0 米」折算。
 * @param reliefMeters    局部地形起伏（米）：采样半径内最高点 − 最低点
 * @param exposure        暴露度 0~1：0 = 深谷/被环山遮蔽，0.5 = 平地，1 = 孤立山顶/山脊
 * @param valleyFloor     是否位于谷底（用于夜间冷池）
 * @param snowCovered     地表是否被雪覆盖（影响夜间辐射冷却强度）
 * @param waterSurface    是否水面（水体热容量大，昼夜温差小、几乎不产生冷池）
 */
public record TerrainProfile(
        double elevationMeters,
        double reliefMeters,
        double exposure,
        boolean valleyFloor,
        boolean snowCovered,
        boolean waterSurface) {

    /** 「不知道地形」时的中性画像：等于不做地形订正。 */
    public static final TerrainProfile NONE =
            new TerrainProfile(0.0D, 0.0D, 0.5D, false, false, false);

    public TerrainProfile {
        exposure = exposure < 0.0D ? 0.0D : exposure > 1.0D ? 1.0D : exposure;
        reliefMeters = Math.max(0.0D, reliefMeters);
    }

    /** 是否具备可用的海拔信息（0 米且无起伏视为未知）。 */
    public boolean hasElevation() {
        return Double.isFinite(elevationMeters) && (elevationMeters != 0.0D || reliefMeters > 0.0D);
    }

    /** 谷底且起伏明显 → 夜间会积冷空气。 */
    public boolean canPoolColdAir() {
        return valleyFloor && !waterSurface && reliefMeters >= 15.0D;
    }

    /** 供命令 / 诊断展示的一行描述（数字用 {@code Locale.ROOT}，文案走翻译缝）。 */
    public String describe() {
        return Text.tr("nowweather.terrain_profile.line",
                String.format(java.util.Locale.ROOT, "%.0f", elevationMeters),
                String.format(java.util.Locale.ROOT, "%.0f", reliefMeters),
                String.format(java.util.Locale.ROOT, "%.2f", exposure),
                valleyFloor ? Text.tr("nowweather.terrain_profile.valley_floor") : "",
                waterSurface ? Text.tr("nowweather.terrain_profile.water_surface") : "");
    }
}

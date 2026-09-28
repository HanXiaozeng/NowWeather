package com.nowweather.forge.compat;

import com.nowweather.core.log.Loggers;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;

import java.lang.reflect.Field;

/**
 * Sound Physics Remastered 的气候修正「挂钩点」。
 *
 * <p>这个类是 Mixin 与 NowWeather 之间唯一的接口，刻意做成<b>不认识 Minecraft</b> 的纯静态状态：
 * Mixin 注入到别人的类里时，只要回调方法不引用 MC 类型，就<b>不需要 refmap</b>，
 * 也就不必给构建加上 Mixin 注解处理器 —— 少一层踩坑的地方。</p>
 *
 * <h2>为什么用 Mixin 而不是「调用它的 API」</h2>
 * <p>读过它的源码（{@code outdated/1.18.2} 分支）：</p>
 * <pre>
 * public static ReverbParams getReverb0() {
 *     ReverbParams r = new ReverbParams();   // 每次调用都新建
 *     r.decayTime = 0.15F;                   // 硬编码常量，不是配置
 *     ...
 * }
 * </pre>
 * <p>参数是硬编码的、每次新建、没有配置/JSON/provider 入口 —— 所以外部<b>没有</b>任何
 * 「按环境改混响」的合法途径。注入 {@code getReverb0()} 的返回值是唯一能在
 * <b>不改它的源码与 jar</b> 的前提下做到这件事的办法（用户照常装官方包）。</p>
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>倍率默认全 1.0 —— 没数据时行为与它原本<b>完全一致</b>；</li>
 *   <li>修正幅度硬性夹在 ±15%（{@link #MAX_ADJUSTMENT}），不覆盖它的射线法遮挡计算；</li>
 *   <li>任何反射失败都静默跳过，绝不让音频线程因为我们的 bug 抛异常。</li>
 * </ul>
 */
public final class SoundPhysicsClimateHook {

    private static final SyncLogger LOGGER = Loggers.console(SyncLogger.Level.WARN);

    /** 修正幅度上限：气候只做微调，不改变它的基本听感。 */
    public static final float MAX_ADJUSTMENT = 0.15F;

    /** 高频空气吸收倍率（湿度主导）。 */
    private static volatile float airAbsorptionFactor = 1.0F;
    /** 混响衰减时间倍率（气温与海拔）。 */
    private static volatile float decayTimeFactor = 1.0F;
    /** 扩散倍率（地形暴露度）。 */
    private static volatile float diffusionFactor = 1.0F;
    /** 高频增益倍率（天气/云量）。 */
    private static volatile float gainHfFactor = 1.0F;

    private static volatile boolean applied;
    private static volatile String lastApplied = "";

    private SoundPhysicsClimateHook() {
    }

    /** 由客户端 tick 调用：把当前气候换算成四个倍率。 */
    public static void setFactors(double temperatureC, double humidity01, double elevationM,
                                  double exposure, boolean precipitating) {
        // 空气对高频的吸收随湿度非单调：中等湿度吸收最强，极干/极湿都更弱
        double humidityPeak = 1.0D - Math.abs(humidity01 - 0.5D) * 0.4D;
        airAbsorptionFactor = clamp(1.0D - (humidityPeak - 0.8D) * 0.5D);
        // 气温 → 声速 ∝ √T；低温衰减更快
        double soundSpeed = Math.sqrt((temperatureC + 273.15D) / 288.15D);
        double decay = 1.0D + (soundSpeed - 1.0D) * 0.5D;
        // 海拔 → 空气稀薄 → 吸收减弱、衰减变慢
        decay += Math.min(0.10D, Math.max(0.0D, elevationM) / 8000.0D);
        decayTimeFactor = clamp(decay);
        // 地形暴露度 → 开阔处扩散更充分
        diffusionFactor = clamp(0.95D + Math.max(0.0D, Math.min(1.0D, exposure)) * 0.10D);
        // 降水/云 → 高频散射增强
        gainHfFactor = clamp(precipitating ? 0.92D : 1.02D);
    }

    /** 没有气候数据时恢复「与它原本完全一致」。 */
    public static void reset() {
        airAbsorptionFactor = 1.0F;
        decayTimeFactor = 1.0F;
        diffusionFactor = 1.0F;
        gainHfFactor = 1.0F;
    }

    public static String describe() {
        // 倍率先按 Locale.ROOT 格式化，语言键里只用 %s，数字格式不随系统区域设置变。
        return applied
                ? Text.tr("nowweather.compat.sound_physics.applied",
                        String.format(java.util.Locale.ROOT, "%.3f", airAbsorptionFactor),
                        String.format(java.util.Locale.ROOT, "%.3f", decayTimeFactor),
                        String.format(java.util.Locale.ROOT, "%.3f", diffusionFactor),
                        String.format(java.util.Locale.ROOT, "%.3f", gainHfFactor),
                        lastApplied)
                : Text.tr("nowweather.compat.sound_physics.inactive");
    }

    /**
     * 由 Mixin 调用：把倍率叠到它的 {@code ReverbParams} 实例上。
     *
     * <p>参数类型写成 {@code Object} 是刻意的 —— 这样本类不依赖 SoundPhysics 的类，
     * 编译期不需要它，运行期用反射改字段。</p>
     */
    public static void apply(Object params) {
        if (params == null) {
            return;
        }
        try {
            boolean any = false;
            any |= multiply(params, "airAbsorptionGainHF", airAbsorptionFactor);
            any |= multiply(params, "decayTime", decayTimeFactor);
            any |= multiply(params, "diffusion", diffusionFactor);
            any |= multiply(params, "gainHF", gainHfFactor);
            if (any) {
                applied = true;
                lastApplied = Text.tr("nowweather.compat.sound_physics.detail",
                        String.format(java.util.Locale.ROOT, "%.0f", MAX_ADJUSTMENT * 100.0F));
            }
        } catch (RuntimeException e) {
            // 音频线程绝不能因为我们抛异常
            LOGGER.debug("叠加气候混响失败（已忽略）：{}", e.toString());
        }
    }

    private static boolean multiply(Object target, String fieldName, float factor) {
        if (Math.abs(factor - 1.0F) < 1.0E-3F) {
            return false;
        }
        try {
            Field field = target.getClass().getField(fieldName);
            if (field.getType() != float.class) {
                return false;
            }
            float original = field.getFloat(target);
            field.setFloat(target, original * factor);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static float clamp(double factor) {
        double low = 1.0D - MAX_ADJUSTMENT;
        double high = 1.0D + MAX_ADJUSTMENT;
        return (float) Math.max(low, Math.min(high, factor));
    }
}

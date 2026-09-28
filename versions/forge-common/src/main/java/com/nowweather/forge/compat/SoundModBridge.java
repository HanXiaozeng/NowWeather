package com.nowweather.forge.compat;

import com.nowweather.core.log.Loggers;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 音频类模组的桥接：<b>Sound Physics Remastered</b> 与 <b>AmbientSounds</b>。
 *
 * <h2>Sound Physics Remastered（物理声效重制版）</h2>
 * <p>对着真实 1.18.2 源码（{@code outdated/1.18.2} 分支）核对过：它做物理混响所需要的旋钮全都有 ——
 * {@code decayTime / density / diffusion / gainHF / reflectionsGain / lateReverbGain /
 * airAbsorptionGainHF …} —— 但这些旋钮是 <b>4 组全局预设</b>，运行时不随「玩家处在什么湿度/气温/海拔」变化。</p>
 *
 * <p>所以这里的做法是：<b>只读</b>它当前的参数（`ReverbParams.getReverb0..3` 是 public static），
 * 用声学关系算出「按当前气候应该调成多少」，把差异报给玩家/管理员看
 * （{@code /nowweather acoustics}）。真正写回需要它暴露一个 setter 或 provider 接口 ——
 * 需求单在 {@code sup_mods/SoundPhysicsRemastered_change.log}。</p>
 *
 * <h3>用到的声学关系（全部本地可算）</h3>
 * <ul>
 *   <li><b>湿度 → 空气对高频的吸收</b>：水汽让空气的高频吸收在中等湿度时最强，
 *       故 {@code airAbsorptionGainHF} 应随湿度呈非单调变化；</li>
 *   <li><b>气温 → 声速 ∝ √T</b>：低温声速慢、混响衰减更快 → {@code decayTime} 略降；</li>
 *   <li><b>海拔 → 气压下降、空气稀薄</b>：吸收减弱、衰减变慢 → {@code decayTime} 略升；</li>
 *   <li><b>地形暴露度</b>：山脊开阔 → 扩散大、早期反射弱；谷底封闭 → 反之。</li>
 * </ul>
 * <p>所有修正幅度都刻意压在 <b>±15%</b> 以内：这是「同一场景下的气候微调」，
 * 不是覆盖它的射线法遮挡计算。</p>
 *
 * <h2>AmbientSounds（自然音效）</h2>
 * <p>它的 region 是数据驱动的，但内置条件只有生物群系/时间/维度/实体/方块，
 * <b>表达不了「气温 25°C 以上」「海拔 800m 以上」</b>。所以这里只能探测存在性并给出可提供的量，
 * 等上游按 {@code sup_mods/AmbientSounds_change.log} 加上条件接口。</p>
 */
public final class SoundModBridge {

    private static final SyncLogger LOGGER = Loggers.console(SyncLogger.Level.WARN);

    public static final String SOUND_PHYSICS_MOD_ID = "soundphysics";
    public static final String AMBIENT_SOUNDS_MOD_ID = "ambientsounds";

    /** 气候对混响的修正幅度上限（比例）。 */
    public static final double MAX_ADJUSTMENT = 0.15D;

    private static volatile boolean probed;
    private static volatile boolean soundPhysicsPresent;
    private static volatile Method getReverb0;
    /** 探测说明。尚未探测时的占位文案按当前语言取（探测一定会覆盖它）。 */
    private static volatile String probeNote = Text.tr("nowweather.compat.sound_mod.not_probed");

    private SoundModBridge() {
    }

    /** 是否存在 Sound Physics Remastered。 */
    public static boolean soundPhysicsPresent() {
        probe();
        return soundPhysicsPresent;
    }

    public static String describe() {
        probe();
        return probeNote;
    }

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        ClassLoader loader = SoundModBridge.class.getClassLoader();
        try {
            Class<?> params = Class.forName("com.sonicether.soundphysics.config.ReverbParams", false, loader);
            getReverb0 = params.getMethod("getReverb0");
            soundPhysicsPresent = true;
            probeNote = Text.tr("nowweather.compat.sound_mod.physics_found");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            soundPhysicsPresent = false;
            probeNote = Text.tr("nowweather.compat.sound_mod.physics_missing",
                    e.getClass().getSimpleName());
        }
        boolean ambient = isLoaded(AMBIENT_SOUNDS_MOD_ID);
        if (ambient) {
            probeNote += Text.tr("nowweather.compat.sound_mod.ambient_sounds");
        }
        LOGGER.info("音频模组桥接：{}", probeNote);
    }

    private static boolean isLoaded(String modId) {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded(modId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 读取它当前的混响参数（读不到返回 null）。 */
    private static Object currentReverb() {
        probe();
        if (getReverb0 == null) {
            return null;
        }
        try {
            return getReverb0.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static float readFloat(Object params, String field) {
        if (params == null) {
            return Float.NaN;
        }
        try {
            java.lang.reflect.Field f = params.getClass().getField(field);
            Object v = f.get(params);
            return v instanceof Number number ? number.floatValue() : Float.NaN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /**
     * 按当前气候算出的混响微调建议。
     *
     * @param temperatureC   当地气温
     * @param humidity01     相对湿度 0~1
     * @param elevationM    海拔（米）
     * @param exposure       地形暴露度 0~1
     * @return 各参数的建议倍率（相对当前值），以及一句话理由
     */
    public static String acousticAdvice(double temperatureC, double humidity01, double elevationM,
                                        double exposure) {
        Object params = currentReverb();
        if (params == null) {
            return "";
        }
        // 空气吸收：湿度影响是非单调的（中等湿度吸收最强），以 50% 为峰做一条抛物线
        double humidityPeak = 1.0D - Math.abs(humidity01 - 0.5D) * 0.4D;
        double absorptionFactor = clamp(1.0D - (humidityPeak - 0.8D) * 0.5D);
        // 气温：声速 ∝ √T，低温衰减更快
        double soundSpeed = Math.sqrt((temperatureC + 273.15D) / 288.15D);
        double decayFactor = clamp(1.0D + (soundSpeed - 1.0D) * 0.5D);
        // 海拔：空气稀薄 → 衰减变慢
        decayFactor = clamp(decayFactor + Math.min(0.10D, elevationM / 8000.0D));
        // 暴露度：开阔 → 扩散大
        double diffusionFactor = clamp(0.95D + exposure * 0.10D);

        float airAbsorption = readFloat(params, "airAbsorptionGainHF");
        float decayTime = readFloat(params, "decayTime");
        float diffusion = readFloat(params, "diffusion");
        // 数值一律先在这里按 Locale.ROOT 格式化好，语言键里只用 %s：
        // 这样数字格式不会跟着玩家系统的区域设置变（德语环境会写成 25,3）。
        StringBuilder sb = new StringBuilder();
        sb.append(Text.tr("nowweather.compat.sound_mod.input",
                String.format(Locale.ROOT, "%.1f", temperatureC),
                String.format(Locale.ROOT, "%.0f", humidity01 * 100.0D),
                String.format(Locale.ROOT, "%.0f", elevationM),
                String.format(Locale.ROOT, "%.2f", exposure)));
        sb.append(Text.tr("nowweather.compat.sound_mod.air_absorption",
                String.format(Locale.ROOT, "%.3f", airAbsorption),
                String.format(Locale.ROOT, "%.3f", airAbsorption * (float) absorptionFactor),
                String.format(Locale.ROOT, "%.3f", absorptionFactor)));
        sb.append(Text.tr("nowweather.compat.sound_mod.decay_time",
                String.format(Locale.ROOT, "%.2f", decayTime),
                String.format(Locale.ROOT, "%.2f", decayTime * (float) decayFactor),
                String.format(Locale.ROOT, "%.3f", decayFactor),
                String.format(Locale.ROOT, "%.1f", temperatureC),
                String.format(Locale.ROOT, "%.0f", elevationM)));
        sb.append(Text.tr("nowweather.compat.sound_mod.diffusion",
                String.format(Locale.ROOT, "%.3f", diffusion),
                String.format(Locale.ROOT, "%.3f", diffusion * (float) diffusionFactor),
                String.format(Locale.ROOT, "%.3f", diffusionFactor)));
        return sb.toString();
    }

    private static double clamp(double factor) {
        return Math.max(1.0D - MAX_ADJUSTMENT, Math.min(1.0D + MAX_ADJUSTMENT, factor));
    }

    /** 客户端当前位置（拿不到返回 null）。 */
    public static Vec3 listenerPos() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.player == null ? null : minecraft.player.position();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

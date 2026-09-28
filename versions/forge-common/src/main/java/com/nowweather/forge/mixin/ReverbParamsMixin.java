package com.nowweather.forge.mixin;

import com.nowweather.forge.compat.SoundPhysicsClimateHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把气候修正叠到 Sound Physics Remastered 的混响参数上。
 *
 * <h2>这个 Mixin 做的事</h2>
 * <p>注入 {@code com.sonicether.soundphysics.config.ReverbParams#getReverb0()} 的返回处，
 * 拿到它刚构造出来的参数对象，把湿度/气温/海拔/暴露度的修正乘上去。
 * 那条调用链是 {@code SoundPhysics.syncReverbParams()} → {@code ReverbParams.getReverb0()}
 * → 写入 OpenAL 的 aux FX slot，所以在返回处改对象就等于改了最终生效的混响。</p>
 *
 * <h2>为什么这样写</h2>
 * <ul>
 *   <li><b>用 {@code targets} 字符串而不是 {@code @Mixin(ReverbParams.class)}</b>：
 *       本模组<b>不依赖也不要编译期依赖</b> Sound Physics，字符串目标让它在没有该模组的
 *       环境里照样能编译；</li>
 *   <li><b>{@code remap = false}</b>：目标是别的模组的类，不是 Minecraft 的混淆类，
 *       不该做映射重写；</li>
 *   <li><b>回调签名里不出现任何 Minecraft 类型</b>：因此不需要 refmap，
 *       也就不必给构建加 Mixin 注解处理器；</li>
 *   <li><b>Mixin 配置里 {@code defaultRequire: 0}</b>：没装 Sound Physics 时目标不存在，
 *       只是不注入，不会崩。</li>
 * </ul>
 *
 * <p><b>覆盖范围</b>：{@code getReverb0} ~ {@code getReverb3} 四组环境预设<b>都已注入</b>，
 * 不是只处理 getReverb0。</p>
 */
@Mixin(targets = "com.sonicether.soundphysics.config.ReverbParams", remap = false)
public class ReverbParamsMixin {

    @Inject(method = "getReverb0", at = @At("RETURN"), remap = false, require = 0)
    private static void nowweather$applyClimateReverb(CallbackInfoReturnable<Object> cir) {
        SoundPhysicsClimateHook.apply(cir.getReturnValue());
    }

    @Inject(method = "getReverb1", at = @At("RETURN"), remap = false, require = 0)
    private static void nowweather$applyClimateReverb1(CallbackInfoReturnable<Object> cir) {
        SoundPhysicsClimateHook.apply(cir.getReturnValue());
    }

    @Inject(method = "getReverb2", at = @At("RETURN"), remap = false, require = 0)
    private static void nowweather$applyClimateReverb2(CallbackInfoReturnable<Object> cir) {
        SoundPhysicsClimateHook.apply(cir.getReturnValue());
    }

    @Inject(method = "getReverb3", at = @At("RETURN"), remap = false, require = 0)
    private static void nowweather$applyClimateReverb3(CallbackInfoReturnable<Object> cir) {
        SoundPhysicsClimateHook.apply(cir.getReturnValue());
    }
}

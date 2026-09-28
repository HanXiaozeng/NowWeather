package com.nowweather.forge.util;

import com.nowweather.core.text.Text;

/**
 * 把 core 的「翻译缝」（{@link Text}）接到 Minecraft 的语言系统上。
 *
 * <p>{@code core} 是纯 Java、零 Minecraft 依赖的模块，它只负责给出「语言键 + 参数」
 * （{@code Text.tr("nowweather.config.section.hud")}），译文由平台层提供。
 * 本类就是那个平台层实现：用 {@code TranslatableComponent} 走 Minecraft 自己的
 * {@code Language} 解析，于是 core 的文案会跟着玩家的语言设置走。</p>
 *
 * <h2>为什么每次调用都重新解析，而且绝不缓存</h2>
 * <p>{@code TranslatableComponent#getString()} 每次都按<b>当前</b>的 {@code Language}
 * 重新查表，本身不缓存结果 —— 这正是我们要的：</p>
 * <ul>
 *   <li>模组构造期（{@link #install()} 被调用的时刻）语言文件<b>可能还没加载</b>，
 *       此时解析同一个键只会拿到键名本身。这没有关系，因为没有任何一处把结果存下来；</li>
 *   <li>玩家在游戏内切换语言、或数据包重载语言文件之后，下一次 {@code Text.tr()} 就会
 *       拿到新语言的译文，不需要重启，也不需要清缓存。</li>
 * </ul>
 * <p><b>所以：不要在这层之上做任何缓存</b>（不要存成 {@code static final String}、
 * 不要塞进 Map、不要在构造器里预先求值）。一旦缓存，就会把「启动时语言还没加载」
 * 拿到的键名永久固化，玩家看到的就是 {@code nowweather.config.section.hud} 这种键名。</p>
 *
 * <h2>为什么兜底返回键名而不是中文</h2>
 * <p>这正是 {@link Text} 的设计：漏装翻译器、或语言文件里漏了键，界面上会<b>显式地</b>
 * 显示成键名，一眼就能发现；静默回退到中文会让英文玩家以为「这个模组没做英文」，很难排查。
 * 占位符与参数个数不匹配时 Minecraft 会抛 {@code TranslatableFormatException}，
 * 这里同样吞掉并返回键名 —— 一行格式错误绝不该把游戏打断。</p>
 */
public final class ForgeText {

    private ForgeText() {
    }

    /**
     * 安装翻译器。必须在模组构造的<b>最开头</b>调用一次，早于任何可能读取配置文案的代码。
     *
     * <p>重复调用是安全的（后者覆盖前者），但正常流程里只应调用一次。</p>
     */
    public static void install() {
        Text.install((key, args) -> {
            try {
                // 1.18.1/1.18.2 里 TranslatableComponent 与 Component 都在 net.minecraft.network.chat 包；
                // 注意 1.18.x 的 Component 没有静态工厂方法（literal/translatable 是 1.19+ 才有的），
                // 只能 new。
                return new net.minecraft.network.chat.TranslatableComponent(key, args).getString();
            } catch (RuntimeException e) {
                // 键不存在 / 占位符与参数个数不匹配时退回键名，绝不抛异常打断游戏
                return key;
            }
        });
    }
}

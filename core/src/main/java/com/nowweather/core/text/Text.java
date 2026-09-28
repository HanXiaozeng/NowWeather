package com.nowweather.core.text;

import java.util.Locale;

/**
 * 平台无关的「文本本地化」入口。
 *
 * <p><b>为什么需要这一层：</b>{@code core} 是纯 Java、零 Minecraft 依赖的模块，
 * 它无法自己决定该用中文还是英文显示。这里把「翻译」抽象成一个由平台注入的函数：
 * 平台层（Forge）在启动时装一个用 Minecraft 语言文件实现的 {@link Translator}，
 * core 只负责给出「语言键 + 参数」。</p>
 *
 * <p><b>默认行为是「原样返回键名」，不是「回退到中文」。</b>
 * 这是刻意的：任何一处漏装翻译器、或语言文件里漏了键，都会在界面上**显式地**
 * 显示成 {@code nowweather.weather.heavy_rain} 这样的键名，一眼就能发现；
 * 而静默回退到中文会让英文用户以为「这个模组没做英文」，很难排查。</p>
 *
 * <p><b>键名约定</b>（全小写、点分、以 {@code nowweather.} 开头）：</p>
 * <ul>
 *   <li>{@code nowweather.weather.<枚举名小写>} —— 天气类型，如 {@code ...storm_rain}</li>
 *   <li>{@code nowweather.weather_category.<...>} —— 天气大类</li>
 *   <li>{@code nowweather.climate_class.<...>} —— 气候大类</li>
 *   <li>{@code nowweather.precipitation.<...>} —— 降水形态</li>
 *   <li>{@code nowweather.confidence.<...>} —— 数据可信度</li>
 *   <li>{@code nowweather.wind_direction.<...>} / {@code nowweather.hemisphere.<...>}</li>
 *   <li>{@code nowweather.moon_phase.<0..7>} —— 月相（0 = 满月，4 = 新月）</li>
 *   <li>{@code nowweather.platform_weather.<clear|rain|thunder>} —— 原版三档天气</li>
 *   <li>{@code nowweather.platform_weather.for_player.<形态>} —— 按玩家习惯的说法</li>
 *   <li>{@code nowweather.command.<命令路径>.<片段>} —— 命令输出</li>
 *   <li>{@code nowweather.config.section.<分组>} / {@code nowweather.config.entry.<项>.label/.hint}</li>
 *   <li>{@code nowweather.reason.<...>} —— 决策依据（聊天面板 / status）</li>
 *   <li>{@code nowweather.sanity.<违规枚举小写>} —— 气候校验违规说明</li>
 * </ul>
 *
 * <p><b>日志不走这一层。</b> {@code LOGGER} 输出面向开发者，恒为中文，不翻译 ——
 * 既省事，也避免日志随玩家语言变化而变得不可检索。</p>
 */
public final class Text {

    /** 由平台注入的翻译函数。参数用 Minecraft 语言文件同一套 {@code %s} 占位符。 */
    @FunctionalInterface
    public interface Translator {
        /**
         * @param key  语言键，如 {@code nowweather.weather.heavy_rain}
         * @param args 占位符参数，可为空
         * @return 当前语言下的文本；键不存在时应返回键名本身
         */
        String translate(String key, Object... args);
    }

    /** 未安装翻译器时的默认实现：原样返回键名（见类注释说明为什么不是回退中文）。 */
    private static final Translator IDENTITY = (key, args) -> key;

    private static volatile Translator translator = IDENTITY;

    private Text() {
    }

    /** 由平台层在模组构造时安装；传 {@code null} 表示恢复默认（返回键名）。 */
    public static void install(Translator value) {
        translator = (value == null) ? IDENTITY : value;
    }

    /** 是否已安装真实翻译器。自检里用它判断「现在拿到的是译文还是键名」。 */
    public static boolean installed() {
        return translator != IDENTITY;
    }

    /** 按当前语言翻译。未安装翻译器时返回键名，便于发现漏配。 */
    public static String tr(String key, Object... args) {
        return translator.translate(key, args);
    }

    /** 由枚举生成语言键片段：{@code STORM_RAIN → storm_rain}（恒为小写，与语言无关）。 */
    public static String segment(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}

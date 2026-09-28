package com.nowweather.core.api;

import com.nowweather.core.text.Text;

import java.util.Locale;

/**
 * Minecraft 层面的天气状态 —— 加载器可以真正落地的三种取值。
 *
 * <p>真实天气有无穷多种，但原版天气系统只有这三档；把「映射」这一步单独抽出来，
 * 是为了让未来接入 Project Atmosphere 这类扩展天气模组时，只需要换一个落地实现
 * （见 {@link WeatherBinding}）。</p>
 */
public enum PlatformWeather {
    CLEAR("晴朗"),
    RAIN("降雨"),
    THUNDER("雷暴");

    /** 中文原文，仅作语言文件（{@code nowweather.platform_weather.*}）的对照保留，不再是返回值。 */
    private final String zhName;

    PlatformWeather(String zhName) {
        this.zhName = zhName;
    }

    /** 稳定语言键，例如 {@code nowweather.platform_weather.rain}。 */
    public String key() {
        return "nowweather.platform_weather." + Text.segment(this);
    }

    /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
    public String zhName() {
        return Text.tr(key());
    }

    public boolean isRain() {
        return this == RAIN;
    }

    public boolean isThunder() {
        return this == THUNDER;
    }

    public static PlatformWeather parse(String raw, PlatformWeather fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (PlatformWeather w : values()) {
            if (w.name().equals(key)) {
                return w;
            }
        }
        return fallback;
    }
}

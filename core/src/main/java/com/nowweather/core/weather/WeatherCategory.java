package com.nowweather.core.weather;

import com.nowweather.core.text.Text;

import java.util.Locale;

/** 天气大类。用于预报状态机与 MC 天气映射。 */
public enum WeatherCategory {
    CLEAR("晴"),
    CLOUDY("多云"),
    FOG("低能见度"),
    RAIN("雨"),
    SNOW("雪"),
    STORM("雷暴"),
    DUST("沙尘"),
    WIND("大风");

    /** 中文原文，仅作语言文件（{@code nowweather.weather_category.*}）的对照保留，不再是返回值。 */
    private final String zhName;

    WeatherCategory(String zhName) {
        this.zhName = zhName;
    }

    /** 稳定语言键，例如 {@code nowweather.weather_category.fog}。 */
    public String key() {
        return "nowweather.weather_category." + Text.segment(this);
    }

    /** 展示名。方法名保留 {@code zh} 是历史包袱，实际返回当前语言文本。 */
    public String zhName() {
        return Text.tr(key());
    }

    public boolean isPrecipitation() {
        return this == RAIN || this == SNOW;
    }

    public static WeatherCategory parse(String raw, WeatherCategory fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (WeatherCategory c : values()) {
            if (c.name().equals(key)) {
                return c;
            }
        }
        return fallback;
    }
}

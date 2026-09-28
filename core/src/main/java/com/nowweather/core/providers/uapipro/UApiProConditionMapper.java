package com.nowweather.core.providers.uapipro;

import com.nowweather.core.weather.WeatherType;

/**
 * UApiPro 天气文本 / 图标码 → {@link WeatherType} 的映射。
 *
 * <p>UApiPro 返回两个可用于判断的字段：中文描述（{@code weather}）与图标码（{@code weather_icon}）。
 * 图标码采用和风天气风格的编码（100 晴、300+ 雨、400+ 雪、500+ 雾霾沙尘），
 * 两者结合判断最稳：文案可能随上游数据源变化，图标码则相对稳定。</p>
 */
public final class UApiProConditionMapper {

    private UApiProConditionMapper() {
    }

    /**
     * 综合映射。
     *
     * @param conditionText 中文天气描述，如「雷阵雨」
     * @param iconCode      图标码字符串，如「302」
     */
    public static WeatherType map(String conditionText, String iconCode) {
        WeatherType byIcon = byIconCode(iconCode);
        WeatherType byText = byText(conditionText);
        if (byIcon != null && byText != null) {
            // 两者冲突时取「更严重」的一个，宁可多下雨也不要漏掉暴雨
            return byIcon.severity() >= byText.severity() ? byIcon : byText;
        }
        if (byIcon != null) {
            return byIcon;
        }
        if (byText != null) {
            return byText;
        }
        return WeatherType.UNKNOWN;
    }

    /** 依据图标码判断。 */
    public static WeatherType byIconCode(String iconCode) {
        if (iconCode == null || iconCode.isBlank()) {
            return null;
        }
        int code;
        try {
            code = Integer.parseInt(iconCode.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (code) {
            case 100, 150 -> WeatherType.CLEAR;
            case 102, 103, 152, 153 -> WeatherType.MAINLY_CLEAR;
            case 101, 151 -> WeatherType.PARTLY_CLOUDY;
            case 104 -> WeatherType.OVERCAST;
            case 300, 350, 305, 309, 314 -> WeatherType.LIGHT_RAIN;
            case 301, 306, 315 -> WeatherType.RAIN;
            case 307, 316 -> WeatherType.HEAVY_RAIN;
            case 302, 351 -> WeatherType.THUNDERSTORM;
            case 303 -> WeatherType.SEVERE_THUNDERSTORM;
            case 304 -> WeatherType.HAIL;
            // 注意：308/310~312/317/318 是「极端降雨 / 暴雨」级别，本身不带雷电，
            // 不应被当成雷暴（否则平原上会莫名其妙打雷）。
            case 308, 310, 311, 312, 317, 318 -> WeatherType.HEAVY_RAIN;
            case 313 -> WeatherType.FREEZING_RAIN;
            case 399 -> WeatherType.RAIN;
            case 400, 408, 456, 457 -> WeatherType.LIGHT_SNOW;
            case 401, 409 -> WeatherType.SNOW;
            case 402, 410 -> WeatherType.HEAVY_SNOW;
            case 403 -> WeatherType.BLIZZARD;
            case 404, 405, 406, 407 -> WeatherType.SLEET;
            case 499 -> WeatherType.SNOW;
            case 500, 501, 509, 510, 514, 515 -> WeatherType.FOG;
            case 502, 511, 512, 513 -> WeatherType.HAZE;
            case 503, 504 -> WeatherType.HAZE;
            case 507, 508 -> WeatherType.SANDSTORM;
            case 900 -> WeatherType.CLEAR;
            case 901 -> WeatherType.SNOW;
            default -> null;
        };
    }

    /**
     * 依据中文描述判断。顺序敏感：先判断复合词（雷阵雨、雨夹雪），再判断单词。
     */
    public static WeatherType byText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().replace(" ", "");

        // --- 复合天气 ---
        if (containsAny(t, "冰雹", "雹")) {
            return WeatherType.HAIL;
        }
        if (containsAny(t, "雨夹雪", "雨雪", "冻雨", "雨凇")) {
            return containsAny(t, "冻雨", "雨凇") ? WeatherType.FREEZING_RAIN : WeatherType.SLEET;
        }
        if (containsAny(t, "雷", "闪电") && containsAny(t, "雨", "阵雨", "暴")) {
            return containsAny(t, "强", "暴", "大") ? WeatherType.SEVERE_THUNDERSTORM : WeatherType.THUNDERSTORM;
        }
        if (containsAny(t, "雷阵雨", "雷雨")) {
            return WeatherType.THUNDERSTORM;
        }
        if (containsAny(t, "台风", "飓风", "热带风暴", "气旋")) {
            return WeatherType.TYPHOON;
        }
        if (containsAny(t, "沙尘暴", "扬沙", "强沙尘")) {
            return WeatherType.SANDSTORM;
        }
        if (containsAny(t, "浮尘", "霾", "烟雾")) {
            return WeatherType.HAZE;
        }
        if (containsAny(t, "雾", "薄雾", "浓雾")) {
            return WeatherType.FOG;
        }
        if (containsAny(t, "暴风雪", "风雪")) {
            return WeatherType.BLIZZARD;
        }

        // --- 雪 ---
        if (containsAny(t, "雪")) {
            if (containsAny(t, "暴雪", "大到暴雪", "特大")) {
                return WeatherType.BLIZZARD;
            }
            if (containsAny(t, "大雪", "中到大雪")) {
                return WeatherType.HEAVY_SNOW;
            }
            if (containsAny(t, "中雪", "小到中雪")) {
                return WeatherType.SNOW;
            }
            return WeatherType.LIGHT_SNOW;
        }

        // --- 雨 ---
        if (containsAny(t, "雨")) {
            if (containsAny(t, "特大暴雨", "大暴雨")) {
                return WeatherType.SEVERE_THUNDERSTORM;
            }
            if (containsAny(t, "暴雨", "大到暴雨")) {
                return WeatherType.HEAVY_RAIN;
            }
            if (containsAny(t, "大雨", "中到大雨")) {
                return WeatherType.HEAVY_RAIN;
            }
            if (containsAny(t, "中雨", "小到中雨")) {
                return WeatherType.RAIN;
            }
            if (containsAny(t, "小雨", "阵雨", "细雨", "毛毛雨")) {
                return WeatherType.LIGHT_RAIN;
            }
            return WeatherType.RAIN;
        }

        // --- 风 ---
        if (containsAny(t, "大风", "强风", "狂风", "飑")) {
            return WeatherType.WINDY;
        }

        // --- 云与晴 ---
        if (containsAny(t, "阴")) {
            return WeatherType.OVERCAST;
        }
        if (containsAny(t, "晴间多云", "晴转多云", "晴到多云")) {
            return WeatherType.MAINLY_CLEAR;
        }
        if (containsAny(t, "多云", "少云")) {
            return WeatherType.PARTLY_CLOUDY;
        }
        if (containsAny(t, "晴")) {
            return WeatherType.CLEAR;
        }
        return null;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

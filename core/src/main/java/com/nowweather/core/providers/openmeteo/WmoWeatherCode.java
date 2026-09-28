package com.nowweather.core.providers.openmeteo;

import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherType;

/**
 * WMO 天气代码 → 本项目 {@link WeatherType} 的映射。
 *
 * <p>Open-Meteo 返回的是 <b>WMO 4677 天气现象代码</b>（0~99），这是个国际标准，
 * 比任何 API 的中文文案都稳定 —— 文案会改，代码不会。</p>
 *
 * <p>参考：WMO Code Table 4677 / Open-Meteo 官方文档的 Weather variable documentation。</p>
 */
public final class WmoWeatherCode {

    private WmoWeatherCode() {
    }

    /** 代码 → 标准天气类型；未知代码返回 null。 */
    public static WeatherType toWeatherType(int code) {
        return switch (code) {
            case 0 -> WeatherType.CLEAR;
            case 1 -> WeatherType.MAINLY_CLEAR;
            case 2 -> WeatherType.PARTLY_CLOUDY;
            case 3 -> WeatherType.OVERCAST;
            case 45, 48 -> WeatherType.FOG;
            case 51, 53 -> WeatherType.DRIZZLE;
            case 55 -> WeatherType.LIGHT_RAIN;
            case 56, 57 -> WeatherType.FREEZING_RAIN;
            case 61 -> WeatherType.LIGHT_RAIN;
            case 63 -> WeatherType.RAIN;
            case 65 -> WeatherType.HEAVY_RAIN;
            case 66, 67 -> WeatherType.FREEZING_RAIN;
            case 71 -> WeatherType.LIGHT_SNOW;
            case 73 -> WeatherType.SNOW;
            case 75 -> WeatherType.HEAVY_SNOW;
            case 77 -> WeatherType.LIGHT_SNOW;
            case 80 -> WeatherType.LIGHT_RAIN;
            case 81 -> WeatherType.RAIN;
            case 82 -> WeatherType.HEAVY_RAIN;
            case 85 -> WeatherType.LIGHT_SNOW;
            case 86 -> WeatherType.HEAVY_SNOW;
            case 95 -> WeatherType.THUNDERSTORM;
            case 96, 99 -> WeatherType.HAIL;
            default -> null;
        };
    }

    /**
     * 代码 → 展示文案（用于 HUD / 命令；拿不到代码时返回「未知(n)」）。
     *
     * <p>每个代码一个语言键 {@code nowweather.wmo.<code>}，文案都在语言文件里，
     * 这里只负责挑键。空值走 {@code nowweather.wmo.unknown}。</p>
     */
    public static String describe(int code) {
        return switch (code) {
            case 0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77,
                 80, 81, 82, 85, 86, 95, 96, 99 -> Text.tr("nowweather.wmo." + code);
            default -> Text.tr("nowweather.wmo.unknown", Integer.toString(code));
        };
    }

    /** 是否是「有降水」的代码。 */
    public static boolean isPrecipitation(int code) {
        WeatherType type = toWeatherType(code);
        return type != null && type.isPrecipitation();
    }
}

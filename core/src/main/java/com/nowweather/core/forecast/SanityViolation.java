package com.nowweather.core.forecast;

import com.nowweather.core.text.Text;

/** 气候合理性违规类型 —— 预报数据「不合常理」的具体原因。 */
public enum SanityViolation {
    TEMPERATURE_ABOVE_RANGE("温度高于该生物群系的正常温度范围"),
    TEMPERATURE_BELOW_RANGE("温度低于该生物群系的正常温度范围"),
    PRECIPITATION_IN_DRY_CLIMATE("干旱气候下不应出现降水"),
    SNOW_ABOVE_FREEZING("温度高于冰点却在下雪"),
    RAIN_BELOW_FREEZING("温度低于冰点却在下雨"),
    FREEZING_RAIN_IN_WARM_CLIMATE("温暖气候下出现冻雨"),
    THUNDER_WITHOUT_PRECIPITATION("没有降水却有雷电"),
    THUNDER_TOO_STRONG_FOR_CLIMATE("雷暴强度超出该气候的合理上限"),
    WIND_TOO_STRONG_FOR_CLIMATE("风力超出该气候的常见范围"),
    FOG_IN_DRY_CLIMATE("干旱气候下出现浓雾"),
    DUST_STORM_IN_WET_CLIMATE("湿润气候下出现沙尘暴"),
    WEATHER_NOT_POSSIBLE_IN_CLIMATE("该天气在此生物群系根本不会出现"),
    EXTREME_EVENT_NOT_ALLOWED("当前配置不允许极端天气事件");

    /**
     * 中文原文，仅作语言文件（{@code nowweather.sanity.*}）的对照保留，不再是返回值。
     */
    private final String zhDescription;

    SanityViolation(String zhDescription) {
        this.zhDescription = zhDescription;
    }

    /** 稳定语言键，例如 {@code nowweather.sanity.snow_above_freezing}。 */
    public String key() {
        return "nowweather.sanity." + Text.segment(this);
    }

    /**
     * 违规项的说明。
     *
     * <p>方法名里的 {@code zh} 是历史包袱（调用点太多），<b>实际返回当前语言文本</b>。</p>
     */
    public String zhDescription() {
        return Text.tr(key());
    }
}

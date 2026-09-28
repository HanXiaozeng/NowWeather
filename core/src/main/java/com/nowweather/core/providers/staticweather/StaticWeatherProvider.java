package com.nowweather.core.providers.staticweather;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProvider;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindScale;

/**
 * 固定天气数据源 —— 用于测试、演示与「服务器想要恒定天气」的场景。
 *
 * <p>它同时是「如何写一个 provider」的最简范例：只要实现 {@link WeatherProvider}，
 * 返回一个 {@link WeatherObservation} 即可，不需要碰任何 Minecraft 代码。</p>
 */
public final class StaticWeatherProvider implements WeatherProvider {

    public static final String ID = "static";

    private final WeatherType type;
    private final double temperatureC;
    private final double humidityPercent;
    private final int windScale;

    public StaticWeatherProvider() {
        this(WeatherType.CLEAR, 20.0D, 50.0D, 2);
    }

    public StaticWeatherProvider(WeatherType type, double temperatureC, double humidityPercent, int windScale) {
        this.type = type;
        this.temperatureC = temperatureC;
        this.humidityPercent = humidityPercent;
        this.windScale = windScale;
    }

    /** 从「晴 / rain / 雷暴」这类文本构造，解析失败时为晴。 */
    public static StaticWeatherProvider fromText(String text, double temperatureC) {
        WeatherType parsed = WeatherType.parse(text, null);
        if (parsed == null) {
            parsed = switch (text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "雨", "rain", "storm_rain" -> WeatherType.RAIN;
                case "雷", "雷暴", "thunder", "storm" -> WeatherType.THUNDERSTORM;
                case "雪", "snow" -> WeatherType.SNOW;
                case "雾", "fog" -> WeatherType.FOG;
                case "多云", "cloudy" -> WeatherType.CLOUDY;
                default -> WeatherType.CLEAR;
            };
        }
        return new StaticWeatherProvider(parsed, temperatureC, 60.0D, 3);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return Text.tr("nowweather.provider.static");
    }

    @Override
    public int priority() {
        return 900;
    }

    @Override
    public boolean requiresNetwork() {
        return false;
    }

    @Override
    public boolean providesLocation() {
        return true;
    }

    @Override
    public boolean isEnabled(ProviderContext context) {
        return false; // 默认关闭，需要显式注册为唯一数据源时才启用
    }

    @Override
    public ProviderResult fetch(ProviderContext context) {
        WeatherObservation observation = WeatherObservation.builder(ID)
                .weatherType(type)
                .conditionText(type.zhName())
                .temperatureC(temperatureC)
                .humidityPercent(humidityPercent)
                .windScale(windScale)
                .windSpeedKmh(WindScale.toKmh(windScale))
                .location("", context.city(), context.adcode())
                .confidence(1.0D)
                .reportTimeText("固定数据")
                .build();
        return ProviderResult.ok(ID, observation);
    }

    /** 无条件的版本：始终可用，适合做「演示模式」。 */
    public static WeatherProvider alwaysOn(WeatherType type, double temperatureC) {
        StaticWeatherProvider delegate = new StaticWeatherProvider(type, temperatureC, 60.0D, 3);
        return new WeatherProvider() {
            @Override
            public String id() {
                return ID + "_always";
            }

            @Override
            public String displayName() {
                return Text.tr("nowweather.provider.static_forced");
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public boolean requiresNetwork() {
                return false;
            }

            @Override
            public boolean providesLocation() {
                return true;
            }

            @Override
            public ProviderResult fetch(ProviderContext context) {
                return delegate.fetch(context);
            }
        };
    }
}

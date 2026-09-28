package com.nowweather.core.sync;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.config.NowWeatherConfig;
import com.nowweather.core.forecast.ClimateSanityFilter;
import com.nowweather.core.forecast.SanityReport;
import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

/**
 * 同步策略 —— 决定「真实天气」如何变成「Minecraft 天气」。
 *
 * <p>三步：</p>
 * <ol>
 *   <li>若开启 {@code respectBiomeClimate}，先用 {@link ClimateSanityFilter}
 *       依据生物群系的正常温度范围校验并修正天气（沙漠不下雨、雪原不暴雨）；</li>
 *   <li>按阈值把标准化天气压到原版三档（晴 / 雨 / 雷暴）；</li>
 *   <li>按强度换算持续时间，避免天气一秒一变。</li>
 * </ol>
 */
public final class SyncPolicy {

    private final NowWeatherConfig config;
    private final ClimateSanityFilter filter;

    public SyncPolicy(NowWeatherConfig config) {
        this.config = config;
        this.filter = new ClimateSanityFilter(config.temperatureToleranceC(), config.allowExtremeEvents());
    }

    public ClimateSanityFilter filter() {
        return filter;
    }

    /**
     * 依据观测数据与生物群系气候给出决策。
     *
     * @param observation 真实天气观测（可为 null）
     * @param climate     当前生物群系气候（可为 null，为 null 时跳过气候修正）
     * @param currentTick 当前世界总刻
     * @param current     当前 MC 天气（用于「无变化」判断）
     */
    public WeatherDecision decide(WeatherObservation observation, BiomeClimate climate, long currentTick,
                                 PlatformWeather current) {
        if (observation == null) {
            return new WeatherDecision(current == null ? PlatformWeather.CLEAR : current, 0, 0,
                    Double.NaN, WeatherType.UNKNOWN, false, Text.tr("nowweather.reason.no_data"), currentTick,
                    java.util.List.of());
        }

        // ★ 下界 / 末地 / 虚空保持原版：直接返回「不动」，durationTicks=0 让控制器完全不写天气
        if (config.leaveNetherAndEndAlone() && climate != null && climate.climateClass().isWeatherFree()) {
            return new WeatherDecision(current == null ? PlatformWeather.CLEAR : current, 0, 0,
                    observation.temperatureC(), observation.weatherType(), false,
                    Text.tr("nowweather.reason.weather_free"), currentTick,
                    java.util.List.of());
        }

        WeatherType type = observation.weatherType();
        double temperature = observation.temperatureC();
        double humidity = observation.humidity01();
        double wind = observation.windiness01();
        boolean adjusted = false;
        java.util.List<com.nowweather.core.forecast.SanityViolation> violations = java.util.List.of();

        if (config.respectBiomeClimate() && climate != null) {
            SanityReport report = filter.inspect(climate, type, temperature, humidity, wind);
            type = report.adjustedType();
            temperature = report.adjustedTempC();
            violations = report.violations();
            adjusted = !violations.isEmpty();
        }

        PlatformWeather target = toPlatformWeather(type);
        int duration = durationFor(type);
        int clearDuration = config.clearDurationTicks();

        String reason = buildReason(observation, type, adjusted, violations);

        return new WeatherDecision(target, duration, clearDuration, temperature, type, adjusted,
                reason, currentTick, violations);
    }

    /** 标准化天气 → 原版三档。 */
    public PlatformWeather toPlatformWeather(WeatherType type) {
        if (type == null || type == WeatherType.UNKNOWN) {
            return PlatformWeather.CLEAR;
        }
        // ★ 雷电必须先判、降水后判。
        //   以前这里先写 `if (!type.isPrecipitation()) return CLEAR;`，
        //   于是下面那句 `thunderRequiresPrecipitation && !isPrecipitation()` 恒为假 ——
        //   配置项 mapping.thunderRequiresPrecipitation 变成了一个「能开关但毫无作用」的摆设。
        //   调整顺序后它对当前天气表仍然完全等价（所有 thunder > 0 的类型降水强度都 > 0），
        //   但将来一旦加入「干雷暴」这类有雷电、无降水的天气，这个开关就是真正起作用的那一个。
        if (type.thunder() >= config.thunderIntensityThreshold()
                && (!config.thunderRequiresPrecipitation() || type.isPrecipitation())) {
            return PlatformWeather.THUNDER;
        }
        if (!type.isPrecipitation()) {
            return PlatformWeather.CLEAR;
        }
        if (type.precipitation() >= config.rainIntensityThreshold()) {
            return PlatformWeather.RAIN;
        }
        // 很弱的降水（毛毛雨之类）在默认阈值下不落地为原版降雨，
        // 因为原版降雨的视觉效果远强于毛毛雨，会显得失真。
        return PlatformWeather.CLEAR;
    }

    /** 依天气强度决定持续时间。 */
    public int durationFor(WeatherType type) {
        double severity = type.severity();
        int min = config.minWeatherDurationTicks();
        int max = config.maxWeatherDurationTicks();
        int duration = (int) Math.round(MathUtil.lerp(min, max, MathUtil.smoothstep(severity)));
        if (type == WeatherType.CLEAR || type.category() == com.nowweather.core.weather.WeatherCategory.CLEAR) {
            duration = config.clearDurationTicks();
        }
        return MathUtil.clamp(duration, 100, 240_000);
    }

    /**
     * 拼「决策依据」—— 刻意做得<b>短</b>。
     *
     * <p>聊天面板上「真实天气」「群系气候」「已落地」已经各自成行，
     * 依据里再复述一遍只会把它撑成一整行天书：</p>
     * <pre>
     *   依据: 真实天气「暴雨」，本地气候 亚热带 18.0~38.0°C（本群系无降水）；
     *         因干旱气候下不应出现降水，天气类型修正为「多云」；
     *         原版只有晴 / 雨 / 雷三档，本次落地「晴朗」          ← 太长
     * </pre>
     * <p>所以这里只保留<b>面板上没有显示、但玩家最需要知道</b>的那件事：
     * <b>为什么被修正</b>，以及<b>修正成了什么</b>。没被修正时一句话带过。</p>
     *
     * <p>短格式：</p>
     * <pre>
     *   符合本地气候
     *   干旱气候下不应出现降水 → 多云
     *   温度高于该生物群系的正常温度范围、风力超出该气候的常见范围 → 大风
     * </pre>
     *
     * <p>「原版落地」那一层由面板的「已落地」行单独显示，这里不再重复 ——
     * 两层天气混淆的问题靠<b>面板本身</b>解决（已落地 = 原版三档，
     * ×× 天气类型 = 群系气候层的三十多种），而不是靠把一句话写得更长。</p>
     */
    private String buildReason(WeatherObservation observation, WeatherType type, boolean adjusted,
                               java.util.List<com.nowweather.core.forecast.SanityViolation> violations) {
        // 最多列两条，再多就只剩噪音了
        int maxShown = 2;
        StringBuilder sb = new StringBuilder();
        if (!adjusted) {
            sb.append(Text.tr("nowweather.reason.ok"));
        } else {
            int shown = Math.min(maxShown, violations.size());
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    joined.append(Text.tr("nowweather.list.separator"));
                }
                joined.append(violations.get(i).zhDescription());
            }
            if (violations.size() > shown) {
                joined.append(Text.tr("nowweather.reason.more", Integer.toString(violations.size())));
            }
            sb.append(Text.tr("nowweather.reason.chain", joined.toString(), type.zhName()));
        }
        if (observation.stale()) {
            sb.append(Text.tr("nowweather.reason.stale"));
        }
        return sb.toString();
    }
}

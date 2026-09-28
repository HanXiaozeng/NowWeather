package com.nowweather.core.weather;

import com.nowweather.core.text.Text;

import java.util.Locale;

/**
 * 标准化天气状态。
 *
 * <p>这是 NowWeather 的「共同语言」：外部天气 API、其它天气模组、Minecraft 原版天气
 * 都会被翻译成这里的枚举值，再由 {@code SyncPolicy} 决定怎么落到游戏里。</p>
 *
 * <p>每个值都带有可量化的物理属性（云量 / 降水强度 / 雷电强度 / 风力），
 * 预报系统与气候合理性校验都依赖这些数值，而不是字符串比较。</p>
 */
public enum WeatherType {

    // 晴与云
    CLEAR("晴", WeatherCategory.CLEAR, 0.00D, 0.00D, 0.00D, 0.15D),
    MAINLY_CLEAR("晴间多云", WeatherCategory.CLEAR, 0.20D, 0.00D, 0.00D, 0.20D),
    PARTLY_CLOUDY("局部多云", WeatherCategory.CLOUDY, 0.45D, 0.00D, 0.00D, 0.25D),
    CLOUDY("多云", WeatherCategory.CLOUDY, 0.70D, 0.00D, 0.00D, 0.30D),
    OVERCAST("阴", WeatherCategory.CLOUDY, 0.95D, 0.00D, 0.00D, 0.35D),

    // 能见度
    FOG("雾", WeatherCategory.FOG, 0.85D, 0.00D, 0.00D, 0.10D),
    HAZE("霾", WeatherCategory.FOG, 0.55D, 0.00D, 0.00D, 0.15D),
    SMOKE("烟霾", WeatherCategory.FOG, 0.45D, 0.00D, 0.00D, 0.20D),

    // 风与沙尘
    WINDY("大风", WeatherCategory.WIND, 0.40D, 0.00D, 0.00D, 0.80D),
    SANDSTORM("沙尘暴", WeatherCategory.DUST, 0.35D, 0.00D, 0.00D, 0.95D),
    TYPHOON("台风", WeatherCategory.STORM, 1.00D, 0.90D, 0.75D, 1.00D),

    // 雨
    DRIZZLE("毛毛雨", WeatherCategory.RAIN, 0.75D, 0.15D, 0.00D, 0.25D),
    LIGHT_RAIN("小雨", WeatherCategory.RAIN, 0.85D, 0.30D, 0.02D, 0.30D),
    RAIN("中雨", WeatherCategory.RAIN, 0.95D, 0.55D, 0.10D, 0.40D),
    HEAVY_RAIN("大雨", WeatherCategory.RAIN, 1.00D, 0.80D, 0.25D, 0.55D),
    /**
     * 暴雨。
     *
     * <p>原来枚举里缺这一档，于是彩云天气报的 {@code STORM_RAIN} 被归并成「大雨」——
     * 结果面板上「真实天气」显示大雨、而「依据」里的数据源原文写着暴雨，同一个事实两个名字。</p>
     */
    STORM_RAIN("暴雨", WeatherCategory.RAIN, 1.00D, 0.95D, 0.40D, 0.70D),
    FREEZING_RAIN("冻雨", WeatherCategory.RAIN, 1.00D, 0.45D, 0.05D, 0.45D),
    THUNDERSTORM("雷阵雨", WeatherCategory.STORM, 1.00D, 0.75D, 0.70D, 0.65D),
    SEVERE_THUNDERSTORM("强雷暴", WeatherCategory.STORM, 1.00D, 0.95D, 1.00D, 0.80D),

    // 雪与冰
    SLEET("雨夹雪", WeatherCategory.SNOW, 0.95D, 0.45D, 0.05D, 0.45D),
    LIGHT_SNOW("小雪", WeatherCategory.SNOW, 0.85D, 0.25D, 0.00D, 0.35D),
    SNOW("中雪", WeatherCategory.SNOW, 0.95D, 0.50D, 0.05D, 0.45D),
    HEAVY_SNOW("大雪", WeatherCategory.SNOW, 1.00D, 0.80D, 0.10D, 0.60D),
    BLIZZARD("暴风雪", WeatherCategory.SNOW, 1.00D, 0.95D, 0.35D, 0.95D),
    HAIL("冰雹", WeatherCategory.STORM, 1.00D, 0.80D, 0.30D, 0.70D),

    // 数据缺失时的占位
    UNKNOWN("未知", WeatherCategory.CLOUDY, 0.50D, 0.00D, 0.00D, 0.30D);

    /**
     * 各档天气的中文原文。
     *
     * <p><b>它已经不再是返回值</b>：{@link #zhName()} 改为查语言键，这里的字面量只作为
     * 语言文件（{@code nowweather.weather.*}）的对照原文保留，便于核对译文有没有漏改。
     * 真正要改玩家看到的文案，请改语言文件，不要改这里。</p>
     */
    private final String zhName;
    private final WeatherCategory category;
    private final double cloudCover;
    private final double precipitation;
    private final double thunder;
    private final double wind;

    WeatherType(String zhName, WeatherCategory category, double cloudCover, double precipitation,
                double thunder, double wind) {
        this.zhName = zhName;
        this.category = category;
        this.cloudCover = cloudCover;
        this.precipitation = precipitation;
        this.thunder = thunder;
        this.wind = wind;
    }

    /** 该档天气的稳定语言键，例如 {@code nowweather.weather.heavy_rain}（与语言无关）。 */
    public String key() {
        return "nowweather.weather." + Text.segment(this);
    }

    /**
     * 该档天气的展示名。
     *
     * <p>方法名里的 {@code zh} 是历史包袱（调用点太多，不便改名），
     * <b>实际返回的是当前游戏语言下的文本</b>；未安装翻译器时返回语言键名。</p>
     */
    public String zhName() {
        return Text.tr(key());
    }

    public WeatherCategory category() {
        return category;
    }

    /** 云量 0~1。 */
    public double cloudCover() {
        return cloudCover;
    }

    /** 降水强度 0~1。 */
    public double precipitation() {
        return precipitation;
    }

    /** 雷电强度 0~1。 */
    public double thunder() {
        return thunder;
    }

    /** 风力 0~1。 */
    public double wind() {
        return wind;
    }

    /** 综合「恶劣程度」0~1，用于排序与 HUD 显示。 */
    public double severity() {
        return Math.min(1.0D, cloudCover * 0.2D + precipitation * 0.4D + thunder * 0.25D + wind * 0.15D);
    }

    public boolean isPrecipitation() {
        return precipitation > 0.0D;
    }

    public boolean isThundering() {
        return thunder > 0.0D;
    }

    public boolean isFrozen() {
        return this == SLEET || this == LIGHT_SNOW || this == SNOW || this == HEAVY_SNOW
                || this == BLIZZARD || this == HAIL || this == FREEZING_RAIN;
    }

    /** 是否需要降低能见度（雾/霾/沙尘/暴风雪）。 */
    public boolean reducesVisibility() {
        return this == FOG || this == HAZE || this == SMOKE || this == SANDSTORM || this == BLIZZARD;
    }

    public boolean isClearSky() {
        return category == WeatherCategory.CLEAR;
    }

    /** 是否阴天以上（云量 >= 0.7）。 */
    public boolean isOvercastOrWorse() {
        return cloudCover >= 0.7D;
    }

    /**
     * 依降水强度与冻结情况换算成对应形态。
     *
     * <p>冻结分支的档位分界与 {@link #snowOf} 保持一致（0.35 / 0.68），
     * 否则同一个强度在这两个入口会得到不同的雪档 ——
     * 例如 0.30（小雨级）冻结后，这里以前给中雪、snowOf 给小雪，玩家会看到「小雨变中雪」。</p>
     *
     * @param celsius 温度
     */
    public WeatherType asFrozen(boolean frozen, double celsius) {
        if (!isPrecipitation()) {
            return this;
        }
        double intensity = precipitation;
        if (frozen) {
            if (thunder > 0.6D) {
                return HAIL;
            }
            if (intensity < 0.35D) {
                return LIGHT_SNOW;
            }
            if (intensity < 0.68D) {
                return SNOW;
            }
            if (intensity < 0.9D && wind > 0.85D) {
                return BLIZZARD;
            }
            return HEAVY_SNOW;
        }
        if (celsius <= 1.5D && intensity > 0.25D) {
            return SLEET;
        }
        if (celsius < 0.0D) {
            return FREEZING_RAIN;
        }
        return this;
    }

    /**
     * 依降水强度取雨类天气。
     *
     * <p><b>必须覆盖全部雨档。</b>这里以前没有 {@link #STORM_RAIN} 那一档，
     * 于是 0.95 强度的暴雨走到这里会被塌回大雨 —— 而 STORM_RAIN 当初就是为了
     * 把「暴雨」和「大雨」区分开才加的，等于这个区分在半数路径上被抹掉了。
     * 阈值与 {@link #snowOf} / {@link #asFrozen} 保持同一套分界。</p>
     */
    public static WeatherType rainOf(double intensity) {
        if (intensity <= 0.05D) {
            return OVERCAST;
        }
        if (intensity < 0.22D) {
            return DRIZZLE;
        }
        if (intensity < 0.45D) {
            return LIGHT_RAIN;
        }
        if (intensity < 0.68D) {
            return RAIN;
        }
        if (intensity < 0.88D) {
            return HEAVY_RAIN;
        }
        return STORM_RAIN;
    }

    /**
     * 依降水强度取雪类天气。
     *
     * <p>阈值与 {@link #rainOf} 的分界一一对应（小雨↔小雪、中雨↔中雪、大雨↔大雪），
     * 这样「雨转雪」不会凭空跳档。{@link #BLIZZARD} 需要风力条件，这里不产出。</p>
     */
    public static WeatherType snowOf(double intensity) {
        if (intensity <= 0.05D) {
            return OVERCAST;
        }
        if (intensity < 0.35D) {
            return LIGHT_SNOW;
        }
        if (intensity < 0.68D) {
            return SNOW;
        }
        return HEAVY_SNOW;
    }

    /**
     * 宽松解析天气名。依次尝试三种写法，任一命中即返回：
     * <ol>
     *   <li><b>枚举名</b>（忽略大小写，{@code -} / 空格 视为 {@code _}）：{@code HEAVY_RAIN}</li>
     *   <li><b>当前语言的展示名</b>（忽略大小写）：中文下的「大雨」、英文下的 {@code Heavy Rain}</li>
     *   <li><b>去掉下划线的枚举名</b>：{@code heavyrain}</li>
     * </ol>
     *
     * <p>第 2 条是补一个真 bug：命令的 Tab 补全给出的是 <b>展示名</b>（如「雷阵雨」），
     * 而旧实现只比对 {@code name()}，于是补全出来的名字反而解析失败。</p>
     *
     * @param raw      原始文本
     * @param fallback 三种写法都没命中时返回它
     */
    public static WeatherType parse(String raw, WeatherType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        // ① 枚举名
        String key = trimmed.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (WeatherType t : values()) {
            if (t.name().equals(key)) {
                return t;
            }
        }
        // ② 当前语言的展示名
        for (WeatherType t : values()) {
            if (t.zhName().equalsIgnoreCase(trimmed)) {
                return t;
            }
        }
        // ③ 去下划线的枚举名（stormrain / heavyrain）
        String compact = key.replace("_", "");
        for (WeatherType t : values()) {
            if (t.name().replace("_", "").equals(compact)) {
                return t;
            }
        }
        return fallback;
    }
}

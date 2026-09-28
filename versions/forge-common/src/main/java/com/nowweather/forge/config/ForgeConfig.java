package com.nowweather.forge.config;

import com.nowweather.core.NowWeatherCore;
import com.nowweather.core.text.Text;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NowWeather 的 Forge 配置定义。
 *
 * <p>因为用的是 Forge 官方的 {@link ForgeConfigSpec}，所以装好模组之后可以直接在
 * <b>游戏内「模组 → NowWeather → 配置」界面</b>里改所有选项（包括 UApiPro 的 API Key），
 * 不需要去翻 {@code config/} 目录下的文件。</p>
 *
 * <ul>
 *   <li>{@code COMMON}：通用配置，单人/服务端都能在游戏内编辑（Forge 允许编辑 COMMON 与 CLIENT，
 *       SERVER 类型在客户端是只读的，所以需要经常改的选项都放在 COMMON）；</li>
 *   <li>{@code CLIENT}：只影响本机显示（HUD）。</li>
 * </ul>
 *
 * <p>配置项改动后由 {@code ModConfigEvent.Reloading} 触发核心热重载，见 {@code NowWeatherMod}。</p>
 */
public final class ForgeConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        Pair<Common, ForgeConfigSpec> common = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();
        Pair<Client, ForgeConfigSpec> client = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    /**
     * 「影子 spec」：同样构造一份配置定义，但<b>不挂到任何文件</b>。
     *
     * <p>Forge 的 {@code ConfigValue#get()} 在 {@code spec.childConfig == null} 时直接返回
     * {@code defaultSupplier}（已读源码确认），所以这份影子定义里的值<b>永远等于默认值</b>。
     * 配置界面要用它来做「这一项是不是已经改过」的判断与「恢复默认」，
     * 否则就得把 50 多个默认值抄一遍 —— 那是必然会漂移的做法。</p>
     */
    private static final Common DEFAULTS_COMMON;
    private static final Client DEFAULTS_CLIENT;
    private static final ForgeConfigSpec DEFAULTS_COMMON_SPEC;
    private static final ForgeConfigSpec DEFAULTS_CLIENT_SPEC;

    static {
        Pair<Common, ForgeConfigSpec> commonDefaults =
                new ForgeConfigSpec.Builder().configure(Common::new);
        DEFAULTS_COMMON = commonDefaults.getLeft();
        DEFAULTS_COMMON_SPEC = commonDefaults.getRight();
        Pair<Client, ForgeConfigSpec> clientDefaults =
                new ForgeConfigSpec.Builder().configure(Client::new);
        DEFAULTS_CLIENT = clientDefaults.getLeft();
        DEFAULTS_CLIENT_SPEC = clientDefaults.getRight();
    }

    private ForgeConfig() {
    }

    /** 默认值来源（只读；不要 set 它，改了不会落盘也没有意义）。 */
    public static Common defaults() {
        return DEFAULTS_COMMON;
    }

    /** 客户端默认值来源。 */
    public static Client clientDefaults() {
        return DEFAULTS_CLIENT;
    }

    /**
     * 影子 spec 本身。
     *
     * <p><b>注意</b>：不要用 {@code spec.get(path)} 取默认值 ——
     * spec 内部存的是 {@code ValueSpec} 对象，{@code get()} 会把那个对象本身返回给你，
     * 而不是解析后的默认值（实机验证时就是这样打出
     * {@code ForgeConfigSpec$ValueSpec@499cfcfe} 的）。
     * 正确做法是拿影子 spec 上的 {@code ConfigValue} 调 {@code get()}：
     * 影子 spec 没有挂文件（{@code childConfig == null}），此时
     * {@code ConfigValue#get()} 直接返回 {@code defaultSupplier}，也就是默认值。
     * 见 {@link #defaultValue(String)}。</p>
     */
    public static ForgeConfigSpec defaultsSpec(boolean client) {
        return client ? DEFAULTS_CLIENT_SPEC : DEFAULTS_COMMON_SPEC;
    }

    /**
     * toml 路径 → 默认值。
     *
     * <p>由影子 spec 上的全部 {@code ConfigValue} 反射枚举而成（字段都是 public final，
     * 一次性构建后缓存）。这样配置界面/命令就不必把 50 多个默认值各抄一遍 ——
     * 抄写必然漂移，查表不会。</p>
     */
    private static final Map<String, Object> DEFAULTS = buildDefaults();

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> map = new HashMap<>();
        collectDefaults(DEFAULTS_COMMON, map);
        collectDefaults(DEFAULTS_CLIENT, map);
        return Map.copyOf(map);
    }

    private static void collectDefaults(Object holder, Map<String, Object> map) {
        for (java.lang.reflect.Field field : holder.getClass().getDeclaredFields()) {
            if (!ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                ForgeConfigSpec.ConfigValue<?> value = (ForgeConfigSpec.ConfigValue<?>) field.get(holder);
                if (value == null) {
                    continue;
                }
                // 影子 spec 未挂文件 → get() 返回 defaultSupplier，即默认值
                map.put(String.join(".", value.getPath()), value.get());
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 单个字段取不到不影响其它项：界面会退化成「没有默认值可显示」
                NowWeatherLogHolder.debug("无法读取配置默认值：{}", field.getName(), e);
            }
        }
    }

    /** 按 toml 路径取默认值；找不到返回 null。 */
    public static Object defaultValue(String path) {
        return DEFAULTS.get(path);
    }

    /** 便于诊断：默认值条目数。 */
    public static int defaultCount() {
        return DEFAULTS.size();
    }

    /** 避免为了打一行 debug 日志而把日志系统耦合进来。 */
    private static final class NowWeatherLogHolder {
        private static void debug(String message, Object arg, Throwable error) {
            org.apache.logging.log4j.LogManager.getLogger("nowweather")
                    .debug(message, arg, error);
        }
    }

    /** HUD 停靠位置。 */
    public enum HudCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        /**
         * 显示名（按当前语言）。
         *
         * <p>名字保留 {@code zhName} 只是为了不动调用点（配置界面用它显示开关当前值），
         * 返回值其实跟随玩家语言：{@code nowweather.config.corner.<枚举名小写>}。</p>
         */
        public String zhName() {
            return Text.tr("nowweather.config.corner." + Text.segment(this));
        }
    }

    // ------------------------------------------------------------------ COMMON

    public static final class Common {

        // 总开关
        public final ForgeConfigSpec.BooleanValue enabled;

        // 位置
        public final ForgeConfigSpec.ConfigValue<String> city;
        public final ForgeConfigSpec.ConfigValue<String> adcode;
        public final ForgeConfigSpec.BooleanValue useIpLocation;
        public final ForgeConfigSpec.BooleanValue announceLocationChange;

        // UApiPro
        public final ForgeConfigSpec.ConfigValue<String> providerId;
        public final ForgeConfigSpec.ConfigValue<String> apiKey;
        public final ForgeConfigSpec.ConfigValue<String> caiyunToken;
        public final ForgeConfigSpec.ConfigValue<String> endpoint;
        public final ForgeConfigSpec.ConfigValue<String> openMeteoEndpoint;
        public final ForgeConfigSpec.BooleanValue openMeteoGeocoding;
        public final ForgeConfigSpec.ConfigValue<String> coordinates;
        public final ForgeConfigSpec.IntValue requestTimeoutMillis;
        public final ForgeConfigSpec.IntValue refreshIntervalSeconds;
        public final ForgeConfigSpec.IntValue staleAfterSeconds;

        // 天气映射
        public final ForgeConfigSpec.BooleanValue respectBiomeClimate;
        public final ForgeConfigSpec.DoubleValue rainIntensityThreshold;
        public final ForgeConfigSpec.DoubleValue thunderIntensityThreshold;
        public final ForgeConfigSpec.IntValue minWeatherDurationTicks;
        public final ForgeConfigSpec.IntValue maxWeatherDurationTicks;
        public final ForgeConfigSpec.IntValue clearDurationTicks;
        public final ForgeConfigSpec.BooleanValue thunderRequiresPrecipitation;
        public final ForgeConfigSpec.BooleanValue leaveNetherAndEndAlone;
        public final ForgeConfigSpec.BooleanValue terrainTransfer;
        public final ForgeConfigSpec.DoubleValue metersPerBlock;
        public final ForgeConfigSpec.IntValue vanillaWeatherMaxMinutes;
        public final ForgeConfigSpec.BooleanValue seasonIntegration;
        public final ForgeConfigSpec.DoubleValue seasonAmplitude;
        public final ForgeConfigSpec.BooleanValue timeSyncEnabled;
        public final ForgeConfigSpec.ConfigValue<Integer> timezoneOffsetMinutes;
        public final ForgeConfigSpec.IntValue timeSyncIntervalTicks;
        public final ForgeConfigSpec.BooleanValue moonPhaseSync;
        public final ForgeConfigSpec.BooleanValue shaderIntensitySync;
        public final ForgeConfigSpec.BooleanValue cloudToRain;
        public final ForgeConfigSpec.DoubleValue cloudRainScale;
    /** 降雨 / 降雪量倍率：精调「雨雪下得多大」（见 shaders 块的注释）。 */
    public final ForgeConfigSpec.DoubleValue rainAmountScale;
    public final ForgeConfigSpec.DoubleValue snowAmountScale;
        public final ForgeConfigSpec.BooleanValue offineFallback;
        public final ForgeConfigSpec.ConfigValue<String> offlineDefaultWeather;

        // 预报
        public final ForgeConfigSpec.IntValue forecastHorizon;
        public final ForgeConfigSpec.IntValue forecastTicksPerEntry;
        public final ForgeConfigSpec.BooleanValue skyForecastEnabled;
        public final ForgeConfigSpec.DoubleValue temperatureToleranceC;
        public final ForgeConfigSpec.BooleanValue allowExtremeEvents;

        // 同步 / 范围
        public final ForgeConfigSpec.BooleanValue syncToClients;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> dimensions;
        public final ForgeConfigSpec.ConfigValue<String> fixedBiomeId;

        // 其它模组兼容
        public final ForgeConfigSpec.BooleanValue yieldToOtherWeatherMods;
        public final ForgeConfigSpec.BooleanValue weather2Drive;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> yieldToModIds;

        // LLM（可选，默认关闭 —— 这是本项目唯一会产生费用的功能）
        public final ForgeConfigSpec.BooleanValue llmEnabled;
        public final ForgeConfigSpec.ConfigValue<String> llmApiKey;
        public final ForgeConfigSpec.ConfigValue<String> llmEndpoint;
        public final ForgeConfigSpec.ConfigValue<String> llmModel;
        public final ForgeConfigSpec.BooleanValue llmThinking;
        public final ForgeConfigSpec.IntValue llmMaxTokens;
        public final ForgeConfigSpec.BooleanValue llmWeatherDaily;
        public final ForgeConfigSpec.BooleanValue llmAllowConfigEdits;

        // 调试
        public final ForgeConfigSpec.BooleanValue logProviderDiagnostics;
        public final ForgeConfigSpec.BooleanValue announceWeatherChanges;

        Common(ForgeConfigSpec.Builder b) {
            b.comment("NowWeather —— 用真实天气驱动 Minecraft",
                            "所有选项都可以在游戏内「模组 → NowWeather → 配置」里修改，改完立即生效。")
                    .push("general");
            enabled = b.comment("总开关。关掉之后 NowWeather 不再干预游戏天气（原版天气照常运行）。")
                    .define("enabled", true);
            b.pop();

            b.comment("位置设置：决定查询哪个地区的真实天气。",
                            "优先级：行政区划代码(adcode) > 城市名 > 按 IP 自动定位。")
                    .push("location");
            adcode = b.comment("行政区划代码，最精确。例如 330106 = 杭州市西湖区、110000 = 北京市。",
                            "留空则使用下面的城市名。")
                    .define("adcode", "");
            city = b.comment("城市名，例如 杭州、北京。",
                            "城市名有歧义时（重名）建议改用 adcode。")
                    .define("city", "");
            useIpLocation = b.comment("上面两项都留空时，是否让 UApiPro 按请求方 IP 自动定位。",
                            "单机游戏时这通常就是你所在的城市；专用服务器上定位到的是机房所在地，建议填 adcode。")
                    .define("useIpLocation", true);
            announceLocationChange = b.comment("玩家跨城 / 跨省 / 跨国时在聊天框播报（默认开）。",
                            "每隔刷新间隔（默认 20 分钟）自动巡检一次位置：",
                            "仍在同一个地级市（adcode 前 4 位相同）就只静默更新经纬度，不打扰玩家；",
                            "跨市 / 跨省 / 跨境或跨越时区时，会调整坐标与时区并播报一次。")
                    .define("announceLocationChange", true);
            coordinates = b.comment("坐标，格式「纬度,经度」，例如 30.274,120.155。",
                            "★ 填了它就用坐标查询（Open-Meteo 全球覆盖，最准确）；",
                            "留空则由 Open-Meteo 用上面的城市名自动地理编码（支持中文），",
                            "或由 UApiPro 按 adcode / IP 定位。")
                    .define("coordinates", "");
            b.pop();

            b.comment("UApiPro 接口设置").push("api");
            providerId = b.comment("首选天气数据源 id。留空 = 按优先级自动回退（推荐）。",
                            "默认回退链（数字小者优先，升序排序）：",
                            "  caiyun    ( 80) 彩云天气 —— 雷达实测降水、1km 分辨率、自带云量与时区；需要 caiyunToken",
                            "  openmeteo ( 90) Open-Meteo —— 全球覆盖、无需密钥；需要城市名或坐标",
                            "  uapipro   (100) UApiPro —— 国内城市/adcode 精确，且能按 IP 自动定位（兜底）",
                            "也可以填 uapipro / openmeteo / caiyun / static，或其它模组注册的数据源 id 强制只用它。")
                    .define("providerId", "");
            caiyunToken = b.comment("彩云天气 token（推荐！彩云是默认最高优先级数据源）。",
                            "去 https://platform.caiyunapp.com 注册即可拿到。",
                            "彩云给的是雷达实测降水、1km 分辨率、分钟级刷新，还自带云量与时区 ——",
                            "实测同一时刻彩云说「中雨 9.18mm/h、雨带距离 0」，而 UApiPro 还在说「多云 34°C」。",
                            "留空则退回用下面的 apiKey。")
                    .define("caiyunToken", "");
            apiKey = b.comment("UApiPro API Key（形如 uapi-xxxxxxxx）。",
                            "基础天气查询不需要 Key 也能用；填上 Key 可以获得更高的调用配额。",
                            "注意：这个值会保存在 config/nowweather-common.toml 里，请勿把它连配置文件一起公开。")
                    .define("apiKey", "");
            endpoint = b.comment("天气接口地址。除非 UApiPro 更换域名，否则不要改。")
                    .define("endpoint", NowWeatherCore.DEFAULT_WEATHER_ENDPOINT);
            openMeteoEndpoint = b.comment("Open-Meteo 接口地址。除非官方更换域名，否则不要改。")
                    .define("openMeteoEndpoint", "https://api.open-meteo.com/v1/forecast");
            openMeteoGeocoding = b.comment("是否允许用城市名去 Open-Meteo 的地理编码接口解析经纬度。",
                            "关掉之后 Open-Meteo 只在填了坐标时才工作。")
                    .define("openMeteoGeocoding", true);
            requestTimeoutMillis = b.comment("单次请求超时（毫秒）。网络不好可以调大。")
                    .defineInRange("requestTimeoutMillis", 10_000, 1_000, 120_000);
            refreshIntervalSeconds = b.comment("真实天气刷新间隔（秒）。默认 1200 秒（20 分钟）。",
                            "Open-Meteo 的数据每 15 分钟更新一次，UApiPro 的实况也是十几分钟一次，",
                            "20 分钟足够及时，也不会浪费免费额度（一天约 72 次请求）。")
                    .defineInRange("refreshIntervalSeconds", 1200, 30, 86_400);
            staleAfterSeconds = b.comment("数据超过这个时间（秒）就标记为「过期」，界面上会提示使用的是缓存数据。")
                    .defineInRange("staleAfterSeconds", 1_800, 60, 604_800);
            b.pop();

            b.comment("天气映射策略：真实天气怎么变成 Minecraft 的天气。").push("mapping");
            respectBiomeClimate = b.comment("★ 是否让生物群系气候约束真实天气（强烈建议开启）。",
                            "开启后：沙漠不会下雨、雪原不会出现 30°C 的晴天、",
                            "预报数据必须落在该群系的「正常温度范围」内，",
                            "并且只使用该群系「可能出现的天气」。")
                    .define("respectBiomeClimate", true);
            rainIntensityThreshold = b.comment("降水的强度阈值（0~1）。大于等于它才会在游戏里真的下雨。",
                            "调高 = 只有大雨才下雨；调低 = 毛毛雨也下雨。")
                    .defineInRange("rainIntensityThreshold", 0.22D, 0.0D, 1.0D);
            thunderIntensityThreshold = b.comment("雷电强度阈值（0~1）。大于等于它才会打雷。")
                    .defineInRange("thunderIntensityThreshold", 0.55D, 0.0D, 1.0D);
            minWeatherDurationTicks = b.comment("一次天气最少持续多少刻（20 刻 = 1 秒）。")
                    .defineInRange("minWeatherDurationTicks", 3_000, 100, 240_000);
            maxWeatherDurationTicks = b.comment("一次天气最多持续多少刻。")
                    .defineInRange("maxWeatherDurationTicks", 18_000, 100, 240_000);
            clearDurationTicks = b.comment("晴朗天气的持续时间（刻）。")
                    .defineInRange("clearDurationTicks", 6_000, 100, 240_000);
            thunderRequiresPrecipitation = b.comment("打雷时是否必须同时下雨（原版行为为「是」）。")
                    .define("thunderRequiresPrecipitation", true);
            leaveNetherAndEndAlone = b.comment("★ 下界 / 末地 / 虚空是否完全保持原版。",
                            "开启时（默认）：这些群系下模组<b>完全不写天气</b>，连计时器都不动。",
                            "原版这些维度本来就没有天气系统，动它们只会让人困惑。")
                    .define("leaveNetherAndEndAlone", true);
            seasonIntegration = b.comment("是否与季节模组（Serene Seasons）联动。",
                            "开启后：按当前季节平移各生物群系的「正常温度范围」——",
                            "温带群系冬天自动允许下雪、夏天自动禁止下雪（白名单是从温度范围推导的）。",
                            "季节模组没装时该项无副作用。")
                    .define("seasonIntegration", true);
            terrainTransfer = b.comment("★ 地形订正：把真实天气按物理关系搬到玩家所在地形。",
                            "平原城市 27°C 的数据搬到 1500m 高原会被订正为约 17°C（递减率），",
                            "湿度按露点守恒升高、气压按标准大气下降、山脊加风、谷底夜间积冷。",
                            "关掉它则任何海拔都用查询坐标的原始数据。")
                    .define("terrainTransfer", true);
            vanillaWeatherMaxMinutes = b.comment("原版 /weather 指令的天气最多生效多少分钟。",
                            "原版指令仍然可用，但到期后模组会重新接管（默认 20 分钟）。")
                    .defineInRange("vanillaWeatherMaxMinutes", 20, 1, 720);
            metersPerBlock = b.comment("★ 一格等于多少米（地形订正用）。",
                            "MC 世界总高只有 384 格，按 1 格 = 1 米算出来的一双高山也只有几百米，",
                            "温差不到 2°C，玩家感觉不到。默认 8.0 让 300 格 ≈ 2400 米，",
                            "与「高原 vs 平原」的直观量级一致。改小会削弱地形影响，改大会放大。")
                    .defineInRange("metersPerBlock", 8.0D, 1.0D, 32.0D);
            seasonAmplitude = b.comment("季节幅度倍率（1.0 = 默认）。",
                            "1.0 = 冬季约 -14°C、夏季约 +8°C；0.5 = 幅度减半；0 = 只收窄范围不做季节平移。")
                    .defineInRange("seasonAmplitude", 1.0D, 0.0D, 3.0D);
            offineFallback = b.comment("离线兜底：拿不到真实天气时，按本地气候生成合理天气，",
                            "而不是放着原版天气不管。")
                    .define("offlineFallback", true);
            offlineDefaultWeather = b.comment("离线兜底使用的天气。",
                            "AUTO = 按本地气候自动决定；也可以填 CLEAR / RAIN / SNOW / THUNDERSTORM 等。")
                    .define("offlineDefaultWeather", "AUTO");
            b.pop();

            b.comment("★ 真实时间同步：让游戏内时间与所在地的真实时间一致").push("timesync");
            timeSyncEnabled = b.comment("开启后：游戏内一天 = 真实 24 小时，游戏内钟点与所在地钟点一致。",
                            "（原版一天只有 20 分钟；开启后太阳/月亮/生物生成都会跟着真实时间走。）",
                            "注意：该模式下「睡觉跳过夜晚」只能把时间短暂跳过去 —— 下一秒会被校回真实时刻，",
                            "因为本模式的目标就是与真实时间一致。想要原版作息请关闭它。")
                    .define("enabled", true);
            // 注意：这里刻意不用 defineInRange —— 哨兵值 Integer.MIN_VALUE 会被范围校验夹到边界
            //（实测把「自动」夹成了 GMT-18:00），所以改用无校验的 define 并在注释里说明取值范围。
            timezoneOffsetMinutes = b.comment("手动指定时区偏移（分钟），例如北京 = 480、纽约 = -300。",
                            "取值范围 -1080 ~ 1080（即 UTC-18:00 ~ UTC+18:00，覆盖实际存在的全部时区）。",
                            "留空（默认为 -2147483648）表示<b>自动解析</b>：",
                            "  1) 优先用天气接口返回的所在地时区（Open-Meteo 会带回来，最准确）；",
                            "  2) 其次用服务器/客户端的系统时区；",
                            "  3) 都没有时按经度估算（每 15° = 1 小时）。",
                            "解析结果会写入本地缓存，所以离线时区依然正确。")
                    .define("timezoneOffsetMinutes", Integer.MIN_VALUE);
            timeSyncIntervalTicks = b.comment("【已废弃，不再生效】服务端现在每刻都写一次世界时间。",
                            "为什么取消节流：游戏内时钟每个真实秒只走约 0.28 刻，而原版日光循环每刻都会",
                            "自行 +1 刻；只要不是每刻写，服务端时钟就会在两次写入之间被带跑",
                            "（旧默认 20 刻会漂 14 刻），客户端 / 季节模组都会看到这个每秒一次的抖动。",
                            "写的是一个整数、不走网络，所以每刻写没有任何代价。",
                            "本项保留只是为了兼容旧配置文件（旧文件里写着 20，现在会被忽略）。")
                    .defineInRange("intervalTicks", 1, 1, 24000);
            moonPhaseSync = b.comment("让月相跟随真实历法（默认开）。",
                            "Minecraft 的月相就是「世界天数 % 8」（0 = 满月、4 = 新月），",
                            "而天数偏移只保证天数连续 —— 不开的话世界天数与朔望月毫无关系，",
                            "中秋节可能不是满月。开启后会把天数偏移平移 0~7 天来对齐真实月相",
                            "（按平均朔望月 29.530588853 天外推，误差 ±0.7 天以内，",
                            "注意这会顺带把世界天数与季节相位整体挪几天）。")
                    .define("moonPhaseSync", true);
            b.pop();

            b.comment("光影联动（Iris / OptiFine）").push("shaders");
            shaderIntensitySync = b.comment("把连续的雨/雷强度写进世界，供光影读取（默认开）。",
                            "原版天气只有「下 / 不下」，所以 rainLevel 只会是 0 或 1；",
                            "而 Iris / OptiFine 给光影的 rainStrength / wetness / thunderStrength",
                            "全部取自 Level#getRainLevel / getThunderLevel。",
                            "开启后毛毛雨 / 中雨 / 暴雨 / 雷阵雨在光影里会有层次，不再是同一个画面。",
                            "★ 云量与降水共用同一个 rainLevel，按区间两段式切分（上界见下面的 cloudRainScale）：",
                            "  0.00 ~ 0.60   云量 —— 阴天 / 多云（由 cloudToRain 写入）",
                            "  0.60 ~ 0.75   缓冲带 —— 两边都不解读，避免边界抖动",
                            "  0.75 ~ 1.00   降水 —— 真下雨 / 下雪（由本项写入）",
                            "注意：原版 LevelRenderer 只要 getRainLevel > 0 就会开启雨致变暗，",
                            "所以「云量压暗天色」是顺带发生的 —— 这正是阴天仿真想要的观感。")
                    .define("intensitySync", true);
            cloudToRain = b.comment("阴天仿真：用真实云量压暗天色（默认开，开不开光影都生效）。",
                            "原版天气只有晴 / 雨 / 雷，根本没有「阴」，所以真实天气是阴天时游戏里会是大晴天。",
                            "做法：把真实云量写进客户端的 rainLevel —— 它既会压暗世界",
                            "（原版暗层 + 天空变灰 + 原版云变厚），又会被光影当作 rainStrength 用来长云。",
                            "同时会接管原版的天气渲染（Forge 公开的 DimensionSpecialEffects.setWeatherRenderHandler），",
                            "这样阴天不会凭空画雨；一旦变成真降水就立刻还原，雨雪照常显示。")
                    .define("cloudToRain", true);
            cloudRainScale = b.comment("云量区间上界（默认 0.60，也就是光影适配契约值 NW_CLOUD_BAND）。",
                            "模组把云量与降水编码进同一个 rainStrength，按区间切分：",
                            "  0.00 ~ 此值   云量（阴天 / 多云走这里：云多但不下雨）",
                            "  0.75 ~ 1.00   降水（真下雨 / 下雪走这里）",
                            "中间留一段缓冲区，这样改过 NowWeather.glsl 的光影包才能区分「阴天」和「下雨」。",
                            "★ 连带关系（改这里之前务必读完）：适配过的光影包把这个上界<b>写死</b>在",
                            "  Lib/IndividualFounctions/NowWeather.glsl 的 NW_CLOUD_BAND 里，值为 0.60",
                            "  （注意目录名 Founctions 是原包拼错的，少一个 c；Settings.glsl 里没有任何 NW_ 宏）。",
                            "  模组改这里<b>不会</b>同步改光影包，于是两边会对不上：",
                            "    调成 0.50 → 真实云量 1.0 只写出 0.50，光影解出的云量只有 0.833，阴天永远到不了满云；",
                            "    调成 0.70 → 云量 0.857 就顶满，画面被压缩。",
                            "  除非同步改了光影包的 NW_CLOUD_BAND，否则请保持默认 0.60。")
                    .defineInRange("cloudRainScale", 0.60D, 0.0D, 0.70D);
            rainAmountScale = b.comment("降雨量倍率（默认 1.0，范围 0.0 ~ 2.0）—— 精调「雨下得多大」。",
                            "它乘在降水强度上，再编码进 rainStrength 的降水区间（0.75 ~ 1.00），",
                            "光影侧解出的 nwPrecipitation() / nwRainAmount() 会随之变化：",
                            "  0.5  → 中雨看起来像小雨，雨幕更薄、太阳遮挡更少",
                            "  1.0  → 原样（毛毛雨 0.15 / 小雨 0.30 / 中雨 0.55 / 大雨 0.80 / 暴雨 0.95）",
                            "  2.0  → 小雨看起来像中雨，雨幕更浓、天色更暗",
                            "  0.0  → 视觉上不再画雨幕（但天气本身仍然是「在下雨」）",
                            "★ 因为它只改降水区间内部的分量，编码值恒 ≥ 0.75，",
                            "  所以无论怎么调都不会掉进缓冲区 —— 不会出现「说下雨却一滴都不画」。")
                    .defineInRange("rainAmountScale", 1.0D, 0.0D, 2.0D);
            snowAmountScale = b.comment("降雪量倍率（默认 1.0，范围 0.0 ~ 2.0）—— 精调「雪下得多大」。",
                            "与降雨量倍率同一套机制，只是作用于冻结形态的降水",
                            "（小雪 / 中雪 / 大雪 / 暴风雪 / 雨夹雪 / 冻雨 / 冰雹）。",
                            "想让雪更薄或更厚，改这里而不要去动降雨量倍率。")
                    .defineInRange("snowAmountScale", 1.0D, 0.0D, 2.0D);
            b.pop();

            b.comment("预报系统").push("forecast");
            forecastHorizon = b.comment("预报格数（每格一个时间段）。")
                    .defineInRange("horizon", 12, 1, 96);
            forecastTicksPerEntry = b.comment("每格预报持续多少刻。",
                            "开启时间同步时一天 = 真实 24 小时，1000 刻 = 1 小时（默认 12 格 ≈ 未来 12 小时）；",
                            "关闭时间同步时按原版一天 20 分钟算，1000 刻 ≈ 50 秒。")
                    .defineInRange("ticksPerEntry", 1_000, 100, 24_000);
            // ★ 以前这一项只在核心里有（NowWeatherConfig#skyForecastEnabled），Forge 侧既没有配置定义、
            //   桥接里也从不设置它，于是 /nowweather sky 提示的「[weather] skyForecastEnabled」无处可改，
            //   天色预报永远关不掉。这里补上真正的配置节与键，并由 ConfigBridge 接到核心。
            skyForecastEnabled = b.comment("是否获取并应用「天色预报」（默认开）。",
                            "开启后模组会额外请求 Open-Meteo 的逐小时云量 / 低中高云 / 能见度 / 降水概率，",
                            "用真实的云量与能见度驱动天色（阴天压暗、雾变浓），而不是只看晴/雨/雷三档。",
                            "关掉之后天气同步完全不受影响，只是天色不再跟随真实云量。")
                    .define("skyForecastEnabled", true);
            temperatureToleranceC = b.comment("温度容差（°C）。",
                            "真实天气温度超出该群系正常范围时，最多允许偏离多少度再开始修正 ——",
                            "完全硬夹会让「真实天气」失去意义，所以留一点余量。")
                    .defineInRange("temperatureToleranceC", 6.0D, 0.0D, 30.0D);
            allowExtremeEvents = b.comment("是否允许极端天气（台风、强雷暴、沙尘暴、暴风雪、冰雹）。",
                            "关掉之后这些天气会被自动降级成温和版本。")
                    .define("allowExtremeEvents", true);
            b.pop();

            b.comment("同步与作用范围").push("sync");
            syncToClients = b.comment("是否把真实天气与预报同步给客户端（用于 HUD 显示与联动）。")
                    .define("syncToClients", true);
            dimensions = b.comment("由 NowWeather 接管天气的维度列表。",
                            "默认只接管主世界 —— 原版下界/末地没有天气系统，强行设置没有意义。")
                    .defineList("dimensions", List.of("minecraft:overworld"),
                            o -> o instanceof String s && s.contains(":"));
            fixedBiomeId = b.comment("强制指定生物群系（调试用）。留空表示按玩家实际所在群系判断。")
                    .define("fixedBiomeId", "");
            b.pop();

            b.comment("其它模组兼容").push("compat");
            weather2Drive = b.comment("★ 用真实/推算天气主动指挥 Weather2 生成风暴（沙尘暴/雪暴）。",
                            "打开后：是否起风暴、起什么类型由本模组的数据决定，Weather2 只负责渲染。",
                            "关掉则回到纯让权（只提供数据，不干预它的生成）。")
                    .define("weather2Drive", true);
            yieldToOtherWeatherMods = b.comment("★ 检测到其它模组正在控制天气时，NowWeather 是否让出控制权。",
                            "开启可以最大程度避免与天气类模组打架（推荐保持开启）。")
                    .define("yieldToOtherWeatherMods", true);
            yieldToModIds = b.comment("需要让出控制权的模组 id 列表。",
                            "留空表示使用内置列表（projectatmosphere / weather2 / dynamicweather 等）。",
                            "格式示例：[\"weather2\", \"projectatmosphere\"]")
                    .defineList("yieldToModIds", List.of(),
                            o -> o instanceof String s && !s.isBlank());
            b.pop();

            b.comment("LLM 助手（DeepSeek 等 OpenAI 兼容接口）",
                            "★ 这是本项目唯一会产生费用的功能，默认全部关闭。",
                            "「每日一次」的天气推算会在缓存目录里记录上次调用时间，24 小时内不会重复调用。")
                    .push("llm");
            llmEnabled = b.comment("总开关。关闭后所有 AI 功能都不可用（/nowweather ask 会提示）。")
                    .define("enabled", false);
            llmApiKey = b.comment("API Key。留空则用 /nowweather config set 再填。")
                    .define("apiKey", "");
            llmEndpoint = b.comment("接口地址（OpenAI 兼容的 /chat/completions）。")
                    .define("endpoint", "https://api.deepseek.com/chat/completions");
            llmModel = b.comment("模型名。").define("model", "deepseek-v4-flash");
            llmThinking = b.comment("是否开启模型的思考模式（会显著增加输出 token = 成本，默认关）。")
                    .define("thinking", false);
            llmMaxTokens = b.comment("单次输出上限（token）。")
                    .defineInRange("maxTokens", 1200, 64, 8192);
            llmWeatherDaily = b.comment("允许每天用 AI 辅助推算一次天气（省成本的硬上限）。")
                    .define("weatherDaily", true);
            llmAllowConfigEdits = b.comment("允许 /nowweather ask 通过自然语言改配置。",
                            "只能改白名单内、且通过同一套范围校验的配置项。")
                    .define("allowConfigEdits", true);
            b.pop();

            b.comment("调试").push("debug");
            logProviderDiagnostics = b.comment("在日志里打印数据源请求细节（排查「为什么拿不到天气」时打开）。")
                    .define("logProviderDiagnostics", false);
            announceWeatherChanges = b.comment("每次天气切换时，在聊天栏广播一条消息。")
                    .define("announceWeatherChanges", false);
            b.pop();
        }
    }

    // ------------------------------------------------------------------ CLIENT

    public static final class Client {

        public final ForgeConfigSpec.BooleanValue hudEnabled;
        public final ForgeConfigSpec.EnumValue<HudCorner> hudCorner;
        public final ForgeConfigSpec.IntValue hudOffsetX;
        public final ForgeConfigSpec.IntValue hudOffsetY;
        public final ForgeConfigSpec.BooleanValue hudBackground;
        public final ForgeConfigSpec.BooleanValue hudShowLocation;
        public final ForgeConfigSpec.BooleanValue hudShowTemperature;
        public final ForgeConfigSpec.BooleanValue hudShowHumidity;
        public final ForgeConfigSpec.BooleanValue hudShowWind;
        public final ForgeConfigSpec.BooleanValue hudShowClimate;
        public final ForgeConfigSpec.BooleanValue hudShowForecast;
        public final ForgeConfigSpec.BooleanValue skyMoodEnabled;
        public final ForgeConfigSpec.IntValue hudForecastLines;
        public final ForgeConfigSpec.BooleanValue hudOnlyWhenHoldingClock;
        public final ForgeConfigSpec.BooleanValue showInDebugScreen;
        public final ForgeConfigSpec.BooleanValue showInChat;

        Client(ForgeConfigSpec.Builder b) {
            b.comment("客户端显示设置（只影响你自己的界面）").push("hud");
            hudEnabled = b.comment("是否在游戏界面上显示真实天气 HUD。")
                    .define("enabled", true);
            hudCorner = b.comment("HUD 停靠的角落。")
                    .defineEnum("corner", HudCorner.TOP_LEFT);
            hudOffsetX = b.comment("水平微调（像素，正数向右）。")
                    .defineInRange("offsetX", 4, -500, 500);
            hudOffsetY = b.comment("垂直微调（像素，正数向下）。")
                    .defineInRange("offsetY", 4, -500, 500);
            hudBackground = b.comment("是否给 HUD 加半透明背景（在亮色场景下更易读）。")
                    .define("background", true);
            hudShowLocation = b.comment("显示地区名称。").define("showLocation", true);
            hudShowTemperature = b.comment("显示真实气温。").define("showTemperature", true);
            hudShowHumidity = b.comment("显示湿度。").define("showHumidity", true);
            hudShowWind = b.comment("显示风向与风力。").define("showWind", true);
            hudShowClimate = b.comment("显示当前生物群系的气候与「正常温度范围」。")
                    .define("showClimate", true);
            hudShowForecast = b.comment("显示本地预报。").define("showForecast", true);
            skyMoodEnabled = b.comment("按现实天气改变天色（默认开）。",
                            "暴晒 → 提亮偏暖、雾推远；阴沉 → 去饱和变灰压暗；",
                            "雨/雷 → 更暗偏冷；雾霾/沙尘 → 雾显著拉近并染色。",
                            "实现方式是 Forge 公开事件 FogColors / FogDensity，只对原版算好的结果做偏移，",
                            "所以生物群系、昼夜、水下的差异全部保留，也不需要 Mixin（与光影不冲突）。")
                    .define("skyMood", true);
            hudForecastLines = b.comment("预报显示行数。")
                    .defineInRange("forecastLines", 3, 0, 12);
            hudOnlyWhenHoldingClock = b.comment("只在手持时钟时显示天气 HUD（喜欢干净的界面可以打开）。")
                    .define("onlyWhenHoldingClock", false);
            showInChat = b.comment("★ 打开聊天框（默认按 T）时，在屏幕左上角显示 NowWeather 运行信息。",
                            "这是查看模组状态的主入口：不用记 F3，正常游玩时也不会挡视野。")
                    .define("showInChat", true);
            showInDebugScreen = b.comment("在 F3 调试界面右侧也显示一份（默认关）。",
                            "原版 F3 右栏很挤，塞进去容易与其他模组的行重叠。")
                    .define("showInDebugScreen", false);
            b.pop();
        }
    }
}

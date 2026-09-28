package com.nowweather.forge.config;

import com.nowweather.core.text.Text;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 配置项的<b>统一描述表</b>：游戏内配置界面与 {@code /nowweather config} 命令共用同一份定义。
 *
 * <p>为什么要有这个东西：Forge 1.18.2 <b>不会</b>为注册了 {@code ForgeConfigSpec} 的模组
 * 自动生成配置界面（那是 1.19.x+ 才有），所以「玩家能在设置里改所有选项」这件事必须自己实现。
 * 而一旦有两个入口（GUI 与命令），就必然会出现「GUI 能改、命令改不了」或者两边的取值范围不一致
 * 这类漂移 —— 把描述集中到一处是唯一能防住它的办法。</p>
 *
 * <p>每一条描述都带：分组、<b>稳定的键名</b>（等于 toml 里的路径，例如 {@code location.city}）、
 * 取值类型、以及（数值型的）取值范围。</p>
 *
 * <p><b>显示文案不在这里抄写</b>：界面标签与悬浮提示都是语言键，
 * 由配置键推导（{@code mapping.cloudToRain} → {@code nowweather.config.entry.mapping_cloudtorain.label}），
 * 译文交给 Minecraft 的语言文件（见 {@code assets/nowweather/lang/*.json}）。
 * 键名<b>统一小写</b>（配置键里的点换成下划线后再整体小写），避免同一个键出现两种大小写写法。
 * 这样描述表里就再也不会出现硬编码的中文，英文玩家也不必看中文界面。</p>
 *
 * <p><b>默认值不在这里抄写</b>：而是从 {@link ForgeConfig#defaultsSpec(boolean)}（一份不挂文件的
 * 「影子 spec」）按 {@code ConfigValue#getPath()} 查出来并快照。抄写默认值是必然要漂移的做法，
 * 查表则永远不会。</p>
 *
 * <p><b>取值范围必须与 {@code ForgeConfig} 里的 {@code defineInRange} 一致</b> ——
 * 因为 {@code ConfigValue#set()} <b>不做范围校验</b>（它直接写进内存配置），
 * 越界的值会被写进 toml，直到下次启动才被 Forge 的 {@code correct()} 悄悄夹回来。
 * 所以这里在写入前自己夹一次，并在界面/命令里明确报错。</p>
 */
public final class ConfigEntries {

    /** 配置分组（= 界面上的标签页）。 */
    public enum Section {
        LOCATION(false),
        PROVIDER(false),
        MAPPING(false),
        TIME(false),
        FORECAST(false),
        SYNC(false),
        LLM(false),
        DEBUG(false),
        HUD(true);

        private final boolean client;

        Section(boolean client) {
            this.client = client;
        }

        /** 语言键片段：枚举名小写（{@code nowweather.config.section.<它>}）。 */
        private String id() {
            return Text.segment(this);
        }

        /** 分组显示名。名字保留 {@code zhName} 只是为了不动调用点，返回值其实跟随玩家语言。 */
        public String zhName() {
            return Text.tr("nowweather.config.section." + id());
        }

        /** 一句话说明（切标签时显示在状态栏）。 */
        public String hint() {
            return Text.tr("nowweather.config.section." + id() + ".hint");
        }

        /** 是否属于客户端配置（专用服务端上不存在，命令不可改）。 */
        public boolean isClient() {
            return client;
        }
    }

    /** 取值类型，决定界面上用什么控件。 */
    public enum Kind {
        /** 开关：布尔值或枚举循环。 */
        TOGGLE,
        /** 数值：整数或浮点，界面用输入框。 */
        NUMBER,
        /** 文本（含列表，列表用逗号分隔）。 */
        TEXT
    }

    /** 一条配置项。不可变；{@code get/set} 直接作用于 {@link ForgeConfigSpec.ConfigValue}。 */
    public static final class Entry {

        private final Section section;
        private final String key;
        private final Kind kind;
        private final ForgeConfigSpec.ConfigValue<?> value;
        private final Object defaultValue;
        private final double min;
        private final double max;
        private final boolean integral;
        private final List<String> enumNames;

        private Entry(Section section, String key, Kind kind,
                      ForgeConfigSpec.ConfigValue<?> value, Object defaultValue,
                      double min, double max, boolean integral, List<String> enumNames) {
            this.section = section;
            this.key = key;
            this.kind = kind;
            this.value = value;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.integral = integral;
            this.enumNames = enumNames;
        }

        public Section section() {
            return section;
        }

        /** 稳定键名（等于 toml 路径），命令与诊断输出都用它。 */
        public String key() {
            return key;
        }

        /** 界面标签（按当前语言）。 */
        public String label() {
            return Text.tr(labelKey());
        }

        /** 悬浮提示（按当前语言）。 */
        public String hint() {
            return Text.tr(hintKey());
        }

        /**
         * 配置键 → 语言键片段：点换成下划线，并<b>统一小写</b>。
         *
         * <p>例：{@code mapping.cloudToRain} → {@code mapping_cloudtorain}。
         * 之所以强制小写，是因为大小写混用的键最容易出现
         * 「代码里写 cloudToRain、语言文件里写 cloudtorain」这种查不出来的漏译。</p>
         */
        private String flatKey() {
            return key.replace('.', '_').toLowerCase(Locale.ROOT);
        }

        private String labelKey() {
            return "nowweather.config.entry." + flatKey() + ".label";
        }

        private String hintKey() {
            return "nowweather.config.entry." + flatKey() + ".hint";
        }

        public Kind kind() {
            return kind;
        }

        public boolean isClient() {
            return section.isClient();
        }

        /** 当前值的显示文本。 */
        public String display() {
            try {
                return format(value.get());
            } catch (RuntimeException e) {
                return "";
            }
        }

        /** 默认值的显示文本。 */
        public String displayDefault() {
            return format(defaultValue);
        }

        public boolean isDefault() {
            String now = display();
            String def = displayDefault();
            if (now.equals(def)) {
                return true;
            }
            try {
                return Double.parseDouble(now) == Double.parseDouble(def);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** 恢复默认值（只改内存，仍需 save() 才落盘）。 */
        public void resetToDefault() {
            setRaw(defaultValue);
        }

        @SuppressWarnings("unchecked")
        private void setRaw(Object raw) {
            ((ForgeConfigSpec.ConfigValue<Object>) value).set(raw);
        }

        /**
         * 用一段文本设置本项。
         *
         * @return {@code null} 表示成功，否则是可展示给玩家的错误说明
         */
        public String setFromString(String raw) {
            if (raw == null) {
                return Text.tr("nowweather.config.error.empty");
            }
            String text = raw.trim();
            if (enumNames != null) {
                for (String name : enumNames) {
                    if (name.equalsIgnoreCase(text)) {
                        return setEnum(name);
                    }
                }
                return Text.tr("nowweather.config.error.one_of", String.join(" / ", enumNames));
            }
            switch (kind) {
                case TOGGLE -> {
                    if (text.equalsIgnoreCase("true") || text.equals("开") || text.equals("1")) {
                        setRaw(Boolean.TRUE);
                    } else if (text.equalsIgnoreCase("false") || text.equals("关") || text.equals("0")) {
                        setRaw(Boolean.FALSE);
                    } else {
                        return Text.tr("nowweather.config.error.boolean");
                    }
                }
                case NUMBER -> {
                    if (isAutoSentinelSupported() && isAutoText(text)) {
                        setRaw(Integer.MIN_VALUE);
                        return null;
                    }
                    double parsed;
                    try {
                        parsed = Double.parseDouble(text);
                    } catch (NumberFormatException e) {
                        return Text.tr("nowweather.config.error.not_number");
                    }
                    if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                        return Text.tr("nowweather.config.error.not_number");
                    }
                    if (!Double.isNaN(min) && parsed < min) {
                        return Text.tr("nowweather.config.error.too_small", number(min));
                    }
                    if (!Double.isNaN(max) && parsed > max) {
                        return Text.tr("nowweather.config.error.too_large", number(max));
                    }
                    setRaw(integral ? (Object) (int) Math.round(parsed) : (Object) parsed);
                }
                case TEXT -> {
                    if (isList()) {
                        // 清空写法：命令里没法传「空字符串」（brigadier 的 greedyString 至少吃一个字符，
                        // 传 "" 会原样带两个引号进来），所以显式支持这几种「清空」记号。
                        // 实机测试时就是因为这个，`set compat.yieldToModIds ""` 把列表写成了
                        // 一个内容为 `""` 的字符串元素 —— 越看越像配置坏了。
                        if (isClearMarker(text)) {
                            setRaw(Collections.emptyList());
                            return null;
                        }
                        List<String> list = new ArrayList<>();
                        for (String part : text.split(",")) {
                            String item = stripQuotes(part.trim());
                            if (!item.isEmpty()) {
                                list.add(item);
                            }
                        }
                        setRaw(Collections.unmodifiableList(list));
                    } else {
                        setRaw(stripQuotes(text));
                    }
                }
                default -> {
                    return Text.tr("nowweather.config.error.unsupported_kind");
                }
            }
            return null;
        }

        /** 开↔关；枚举则循环到下一个。返回 null 表示成功。 */
        public String toggle() {
            try {
                if (enumNames != null) {
                    Object current = value.get();
                    String currentName = current instanceof Enum<?> e ? e.name() : enumNames.get(0);
                    int index = enumNames.indexOf(currentName);
                    index = (index + 1) % enumNames.size();
                    return setEnum(enumNames.get(index));
                }
                Object current = value.get();
                if (current instanceof Boolean b) {
                    setRaw(!b);
                    return null;
                }
                return Text.tr("nowweather.config.error.not_toggle");
            } catch (RuntimeException e) {
                return e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }

        /** 枚举当前值的显示名（HUD 角落用）；非枚举返回 null。 */
        public String enumZhName() {
            if (enumNames == null) {
                return null;
            }
            try {
                Object current = value.get();
                if (current instanceof ForgeConfig.HudCorner corner) {
                    return corner.zhName();
                }
                return current instanceof Enum<?> e ? e.name() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private String setEnum(String name) {
            try {
                if (value instanceof ForgeConfigSpec.EnumValue<?> enumValue) {
                    setEnumRaw(enumValue, name);
                    return null;
                }
                return Text.tr("nowweather.config.error.not_enum");
            } catch (IllegalArgumentException e) {
                return Text.tr("nowweather.config.error.bad_enum_value", name);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void setEnumRaw(ForgeConfigSpec.EnumValue<?> enumValue, String name) {
            Object current = enumValue.get();
            Class<? extends Enum> type = current instanceof Enum<?> e
                    ? e.getClass() : ForgeConfig.HudCorner.class;
            ((ForgeConfigSpec.EnumValue) enumValue).set(Enum.valueOf(type, name));
        }

        private boolean isList() {
            return defaultValue instanceof List<?>;
        }

        /**
         * 是否支持 {@code auto} 哨兵值（只有时区偏移用 Integer.MIN_VALUE 表示「跟随接口」）。
         *
         * <p><b>按配置键判定，而不是比对 min/max。</b>原先的写法是
         * {@code defaultValue instanceof Integer && min == -1080 && max == 1080}，
         * 这把「支持 auto」这件事<b>绑死在取值范围的字面量上</b> ——
         * 只要有人把范围改成 -720~840（这在需求里看起来完全合理），
         * {@code config set timesync.timezoneOffsetMinutes auto} 就会静默失效。
         * 键名是稳定的，范围不是。</p>
         */
        private boolean isAutoSentinelSupported() {
            return AUTO_SENTINEL_KEYS.contains(key);
        }

        private static boolean isAutoText(String text) {
            return "auto".equalsIgnoreCase(text) || "自动".equals(text) || "跟随".equals(text)
                    || "-".equals(text);
        }
    }

    /** 「自动」哨兵值：时区偏移用 Integer.MIN_VALUE 表示跟随天气接口。 */
    public static boolean isAutoSentinel(int value) {
        return value == Integer.MIN_VALUE;
    }

    /** 支持 {@code auto} 哨兵的配置键（见 {@code Entry#isAutoSentinelSupported()}）。 */
    private static final List<String> AUTO_SENTINEL_KEYS = List.of("timesync.timezoneOffsetMinutes");

    /**
     * 列表/文本的「清空」记号。
     *
     * <p>命令里传不了真正的空字符串（brigadier 的 {@code greedyString} 至少要一个字符，
     * 写 {@code ""} 会原样带两个引号进来），所以必须显式认出这几种写法。</p>
     */
    private static boolean isClearMarker(String text) {
        return text.isEmpty()
                || text.equals("\"\"") || text.equals("''") || text.equals("[]") || text.equals("{}")
                || text.equals("-") || text.equals("空") || text.equals("清空")
                || text.equalsIgnoreCase("none") || text.equalsIgnoreCase("empty")
                || text.equalsIgnoreCase("clear");
    }

    /** 去掉一层成对的引号（玩家从 toml 里复制粘贴时经常带着）。 */
    private static String stripQuotes(String text) {
        String value = text.trim();
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static String format(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        if (raw instanceof Integer i && isAutoSentinel(i)) {
            return "AUTO";
        }
        if (raw instanceof Double d) {
            String text = String.format(Locale.ROOT, "%.4f", d);
            text = text.replaceAll("0+$", "");
            return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        }
        return String.valueOf(raw);
    }

    private static String number(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private static final List<Entry> ALL = build();

    private ConfigEntries() {
    }

    /** 全部配置项（顺序即界面顺序）。 */
    public static List<Entry> all() {
        return ALL;
    }

    /** 某一个分组的全部配置项。 */
    public static List<Entry> of(Section section) {
        List<Entry> list = new ArrayList<>();
        for (Entry entry : ALL) {
            if (entry.section() == section) {
                list.add(entry);
            }
        }
        return list;
    }

    /** 某一个分组、能改的配置项。 */
    public static List<Entry> of(Section section, boolean includeClient) {
        if (includeClient) {
            return of(section);
        }
        List<Entry> list = new ArrayList<>();
        for (Entry entry : of(section)) {
            if (!entry.isClient()) {
                list.add(entry);
            }
        }
        return list;
    }

    /** 服务端能改的键（命令补全用）。 */
    public static List<String> serverKeys() {
        List<String> list = new ArrayList<>();
        for (Entry entry : ALL) {
            if (!entry.isClient()) {
                list.add(entry.key());
            }
        }
        return list;
    }

    /**
     * 按名字找分组：接受三种写法 —— 枚举名（{@code TIME}）、当前语言下的分组名、
     * 以及该分组下配置键的 toml 前缀（{@code timesync}）。
     *
     * <p>为什么要接受第三种：玩家看到的是配置文件里的 {@code [timesync]}，不是枚举名。
     * 实机测试时就踩到了这个 —— 输入 {@code timesync} 被告知「未知分组」，
     * 唯一正确的写法却是 {@code time}。这种不一致纯属自找麻烦。</p>
     *
     * <p><b>歧义时返回 null</b>：如果同一个 toml 前缀出现在两个分组里
     * （例如 {@code shaders} 既在时间同步又在天气映射），就<b>不能</b>按枚举顺序随便挑一个 ——
     * 那会让 {@code config list shaders} 静默漏掉另一组。此时返回 null，让上层报「未知分组」，
     * 总比给出一个不完整的列表强。（当前描述表里所有前缀都是唯一的，
     * 这条兜底是为了防止以后有人再把同前缀的项拆到两个分组里。）</p>
     */
    public static Section byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Section section : Section.values()) {
            if (section.name().equalsIgnoreCase(wanted) || section.zhName().equalsIgnoreCase(wanted)) {
                return section;
            }
        }
        Section found = null;
        for (Section section : Section.values()) {
            for (String prefix : prefixesOf(section)) {
                if (prefix.equalsIgnoreCase(wanted)) {
                    if (found != null && found != section) {
                        return null;
                    }
                    found = section;
                }
            }
        }
        return found;
    }

    /** 某个分组用到的全部 toml 顶层前缀（MAPPING 同时有 general、mapping 与 shaders）。 */
    public static List<String> prefixesOf(Section section) {
        List<String> prefixes = new ArrayList<>();
        for (Entry entry : of(section)) {
            int dot = entry.key().indexOf('.');
            String prefix = dot < 0 ? entry.key() : entry.key().substring(0, dot);
            if (!prefixes.contains(prefix)) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    /** 命令补全用的分组别名（枚举名 + 全部 toml 前缀）。 */
    public static List<String> sectionAliases(boolean includeClient) {
        List<String> names = new ArrayList<>();
        for (Section section : Section.values()) {
            if (!includeClient && section.isClient()) {
                continue;
            }
            names.add(section.name().toLowerCase(Locale.ROOT));
            names.addAll(prefixesOf(section));
        }
        return names;
    }

    /**
     * 按稳定键名查找（大小写不敏感）。
     *
     * <p>也接受省略分组前缀的写法（{@code timezoneOffsetMinutes}），但<b>只在全局唯一时才生效</b>：
     * 例如 {@code enabled} 同时是 {@code general.enabled} / {@code timesync.enabled} /
     * {@code llm.enabled} / {@code hud.enabled} 的短名，此时返回 null，
     * 由上层提示「请写完整键名」—— 而不是默默挑中枚举顺序里的第一条
     * （那会让 {@code config set enabled false} 去改总开关，与玩家的预期完全不符）。</p>
     */
    public static Entry byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (Entry entry : ALL) {
            if (entry.key().equalsIgnoreCase(wanted)) {
                return entry;
            }
        }
        Entry found = null;
        for (Entry entry : ALL) {
            int dot = entry.key().indexOf('.');
            String shortKey = dot < 0 ? entry.key() : entry.key().substring(dot + 1);
            if (shortKey.equalsIgnoreCase(wanted)) {
                if (found != null) {
                    return null; // 短名有歧义：必须写完整键名
                }
                found = entry;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ 描述表
    //
    // 说明：这里只写「分组 + 配置键 + 取值范围」，不再写显示文案 ——
    // 标签与提示由配置键推导成语言键（见 Entry#labelKey/#hintKey），译文在语言文件里。
    //
    // ★ 同一个 toml 顶层前缀只能出现在一个分组里，否则 `config list <前缀>` 会有歧义
    //   （shaders.* 三项因此全部集中在天气映射分组）。

    private static List<Entry> build() {
        ForgeConfig.Common c = ForgeConfig.COMMON;
        ForgeConfig.Client cl = ForgeConfig.CLIENT;
        List<Entry> list = new ArrayList<>();

        // ---- 位置
        add(list, Section.LOCATION, "location.adcode", c.adcode, false);
        add(list, Section.LOCATION, "location.city", c.city, false);
        add(list, Section.LOCATION, "location.coordinates", c.coordinates, false);
        add(list, Section.LOCATION, "location.useIpLocation", c.useIpLocation, false);
        add(list, Section.LOCATION, "location.announceLocationChange", c.announceLocationChange, false);

        // ---- 数据源
        add(list, Section.PROVIDER, "api.providerId", c.providerId, false);
        add(list, Section.PROVIDER, "api.apiKey", c.apiKey, false);
        add(list, Section.PROVIDER, "api.caiyunToken", c.caiyunToken, false);
        add(list, Section.PROVIDER, "api.refreshIntervalSeconds", c.refreshIntervalSeconds, 30, 86_400);
        add(list, Section.PROVIDER, "api.staleAfterSeconds", c.staleAfterSeconds, 60, 604_800);
        add(list, Section.PROVIDER, "api.requestTimeoutMillis", c.requestTimeoutMillis, 1_000, 120_000);
        add(list, Section.PROVIDER, "api.openMeteoGeocoding", c.openMeteoGeocoding, false);
        add(list, Section.PROVIDER, "api.endpoint", c.endpoint, false);
        add(list, Section.PROVIDER, "api.openMeteoEndpoint", c.openMeteoEndpoint, false);

        // ---- 天气映射
        add(list, Section.MAPPING, "general.enabled", c.enabled, false);
        add(list, Section.MAPPING, "mapping.respectBiomeClimate", c.respectBiomeClimate, false);
        add(list, Section.MAPPING, "mapping.rainIntensityThreshold", c.rainIntensityThreshold, 0.0D, 1.0D);
        add(list, Section.MAPPING, "mapping.thunderIntensityThreshold", c.thunderIntensityThreshold, 0.0D, 1.0D);
        add(list, Section.MAPPING, "mapping.minWeatherDurationTicks", c.minWeatherDurationTicks, 100, 240_000);
        add(list, Section.MAPPING, "mapping.maxWeatherDurationTicks", c.maxWeatherDurationTicks, 100, 240_000);
        add(list, Section.MAPPING, "mapping.clearDurationTicks", c.clearDurationTicks, 100, 240_000);
        add(list, Section.MAPPING, "mapping.thunderRequiresPrecipitation", c.thunderRequiresPrecipitation, false);
        add(list, Section.MAPPING, "mapping.leaveNetherAndEndAlone", c.leaveNetherAndEndAlone, false);
        add(list, Section.MAPPING, "mapping.seasonIntegration", c.seasonIntegration, false);
        add(list, Section.MAPPING, "mapping.terrainTransfer", c.terrainTransfer, false);
        add(list, Section.MAPPING, "mapping.vanillaWeatherMaxMinutes", c.vanillaWeatherMaxMinutes, 1, 720);
        add(list, Section.MAPPING, "mapping.metersPerBlock", c.metersPerBlock, 1.0D, 32.0D);
        add(list, Section.MAPPING, "mapping.seasonAmplitude", c.seasonAmplitude, 0.0D, 3.0D);
        add(list, Section.MAPPING, "mapping.offlineFallback", c.offineFallback, false);
        add(list, Section.MAPPING, "mapping.offlineDefaultWeather", c.offlineDefaultWeather, false);
        // shaders.* 三项都定义在 COMMON（服务端可改），必须放在<b>非客户端</b>分组里，
        // 否则 config set / config list / ask 白名单都会把它们当成客户端配置排除掉。
        // 三项同组还保证 `config list shaders` 不会漏项。
        add(list, Section.MAPPING, "shaders.intensitySync", c.shaderIntensitySync, false);
        add(list, Section.MAPPING, "shaders.cloudToRain", c.cloudToRain, false);
        add(list, Section.MAPPING, "shaders.cloudRainScale", c.cloudRainScale, 0.0D, 0.70D);
        // 精调雨 / 雪量。与上面三项同组，保证 `config list shaders` 不漏项。
        add(list, Section.MAPPING, "shaders.rainAmountScale", c.rainAmountScale, 0.0D, 2.0D);
        add(list, Section.MAPPING, "shaders.snowAmountScale", c.snowAmountScale, 0.0D, 2.0D);

        // ---- 时间同步
        add(list, Section.TIME, "timesync.enabled", c.timeSyncEnabled, false);
        add(list, Section.TIME, "timesync.timezoneOffsetMinutes", c.timezoneOffsetMinutes, -1080, 1080);
        add(list, Section.TIME, "timesync.intervalTicks", c.timeSyncIntervalTicks, 1, 24_000);
        add(list, Section.TIME, "timesync.moonPhaseSync", c.moonPhaseSync, false);

        // ---- 预报
        add(list, Section.FORECAST, "forecast.horizon", c.forecastHorizon, 1, 96);
        add(list, Section.FORECAST, "forecast.ticksPerEntry", c.forecastTicksPerEntry, 100, 24_000);
        // ★ 这一项以前只存在于 core（NowWeatherConfig#skyForecastEnabled）与命令提示里，
        //   Forge 侧既没有配置定义也没有桥接，导致「天色预报」永远关不掉。此处补齐。
        add(list, Section.FORECAST, "forecast.skyForecastEnabled", c.skyForecastEnabled, false);
        add(list, Section.FORECAST, "forecast.temperatureToleranceC", c.temperatureToleranceC, 0.0D, 30.0D);
        add(list, Section.FORECAST, "forecast.allowExtremeEvents", c.allowExtremeEvents, false);

        // ---- 同步与兼容
        add(list, Section.SYNC, "sync.syncToClients", c.syncToClients, false);
        add(list, Section.SYNC, "sync.dimensions", c.dimensions, false);
        add(list, Section.SYNC, "sync.fixedBiomeId", c.fixedBiomeId, false);
        add(list, Section.SYNC, "compat.yieldToOtherWeatherMods", c.yieldToOtherWeatherMods, false);
        add(list, Section.SYNC, "compat.weather2Drive", c.weather2Drive, false);
        add(list, Section.SYNC, "compat.yieldToModIds", c.yieldToModIds, false);

        // ---- LLM
        add(list, Section.LLM, "llm.enabled", c.llmEnabled, false);
        add(list, Section.LLM, "llm.apiKey", c.llmApiKey, false);
        add(list, Section.LLM, "llm.endpoint", c.llmEndpoint, false);
        add(list, Section.LLM, "llm.model", c.llmModel, false);
        add(list, Section.LLM, "llm.thinking", c.llmThinking, false);
        add(list, Section.LLM, "llm.maxTokens", c.llmMaxTokens, 64, 8192);
        add(list, Section.LLM, "llm.weatherDaily", c.llmWeatherDaily, false);
        add(list, Section.LLM, "llm.allowConfigEdits", c.llmAllowConfigEdits, false);

        // ---- 调试
        add(list, Section.DEBUG, "debug.logProviderDiagnostics", c.logProviderDiagnostics, false);
        add(list, Section.DEBUG, "debug.announceWeatherChanges", c.announceWeatherChanges, false);

        // ---- 客户端 HUD
        add(list, Section.HUD, "hud.enabled", cl.hudEnabled, true);
        add(list, Section.HUD, "hud.showInChat", cl.showInChat, true);
        add(list, Section.HUD, "hud.showInDebugScreen", cl.showInDebugScreen, true);
        addEnum(list, Section.HUD, "hud.corner", cl.hudCorner);
        add(list, Section.HUD, "hud.offsetX", cl.hudOffsetX, -500, 500);
        add(list, Section.HUD, "hud.offsetY", cl.hudOffsetY, -500, 500);
        add(list, Section.HUD, "hud.background", cl.hudBackground, true);
        add(list, Section.HUD, "hud.showLocation", cl.hudShowLocation, true);
        add(list, Section.HUD, "hud.showTemperature", cl.hudShowTemperature, true);
        add(list, Section.HUD, "hud.showHumidity", cl.hudShowHumidity, true);
        add(list, Section.HUD, "hud.showWind", cl.hudShowWind, true);
        add(list, Section.HUD, "hud.showClimate", cl.hudShowClimate, true);
        add(list, Section.HUD, "hud.showForecast", cl.hudShowForecast, true);
        add(list, Section.HUD, "hud.skyMood", cl.skyMoodEnabled, false);
        add(list, Section.HUD, "hud.forecastLines", cl.hudForecastLines, 0, 12);
        add(list, Section.HUD, "hud.onlyWhenHoldingClock", cl.hudOnlyWhenHoldingClock, true);

        return Collections.unmodifiableList(list);
    }

    /** 布尔 / 字符串 / 列表 / 枚举。 */
    private static void add(List<Entry> list, Section section, String key,
                            ForgeConfigSpec.ConfigValue<?> value, boolean client) {
        Kind kind = value instanceof ForgeConfigSpec.BooleanValue ? Kind.TOGGLE : Kind.TEXT;
        list.add(new Entry(section, key, kind, value, defaultOf(key, client),
                Double.NaN, Double.NaN, false, null));
    }

    private static void add(List<Entry> list, Section section, String key,
                            ForgeConfigSpec.IntValue value, int min, int max) {
        list.add(new Entry(section, key, Kind.NUMBER, value, defaultOf(key, false),
                min, max, true, null));
    }

    /** 整数但用的是无范围校验的 {@code define()}（时区偏移的 AUTO 哨兵）。 */
    private static void add(List<Entry> list, Section section, String key,
                            ForgeConfigSpec.ConfigValue<Integer> value, int min, int max) {
        list.add(new Entry(section, key, Kind.NUMBER, value, defaultOf(key, false),
                min, max, true, null));
    }

    private static void add(List<Entry> list, Section section, String key,
                            ForgeConfigSpec.DoubleValue value, double min, double max) {
        list.add(new Entry(section, key, Kind.NUMBER, value, defaultOf(key, false),
                min, max, false, null));
    }

    private static void addEnum(List<Entry> list, Section section, String key,
                                ForgeConfigSpec.EnumValue<ForgeConfig.HudCorner> value) {
        List<String> names = new ArrayList<>();
        for (ForgeConfig.HudCorner corner : ForgeConfig.HudCorner.values()) {
            names.add(corner.name());
        }
        list.add(new Entry(section, key, Kind.TOGGLE, value, defaultOf(key, true),
                Double.NaN, Double.NaN, false, Collections.unmodifiableList(names)));
    }

    /**
     * 从「影子 spec」按 toml 路径取默认值（缓存于 {@code Entry}）。
     *
     * <p>影子 spec 没有挂文件，所以 {@code ConfigValue#get()} 一定返回 defaultSupplier ——
     * 这正是我们要的默认值。</p>
     */
    private static Object defaultOf(String key, boolean client) {
        try {
            return ForgeConfig.defaultValue(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

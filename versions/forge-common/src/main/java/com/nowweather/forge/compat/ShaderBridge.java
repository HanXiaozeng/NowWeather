package com.nowweather.forge.compat;

import com.nowweather.core.text.Text;
import com.nowweather.forge.NowWeatherLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 光影（Iris / OptiFine）探测。
 *
 * <h2>为什么是「探测」而不是「注入」</h2>
 * 光影是<b>着色器程序</b>，模组没法直接改它的 uniform。查过 Iris 官方文档
 * （Reference → Shaders.Properties → Custom Uniforms）后确认：
 * {@code shaders.properties} 里的自定义 uniform 只能由<b>内置 uniform</b>
 * （以及其他自定义 uniform）、字面量和运算符组合而成，<b>Iris 没有提供让第三方模组
 * 写入 uniform 的 API</b>。官方列出的「Mod Support」也只有固定几个模组。
 *
 * <p>因此本模组的联动方式只有一条，但它是真正有效的：
 * <b>驱动原版世界状态</b>。Iris / OptiFine 给光影的天气类 uniform 是
 * {@code rainStrength} / {@code wetness} / {@code thunderStrength}，
 * 来源都是 {@code Level#getRainLevel} / {@code getThunderLevel}；
 * 时间类的是 {@code worldTime} / {@code worldDay} / {@code moonPhase} / {@code sunAngle}。
 * 这些我们全都已经在连续地写（见
 * {@link com.nowweather.forge.platform.ForgePlatformAccess#setWeatherIntensity}、
 * {@code WeatherController#applyTimeSync} 与月相同步）。</p>
 *
 * <p>本类只负责「告诉玩家现在是什么光影、我们喂了什么值」，不做任何注入尝试 ——
 * 探测失败也绝不影响游戏。</p>
 */
public final class ShaderBridge {

    private static volatile boolean probed;
    private static volatile String loader = Text.tr("nowweather.compat.shader.loader_none");
    private static volatile String packName = Text.tr("nowweather.compat.shader.pack_none");
    private static volatile boolean packActive;
    private static volatile String note = "";

    private ShaderBridge() {
    }

    /**
     * 「没有启用光影包」的占位文案。
     *
     * <p>它是一个会被相互比较的值（见 {@link #probeOptiFine()}），所以赋值与比较
     * <b>必须取同一个来源</b> —— 写死成字面量的话，一旦文案被翻译，比较就会永远为假。</p>
     */
    private static String packNone() {
        return Text.tr("nowweather.compat.shader.pack_none");
    }

    /** 光影加载器名称：{@code OptiFine} / {@code Iris} / 「未检测到」（按当前语言）。 */
    public static String loader() {
        probe();
        return loader;
    }

    /** 当前启用的光影包名；未启用时为「（无）」（按当前语言，见 {@link #packNone()}）。 */
    public static String packName() {
        probe();
        return packName;
    }

    /** 当前是否真的在用光影（决定「我们喂的值有没有意义」）。 */
    public static boolean packActive() {
        probe();
        return packActive;
    }

    /** 探测过程中的补充说明（失败原因等），供诊断显示。 */
    public static String note() {
        probe();
        return note;
    }

    /** 光影包切换后重新探测（{@code /nowweather shaders} 与配置重载时调用）。 */
    public static void invalidate() {
        probed = false;
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        loader = Text.tr("nowweather.compat.shader.loader_none");
        packName = packNone();
        packActive = false;
        note = "";

        // Iris 优先：它的 API 是公开且稳定的（IrisApi.getInstance()）
        if (probeIris()) {
            return;
        }
        if (probeOptiFine()) {
            return;
        }
    }

    /** Iris（含 Oculus 等分支）：{@code IrisApi.getInstance().isShaderPackInUse()}。 */
    private static boolean probeIris() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object inUse = apiClass.getMethod("isShaderPackInUse").invoke(api);
            loader = "Iris";
            packActive = Boolean.TRUE.equals(inUse);
            // Iris 的 getConfig() 不保证存在，包名取不到就留空，不影响判断
            try {
                Object holder = apiClass.getMethod("getConfig").invoke(api);
                if (holder != null) {
                    Object name = holder.getClass().getMethod("getShaderPackName").invoke(holder);
                    if (name instanceof String text && !text.isBlank()) {
                        packName = text;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                packName = packActive
                        ? Text.tr("nowweather.compat.shader.pack_name_unavailable") : packNone();
            }
            if (!packActive) {
                packName = packNone();
            }
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            loader = Text.tr("nowweather.compat.shader.iris_probe_failed");
            note = e.getClass().getSimpleName() + ": " + e.getMessage();
            NowWeatherLog.LOGGER.debug("Iris 探测失败: {}", note);
            return true;
        }
    }

    /**
     * OptiFine。
     *
     * <h2>为什么不能只看一个字段</h2>
     * 一开始这里只读 {@code Shaders.shaderPackLoaded}，读不到就把 {@code packActive} 留成 false ——
     * 结果日志里出现了「未检测到光影包」，而玩家其实装着 OptiFine 也开着光影包，
     * 白白把排查方向带偏。现在按「字段 → OptiFine 自己的 Config」依次尝试，
     * 并把每一步的结果记进 {@code note}，探测失败也能在 {@code /nowweather shaders} 里看见。
     */
    private static boolean probeOptiFine() {
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            loader = "OptiFine";
            StringBuilder how = new StringBuilder();

            boolean loaded = readStaticBoolean(shaders, "shaderPackLoaded");
            how.append(loaded ? "Shaders.shaderPackLoaded=true"
                    : Text.tr("nowweather.compat.shader.field_missing"));
            if (!loaded) {
                boolean viaConfig = probeOptiFineConfig();
                how.append(viaConfig
                        ? "；Config.isShaders()=true"
                        : "；" + Text.tr("nowweather.compat.shader.config_missing"));
                loaded = viaConfig;
            }
            packActive = loaded;

            Object name = readStaticField(shaders, "shaderPackName");
            if (name instanceof String text && !text.isBlank()) {
                packName = text;
            }
            if (!packActive) {
                packName = packNone();
            } else if (packNone().equals(packName) || packName.isBlank()) {
                packName = optifineConfiguredPack();
            }
            note = how.toString();
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            // 这里只可能是 RuntimeException：ClassNotFoundException 已被上一个 catch 接走，
            // 而 try 块里其它调用（readStaticBoolean / readStaticField / probeOptiFineConfig）
            // 都在内部各自处理了反射异常，不会向外抛受检异常。
            // 以前这里写的是 catch (ReflectiveOperationException | RuntimeException e)，
            // 编译器会报「catch 子句无法访问」（ReflectiveOperationException 那一半不可达）。
            loader = Text.tr("nowweather.compat.shader.optifine_probe_failed");
            note = e.getClass().getSimpleName() + ": " + e.getMessage();
            NowWeatherLog.LOGGER.debug("OptiFine 探测失败: {}", note);
            return true;
        }
    }

    /** 读静态布尔字段；字段不存在或不是布尔时返回 false（绝不抛）。 */
    private static boolean readStaticBoolean(Class<?> type, String field) {
        try {
            Field f = type.getField(field);
            return f.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 读静态字段并返回其值；失败返回 null。 */
    private static Object readStaticField(Class<?> type, String field) {
        try {
            return type.getField(field).get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 退一步问 OptiFine 自己的配置：{@code net.optifine.Config.isShaders()}。 */
    private static boolean probeOptiFineConfig() {
        try {
            Class<?> config = Class.forName("net.optifine.Config");
            Object value = config.getMethod("isShaders").invoke(null);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 从 OptiFine 自己的 Shaders 配置里读「当前选中的光影包名」。 */
    private static String optifineConfiguredPack() {
        try {
            Class<?> config = Class.forName("net.optifine.Config");
            Method getter = config.getMethod("getShaders");
            Object shaders = getter.invoke(null);
            if (shaders != null) {
                Method nameGetter = shaders.getClass().getMethod("getShaderPackName");
                Object value = nameGetter.invoke(shaders);
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 探测不到就返回未知，绝不抛
        }
        return Text.tr("nowweather.compat.shader.pack_unknown_name");
    }
}

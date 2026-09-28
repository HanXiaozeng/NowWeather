package com.nowweather.forge.compat;

import com.nowweather.core.log.Loggers;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 与 <b>Weather2 / Weather, Storms &amp; Tornadoes</b>（Corosus）的联动桥。
 *
 * <h2>为什么是反射，而不是编译期依赖</h2>
 * <p>勘查结论（对着真实 jar 用 {@code javap} 逐条核对过）：</p>
 * <ul>
 *   <li>它 <b>没有公开 API 包</b> —— 全部相关类都在内部包
 *       {@code weather2.weathersystem}、{@code weather2.weathersystem.storm} 下；</li>
 *   <li>它的 modId 是 {@code weather2}，<b>强制依赖 {@code coroutil}</b>
 *       （{@code versionRange="[1.18.2-1.2.45,)"}）；</li>
 *   <li>1.18.2 的它<b>只发布在 CurseForge</b>，Modrinth 上该项目的版本是 1.20.1/1.21+
 *       —— 所以没有可自动获取的构建来源。</li>
 * </ul>
 * <p>因此这里不写编译期依赖，改用反射调用它的<b>真实公开方法</b>：</p>
 * <pre>
 * weather2.ServerTickHandler.getWeatherManagerFor(ResourceKey&lt;Level&gt;) → WeatherManagerServer
 * weather2.weathersystem.WeatherManager.getStormObjects()                 → List&lt;WeatherObject&gt;
 * weather2.weathersystem.storm.WeatherObject.isDead (public 字段)
 * weather2.weathersystem.storm.WeatherObject.pos    (public Vec3 字段)
 * weather2.weathersystem.storm.WeatherObject.getSize() → int
 * weather2.ServerWeatherProxy.getWindSpeed(ServerLevel) → float
 * </pre>
 *
 * <h2>已经能做到什么</h2>
 * <p>把它的风暴<b>读出来</b>用于本模组的天气计算与展示：最近的龙卷/风暴、类型、尺寸、
 * 距离，以及它算出的风场。因为默认策略是<b>让权</b>（{@code compat.yieldToOtherWeatherMods}），
 * 我们不改写它的世界天气；但可以据此在自己的 HUD / 预报 / LLM 上下文里如实反映
 * 「附近正有一个风暴」。</p>
 *
 * <p>所有调用都包在 try/catch 里，任何一步失败都只是「这一项不可用」，
 * 绝不影响主流程 —— 外部模组版本不同不该把我们的天气系统拖垮。</p>
 */
public final class Weather2Bridge {

    private static final SyncLogger LOGGER = Loggers.console(SyncLogger.Level.WARN);

    public static final String MOD_ID = "weather2";
    public static final String ALT_MOD_ID = "weatherstormsandtornadoes";

    /** 一个可读到的风暴快照（只含我们真正会用的字段）。 */
    public record StormInfo(double x, double y, double z, int size, boolean dead, String typeName) {

        /** 到某点的水平距离（格）。 */
        public double horizontalDistanceTo(double px, double pz) {
            double dx = x - px;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** 粗略的强度 0~1：尺寸 200 格以上视为极端。 */
        public double severity() {
            if (dead || size <= 0) {
                return 0.0D;
            }
            return Math.min(1.0D, size / 200.0D);
        }
    }

    private static volatile boolean probed;
    /** 探测状态。尚未探测时的占位文案按当前语言取（探测一定会覆盖它）。 */
    private static volatile String status = Text.tr("nowweather.compat.weather2.not_probed");
    private static volatile Method getManagerFor;
    private static volatile Method getStormObjects;
    private static volatile Method getSize;
    private static volatile Method getWindSpeed;
    private static volatile Field isDeadField;
    private static volatile Field posField;
    private static volatile boolean available;

    private Weather2Bridge() {
    }

    /** 人类可读的探测状态（给 {@code /nowweather weather2} 用）。 */
    public static String describe() {
        if (!probed) {
            probe();
        }
        return status;
    }

    /** 是否成功接上（能读到风暴列表）。 */
    public static boolean available() {
        if (!probed) {
            probe();
        }
        return available;
    }

    /** 读取某个维度的全部风暴。读不到就返回空列表，永不抛异常。 */
    public static List<StormInfo> storms(ServerLevel level) {
        if (!probed) {
            probe();
        }
        List<StormInfo> result = new ArrayList<>();
        if (!available || level == null || getManagerFor == null) {
            return result;
        }
        try {
            Object manager = getManagerFor.invoke(null, level.dimension());
            if (manager == null) {
                return result;
            }
            Object raw = getStormObjects.invoke(manager);
            if (!(raw instanceof List<?> list)) {
                return result;
            }
            for (Object storm : list) {
                if (storm == null) {
                    continue;
                }
                boolean dead = isDeadField != null && Boolean.TRUE.equals(isDeadField.get(storm));
                Vec3 pos = posField == null ? null : (Vec3) posField.get(storm);
                if (pos == null) {
                    continue;
                }
                int size = 0;
                if (getSize != null) {
                    Object s = getSize.invoke(storm);
                    if (s instanceof Number number) {
                        size = number.intValue();
                    }
                }
                result.add(new StormInfo(pos.x, pos.y, pos.z, size, dead,
                        storm.getClass().getSimpleName()));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 一次失败不代表永久失败：不清 available，也不抛
            LOGGER.debug("读取 Weather2 风暴失败：{}", e.toString());
        }
        return result;
    }

    /** Weather2 自己算出的风速（格/秒量级）。取不到返回 NaN。 */
    public static double windSpeed(ServerLevel level) {
        if (!probed) {
            probe();
        }
        if (getWindSpeed == null || level == null) {
            return Double.NaN;
        }
        try {
            Object value = getWindSpeed.invoke(null, level);
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }

    /** 距离给定位置最近的风暴（没有则 null）。 */
    public static StormInfo nearestStorm(ServerLevel level, BlockPos pos) {
        StormInfo best = null;
        double bestDistance = Double.MAX_VALUE;
        for (StormInfo storm : storms(level)) {
            if (storm.dead()) {
                continue;
            }
            double distance = storm.horizontalDistanceTo(pos.getX(), pos.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = storm;
            }
        }
        return best;
    }

    /** 给状态命令/LLM 上下文用的一句话摘要。 */
    public static String summary(ServerLevel level, BlockPos pos) {
        if (!available()) {
            return "";
        }
        List<StormInfo> all = storms(level);
        if (all.isEmpty()) {
            return Text.tr("nowweather.compat.weather2.no_storms");
        }
        StormInfo nearest = nearestStorm(level, pos);
        StringBuilder sb = new StringBuilder(Text.tr("nowweather.compat.weather2.count", all.size()));
        if (nearest != null) {
            // 数值先按 Locale.ROOT 格式化好再交给语言键：译文里不带 %f，
            // 免得数字格式跟着玩家系统的区域设置跑（德语环境会变成 120,5）。
            sb.append(Text.tr("nowweather.compat.weather2.nearest",
                    nearest.typeName(),
                    String.format(java.util.Locale.ROOT, "%.0f",
                            nearest.horizontalDistanceTo(pos.getX(), pos.getZ())),
                    nearest.size(),
                    String.format(java.util.Locale.ROOT, "%.0f", nearest.severity() * 100.0D)));
        }
        return sb.toString();
    }

    /** 探测一次：按 javap 核对过的真实签名逐个取用。 */
    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> handler = Class.forName("weather2.ServerTickHandler",
                    false, Weather2Bridge.class.getClassLoader());
            getManagerFor = handler.getMethod("getWeatherManagerFor", ResourceKey.class);

            Class<?> manager = Class.forName("weather2.weathersystem.WeatherManager",
                    false, Weather2Bridge.class.getClassLoader());
            getStormObjects = manager.getMethod("getStormObjects");

            Class<?> stormObject = Class.forName("weather2.weathersystem.storm.WeatherObject",
                    false, Weather2Bridge.class.getClassLoader());
            isDeadField = stormObject.getField("isDead");
            posField = stormObject.getField("pos");
            getSize = stormObject.getMethod("getSize");

            try {
                Class<?> proxy = Class.forName("weather2.ServerWeatherProxy",
                        false, Weather2Bridge.class.getClassLoader());
                getWindSpeed = proxy.getMethod("getWindSpeed", ServerLevel.class);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                getWindSpeed = null;      // 可选能力
            }
            available = true;
            status = Text.tr("nowweather.compat.weather2.connected",
                    getWindSpeed != null
                            ? Text.tr("nowweather.compat.weather2.wind_ok")
                            : Text.tr("nowweather.compat.weather2.wind_missing"));
            LOGGER.info("Weather2 桥接已接通：{}", status);
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            available = false;
            status = Text.tr("nowweather.compat.weather2.not_connected",
                    e.getClass().getSimpleName(), e.getMessage());
            LOGGER.info("Weather2 桥接未接通：{}", status);
        }
    }
}

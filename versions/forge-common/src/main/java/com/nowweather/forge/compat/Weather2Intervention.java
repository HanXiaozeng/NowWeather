package com.nowweather.forge.compat;

import com.nowweather.core.log.Loggers;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 由 NowWeather <b>主动干预</b> Weather2 的风暴生成。
 *
 * <h2>与「让权」的关系（重要）</h2>
 * <p>默认策略是让权：检测到 Weather2 在管天气时，我们只提供数据、不写世界天气。
 * 但那是「我们不去抢」；本模块是反过来 —— <b>用真实/推算出来的天气去指挥它造风暴</b>。
 * 因此它是一个独立开关（{@code compat.weather2Drive}，默认开），
 * 打开时对本模组而言 Weather2 从「对手」变成「渲染器」：
 * 天气该不该来、来什么类型，由我们的数据决定；它负责把风暴画出来。</p>
 *
 * <h2>为什么要这么做</h2>
 * <p>Weather2 自己按玩家与随机数生成风暴，跟真实天气毫无关系。
 * 而 NowWeather 手里有：真实天气、台风路径与风场（Rankine + 移动不对称）、
 * 群系气候、地形订正后的海拔与暴露度、季节。用这些去决定「这里此刻该不该有沙尘暴/雪暴/龙卷」
 * 显然比随机合理得多。</p>
 *
 * <h2>调用的真实 API（对着 1.18.2-2.7.5 的 jar 用 javap 核对）</h2>
 * <pre>
 * weather2.ServerTickHandler.getWeatherManagerFor(ResourceKey&lt;Level&gt;) → WeatherManagerServer
 * WeatherManagerServer.trySpawnParticleStormNearPos(Level, Vec3, StormType) → boolean
 * WeatherObjectParticleStorm$StormType = { SANDSTORM, SNOWSTORM }
 * WeatherObjectParticleStorm.canSpawnHere(Level, BlockPos, StormType, boolean) → boolean
 * </pre>
 * <p>注意 {@code trySpawnParticleStormNearPos} <b>接受显式坐标</b>，
 * 所以即使服务器上没有玩家也能生成 —— 这点对真机验证很关键。</p>
 */
public final class Weather2Intervention {

    private static final SyncLogger LOGGER = Loggers.console(SyncLogger.Level.WARN);

    /** 两次主动生成之间的最短间隔（毫秒）：避免刷屏式造风暴。 */
    public static final long SPAWN_COOLDOWN_MILLIS = 5L * 60L * 1000L;

    /** 判定「该有沙尘暴」的湿度上限。 */
    public static final double SANDSTORM_MAX_HUMIDITY = 0.35D;

    /** 判定「该有沙尘暴」的风级下限。 */
    public static final int SANDSTORM_MIN_WIND_SCALE = 6;

    /** 判定「该有雪暴」的风级下限。 */
    public static final int SNOWSTORM_MIN_WIND_SCALE = 5;

    private static volatile boolean probed;
    private static volatile boolean canDrive;
    /** 探测状态。尚未探测时的占位文案按当前语言取（探测一定会覆盖它）。 */
    private static volatile String status = Text.tr("nowweather.compat.weather2_intervention.not_probed");
    private static volatile Method getManagerFor;
    private static volatile Method spawnParticleStorm;
    private static volatile Method canSpawnHere;
    private static volatile Class<?> stormTypeClass;
    private static volatile long lastSpawnAt;
    private static volatile String lastSpawnNote = "";

    private Weather2Intervention() {
    }

    public static boolean canDrive() {
        probe();
        return canDrive;
    }

    public static String describe() {
        probe();
        return status;
    }

    /** 最近一次主动生成的说明（诊断用）。 */
    public static String lastSpawnNote() {
        return lastSpawnNote;
    }

    /**
     * 根据当前真实/推算天气，决定是否要在该位置催生一个风暴。
     *
     * <p>判据（全部来自本模组已有的量，不额外请求任何接口）：</p>
     * <ul>
     *   <li><b>沙尘暴</b>：群系干旱（湿度 &lt; {@link #SANDSTORM_MAX_HUMIDITY}）
     *       且风级 ≥ {@link #SANDSTORM_MIN_WIND_SCALE} —— 这是真实沙尘暴的两个必要条件；</li>
     *   <li><b>雪暴</b>：气温 &lt; 0°C、正在降水（雪）且风级 ≥ {@link #SNOWSTORM_MIN_WIND_SCALE}；</li>
     *   <li>其余情况（雷暴、台风）交给 Weather2 自己的 StormObject 体系，
     *       我们只用 {@code /nowweather weather2} 把风场读出来展示。</li>
     * </ul>
     *
     * @return 本次是否真的催生了风暴
     */
    public static boolean maybeSpawn(ServerLevel level, BlockPos pos, WeatherObservation observation,
                                     boolean climateCanPrecipitate, boolean dryClimate) {
        if (!probe() || !canDrive() || level == null || pos == null || observation == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSpawnAt < SPAWN_COOLDOWN_MILLIS) {
            return false;
        }
        double humidity01 = Math.max(0.0D, Math.min(1.0D, observation.humidityPercent() / 100.0D));
        int windScale = observation.windScale();
        double temperature = observation.temperatureC();
        WeatherType type = observation.weatherType();

        String requested = null;
        if (dryClimate && humidity01 < SANDSTORM_MAX_HUMIDITY && windScale >= SANDSTORM_MIN_WIND_SCALE) {
            requested = "SANDSTORM";
        } else if (temperature < 0.0D && type != null && type.isPrecipitation()
                && windScale >= SNOWSTORM_MIN_WIND_SCALE) {
            requested = "SNOWSTORM";
        }
        if (requested == null) {
            return false;
        }
        boolean spawned = spawn(level, pos, requested);
        lastSpawnAt = now;
        lastSpawnNote = Text.tr("nowweather.compat.weather2_intervention.spawn_note",
                spawned ? Text.tr("nowweather.compat.weather2_intervention.action_spawned")
                        : Text.tr("nowweather.compat.weather2_intervention.action_attempted"),
                pos.getX(), pos.getY(), pos.getZ(), requested,
                String.format(Locale.ROOT, "%.1f", temperature),
                String.format(Locale.ROOT, "%.0f", humidity01 * 100.0D),
                windScale,
                spawned ? Text.tr("nowweather.compat.weather2_intervention.result_ok")
                        : Text.tr("nowweather.compat.weather2_intervention.result_rejected"));
        LOGGER.info("[Weather2 干预] {}", lastSpawnNote);
        return spawned;
    }

    /** 直接尝试在指定位置生成一个粒子风暴（供命令与测试用）。 */
    public static boolean spawn(ServerLevel level, BlockPos pos, String typeName) {
        if (!probe() || !canDrive()) {
            return false;
        }
        try {
            Object manager = getManagerFor.invoke(null, level.dimension());
            if (manager == null) {
                return false;
            }
            Object stormType = Enum.valueOf(stormTypeClass.asSubclass(Enum.class), typeName);
            // 先问它「这里能不能生成」，被拒绝就不硬来
            if (canSpawnHere != null) {
                Object allowed = canSpawnHere.invoke(null, level, pos, stormType, true);
                if (Boolean.FALSE.equals(allowed)) {
                    lastSpawnNote = Text.tr(
                            "nowweather.compat.weather2_intervention.can_spawn_rejected", typeName);
                    LOGGER.info("[Weather2 干预] {}", lastSpawnNote);
                    return false;
                }
            }
            Vec3 center = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            Object result = spawnParticleStorm.invoke(manager, level, center, stormType);
            boolean ok = Boolean.TRUE.equals(result);
            // 成功时也要更新说明，否则界面/命令上会一直显示上一次的失败原因（实测踩到过）
            lastSpawnNote = Text.tr("nowweather.compat.weather2_intervention.spawn_result",
                    typeName, pos.getX(), pos.getY(), pos.getZ(),
                    ok ? Text.tr("nowweather.compat.weather2_intervention.generated")
                            : Text.tr("nowweather.compat.weather2_intervention.returned_false"));
            return ok;
        } catch (ReflectiveOperationException | RuntimeException e) {
            lastSpawnNote = Text.tr("nowweather.compat.weather2_intervention.spawn_failed",
                    e.getClass().getSimpleName(), e.getMessage());
            LOGGER.warn("[Weather2 干预] {}", lastSpawnNote);
            return false;
        }
    }

    private static synchronized boolean probe() {
        if (probed) {
            return canDrive;
        }
        probed = true;
        ClassLoader loader = Weather2Intervention.class.getClassLoader();
        try {
            Class<?> handler = Class.forName("weather2.ServerTickHandler", false, loader);
            getManagerFor = handler.getMethod("getWeatherManagerFor", net.minecraft.resources.ResourceKey.class);

            Class<?> managerServer = Class.forName("weather2.weathersystem.WeatherManagerServer", false, loader);
            stormTypeClass = Class.forName("weather2.weathersystem.storm.WeatherObjectParticleStorm$StormType",
                    false, loader);
            spawnParticleStorm = managerServer.getMethod("trySpawnParticleStormNearPos",
                    net.minecraft.world.level.Level.class, Vec3.class, stormTypeClass);
            try {
                Class<?> particleStorm = Class.forName(
                        "weather2.weathersystem.storm.WeatherObjectParticleStorm", false, loader);
                canSpawnHere = particleStorm.getMethod("canSpawnHere", net.minecraft.world.level.Level.class,
                        BlockPos.class, stormTypeClass, boolean.class);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                canSpawnHere = null;      // 可选：没有也能生成，只是少了预检
            }
            canDrive = true;
            status = Text.tr("nowweather.compat.weather2_intervention.ready");
            LOGGER.info("Weather2 干预已就绪：{}", status);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            canDrive = false;
            status = Text.tr("nowweather.compat.weather2_intervention.unavailable",
                    e.getClass().getSimpleName(), e.getMessage());
            LOGGER.info("Weather2 干预不可用：{}", status);
        }
        return canDrive;
    }
}

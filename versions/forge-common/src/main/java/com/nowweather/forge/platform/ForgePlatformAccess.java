package com.nowweather.forge.platform;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.api.PlatformWeatherAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

/**
 * Forge 侧的天气读写实现。
 *
 * <p>1.18.x 的原版天气接口是
 * {@code ServerLevel#setWeatherParameters(int clearTime, int rainTime, boolean raining, boolean thundering)}，
 * 其中：</p>
 * <ul>
 *   <li>{@code clearTime} —— 还要晴多久（刻），只有在 {@code raining=false} 时才有意义；</li>
 *   <li>{@code rainTime} —— 这场雨还要下多久（刻）；</li>
 *   <li>{@code raining} / {@code thundering} —— 是否下雨 / 是否打雷。</li>
 * </ul>
 *
 * <p>注意：原版只有「晴 / 雨 / 雷暴」三档，而且天气是<b>维度级</b>的。
 * 更细腻的天气（雾、沙尘、暴风雪……）目前体现在 HUD、预报与决策依据上，
 * 要真正落地成视觉效果需要联动扩展天气模组（见 {@code WeatherBinding}）。</p>
 */
public final class ForgePlatformAccess implements PlatformWeatherAccess {

    private final MinecraftServer server;
    /** doWeatherCycle=false 的提示只打一次，避免刷屏。 */
    private volatile boolean frozenWeatherWarned;

    public ForgePlatformAccess(MinecraftServer server) {
        this.server = server;
    }

    private ServerLevel level(String dimensionId) {
        if (server == null) {
            return null;
        }
        return server.getLevel(ForgeBiomeFactsFactory.dimensionKey(dimensionId));
    }

    @Override
    public PlatformWeather currentWeather(String dimensionId) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return PlatformWeather.CLEAR;
        }
        // ★ 必须读「原始天气标志」（LevelData），不能读 level.isRaining() / level.isThundering()：
        //   后两者读的是**插值后的渲染等级**（字节码：getRainLevel(1.0F) > 0.2），
        //   刚设置完天气时要过十几刻才越过阈值 —— 用它判断会让控制器误以为「天气没变」，
        //   从而每个冷却周期都重设一次天气（原版会因此不断重置天气计时器）。
        LevelData data = level.getLevelData();
        if (data.isThundering()) {
            return PlatformWeather.THUNDER;
        }
        return data.isRaining() ? PlatformWeather.RAIN : PlatformWeather.CLEAR;
    }

    @Override
    public boolean applyWeather(String dimensionId, PlatformWeather target, int durationTicks,
                               int clearDurationTicks) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return false;
        }
        // 尊重服主：doWeatherCycle=false 表示「不要让天气自己变」，此时我们不插手
        try {
            if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)) {
                if (!frozenWeatherWarned) {
                    frozenWeatherWarned = true;
                    com.nowweather.forge.NowWeatherLog.LOGGER.info(
                            "维度 {} 的 doWeatherCycle=false（服主冻结了天气）：NowWeather 只提供数据，不修改天气",
                            dimensionId);
                }
                return false;
            }
        } catch (RuntimeException ignored) {
            // 取不到游戏规则就照常处理
        }

        int clear = Math.max(1, clearDurationTicks);
        int rain = Math.max(1, durationTicks);
        try {
            switch (target) {
                case CLEAR -> level.setWeatherParameters(clear, 0, false, false);
                case RAIN -> level.setWeatherParameters(0, rain, true, false);
                case THUNDER -> level.setWeatherParameters(0, rain, true, true);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long rainTimeRemaining(String dimensionId) {
        ServerLevel level = level(dimensionId);
        if (level == null || !(level.getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData data)) {
            return -1L;
        }
        return data.getRainTime();
    }

    @Override
    public long clearTimeRemaining(String dimensionId) {
        ServerLevel level = level(dimensionId);
        if (level == null || !(level.getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData data)) {
            return -1L;
        }
        return data.getClearWeatherTime();
    }

    @Override
    public long totalDayTime(String dimensionId) {
        ServerLevel level = level(dimensionId);
        return level == null ? -1L : level.getDayTime();
    }

    @Override
    public boolean setDayTime(String dimensionId, long dayTime) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return false;
        }
        try {
            // 1.18.1 / 1.18.2 都直接有 ServerLevel#setDayTime(long)，不需要 mixin
            level.setDayTime(dayTime);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 把<b>连续的</b>雨/雷强度写进世界 —— 这是光影（Iris / OptiFine）天气表现的总闸门。
     *
     * <p>为什么要同时写 {@code oRainLevel}：Iris 的 {@code rainStrength} 取的是
     * {@code Level#getRainLevel(partialTick)}，而这个方法返回的是
     * {@code lerp(partial, oRainLevel, rainLevel)} —— 也就是「上一刻」和「此刻」的插值。
     * 只写 {@code rainLevel} 的话，原版每刻还会把 {@code oRainLevel} 设成旧值并让
     * {@code rainLevel} 朝 0/1 走，于是雨量会在我们写的值附近来回抖。
     * 两个都写成同一个值，插值结果就等于我们想要的值，且原版没有余地把它拉走。</p>
     *
     * <p><b>为什么必须挡掉没有天空光的维度</b>：原版 {@code ServerLevel#advanceWeatherCycle()}
     * 的整个推进分支是被 {@code if (dimensionType().hasSkyLight())} 包住的 —— 在下界/末地
     * 它<b>根本不会</b>把 {@code rainLevel} 拉回 0。所以一旦写进去，这个值就<b>永久留在那里</b>；
     * 而 {@code Level#isRaining()} 恰恰是 {@code getRainLevel(1.0F) > 0.2}，<b>没有</b>天空光守卫
     * （只有 {@code isThundering()} 有），于是下界的 {@code isRaining()} 会变成 true，
     * 影响到所有按「是否下雨」判定的玩法（耕地、炼药锅、怪物生成…）。
     * 这与「下界 / 末地 / 虚空完全保持原版」的承诺直接冲突 —— 所以这里和客户端侧
     * {@code ShaderSkyBridge} 用同一口径直接拒绝写入。</p>
     */
    @Override
    public boolean setWeatherIntensity(String dimensionId, float rain, float thunder) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return false;
        }
        if (!level.dimensionType().hasSkyLight()) {
            return false;
        }
        try {
            float r = Math.max(0.0F, Math.min(1.0F, rain));
            float t = Math.max(0.0F, Math.min(1.0F, thunder));
            level.rainLevel = r;
            level.oRainLevel = r;
            level.thunderLevel = t;
            level.oThunderLevel = t;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long totalWorldTime(String dimensionId) {
        ServerLevel level = level(dimensionId);
        return level == null ? 0L : level.getGameTime();
    }

    @Override
    public long dayTime(String dimensionId) {
        ServerLevel level = level(dimensionId);
        return level == null ? 0L : level.dayTime();
    }

    @Override
    public long worldSeed() {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        return overworld == null ? 0L : overworld.getSeed();
    }

    @Override
    public String biomeIdAt(String dimensionId, double x, double y, double z) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return "";
        }
        var facts = ForgeBiomeFactsFactory.biomeAt(level,
                new net.minecraft.core.BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        return facts == null ? "" : facts.id();
    }
}

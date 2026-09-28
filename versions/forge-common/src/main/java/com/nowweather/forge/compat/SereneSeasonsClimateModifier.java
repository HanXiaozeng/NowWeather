package com.nowweather.forge.compat;

import com.nowweather.core.api.ModPresence;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.ClimateModifier;
import com.nowweather.core.climate.SeasonalTemperatureCurve;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;
import com.nowweather.forge.NowWeatherLog;
import com.nowweather.forge.platform.ForgeBiomeFactsFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * <b>Serene Seasons（季节模组）联动</b> —— 按当前季节平移各生物群系的「正常温度范围」。
 *
 * <h2>为什么是这个方向</h2>
 * 季节模组管的是「季节」，我们管的是「天气」。两者交汇的正确位置是
 * <b>生物群系的正常温度范围</b>：把它按季节上下平移，天气白名单（从温度范围推导）就自动跟着变 ——
 * 温带群系冬天自动允许下雪、夏天自动禁止下雪，不需要两套规则互相打架。
 *
 * <h2>为什么用反射</h2>
 * Serene Seasons 是<b>可选</b>依赖。为了不引入编译期依赖（也避免 9.x 换 API 就把我们编崩），
 * 这里只用反射调用它公开的 API —— API 签名是对着真实 jar 用 {@code javap} 核对过的：
 *
 * <pre>
 * sereneseasons.api.season.SeasonHelper
 *   public static ISeasonState getSeasonState(net.minecraft.world.level.Level)
 * sereneseasons.api.season.ISeasonState
 *   Season getSeason();            // 枚举 SPRING/SUMMER/AUTUMN/WINTER（声明顺序 0..3）
 *   int getSeasonDuration();
 *   int getSeasonCycleTicks();
 * </pre>
 *
 * <p>模组不在场、或 API 变了，这个修正器就安静地失效（返回原气候），绝不抛异常。
 * 它同时是 {@code ClimateModifier} 扩展点的一个真实范例：其它模组可以照这个写法做自己的联动。</p>
 */
public final class SereneSeasonsClimateModifier implements ClimateModifier {

    public static final String SERENE_SEASONS_MOD_ID = "sereneseasons";

    private static final String SEASON_HELPER_CLASS = "sereneseasons.api.season.SeasonHelper";
    private static final String SEASON_STATE_CLASS = "sereneseasons.api.season.ISeasonState";

    private final ModPresence presence;
    private final double amplitude;
    private final double narrowFactor;

    private Method getSeasonState;
    private Method getSeason;
    private Method getSeasonDuration;
    private Method getSeasonCycleTicks;
    private boolean reflectionReady;
    private String unavailableReason = "";

    /** 用于日志去重：同一个季节只打一次日志。 */
    private volatile String lastLoggedSeason = "";

    private SereneSeasonsClimateModifier(ModPresence presence, double amplitude, double narrowFactor) {
        this.presence = presence;
        this.amplitude = MathUtil.clamp(amplitude, 0.0D, 3.0D);
        this.narrowFactor = narrowFactor;
    }

    /**
     * 尝试创建修正器。
     *
     * @param presence      模组存在性探测
     * @param amplitude     季节幅度倍率（1.0 = 默认，0 = 只做温度范围收窄不做季节平移）
     * @return 可用时返回实例；Serene Seasons 不在场或 API 不匹配时返回 null
     */
    public static SereneSeasonsClimateModifier createIfAvailable(ModPresence presence, double amplitude) {
        if (presence == null || !presence.isLoaded(SERENE_SEASONS_MOD_ID)) {
            return null;
        }
        SereneSeasonsClimateModifier modifier =
                new SereneSeasonsClimateModifier(presence, amplitude, SeasonalTemperatureCurve.DEFAULT_NARROW_FACTOR);
        return modifier.prepareReflection() ? modifier : null;
    }

    /** 解析 Serene Seasons 的 API；失败时记录原因并返回 false。 */
    private boolean prepareReflection() {
        try {
            Class<?> helper = Class.forName(SEASON_HELPER_CLASS);
            Class<?> state = Class.forName(SEASON_STATE_CLASS);
            getSeasonState = helper.getMethod("getSeasonState", Level.class);
            getSeason = state.getMethod("getSeason");
            getSeasonDuration = state.getMethod("getSeasonDuration");
            getSeasonCycleTicks = state.getMethod("getSeasonCycleTicks");
            reflectionReady = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionReady = false;
            unavailableReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            NowWeatherLog.LOGGER.warn("检测到 Serene Seasons，但没能解析它的季节 API（季节联动将不生效）：{}",
                    unavailableReason);
            return false;
        }
    }

    @Override
    public String id() {
        return "sereneseasons:seasonal";
    }

    @Override
    public int priority() {
        // 排在其它修正器之前：季节是最基础的「大前提」
        return 100;
    }

    @Override
    public boolean isActive() {
        return reflectionReady && presence.isLoaded(SERENE_SEASONS_MOD_ID);
    }

    @Override
    public BiomeClimate modify(BiomeFacts facts, BiomeClimate base) {
        if (base == null || !isActive()) {
            return base;
        }
        // 下界 / 末地 / 虚空没有天气系统，季节也不该给它们任何偏移
        if (base.climateClass().isWeatherFree()) {
            return base;
        }
        try {
            Level level = levelOf(facts);
            if (level == null) {
                return base;
            }
            Object state = getSeasonState.invoke(null, level);
            if (state == null) {
                return base;
            }
            Enum<?> season = (Enum<?>) getSeason.invoke(state);
            int seasonDuration = (int) getSeasonDuration.invoke(state);
            int cycleTicks = (int) getSeasonCycleTicks.invoke(state);
            if (season == null || seasonDuration <= 0) {
                return base;
            }
            double progress = MathUtil.clamp((double) cycleTicks / seasonDuration, 0.0D, 1.0D);
            double offset = SeasonalTemperatureCurve.offsetFor(season.ordinal(), progress,
                    scaledAnchors()) ;

            String seasonName = season.name();
            if (!seasonName.equals(lastLoggedSeason)) {
                lastLoggedSeason = seasonName;
                NowWeatherLog.LOGGER.info("Serene Seasons 联动：当前季节 {}（进度 {}%），"
                                + "各生物群系的正常温度范围整体平移 {}{}°C",
                        seasonName, Math.round(progress * 100.0D), offset >= 0 ? "+" : "",
                        String.format(Locale.ROOT, "%.1f", offset));
            }
            String source = String.format(Locale.ROOT, "sereneseasons:%s(%.1f%%)", seasonName,
                    progress * 100.0D);
            return SeasonalTemperatureCurve.apply(base, offset, narrowFactor, source);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 联动失败绝不能影响主流程
            NowWeatherLog.LOGGER.warn("Serene Seasons 季节联动出错（本次忽略）：{}", e.toString());
            return base;
        }
    }

    private double[] scaledAnchors() {
        if (Math.abs(amplitude - 1.0D) < 1.0E-6) {
            return SeasonalTemperatureCurve.DEFAULT_ANCHORS;
        }
        double[] base = SeasonalTemperatureCurve.DEFAULT_ANCHORS;
        double[] scaled = new double[base.length];
        for (int i = 0; i < base.length; i++) {
            scaled[i] = base[i] * amplitude;
        }
        return scaled;
    }

    private Level levelOf(BiomeFacts facts) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = server.getLevel(ForgeBiomeFactsFactory.dimensionKey(facts.dimension()));
        return level != null ? level : server.overworld();
    }

    /** 供 {@code /nowweather compat} 展示的状态描述。 */
    public String describe() {
        if (!presence.isLoaded(SERENE_SEASONS_MOD_ID)) {
            return Text.tr("nowweather.compat.serene_seasons.not_installed");
        }
        if (!reflectionReady) {
            return Text.tr("nowweather.compat.serene_seasons.api_failed", unavailableReason);
        }
        String season = lastLoggedSeason.isEmpty()
                ? Text.tr("nowweather.compat.serene_seasons.season_unknown") : lastLoggedSeason;
        return Text.tr("nowweather.compat.serene_seasons.active", season,
                String.format(Locale.ROOT, "%.2f", amplitude));
    }
}

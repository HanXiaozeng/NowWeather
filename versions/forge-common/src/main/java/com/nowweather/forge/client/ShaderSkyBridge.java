package com.nowweather.forge.client;

import com.nowweather.core.sync.SyncPayload;
import com.nowweather.core.weather.SkyMood;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.forge.NowWeatherLog;
import com.nowweather.forge.config.ForgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;

/**
 * 阴天仿真 —— 让「阴」在 Minecraft 里真的看得见。
 *
 * <h2>问题</h2>
 * 原版天气只有<b>晴 / 雨 / 雷</b>三个状态，根本没有「阴」。所以真实天气是阴天时，
 * 策略只能映射成「晴朗」，游戏里就是个大晴天 —— 和现实完全对不上。
 *
 * <p>在光影下更麻烦：Iris / OptiFine 给光影的天气输入只有
 * {@code rainStrength} / {@code wetness} / {@code thunderStrength}，
 * 而光影的<b>云量是从 rainStrength 推出来的</b>。阴天没有降水 → 原版 {@code rainLevel = 0}
 * → 光影认为万里无云 → 照样画一个大太阳。</p>
 *
 * <h2>做法</h2>
 * 唯一的杠杆是 {@code rainLevel}：它既能压暗世界（原版 {@code LightTexture} 的暗层 +
 * 天空变灰 + 原版云变厚），又能通过 {@code rainStrength} 让光影长出云来。
 * 但原版只要 {@code getRainLevel > 0} 就会画雨柱 —— 所以必须同时<b>接管原版天气渲染</b>，
 * 用 Forge 公开的 {@link DimensionSpecialEffects#setWeatherRenderHandler} 装一个空实现。
 *
 * <h3>三种情况</h3>
 * <ul>
 *   <li><b>真降水（雨 / 雪 / 雷）</b>：rainLevel = 真实降水强度；<b>不接管</b>原版雨渲染 ——
 *       原版照常画雨，不会把真雨吃掉；</li>
 *   <li><b>阴 / 多云 / 雾霾 / 沙尘</b>：rainLevel = 真实云量 × 倍率（压暗世界、天空变灰）；
 *       <b>接管</b>原版雨渲染 —— 否则阴天会莫名其妙下起雨；</li>
 *   <li><b>晴</b>：rainLevel = 0；接管与否都无所谓。</li>
 * </ul>
 *
 * <p>★ 这套逻辑<b>不依赖光影探测</b>：不开光影时它就是「阴天仿真」，开了光影时同一个
 * {@code rainStrength} 又正好被光影拿去长云。之前那版要求先探测到光影包才生效，
 * 一旦 OptiFine 的反射探测失败整个功能就消失了 —— 那正是「光影下阴天不明显」的原因。</p>
 *
 * <p>所有副作用都可还原：把 handler 设回 {@code null}，原版天气渲染立刻恢复。</p>
 */
public final class ShaderSkyBridge {

    /** 原版天气渲染当前是否被我们接管。 */
    private static boolean weatherSuppressed;
    /** 上一次写入的 rainLevel，避免每帧重复写。 */
    private static float lastWrittenStrength = Float.NaN;
    /** 帧计数：用来周期性重新探测光影，中途开/关光影也能生效。 */
    private static int frameCounter;
    /** 上一次日志里报告过的接管状态，避免每帧刷屏。 */
    private static Boolean lastLogged;

    private ShaderSkyBridge() {
    }

    /** 原版天气渲染当前是否被我们接管（诊断用：接管时原版不画雨）。 */
    public static boolean isWeatherSuppressed() {
        return weatherSuppressed;
    }

    /** 上一次写入客户端的 rainLevel；NaN 表示尚未写过。 */
    public static float lastRainStrength() {
        return lastWrittenStrength;
    }

    /**
     * 每帧调用（渲染线程）。
     *
     * @param payload 服务端同步过来的快照；为 null 时按「不下雨」处理
     */
    public static void tick(Minecraft minecraft, SyncPayload payload) {
        try {
            ClientLevel level = minecraft.level;
            if (level == null) {
                return;
            }
            // ★ 下界 / 末地不参与：模组的总原则是「下界末地保持原版」。
            // 那里没有天空，天气渲染本来也不该有；但如果我们照样把 rainLevel 写进去，
            // 光影读到的 rainStrength 就会>0 —— 表现为「在下界里看到云和雨」。
            // 用「该维度是否有天空光照」判断，比硬编码维度 id 更稳（数据包可以自定义维度）。
            if (!level.dimensionType().hasSkyLight()) {
                if (weatherSuppressed) {
                    setWeatherSuppressed(level, false);
                }
                lastWrittenStrength = Float.NaN;
                return;
            }
            if (!ForgeConfig.COMMON.cloudToRain.get()) {
                // 关掉开关时必须还原，不能留下副作用
                if (weatherSuppressed) {
                    setWeatherSuppressed(level, false);
                }
                lastWrittenStrength = Float.NaN;
                return;
            }

            // 每 100 帧重新探测一次光影开关状态：玩家中途开光影也能生效，
            // 而不是因为探测结果被永久缓存而一直不生效。
            if (++frameCounter % 100 == 0) {
                com.nowweather.forge.compat.ShaderBridge.invalidate();
            }
            WeatherObservation observation = payload == null ? null : payload.observation();
            WeatherType type = observation == null ? null : observation.weatherType();
            if (type == null) {
                // ★ 没有任何观测 = 我们什么都不知道，那就必须完全交还给原版：
                //   既不写 rainLevel、也不接管原版雨渲染。
                //
                //   这里原来把「没有数据」当成了「没有降水」，于是两件事同时发生：
                //   接管原版天气渲染 + 把 rainLevel 写成 0（{@code SkyMood.shaderRainStrength}
                //   在 type == null 时返回 0）。后果是：天气还没同步、或取数失败的那段时间里，
                //   原版明明在下雨，玩家看到的却是「雨停了」—— 与「面板说在下雨、游戏里一滴都没有」
                //   属于同一类事故，只是触发条件换成「数据还没到」。
                //
                //   交还之后，原版的雨重新由服务端广播驱动；lastWrittenStrength 置回 NaN，
                //   下次拿到观测时会重新写一遍（不会被差值判断跳过去）。
                if (weatherSuppressed) {
                    setWeatherSuppressed(level, false);
                }
                lastWrittenStrength = Float.NaN;
                return;
            }

            // 原版自己有没有在下雨 —— 作为兜底，但<b>只能在我们还没写过任何 rainLevel 时采信</b>。
            // rainLevel 是我们每帧写的，写过之后 {@code isRaining()} 读回来的是我们自己写进去的
            // 云量值（阴天压暗时常常 > 0.2），无条件 OR 进来会把「阴天抑制」整个抵消掉 ——
            // 那正是本类要解决的问题，不能反过来被它破坏。
            float levelRain = level.getRainLevel(1.0F);
            boolean vanillaRaining = Float.isNaN(lastWrittenStrength)
                    && level.isRaining() && levelRain > 0.0F;
            boolean precipitating = type.isPrecipitation() || vanillaRaining;

            // ★ 只在「当前不下雨，但我们仍要写 rainLevel 压暗天色」时才接管原版天气渲染。
            //
            // 曾经这里还 OR 了「检测到光影包」——那是错的：OptiFine 即便 shaderPack=OFF，
            // shaderPackLoaded 也可能为真，于是原版的雨柱被我们永久吞掉，
            // 表现就是「面板说在下雨，游戏里一滴都没有」。
            // 光影探测只是个加强项，绝不能让它决定「要不要把雨吃掉」。
            boolean needSuppress = !precipitating;
            if (needSuppress != weatherSuppressed) {
                setWeatherSuppressed(level, needSuppress);
            }

            double cloud = payload == null ? Double.NaN : payload.realCloudCover01();
            // 降水时这个值至少等于真实降水强度（shaderRainStrength 内部保证），
            // 所以「说在下雨就一定有 rainLevel」，不依赖服务端的广播是否及时。
            //
            // 雨 / 雪各有自己的主观倍率（精调「下得多大」）：
            // 冻结形态的降水走 snowAmountScale，其余走 rainAmountScale。
            // 倍率只作用在降水区间内部，编码值恒 ≥ 0.75，不会掉进缓冲带。
            double amountScale = type.isFrozen()
                    ? ForgeConfig.COMMON.snowAmountScale.get()
                    : ForgeConfig.COMMON.rainAmountScale.get();
            float strength = SkyMood.shaderRainStrength(cloud, type,
                    ForgeConfig.COMMON.cloudRainScale.get(), amountScale);

            // ★★ 必须「每帧无条件」写入，不能只在数值变化时才写。
            //
            // 这是整个阴天仿真失效的根因：原版 {@code ClientLevel.tickWeather()} 每刻都在跑
            //   {@code oRainLevel = rainLevel;  rainLevel ±= 0.01;}
            // 也就是不降雨时以 0.01/刻（约 0.2/秒）把 rainLevel 拉回 0。
            // 于是「只在值变了才写」等于「写一次就被原版抹掉」：
            // 写进去 0.35 → 一秒后回到 0 → 光影的 rainStrength 归零 → nwCloudCover()=0
            // → 云彩消失、天色恢复全亮。玩家看到的就是「天气根本没同步、天空亮得刺眼」，
            // 而只有服务端真的改天气（例如 /nowweather test all）时才短暂正常。
            //
            // setRainLevel 是纯字段赋值（同时写 rainLevel / oRainLevel，并 clamp 0~1），
            // 每帧调用没有任何开销，也不发网络包。
            level.setRainLevel(strength);
            lastWrittenStrength = strength;
        } catch (RuntimeException e) {
            // 天色 / 光影联动绝不能让渲染崩掉
            NowWeatherLog.LOGGER.debug("阴天仿真写入失败: {}", e.getMessage());
        }
    }

    /** 装上 / 撤掉原版天气渲染的接管。 */
    private static void setWeatherSuppressed(ClientLevel level, boolean suppressed) {
        try {
            DimensionSpecialEffects effects = DimensionSpecialEffects.forType(level.dimensionType());
            if (suppressed) {
                // 空实现 = 原版不画雨柱、不画雨粒子。
                // 真降水时不会走到这里（needSuppress=false），所以不会把真雨吃掉。
                effects.setWeatherRenderHandler((ticks, partialTick, lvl, mc, lightTexture, x, y, z) -> {
                    // 故意什么都不做
                });
                effects.setWeatherParticleRenderHandler((ticks, lvl, mc, camera) -> {
                    // 故意什么都不做
                });
                if (!Boolean.TRUE.equals(lastLogged)) {
                    lastLogged = Boolean.TRUE;
                    NowWeatherLog.LOGGER.info("阴天仿真：当前无降水，已用真实云量压暗天色"
                                    + "（同时接管原版雨渲染，避免阴天却画雨）{}",
                            com.nowweather.forge.compat.ShaderBridge.packActive()
                                    ? "；光影包「" + com.nowweather.forge.compat.ShaderBridge.packName()
                                            + "」会拿同一个 rainStrength 去长云"
                                    : "；" + com.nowweather.forge.compat.ShaderBridge.loader()
                                            + " " + com.nowweather.forge.compat.ShaderBridge.packName()
                                            + "（未启用光影包，仅做原版阴天仿真）");
                }
            } else {
                effects.setWeatherRenderHandler(null);
                effects.setWeatherParticleRenderHandler(null);
                if (!Boolean.FALSE.equals(lastLogged)) {
                    lastLogged = Boolean.FALSE;
                    NowWeatherLog.LOGGER.info("当前为降水天气：已还原原版天气渲染，雨雪照常显示");
                }
            }
            weatherSuppressed = suppressed;
        } catch (RuntimeException e) {
            // 装不上就算了，最多是多画点雨，绝不影响游戏
            NowWeatherLog.LOGGER.warn("接管原版天气渲染失败（不影响游戏）: {}", e.getMessage());
        }
    }
}

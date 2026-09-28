package com.nowweather.forge.client;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.WeatherAllowance;
import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.forecast.ForecastEntry;
import com.nowweather.core.sync.SyncPayload;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.forge.config.ForgeConfig;
import com.nowweather.forge.net.RequestNowWeatherPacket;
import com.nowweather.forge.net.NowWeatherNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 客户端 HUD：把真实天气、本地气候与预报画在游戏界面上。
 *
 * <p>数据全部来自服务端同步（见 {@link ClientWeatherState}），所以在多人游戏里
 * 所有玩家看到的是同一份真实天气，而不是各自按自己的网络位置查到的不同天气。</p>
 */
public final class NowWeatherClientEvents {

    private static long lastRequestTick = -10_000L;

    private NowWeatherClientEvents() {
    }

    /** 客户端 tick：数据过期时主动向服务端要一份。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // ★ 必须先判 phase。
        // ClientTickEvent 每刻会发两次（START 与 END），原来把音频钩子写在 phase 判断之前，
        // 等于每刻白跑两遍、每次多分配一个 Optional。这里放到 END 之后只跑一次。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        publishClimateToAudioMods();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        long tick = minecraft.level.getGameTime();
        if (tick - lastRequestTick < 200L || !ClientWeatherState.isStale()) {
            return;
        }
        lastRequestTick = tick;
        try {
            NowWeatherNetwork.CHANNEL.sendToServer(new RequestNowWeatherPacket());
        } catch (RuntimeException ignored) {
            // 单人游戏或未连接时不发
        }
    }

    /** 渲染 HUD。 */
    /**
     * 把「真实时间」写进客户端世界时间。
     *
     * <p>为什么必须在客户端做：原版客户端每刻都会自行把 {@code dayTime} +1（20 分钟一天），
     * 而真实时间同步下一天是 24 小时（1 刻 = 3.6 秒）。只靠服务端每秒一次的校正包，
     * 客户端会在两次校正之间被原版日光循环带跑约 14 刻，然后被硬拉回去 ——
     * 玩家看到的就是「天空每隔几秒往前挪一下又闪回右边」，天色/亮度也跟着反复重算（看起来像闪烁）。</p>
     *
     * <p>这里改为用服务端下发的锚点（真实毫秒 + 当时的世界刻）自行外推，每帧写入，
     * 于是客户端时间始终与真实时间一致，没有漂移也就没有被拉回。参考做法见 TimeSync（Fabric）。</p>
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        SyncPayload payload = ClientWeatherState.payload();
        Minecraft minecraft = Minecraft.getInstance();
        if (payload == null || minecraft.level == null) {
            return;
        }
        // 光影的云量：把真实云量翻译成 rainStrength（没有光影时内部直接返回）。
        // 放在时间同步之前 —— 它不依赖时间锚点，关掉「真实时间同步」的人也要能有天色。
        ShaderSkyBridge.tick(minecraft, payload);

        SyncPayload.TimeAnchor anchor = payload.timeAnchor();
        if (anchor == null) {
            return;
        }
        try {
            long target = anchor.dayTimeAt(System.currentTimeMillis());
            if (minecraft.level.getDayTime() != target) {
                minecraft.level.setDayTime(target);
            }
        } catch (RuntimeException ignored) {
            // 时间同步绝不能影响渲染
        }
    }

    // ------------------------------------------------------------------ 天色（现实天气 → 雾色 / 雾距）

    /**
     * 当前天色。由服务端同步过来的实况天气推算，没有数据时保持原版。
     *
     * <p>用 Forge 的公开事件（{@code EntityViewRenderEvent.FogColors / RenderFogEvent}）实现，
     * <b>不需要 Mixin</b> —— 这点很重要：OptiFine 与 Iris 都会改写
     * {@code FogRenderer} / {@code LevelRenderer}，往 MC 类里 Mixin 在装了光影的环境里
     * 极易冲突，而事件是 Forge 保证的稳定接口。</p>
     */
    private static com.nowweather.core.weather.SkyMood currentSkyMood() {
        if (!ForgeConfig.CLIENT.skyMoodEnabled.get()) {
            return com.nowweather.core.weather.SkyMood.NEUTRAL;
        }
        var observation = ClientWeatherState.observation();
        if (observation.isEmpty() || observation.get().stale()) {
            return com.nowweather.core.weather.SkyMood.NEUTRAL;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return com.nowweather.core.weather.SkyMood.NEUTRAL;
        }
        double sunHeight = com.nowweather.core.time.RealTimeSyncModel.sunHeight(
                minecraft.level.getDayTime() % 24000L);
        // ★ 有真实预报就用真实云量：天气类型只能给出「多云=0.70」这种常量，
        // 而真实云量会从 30% 飘到 95%，天色才有「云压过来」的过程感。
        double realCloud = Double.NaN;
        var payload = ClientWeatherState.payload();
        if (payload != null) {
            realCloud = payload.realCloudCover01();
        }
        return com.nowweather.core.weather.SkyMood.of(
                observation.get().weatherType(), sunHeight, realCloud);
    }

    /** 天色只做「偏移」：以原版算好的雾色为基底，叠加天气造成的差异。 */
    @SubscribeEvent
    public static void onFogColors(net.minecraftforge.client.event.EntityViewRenderEvent.FogColors event) {
        try {
            com.nowweather.core.weather.SkyMood mood = currentSkyMood();
            if (mood.isNeutral()) {
                return;
            }
            float[] rgb = mood.applyTo(event.getRed(), event.getGreen(), event.getBlue());
            event.setRed(rgb[0]);
            event.setGreen(rgb[1]);
            event.setBlue(rgb[2]);
        } catch (RuntimeException ignored) {
            // 天色绝不能让渲染崩掉
        }
    }

    /**
     * 雾距：降水 / 雾天拉近，晴天推远。
     *
     * <h2>★ 千万不要用 FogDensity 事件</h2>
     * 这里原来用的是 {@code EntityViewRenderEvent.FogDensity} 并且无条件 {@code setCanceled(true)}，
     * 结果阴天整个屏幕灰蒙蒙、完全看不见方块。原因是 {@code FogRenderer.setupFog} 的语义是：
     * <pre>
     *   float density = ForgeHooksClient.getFogDensity(...);   // 返回 &lt; 0 才走正常路径
     *   if (density &gt;= 0) { fogStart = -8.0F; fogEnd = density * 0.5F; }   // 完全绕开正常雾距
     * </pre>
     * 也就是说「取消这个事件」等于把返回的数值当成<b>绝对雾距</b>用 ——
     * 而我们传进去的是 0.1 量级的密度常数，于是 fogEnd ≈ 0.05，整屏被雾填满。
     *
     * <p>{@link net.minecraftforge.client.event.EntityViewRenderEvent.RenderFogEvent}
     * 才是对的东西：它给的是 {@code near/far plane distance}，并且提供
     * {@code scaleFarPlaneDistance} / {@code scaleNearPlaneDistance} 这种<b>乘性</b>接口，
     * 语义清晰、不可能把距离算成 0。</p>
     *
     * <p>只处理 {@code FOG_TERRAIN}：水下 / 岩浆 / 细雪这些雾是有玩法意义的，不动它们。</p>
     */
    @SubscribeEvent
    public static void onRenderFog(net.minecraftforge.client.event.EntityViewRenderEvent.RenderFogEvent event) {
        try {
            if (event.getMode() != net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) {
                return;
            }
            com.nowweather.core.weather.SkyMood mood = currentSkyMood();
            if (mood.isNeutral()) {
                return;
            }
            float factor = (float) mood.fogDensityFactor();
            if (Math.abs(factor - 1.0F) < 1.0E-3F) {
                return;
            }
            ensureFogScaleProbed(event.getClass());
            // factor < 1 表示雾更浓 -> 可见距离变近，所以是「乘」而不是「除」。
            // 用反射调用：1.18.1 的 Forge 里 RenderFogEvent 只有 getFarPlaneDistance()，
            // 完全没有 setter / scale 接口，直接写会编译不过；1.18.2 才有。
            scaleFogDistance(fogScaleFarMethod, event, factor, "scaleFarPlaneDistance");
            scaleFogDistance(fogScaleNearMethod, event, factor, "scaleNearPlaneDistance");
        } catch (RuntimeException ignored) {
            // 天色绝不能让渲染崩掉
        }
    }

    /** 雾距缩放的反射句柄；{@code null} 表示「这一版 Forge 没有这个接口」。 */
    private static java.lang.reflect.Method fogScaleFarMethod;
    private static java.lang.reflect.Method fogScaleNearMethod;
    private static boolean fogScaleProbed;

    /**
     * 只探测一次，并把「这一版根本不支持」这件事**说清楚**。
     *
     * <p>以前这里是每帧静默 try/catch：1.18.1 上雾距缩放<b>整体不生效</b>，
     * 却没有任何日志、界面上也看不出区别 —— 玩家只会觉得「天气雾效好像没做」。
     * 现在改成一次性探测 + 一次性说明，顺便把反射句柄缓存下来，不再每帧查方法。</p>
     */
    private static void ensureFogScaleProbed(Class<?> eventClass) {
        if (fogScaleProbed) {
            return;
        }
        fogScaleProbed = true;
        fogScaleFarMethod = findFogScaler(eventClass, "scaleFarPlaneDistance");
        fogScaleNearMethod = findFogScaler(eventClass, "scaleNearPlaneDistance");
        if (fogScaleFarMethod == null || fogScaleNearMethod == null) {
            com.nowweather.forge.NowWeatherLog.LOGGER.info(
                    "当前 Forge 版本的 RenderFogEvent 没有雾距缩放接口"
                            + "（1.18.1 只有 getFarPlaneDistance），"
                            + "因此本版本上「按真实能见度收拢雾距」不会生效（1.18.2 起才有 "
                            + "scaleFarPlaneDistance / scaleNearPlaneDistance）。天色明暗不受影响。");
        }
    }

    private static java.lang.reflect.Method findFogScaler(Class<?> eventClass, String name) {
        try {
            return eventClass.getMethod(name, float.class);
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /** 反射调用雾距缩放。{@code method} 为 {@code null} 表示这一版不支持，已在探测时说明过。 */
    private static void scaleFogDistance(java.lang.reflect.Method method, Object event,
                                         float factor, String name) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(event, factor);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 调用失败同样要留痕，否则又是一个查不出来的静默失效
            com.nowweather.forge.NowWeatherLog.LOGGER.warn("调用 {} 失败：{}", name, e.toString());
        }
    }

    /**
     * 把当前气候发布给 Sound Physics 的挂钩点（{@link com.nowweather.forge.compat.SoundPhysicsClimateHook}）。
     *
     * <p>只在有真实天气数据时发布；没有数据就恢复成 1.0 倍率，保证「没数据时听感与它原本完全一致」。</p>
     */
    private static void publishClimateToAudioMods() {
        var observation = com.nowweather.forge.client.ClientWeatherState.observation();
        if (observation.isEmpty()) {
            com.nowweather.forge.compat.SoundPhysicsClimateHook.reset();
            return;
        }
        var o = observation.get();
        var location = com.nowweather.forge.compat.SoundModBridge.listenerPos();
        double elevation = 0.0D;
        // 客户端拿不到服务端算好的海拔，就用 0（= 不做海拔项），避免编造数据
        com.nowweather.forge.compat.SoundPhysicsClimateHook.setFactors(
                o.temperatureC(),
                Math.max(0.0D, Math.min(1.0D, o.humidityPercent() / 100.0D)),
                elevation, 0.5D,
                o.weatherType().isPrecipitation());
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (!isEnabled(minecraft)) {
            return;
        }
        try {
            render(minecraft, event.getMatrixStack());
        } catch (RuntimeException e) {
            // HUD 出错绝不能影响游戏渲染
        }
    }

    private static boolean isEnabled(Minecraft minecraft) {
        try {
            if (!ForgeConfig.CLIENT.hudEnabled.get()) {
                return false;
            }
            if (ForgeConfig.CLIENT.hudOnlyWhenHoldingClock.get()) {
                return minecraft.player.getMainHandItem().is(net.minecraft.world.item.Items.CLOCK)
                        || minecraft.player.getOffhandItem().is(net.minecraft.world.item.Items.CLOCK);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void render(Minecraft minecraft, com.mojang.blaze3d.vertex.PoseStack pose) {
        List<Component> lines = buildLines();
        if (lines.isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        int lineHeight = 10;
        int maxWidth = 0;
        for (Component line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }
        int totalHeight = lines.size() * lineHeight;

        boolean right = ForgeConfig.CLIENT.hudCorner.get() == ForgeConfig.HudCorner.TOP_RIGHT
                || ForgeConfig.CLIENT.hudCorner.get() == ForgeConfig.HudCorner.BOTTOM_RIGHT;
        boolean bottom = ForgeConfig.CLIENT.hudCorner.get() == ForgeConfig.HudCorner.BOTTOM_LEFT
                || ForgeConfig.CLIENT.hudCorner.get() == ForgeConfig.HudCorner.BOTTOM_RIGHT;

        int offsetX = ForgeConfig.CLIENT.hudOffsetX.get();
        int offsetY = ForgeConfig.CLIENT.hudOffsetY.get();
        int x = right ? minecraft.getWindow().getGuiScaledWidth() - maxWidth - 4 - offsetX : 4 + offsetX;
        int y = bottom ? minecraft.getWindow().getGuiScaledHeight() - totalHeight - 4 - offsetY : 4 + offsetY;

        if (ForgeConfig.CLIENT.hudBackground.get()) {
            net.minecraft.client.gui.GuiComponent.fill(pose, x - 2, y - 2, x + maxWidth + 2,
                    y + totalHeight + 1, 0x66000000);
        }
        int color = 0xFFFFFF;
        for (Component line : lines) {
            font.drawShadow(pose, line, x, y, color);
            y += lineHeight;
        }
    }

    /** 组装 HUD 文本。 */
    private static List<Component> buildLines() {
        List<Component> lines = new ArrayList<>();
        SyncPayload payload = ClientWeatherState.payload();
        WeatherObservation observation = payload == null ? null : payload.observation();

        if (observation == null) {
            lines.add(new TextComponent("§bNowWeather §7" + Text.tr("nowweather.hud.waiting_sync")));
            return lines;
        }

        String condition = observation.conditionText().isEmpty()
                ? Text.tr("nowweather.weather." + Text.segment(observation.weatherType()))
                : observation.conditionText();
        StringBuilder head = new StringBuilder();
        head.append("§b").append(condition);
        // ★ 气温也要受 hud.showTemperature 控制：原来它被无条件拼进来，
        // 于是「显示气温」这个开关从来没生效过（位置/湿度/风/气候/预报都有 show(...) 判定）。
        if (show(() -> ForgeConfig.CLIENT.hudShowTemperature.get()) && observation.hasTemperature()) {
            head.append(String.format(Locale.ROOT, " §f%.1f°C", observation.temperatureC()));
        }
        if (payload.climateAdjusted()) {
            head.append(" §7(").append(Text.tr("nowweather.hud.climate_adjusted")).append(")");
        }
        if (observation.stale()) {
            head.append(" §8(").append(Text.tr("nowweather.hud.stale")).append(")");
        }
        lines.add(new TextComponent(head.toString()));

        if (show(() -> ForgeConfig.CLIENT.hudShowLocation.get()) && !observation.city().isEmpty()) {
            lines.add(new TextComponent("§7" + Text.tr("nowweather.hud.location")
                    + "§f" + observation.locationDisplay()));
        }
        if (show(() -> ForgeConfig.CLIENT.hudShowHumidity.get()) && observation.humidityPercent() >= 0.0D) {
            lines.add(new TextComponent("§7" + Text.tr("nowweather.hud.humidity") + "§f"
                    + String.format(Locale.ROOT, "%.0f%%", observation.humidityPercent())));
        }
        if (show(() -> ForgeConfig.CLIENT.hudShowWind.get())) {
            // 风向取枚举、风力取风级：两者都走语言键，HUD 才不会在中英环境下混着显示。
            StringBuilder wind = new StringBuilder("§7");
            wind.append(Text.tr("nowweather.hud.wind")).append("§f");
            wind.append(Text.tr("nowweather.wind_direction." + Text.segment(observation.windDirection())));
            if (observation.windScale() >= 0) {
                wind.append(' ').append(Text.tr("nowweather.hud.wind_scale", observation.windScale()));
            }
            lines.add(new TextComponent(wind.toString()));
        }

        BiomeClimate climate = payload.climate();
        if (climate != null && show(() -> ForgeConfig.CLIENT.hudShowClimate.get())) {
            lines.add(new TextComponent("§7" + Text.tr("nowweather.hud.climate") + "§f"
                    + Text.tr("nowweather.climate_class." + Text.segment(climate.climateClass()))
                    + " §7" + Text.tr("nowweather.hud.climate_normal") + " "
                    + climate.tempRange().describe()));
            lines.add(new TextComponent("§7" + Text.tr("nowweather.hud.possible_weather") + "§f"
                    + WeatherAllowance.describePlatform(climate.possiblePlatformWeather())));
        }

        Forecast forecast = payload.forecast();
        if (forecast != null && !forecast.isEmpty() && show(() -> ForgeConfig.CLIENT.hudShowForecast.get())) {
            int limit;
            try {
                limit = Math.max(0, ForgeConfig.CLIENT.hudForecastLines.get());
            } catch (RuntimeException e) {
                limit = 3;
            }
            for (int i = 0; i < Math.min(limit, forecast.size()); i++) {
                ForecastEntry entry = forecast.entries().get(i);
                lines.add(new TextComponent("§7" + Text.tr("nowweather.hud.forecast") + "§f"
                        + entry.clockDisplay() + " "
                        + Text.tr("nowweather.weather." + Text.segment(entry.weatherType()))
                        + String.format(Locale.ROOT, " %.0f°C", entry.temperatureC())));
            }
        }
        return lines;
    }

    /** 读配置时容错：配置尚未加载时按「显示」处理，避免 HUD 整个消失。 */
    private static boolean show(java.util.function.BooleanSupplier accessor) {
        try {
            return accessor.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
    }
}

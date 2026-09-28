package com.nowweather.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nowweather.core.NowWeatherCore;
import com.nowweather.core.text.Text;
import com.nowweather.forge.config.ForgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NowWeather 的运行信息面板。
 *
 * <h2>显示位置</h2>
 * <p>主入口是<b>打开聊天框（默认按 T）时在屏幕左上角显示</b>（{@link #onChatScreenRender}）——
 * 这是玩家想看信息时最自然的一个动作，不需要记 F3，也不会在正常游玩时挡住视野。</p>
 * <p>F3 那一份改为可选且<b>默认关闭</b>（{@code showInDebugScreen}）：
 * 原版 F3 右栏本身已经很挤，塞进去容易与其他模组/原版的行重叠。</p>
 *
 * <h2>显示什么</h2>
 * <p>只放排查问题时最需要的字段：真实天气与是否过期、群系气候与正常温度范围、
 * 预报首格、已落地天气与是否被气候修正、决策依据、LLM 与音频模组开关。
 * 内容由 {@link #buildLines()} 生成 —— 做成纯函数是为了能对「行数与内容」单独验证，
 * 而不是只能靠人眼看着。</p>
 */
public final class NowWeatherDebugOverlay {

    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND = 0x90000000;

    private NowWeatherDebugOverlay() {
    }

    // ------------------------------------------------------------------ 聊天框面板（主入口）

    /**
     * 打开聊天框时渲染信息面板。
     *
     * <p>用 {@link ScreenEvent.DrawScreenEvent.Post} 而不是 overlay 事件：这样它跟着聊天界面的生命周期走，
     * 关掉聊天框就自动消失，也不必自己判断「现在到底是不是聊天界面」。</p>
     */
    @SubscribeEvent
    public static void onChatScreenRender(ScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }
        if (!ForgeConfig.CLIENT.showInChat.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        int y = MARGIN_Y;
        for (Line line : buildLines()) {
            int width = minecraft.font.width(line.text());
            // 半透明底：保证在任何背景（天空/地形/水底）上都读得清
            net.minecraft.client.gui.GuiComponent.fill(pose, MARGIN_X - 2, y - 1,
                    MARGIN_X + width + 2, y + LINE_HEIGHT - 1, BACKGROUND);
            minecraft.font.drawShadow(pose, line.text(), MARGIN_X, y, line.color());
            y += LINE_HEIGHT;
        }
    }

    // ------------------------------------------------------------------ F3（可选，默认关）

    @SubscribeEvent
    public static void onDebugText(RenderGameOverlayEvent.Text event) {
        if (!ForgeConfig.CLIENT.showInDebugScreen.get()) {
            return;
        }
        event.getRight().add("");
        for (Line line : buildLines()) {
            event.getRight().add(line.text());
        }
    }

    // ------------------------------------------------------------------ 内容

    /** 一行：文本 + 兜底颜色（文本里的 § 颜色码优先）。 */
    public record Line(String text, int color) {
    }

    /** 组装要显示的行。 */
    public static List<Line> buildLines() {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("§bNowWeather §7v" + com.nowweather.forge.NowWeatherMod.displayVersion(), 0xFFFFFF));

        var observation = ClientWeatherState.observation();
        if (observation.isEmpty()) {
            lines.add(new Line(Text.tr("nowweather.hud.waiting_sync"), 0xAAAAAA));
        } else {
            var o = observation.get();
            // 数值先在代码里格式化再作为参数传进去：语言文件里只保留 %s，
            // 免得翻译时把小数的位数、百分号一起改坏。
            String weather = String.format(Locale.ROOT, "%s %.1f°C",
                    o.weatherType().zhName(), o.temperatureC());
            String humidity = String.format(Locale.ROOT, "%.0f%%", o.humidityPercent());
            lines.add(new Line(Text.tr("nowweather.debug.weather", weather, humidity), 0xFFFFFF));
            String reported = o.reportTimeText().isEmpty()
                    ? Text.tr("nowweather.debug.unknown") : o.reportTimeText();
            // 括号与颜色留在这里、语言文件里只放词：与 HUD（NowWeatherClientEvents）保持同一套写法
            String stale = ClientWeatherState.isStale()
                    ? " §8(" + Text.tr("nowweather.hud.stale") + ")" : "";
            lines.add(new Line(Text.tr("nowweather.debug.source",
                    o.providerId(), reported, stale), 0xFFFFFF));
            // 来源是离线推算时，把「为什么」一并显示出来 —— 否则玩家只能看到 offline 却不知道原因
            var payloadNow = ClientWeatherState.payload();
            if ("offline".equals(o.providerId())) {
                String note = payloadNow == null ? "" : payloadNow.fetchNote();
                lines.add(new Line(Text.tr("nowweather.debug.offline")
                        + (note.isEmpty() ? "" : " §7· §f" + note), 0xFFCC66));
            } else if (payloadNow != null && !payloadNow.fetchNote().isEmpty()) {
                lines.add(new Line("§7  " + payloadNow.fetchNote(), 0xAAAAAA));
            }
        }

        com.nowweather.core.climate.BiomeClimate climate = ClientWeatherState.climate();
        if (climate != null) {
            lines.add(new Line(Text.tr("nowweather.debug.climate",
                    climate.climateClass().zhName(), climate.tempRange().describe()), 0xFFFFFF));
        }

        com.nowweather.core.forecast.Forecast forecast = ClientWeatherState.forecast();
        if (forecast != null && !forecast.entries().isEmpty()) {
            var first = forecast.entries().get(0);
            lines.add(new Line(Text.tr("nowweather.debug.forecast_first",
                    first.weatherType().zhName(),
                    String.format(Locale.ROOT, "%.1f°C", first.temperatureC())), 0xFFFFFF));
        }

        var payload = ClientWeatherState.payload();
        if (payload != null) {
            lines.add(new Line(Text.tr("nowweather.debug.applied",
                    payload.applied().zhName(),
                    payload.climateAdjusted()
                            ? "§e(" + Text.tr("nowweather.hud.climate_adjusted") + ")" : ""), 0xFFFFFF));
            if (!payload.decisionReason().isEmpty()) {
                lines.add(new Line(Text.tr("nowweather.debug.reason",
                        payload.decisionReason()), 0xFFFFFF));
            }
        }

        lines.add(new Line(Text.tr("nowweather.debug.modules",
                ForgeConfig.COMMON.llmEnabled.get()
                        ? Text.tr("nowweather.debug.state.on") : Text.tr("nowweather.debug.state.off"),
                com.nowweather.forge.compat.SoundModBridge.soundPhysicsPresent()
                        ? Text.tr("nowweather.debug.state.attached")
                        : Text.tr("nowweather.debug.state.absent")), 0xFFFFFF));
        lines.add(new Line(Text.tr("nowweather.debug.command_hint"), 0x888888));
        return lines;
    }
}

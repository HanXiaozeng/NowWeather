package com.nowweather.forge.client;

import com.nowweather.forge.NowWeatherLog;
import com.nowweather.forge.net.NowWeatherNetwork;
import net.minecraftforge.client.ConfigGuiHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * 客户端初始化：注册网络接收器、HUD 事件与断开连接时的清理。
 *
 * <p>这个类只会在客户端被加载（由 {@code NowWeatherMod} 通过 {@code DistExecutor} 调用），
 * 因此可以安全地引用客户端专用类 —— 专用服务器上不会碰到它。</p>
 */
public final class NowWeatherClientSetup {

    private NowWeatherClientSetup() {
    }

    public static void init() {
        NowWeatherNetwork.setClientSink(ClientWeatherState::accept);
        MinecraftForge.EVENT_BUS.register(NowWeatherClientEvents.class);
        // F3 调试界面的右下角面板
        MinecraftForge.EVENT_BUS.register(NowWeatherDebugOverlay.class);
        MinecraftForge.EVENT_BUS.register(Lifecycle.class);

        // 配置界面：注册成 IExtensionPoint。
        //
        // ★ 这里是 1.18.2 最容易踩的坑之一：Forge 1.18.2 **不会**自动为注册了
        //   ForgeConfigSpec 的模组生成配置界面 —— 没注册 ConfigGuiFactory 的话，
        //   模组列表里那个「Config」按钮是灰的（ModListScreen.java:400 用
        //   ConfigGuiHandler.getGuiFactoryFor(...).isPresent() 决定是否启用）。
        //   自动生成配置界面是 1.19.x+ 才有的功能。
        ModLoadingContext.get().registerExtensionPoint(
                ConfigGuiHandler.ConfigGuiFactory.class,
                () -> new ConfigGuiHandler.ConfigGuiFactory(
                        (minecraft, parent) -> new NowWeatherConfigScreen(parent)));

        NowWeatherLog.LOGGER.info("NowWeather 客户端已就绪（HUD 与游戏内配置界面已注册）");
    }

    /** 客户端生命周期事件。 */
    public static final class Lifecycle {

        private Lifecycle() {
        }

        /** 退出服务器/存档时清掉数据，避免在标题界面显示上一个世界的天气。 */
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            ClientWeatherState.clear();
        }
    }
}

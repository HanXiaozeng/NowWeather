package com.nowweather.forge.net;

import com.nowweather.core.NowWeatherCore;
import com.nowweather.core.sync.SyncPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Consumer;

/**
 * NowWeather 网络通道（服务端 → 客户端同步真实天气与预报）。
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>协议版本号用于防止客户端/服务端模组版本不一致时错读数据；</li>
 *   <li>包体就是 {@link SyncPayload} 的 JSON —— 核心已经定义了稳定的序列化格式
 *       （见 {@code SyncPayload.toJson/fromJson}），这样跨版本升级协议时不必重写编解码；</li>
 *   <li>客户端处理通过一个「接收器」转发，避免在通用代码里直接引用客户端类
 *       （在专用服务器上加载客户端类会直接崩服）。</li>
 * </ul>
 */
public final class NowWeatherNetwork {

    /** 协议版本：客户端与服务端必须一致。改动 {@link SyncPayload} 的格式时请一并提升。 */
    public static final String PROTOCOL_VERSION = "1";

    private static final int MAX_PAYLOAD_CHARS = 256 * 1024;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NowWeatherCore.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** 客户端接收器：由客户端初始化时注入，服务端上保持为空实现。 */
    private static volatile Consumer<String> clientSink = json -> {
    };

    private NowWeatherNetwork() {
    }

    public static void register() {
        int index = 0;
        CHANNEL.registerMessage(index++, SyncWeatherPacket.class,
                SyncWeatherPacket::encode, SyncWeatherPacket::decode, SyncWeatherPacket::handle);
        CHANNEL.registerMessage(index++, RequestNowWeatherPacket.class,
                RequestNowWeatherPacket::encode, RequestNowWeatherPacket::decode,
                RequestNowWeatherPacket::handle);
    }

    /** 客户端注册自己的接收器（只在客户端调用）。 */
    public static void setClientSink(Consumer<String> sink) {
        clientSink = sink == null ? json -> {
        } : sink;
    }

    static void deliverToClient(String json) {
        clientSink.accept(json);
    }

    static int maxPayloadChars() {
        return MAX_PAYLOAD_CHARS;
    }

    // ------------------------------------------------------------------ 发送

    public static void sendTo(ServerPlayer player, SyncPayload payload) {
        if (payload == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncWeatherPacket(payload.toJsonString()));
    }

    public static void sendToAll(MinecraftServer server, SyncPayload payload) {
        if (payload == null || server == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.ALL.noArg(), new SyncWeatherPacket(payload.toJsonString()));
    }
}

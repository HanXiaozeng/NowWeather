package com.nowweather.forge.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求立刻同步一次真实天气。
 *
 * <p>玩家刚进服、或客户端 HUD 发现数据太旧时发这个包，服务端会立刻回一份最新的
 * {@link SyncWeatherPacket}，而不必等下一次定时广播。</p>
 */
public final class RequestNowWeatherPacket {

    public RequestNowWeatherPacket() {
    }

    public static void encode(RequestNowWeatherPacket message, FriendlyByteBuf buffer) {
        // 无字段
    }

    public static RequestNowWeatherPacket decode(FriendlyByteBuf buffer) {
        return new RequestNowWeatherPacket();
    }

    public static void handle(RequestNowWeatherPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                com.nowweather.forge.NowWeatherMod.sendSnapshotTo(player);
            }
        });
        context.setPacketHandled(true);
    }
}

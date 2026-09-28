package com.nowweather.forge.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端 → 客户端：同步真实天气 / 预报（内容为 SyncPayload 的 JSON）。 */
public final class SyncWeatherPacket {

    private final String json;

    public SyncWeatherPacket(String json) {
        this.json = json == null ? "" : json;
    }

    public static void encode(SyncWeatherPacket message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.json, NowWeatherNetwork.maxPayloadChars());
    }

    public static SyncWeatherPacket decode(FriendlyByteBuf buffer) {
        return new SyncWeatherPacket(buffer.readUtf(NowWeatherNetwork.maxPayloadChars()));
    }

    public static void handle(SyncWeatherPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> NowWeatherNetwork.deliverToClient(message.json));
        context.setPacketHandled(true);
    }
}

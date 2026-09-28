package com.nowweather.forge.platform;

import com.nowweather.core.api.PlayerHandle;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** 把 {@link ServerPlayer} 包装成核心认识的 {@link PlayerHandle}。 */
public final class ForgePlayerHandle implements PlayerHandle {

    private final ServerPlayer player;
    private final String biomeId;

    public ForgePlayerHandle(ServerPlayer player, String biomeId) {
        this.player = player;
        this.biomeId = biomeId == null || biomeId.isBlank() ? "minecraft:plains" : biomeId;
    }

    public ServerPlayer player() {
        return player;
    }

    @Override
    public UUID id() {
        return player.getUUID();
    }

    @Override
    public String name() {
        return player.getName().getString();
    }

    @Override
    public String dimensionId() {
        ResourceLocation location = player.getLevel().dimension().location();
        return location.toString();
    }

    @Override
    public String biomeId() {
        return biomeId;
    }

    @Override
    public double x() {
        return player.getX();
    }

    @Override
    public double y() {
        return player.getY();
    }

    @Override
    public double z() {
        return player.getZ();
    }

    @Override
    public boolean canSeeSky() {
        return player.getLevel().canSeeSky(player.blockPosition());
    }

    @Override
    public boolean hasPermission(int level) {
        return player.hasPermissions(level);
    }

    @Override
    public void sendMessage(String text) {
        player.sendMessage(new TextComponent(text == null ? "" : text), Util.NIL_UUID);
    }

    @Override
    public String language() {
        return "zh_cn";
    }
}

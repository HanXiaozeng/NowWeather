package com.nowweather.core.sync;

import com.nowweather.core.api.ModPresence;
import com.nowweather.core.api.PlayerHandle;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.json.JsonObject;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一次服务端 tick 的上下文 —— 加载器把「这一刻世界的样子」打包传给核心。
 *
 * @param dimensionId     被驱动的维度
 * @param totalWorldTime  世界总刻
 * @param dayTime         当日时刻（0~23999）
 * @param worldSeed       世界种子
 * @param players         在线玩家（核心用第一个玩家所在位置作为「当前地区」的锚点）
 * @param biomeFacts      锚点玩家所在生物群系的事实（可为 null）
 * @param presence        模组存在性探测（用于出站联动）
 * @param bindingSettings 联动专用配置
 */
public record ServerTickContext(
        String dimensionId,
        long totalWorldTime,
        long dayTime,
        long worldSeed,
        List<PlayerHandle> players,
        BiomeFacts biomeFacts,
        ModPresence presence,
        JsonObject bindingSettings,
        com.nowweather.core.terrain.TerrainProfile terrain) {

    public ServerTickContext {
        players = players == null ? List.of() : List.copyOf(players);
        bindingSettings = bindingSettings == null ? new JsonObject() : bindingSettings;
        presence = presence == null ? ModPresence.NONE : presence;
        terrain = terrain == null ? com.nowweather.core.terrain.TerrainProfile.NONE : terrain;
    }

    /** 兼容旧签名：没有地形信息时等同于不做地形订正。 */
    public ServerTickContext(String dimensionId, long totalWorldTime, long dayTime, long worldSeed,
                             List<PlayerHandle> players, BiomeFacts biomeFacts, ModPresence presence,
                             JsonObject bindingSettings) {
        this(dimensionId, totalWorldTime, dayTime, worldSeed, players, biomeFacts, presence,
                bindingSettings, com.nowweather.core.terrain.TerrainProfile.NONE);
    }

    /** 选一个「锚点玩家」：优先有权限的、其次按名字排序保证稳定。 */
    public Optional<PlayerHandle> anchorPlayer() {
        return players.stream()
                .filter(p -> dimensionId.equals(p.dimensionId()))
                .min(Comparator.comparing(PlayerHandle::name));
    }

    public Optional<PlayerHandle> anyPlayer() {
        return players.stream().findFirst();
    }

    public int playerCount() {
        return players.size();
    }
}

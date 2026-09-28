package com.nowweather.forge.client;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.sync.SyncPayload;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.forge.NowWeatherLog;

import java.util.Optional;

/**
 * 客户端持有的「服务端同步过来的真实天气」。
 *
 * <p>客户端不自己去请求天气 API（避免每个玩家都刷一遍接口），
 * 只接收服务端推送的快照；HUD、以及其它模组的客户端侧联动都从这里读。</p>
 */
public final class ClientWeatherState {

    private static volatile SyncPayload payload;
    private static volatile long receivedAtMillis;

    private ClientWeatherState() {
    }

    /** 网络包入口：解析服务端发来的快照。 */
    public static void accept(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            payload = SyncPayload.fromJsonString(json);
            receivedAtMillis = System.currentTimeMillis();
        } catch (RuntimeException e) {
            NowWeatherLog.LOGGER.warn("解析服务端同步的天气数据失败: {}", e.getMessage());
        }
    }

    public static SyncPayload payload() {
        return payload;
    }

    public static Optional<WeatherObservation> observation() {
        SyncPayload current = payload;
        return current == null ? Optional.empty() : Optional.ofNullable(current.observation());
    }

    public static Forecast forecast() {
        SyncPayload current = payload;
        return current == null ? null : current.forecast();
    }

    public static BiomeClimate climate() {
        SyncPayload current = payload;
        return current == null ? null : current.climate();
    }

    public static boolean hasData() {
        return payload != null && payload.observation() != null;
    }

    public static long receivedAtMillis() {
        return receivedAtMillis;
    }

    public static boolean isStale() {
        return !hasData() || System.currentTimeMillis() - receivedAtMillis > 60_000L;
    }

    /** 断开连接时清理，避免在标题界面显示上一个服务器的天气。 */
    public static void clear() {
        payload = null;
        receivedAtMillis = 0L;
    }
}

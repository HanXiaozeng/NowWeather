package com.nowweather.core.sync;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.weather.WeatherObservation;

/**
 * 服务端 → 客户端的同步包。
 *
 * <p>同步内容刻意保持精简：实况观测 + 生物群系气候 + 预报。客户端拿到后可以：</p>
 * <ul>
 *   <li>直接在 HUD 上显示真实天气；</li>
 *   <li>用同一个种子在本地复现预报曲线（做平滑插值）；</li>
 *   <li>把数据转发给它自己装的天气模组做视觉表现。</li>
 * </ul>
 */
public final class SyncPayload {

    /**
     * 「真实时间 → 世界刻」的锚点。
     *
     * <p>服务端把「生成这份同步包时的真实毫秒」和「那一刻的世界总刻」一起发过来，
     * 客户端就能自己推出任意时刻应当的世界刻（{@code 1 刻 = 3600 毫秒}），
     * 从而<b>每帧直接把天空时间设成真实时间</b>，而不是等每秒一次的校正包。</p>
     *
     * <p>这是解决「天空每隔几秒往前挪一下又跳回去」的关键：客户端不再依赖
     * 服务端的周期校正，也不需要时区/日偏移等额外参数 —— 一个锚点就够。</p>
     */
    public record TimeAnchor(long epochMillis, long dayTime) {

        /** 真实时间同步下，一刻对应的毫秒数（一天 24000 刻 = 86400 秒）。 */
        public static final double MILLIS_PER_TICK = 3600.0D;

        /** 从锚点外推到 {@code nowMillis} 时应当写入的世界总刻。 */
        public long dayTimeAt(long nowMillis) {
            long elapsed = nowMillis - epochMillis;
            if (elapsed <= 0L) {
                return dayTime;
            }
            return dayTime + (long) Math.floor(elapsed / MILLIS_PER_TICK);
        }
    }

    private final long sequence;
    private final long serverTick;
    private final String dimensionId;
    private final WeatherObservation observation;
    private final Forecast forecast;
    private final BiomeClimate climate;
    private final PlatformWeather applied;
    private final String decisionReason;
    private final boolean climateAdjusted;
    private final TimeAnchor timeAnchor;
    private final String fetchNote;
    /** 真实预报的云量（0~1）；NaN = 没有真实预报数据，客户端退回按天气类型推算。 */
    private final double realCloudCover01;
    /** 真实预报的能见度（km）；NaN = 未知。 */
    private final double realVisibilityKm;

    public SyncPayload(long sequence, long serverTick, String dimensionId, WeatherObservation observation,
                       Forecast forecast, BiomeClimate climate, PlatformWeather applied,
                       String decisionReason, boolean climateAdjusted) {
        this(sequence, serverTick, dimensionId, observation, forecast, climate, applied,
                decisionReason, climateAdjusted, null, "");
    }

    public SyncPayload(long sequence, long serverTick, String dimensionId, WeatherObservation observation,
                       Forecast forecast, BiomeClimate climate, PlatformWeather applied,
                       String decisionReason, boolean climateAdjusted, TimeAnchor timeAnchor) {
        this(sequence, serverTick, dimensionId, observation, forecast, climate, applied,
                decisionReason, climateAdjusted, timeAnchor, "");
    }

    public SyncPayload(long sequence, long serverTick, String dimensionId, WeatherObservation observation,
                       Forecast forecast, BiomeClimate climate, PlatformWeather applied,
                       String decisionReason, boolean climateAdjusted, TimeAnchor timeAnchor,
                       String fetchNote) {
        this(sequence, serverTick, dimensionId, observation, forecast, climate, applied,
                decisionReason, climateAdjusted, timeAnchor, fetchNote, Double.NaN, Double.NaN);
    }

    public SyncPayload(long sequence, long serverTick, String dimensionId, WeatherObservation observation,
                       Forecast forecast, BiomeClimate climate, PlatformWeather applied,
                       String decisionReason, boolean climateAdjusted, TimeAnchor timeAnchor,
                       String fetchNote, double realCloudCover01, double realVisibilityKm) {
        this.sequence = sequence;
        this.serverTick = serverTick;
        this.dimensionId = dimensionId == null ? "minecraft:overworld" : dimensionId;
        this.observation = observation;
        this.forecast = forecast;
        this.climate = climate;
        this.applied = applied == null ? PlatformWeather.CLEAR : applied;
        this.decisionReason = decisionReason == null ? "" : decisionReason;
        this.climateAdjusted = climateAdjusted;
        this.timeAnchor = timeAnchor;
        this.fetchNote = fetchNote == null ? "" : fetchNote;
        this.realCloudCover01 = realCloudCover01;
        this.realVisibilityKm = realVisibilityKm;
    }

    /** 真实时间同步锚点；服务端未开启该功能时为 null。 */
    public TimeAnchor timeAnchor() {
        return timeAnchor;
    }

    /**
     * 真实天气的取数状态说明（为空表示一切正常）。
     *
     * <p>用来回答「为什么现在显示的是离线推算」—— 上次失败原因、还有多久重试。</p>
     */
    public String fetchNote() {
        return fetchNote;
    }

    /** 真实预报的云量（0~1）；NaN 表示没有真实预报数据。 */
    public double realCloudCover01() {
        return realCloudCover01;
    }

    /** 真实预报的能见度（km）；NaN 表示未知。 */
    public double realVisibilityKm() {
        return realVisibilityKm;
    }

    public long sequence() {
        return sequence;
    }

    public long serverTick() {
        return serverTick;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public WeatherObservation observation() {
        return observation;
    }

    public Forecast forecast() {
        return forecast;
    }

    public BiomeClimate climate() {
        return climate;
    }

    public PlatformWeather applied() {
        return applied;
    }

    public String decisionReason() {
        return decisionReason;
    }

    public boolean climateAdjusted() {
        return climateAdjusted;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("sequence", sequence);
        json.put("serverTick", serverTick);
        json.put("dimension", dimensionId);
        json.put("applied", applied.name());
        if (!decisionReason.isEmpty()) {
            json.put("reason", decisionReason);
        }
        json.put("climateAdjusted", climateAdjusted);
        if (observation != null) {
            json.put("observation", observation.toJson());
        }
        if (forecast != null) {
            json.put("forecast", forecast.toJson());
        }
        if (climate != null) {
            json.put("climate", climate.toJson());
        }
        if (timeAnchor != null) {
            JsonObject time = new JsonObject();
            time.put("epochMillis", timeAnchor.epochMillis());
            time.put("dayTime", timeAnchor.dayTime());
            json.put("timeAnchor", time);
        }
        if (!fetchNote.isEmpty()) {
            json.put("fetchNote", fetchNote);
        }
        if (!Double.isNaN(realCloudCover01)) {
            json.put("realCloud", realCloudCover01);
        }
        if (!Double.isNaN(realVisibilityKm)) {
            json.put("realVisibilityKm", realVisibilityKm);
        }
        return json;
    }

    public String toJsonString() {
        return toJson().toJson();
    }

    public static SyncPayload fromJson(JsonObject json) {
        JsonObject obs = json.optObject("observation");
        JsonObject fc = json.optObject("forecast");
        JsonObject cl = json.optObject("climate");
        JsonObject ta = json.optObject("timeAnchor");
        TimeAnchor timeAnchor = null;
        if (ta != null) {
            long epoch = ta.optLong("epochMillis", 0L);
            if (epoch > 0L) {
                timeAnchor = new TimeAnchor(epoch, ta.optLong("dayTime", 0L));
            }
        }
        return new SyncPayload(
                json.optLong("sequence", 0L),
                json.optLong("serverTick", 0L),
                json.optString("dimension", "minecraft:overworld"),
                obs == null ? null : WeatherObservation.fromJson(obs),
                fc == null ? null : Forecast.fromJson(fc),
                cl == null ? null : BiomeClimate.fromJson(
                        cl.optString("id", "unknown"), cl, null),
                PlatformWeather.parse(json.optString("applied", null), PlatformWeather.CLEAR),
                json.optString("reason", ""),
                json.optBoolean("climateAdjusted", false),
                timeAnchor,
                json.optString("fetchNote", ""),
                json.optDouble("realCloud", Double.NaN),
                json.optDouble("realVisibilityKm", Double.NaN));
    }

    public static SyncPayload fromJsonString(String text) {
        return fromJson(com.nowweather.core.json.JsonValue.parse(text).asObject());
    }

    public byte[] toBytes() {
        return toJsonString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "SyncPayload[#" + sequence + " " + applied + " " + dimensionId
                + (observation == null ? " 无观测" : " " + observation.weatherType().zhName()) + "]";
    }
}

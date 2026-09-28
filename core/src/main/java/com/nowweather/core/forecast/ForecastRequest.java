package com.nowweather.core.forecast;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.weather.WeatherObservation;

/**
 * 一次预报生成请求。
 *
 * @param climate     目标生物群系的气候（提供「正常温度范围」）
 * @param anchor      真实天气锚点，可为 null（纯离线模拟）
 * @param seed        确定性种子（世界种子 + 天数 + 群系 id），保证服务端/客户端生成一致
 * @param startTick   预报起点（世界总刻）
 * @param startEpochMillis 预报起点（真实时间，用于 HUD 显示时刻）
 * @param dayTime     预报起点时的 MC 当日时刻（0~23999）
 * @param horizon     预报格数
 * @param ticksPerEntry 每格持续刻数
 * @param millisPerTick 一刻对应的真实毫秒数（原版 50，开启真实时间同步后 3600）——
 *                      预报格的时间戳必须按它推进，否则 HUD 上会出现「预报格只差 1 分钟」
 * @param toleranceC  气候合理性校验的温度容差
 * @param allowExtremeEvents 是否允许偶发极端天气
 */
public record ForecastRequest(
        BiomeClimate climate,
        WeatherObservation anchor,
        long seed,
        long startTick,
        long startEpochMillis,
        long dayTime,
        int horizon,
        long ticksPerEntry,
        double millisPerTick,
        double toleranceC,
        boolean allowExtremeEvents) {

    /** 原版一天 20 分钟 → 1 刻 = 50 毫秒。 */
    public static final double VANILLA_MILLIS_PER_TICK = 50.0D;

    public ForecastRequest {
        if (climate == null) {
            throw new IllegalArgumentException("ForecastRequest 需要生物群系气候");
        }
        if (horizon <= 0) {
            throw new IllegalArgumentException("horizon 必须为正数");
        }
        if (ticksPerEntry <= 0L) {
            throw new IllegalArgumentException("ticksPerEntry 必须为正数");
        }
        if (!(millisPerTick > 0.0D)) {
            throw new IllegalArgumentException("millisPerTick 必须为正数");
        }
    }

    public static Builder builder(BiomeClimate climate) {
        return new Builder(climate);
    }

    public static final class Builder {
        private final BiomeClimate climate;
        private WeatherObservation anchor;
        private long seed = 0L;
        private long startTick = 0L;
        private long startEpochMillis = System.currentTimeMillis();
        private long dayTime = 0L;
        private int horizon = 12;
        private long ticksPerEntry = 1000L;
        private double millisPerTick = VANILLA_MILLIS_PER_TICK;
        private double toleranceC = 6.0D;
        private boolean allowExtremeEvents = true;

        private Builder(BiomeClimate climate) {
            this.climate = climate;
        }

        public Builder anchor(WeatherObservation anchor) {
            this.anchor = anchor;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder startTick(long startTick) {
            this.startTick = startTick;
            return this;
        }

        public Builder startEpochMillis(long startEpochMillis) {
            this.startEpochMillis = startEpochMillis;
            return this;
        }

        public Builder dayTime(long dayTime) {
            this.dayTime = dayTime;
            return this;
        }

        public Builder horizon(int horizon) {
            this.horizon = horizon;
            return this;
        }

        public Builder ticksPerEntry(long ticksPerEntry) {
            this.ticksPerEntry = ticksPerEntry;
            return this;
        }

        /** 一刻对应的真实毫秒数；开启真实时间同步时传 {@code RealTimeSyncModel} 换算出的 3600。 */
        public Builder millisPerTick(double millisPerTick) {
            this.millisPerTick = millisPerTick;
            return this;
        }

        public Builder toleranceC(double toleranceC) {
            this.toleranceC = toleranceC;
            return this;
        }

        public Builder allowExtremeEvents(boolean allowExtremeEvents) {
            this.allowExtremeEvents = allowExtremeEvents;
            return this;
        }

        public ForecastRequest build() {
            return new ForecastRequest(climate, anchor, seed, startTick, startEpochMillis, dayTime,
                    horizon, ticksPerEntry, millisPerTick, toleranceC, allowExtremeEvents);
        }
    }
}

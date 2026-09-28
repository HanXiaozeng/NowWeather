package com.nowweather.core.forecast;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

/**
 * 一份不可变的预报。
 *
 * <p>服务端生成后会通过 {@code SyncPayload} 同步给客户端，因此这里的
 * {@link #toJson()} / {@link #fromJson(JsonObject)} 是网络协议的一部分，改动需谨慎。</p>
 */
public final class Forecast {

    private final String biomeId;
    private final String climateSummary;
    private final String providerId;
    private final long generatedAtEpochMillis;
    private final long startTick;
    private final long ticksPerEntry;
    private final long seed;
    private final List<ForecastEntry> entries;

    public Forecast(String biomeId, String climateSummary, String providerId, long generatedAtEpochMillis,
                    long startTick, long ticksPerEntry, long seed, List<ForecastEntry> entries) {
        this.biomeId = biomeId == null ? "" : biomeId;
        this.climateSummary = climateSummary == null ? "" : climateSummary;
        this.providerId = providerId == null ? "" : providerId;
        this.generatedAtEpochMillis = generatedAtEpochMillis;
        this.startTick = startTick;
        this.ticksPerEntry = Math.max(1L, ticksPerEntry);
        this.seed = seed;
        this.entries = List.copyOf(entries);
    }

    public static Forecast empty(String biomeId, long generatedAt) {
        return new Forecast(biomeId, "", "", generatedAt, 0L, 1000L, 0L, List.of());
    }

    public String biomeId() {
        return biomeId;
    }

    public String climateSummary() {
        return climateSummary;
    }

    /** 锚点真实天气来自哪个数据源。 */
    public String providerId() {
        return providerId;
    }

    public long generatedAtEpochMillis() {
        return generatedAtEpochMillis;
    }

    public long startTick() {
        return startTick;
    }

    public long ticksPerEntry() {
        return ticksPerEntry;
    }

    public long seed() {
        return seed;
    }

    public List<ForecastEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public long endTick() {
        return startTick + ticksPerEntry * entries.size();
    }

    /** 取某个世界总刻对应的预报格。 */
    public Optional<ForecastEntry> at(long tick) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        long offset = tick - startTick;
        if (offset < 0L) {
            return Optional.of(entries.get(0));
        }
        int index = (int) (offset / ticksPerEntry);
        if (index >= entries.size()) {
            return Optional.of(entries.get(entries.size() - 1));
        }
        return Optional.of(entries.get(index));
    }

    /** 取真实时间对应的预报格（客户端 HUD 用）。 */
    public Optional<ForecastEntry> atEpoch(long epochMillis) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        for (ForecastEntry entry : entries) {
            long start = entry.startEpochMillis();
            long end = start + entry.durationTicks() * 50L;
            if (epochMillis >= start && epochMillis < end) {
                return Optional.of(entry);
            }
        }
        return Optional.of(entries.get(entries.size() - 1));
    }

    /** 是否已经过期需要重新生成。 */
    public boolean isExpired(long nowEpochMillis, long maxAgeMillis) {
        return isEmpty() || nowEpochMillis - generatedAtEpochMillis > maxAgeMillis;
    }

    public static Forecast of(BiomeClimate climate, String providerId, long generatedAt, long startTick,
                              long ticksPerEntry, long seed, List<ForecastEntry> entries) {
        return new Forecast(climate.biomeId(),
                climate.climateClass().zhName() + " " + climate.tempRange().describe(),
                providerId, generatedAt, startTick, ticksPerEntry, seed, entries);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("biomeId", biomeId);
        json.put("climate", climateSummary);
        json.put("provider", providerId);
        json.put("generatedAt", generatedAtEpochMillis);
        json.put("startTick", startTick);
        json.put("ticksPerEntry", ticksPerEntry);
        json.put("seed", seed);
        JsonArray arr = new JsonArray();
        entries.forEach(e -> arr.add(e.toJson()));
        json.put("entries", arr);
        return json;
    }

    public static Forecast fromJson(JsonObject json) {
        JsonArray arr = json.optArray("entries");
        List<ForecastEntry> list = arr == null ? List.of() : arr.mapObjects(ForecastEntry::fromJson);
        return new Forecast(
                json.optString("biomeId", ""),
                json.optString("climate", ""),
                json.optString("provider", ""),
                json.optLong("generatedAt", 0L),
                json.optLong("startTick", 0L),
                json.optLong("ticksPerEntry", 1000L),
                json.optLong("seed", 0L),
                list);
    }

    @Override
    public String toString() {
        return "Forecast[" + biomeId + " " + entries.size() + " 格，共 "
                + (ticksPerEntry * entries.size()) + " 刻]";
    }
}

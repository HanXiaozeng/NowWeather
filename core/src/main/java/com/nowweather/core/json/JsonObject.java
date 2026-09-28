package com.nowweather.core.json;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** JSON 对象（保持插入顺序，便于生成可读的配置文件）。 */
public final class JsonObject extends JsonValue {

    private final Map<String, JsonValue> entries = new LinkedHashMap<>();

    public JsonObject put(String key, JsonValue value) {
        entries.put(key, value == null ? JsonPrimitive.nul() : value);
        return this;
    }

    public JsonObject put(String key, String value) {
        return put(key, value == null ? JsonPrimitive.nul() : JsonPrimitive.of(value));
    }

    public JsonObject put(String key, double value) {
        return put(key, JsonPrimitive.of(value));
    }

    public JsonObject put(String key, long value) {
        return put(key, JsonPrimitive.of(value));
    }

    public JsonObject put(String key, boolean value) {
        return put(key, JsonPrimitive.of(value));
    }

    public JsonObject putNull(String key) {
        return put(key, JsonPrimitive.nul());
    }

    public JsonObject putAll(Map<String, ? extends JsonValue> map) {
        map.forEach(this::put);
        return this;
    }

    public JsonValue get(String key) {
        return entries.get(key);
    }

    /** 取不到时抛异常，用于「必须有」的字段。 */
    public JsonValue require(String key) {
        JsonValue v = entries.get(key);
        if (v == null) {
            throw new JsonException("缺少必需字段: " + key);
        }
        return v;
    }

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public boolean hasNonNull(String key) {
        JsonValue v = entries.get(key);
        return v != null && !v.isNull();
    }

    public JsonObject optObject(String key) {
        JsonValue v = entries.get(key);
        return v == null ? null : v.asObjectOrNull();
    }

    public JsonObject objectOrEmpty(String key) {
        JsonObject o = optObject(key);
        return o == null ? new JsonObject() : o;
    }

    public JsonArray optArray(String key) {
        JsonValue v = entries.get(key);
        return v == null ? null : v.asArrayOrNull();
    }

    public JsonArray arrayOrEmpty(String key) {
        JsonArray a = optArray(key);
        return a == null ? new JsonArray() : a;
    }

    public String optString(String key, String fallback) {
        JsonValue v = entries.get(key);
        return v == null ? fallback : v.asStringOr(fallback);
    }

    public String stringOrEmpty(String key) {
        return optString(key, "");
    }

    public double optDouble(String key, double fallback) {
        JsonValue v = entries.get(key);
        return v == null ? fallback : v.asDoubleOr(fallback);
    }

    /** 从「带单位」的文本里抽数字（例如 {@code "16%"}、{@code "4级"}）。 */
    public double optNumberish(String key, double fallback) {
        JsonValue v = entries.get(key);
        if (v instanceof JsonPrimitive p) {
            return p.extractNumber(fallback);
        }
        return fallback;
    }

    public int optInt(String key, int fallback) {
        return (int) Math.round(optDouble(key, fallback));
    }

    public long optLong(String key, long fallback) {
        JsonValue v = entries.get(key);
        return v == null ? fallback : v.asLongOr(fallback);
    }

    public boolean optBoolean(String key, boolean fallback) {
        JsonValue v = entries.get(key);
        return v == null ? fallback : v.asBooleanOr(fallback);
    }

    public JsonValue remove(String key) {
        return entries.remove(key);
    }

    public Set<String> keys() {
        return java.util.Collections.unmodifiableSet(entries.keySet());
    }

    public Collection<JsonValue> values() {
        return java.util.Collections.unmodifiableCollection(entries.values());
    }

    public Set<Map.Entry<String, JsonValue>> entrySet() {
        return java.util.Collections.unmodifiableSet(entries.entrySet());
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonObject other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }
}

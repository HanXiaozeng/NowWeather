package com.nowweather.core.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/** JSON 数组。 */
public final class JsonArray extends JsonValue implements Iterable<JsonValue> {

    private final List<JsonValue> values = new ArrayList<>();

    public JsonArray add(JsonValue value) {
        values.add(value == null ? JsonPrimitive.nul() : value);
        return this;
    }

    public JsonArray add(String value) {
        return add(JsonPrimitive.of(value));
    }

    public JsonArray add(double value) {
        return add(JsonPrimitive.of(value));
    }

    public JsonArray add(long value) {
        return add(JsonPrimitive.of(value));
    }

    public JsonArray add(boolean value) {
        return add(JsonPrimitive.of(value));
    }

    /** 把普通 Java 值转成 JSON 值加入（支持 String / Number / Boolean / null / JsonValue）。 */
    public JsonArray addAny(Object value) {
        if (value == null) {
            return add(JsonPrimitive.nul());
        }
        if (value instanceof JsonValue jv) {
            return add(jv);
        }
        if (value instanceof String s) {
            return add(s);
        }
        if (value instanceof Boolean b) {
            return add(b);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return add(((Number) value).longValue());
        }
        if (value instanceof Number n) {
            return add(n.doubleValue());
        }
        if (value instanceof Iterable<?> it) {
            JsonArray nested = new JsonArray();
            it.forEach(nested::addAny);
            return add(nested);
        }
        return add(String.valueOf(value));
    }

    public JsonValue get(int index) {
        return values.get(index);
    }

    public JsonObject optObject(int index) {
        JsonValue v = index >= 0 && index < values.size() ? values.get(index) : null;
        return v == null ? null : v.asObjectOrNull();
    }

    public String optString(int index, String fallback) {
        JsonValue v = index >= 0 && index < values.size() ? values.get(index) : null;
        return v == null ? fallback : v.asStringOr(fallback);
    }

    public double optDouble(int index, double fallback) {
        JsonValue v = index >= 0 && index < values.size() ? values.get(index) : null;
        return v == null ? fallback : v.asDoubleOr(fallback);
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public List<JsonValue> values() {
        return java.util.Collections.unmodifiableList(values);
    }

    /** 把每个元素映射成对象并收集（自动跳过非对象元素）。 */
    public List<JsonObject> objects() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonValue v : values) {
            JsonObject o = v.asObjectOrNull();
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    public <T> List<T> mapObjects(Function<JsonObject, T> mapper) {
        List<T> out = new ArrayList<>();
        for (JsonObject o : objects()) {
            out.add(mapper.apply(o));
        }
        return out;
    }

    public Stream<JsonValue> stream() {
        return values.stream();
    }

    @Override
    public Iterator<JsonValue> iterator() {
        return values.iterator();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonArray other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }
}

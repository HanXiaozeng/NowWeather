package com.nowweather.core.json;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 JSON 值模型。
 *
 * <p>核心模块不依赖 Gson / Jackson：既避免与 Minecraft 自带的 Gson 版本打架，
 * 也保证核心可以脱离 Minecraft 单独编译与测试。</p>
 */
public abstract sealed class JsonValue permits JsonObject, JsonArray, JsonPrimitive {

    public static JsonValue parse(String text) {
        return JsonParser.parse(text);
    }

    /** 容错解析：失败时返回 {@code fallback}，用于处理第三方 API 的脏数据。 */
    public static JsonValue parseOr(String text, JsonValue fallback) {
        try {
            return JsonParser.parse(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    public static JsonValue of(String value) {
        return JsonPrimitive.of(value);
    }

    public static JsonValue of(double value) {
        return JsonPrimitive.of(value);
    }

    public static JsonValue of(long value) {
        return JsonPrimitive.of(value);
    }

    public static JsonValue of(boolean value) {
        return JsonPrimitive.of(value);
    }

    public static JsonValue nul() {
        return JsonPrimitive.nul();
    }

    // ------------------------------------------------------------------ 类型判断

    public boolean isObject() {
        return this instanceof JsonObject;
    }

    public boolean isArray() {
        return this instanceof JsonArray;
    }

    public boolean isPrimitive() {
        return this instanceof JsonPrimitive;
    }

    public boolean isNull() {
        return this instanceof JsonPrimitive p && p.raw() == null;
    }

    public boolean isString() {
        return this instanceof JsonPrimitive p && p.raw() instanceof String;
    }

    public boolean isNumber() {
        return this instanceof JsonPrimitive p && p.raw() instanceof Number;
    }

    public boolean isBoolean() {
        return this instanceof JsonPrimitive p && p.raw() instanceof Boolean;
    }

    // ------------------------------------------------------------------ 强制转换

    public JsonObject asObject() {
        if (this instanceof JsonObject o) {
            return o;
        }
        throw new JsonException("期望 JSON 对象，实际是 " + typeName());
    }

    public JsonObject asObjectOrNull() {
        return this instanceof JsonObject o ? o : null;
    }

    public JsonArray asArray() {
        if (this instanceof JsonArray a) {
            return a;
        }
        throw new JsonException("期望 JSON 数组，实际是 " + typeName());
    }

    public JsonArray asArrayOrNull() {
        return this instanceof JsonArray a ? a : null;
    }

    public JsonPrimitive asPrimitive() {
        if (this instanceof JsonPrimitive p) {
            return p;
        }
        throw new JsonException("期望 JSON 基本类型，实际是 " + typeName());
    }

    /** 宽松取字符串：数字与布尔也会转成字面量。 */
    public String asString() {
        if (this instanceof JsonPrimitive p) {
            return p.asString();
        }
        throw new JsonException("期望字符串，实际是 " + typeName());
    }

    public String asStringOr(String fallback) {
        if (this instanceof JsonPrimitive p && !p.isNull()) {
            return p.asString();
        }
        return fallback;
    }

    public double asDouble() {
        if (this instanceof JsonPrimitive p) {
            return p.asDouble();
        }
        throw new JsonException("期望数字，实际是 " + typeName());
    }

    public double asDoubleOr(double fallback) {
        if (this instanceof JsonPrimitive p && !p.isNull()) {
            return p.asDoubleOr(fallback);
        }
        return fallback;
    }

    public int asInt() {
        return (int) Math.round(asDouble());
    }

    public int asIntOr(int fallback) {
        return (int) Math.round(asDoubleOr(fallback));
    }

    public long asLong() {
        if (this instanceof JsonPrimitive p) {
            return p.asLong();
        }
        throw new JsonException("期望整数，实际是 " + typeName());
    }

    public long asLongOr(long fallback) {
        if (this instanceof JsonPrimitive p && !p.isNull()) {
            return p.asLongOr(fallback);
        }
        return fallback;
    }

    public boolean asBoolean() {
        if (this instanceof JsonPrimitive p) {
            return p.asBoolean();
        }
        throw new JsonException("期望布尔值，实际是 " + typeName());
    }

    public boolean asBooleanOr(boolean fallback) {
        if (this instanceof JsonPrimitive p && !p.isNull()) {
            return p.asBooleanOr(fallback);
        }
        return fallback;
    }

    public String typeName() {
        return JsonWriter.typeName(this);
    }

    /** 紧凑序列化。 */
    public String toJson() {
        return JsonWriter.write(this, false);
    }

    /** 便于人阅读 / 便于写进配置文件的缩进序列化。 */
    public String toPrettyJson() {
        return JsonWriter.write(this, true);
    }

    @Override
    public String toString() {
        return toJson();
    }

    /** 把数组元素收集成字符串列表（忽略非基本类型元素）。 */
    public List<String> asStringList() {
        List<String> out = new ArrayList<>();
        if (this instanceof JsonArray a) {
            for (JsonValue v : a.values()) {
                if (v instanceof JsonPrimitive p && !p.isNull()) {
                    out.add(p.asString());
                }
            }
        }
        return out;
    }
}

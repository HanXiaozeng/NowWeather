package com.nowweather.core.json;

/** JSON 序列化（紧凑 / 缩进两种模式）。 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(JsonValue value, boolean pretty) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(value, sb, pretty, 0);
        return sb.toString();
    }

    public static String typeName(JsonValue value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JsonObject) {
            return "对象";
        }
        if (value instanceof JsonArray) {
            return "数组";
        }
        JsonPrimitive p = value.asPrimitive();
        Object raw = p.raw();
        if (raw == null) {
            return "null";
        }
        if (raw instanceof String) {
            return "字符串";
        }
        if (raw instanceof Number) {
            return "数字";
        }
        if (raw instanceof Boolean) {
            return "布尔";
        }
        return raw.getClass().getSimpleName();
    }

    private static void writeValue(JsonValue value, StringBuilder sb, boolean pretty, int depth) {
        if (value == null || value.isNull()) {
            sb.append("null");
            return;
        }
        if (value instanceof JsonObject o) {
            writeObject(o, sb, pretty, depth);
            return;
        }
        if (value instanceof JsonArray a) {
            writeArray(a, sb, pretty, depth);
            return;
        }
        writePrimitive(value.asPrimitive(), sb);
    }

    private static void writeObject(JsonObject object, StringBuilder sb, boolean pretty, int depth) {
        if (object.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (var entry : object.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, pretty, depth + 1);
            writeString(entry.getKey(), sb);
            sb.append(pretty ? ": " : ":");
            writeValue(entry.getValue(), sb, pretty, depth + 1);
        }
        newline(sb, pretty, depth);
        sb.append('}');
    }

    private static void writeArray(JsonArray array, StringBuilder sb, boolean pretty, int depth) {
        if (array.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (JsonValue value : array) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, pretty, depth + 1);
            writeValue(value, sb, pretty, depth + 1);
        }
        newline(sb, pretty, depth);
        sb.append(']');
    }

    private static void writePrimitive(JsonPrimitive primitive, StringBuilder sb) {
        Object raw = primitive.raw();
        if (raw == null) {
            sb.append("null");
        } else if (raw instanceof String s) {
            writeString(s, sb);
        } else if (raw instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else {
                sb.append(primitive.asString());
            }
        } else {
            sb.append(String.valueOf(raw));
        }
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }
}

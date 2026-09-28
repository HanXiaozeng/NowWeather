package com.nowweather.core.json;

import java.util.Objects;

/** JSON 基本类型：字符串 / 数字 / 布尔 / null。 */
public final class JsonPrimitive extends JsonValue {

    private final Object value;

    private JsonPrimitive(Object value) {
        this.value = value;
    }

    public static JsonPrimitive of(String value) {
        return new JsonPrimitive(value);
    }

    public static JsonPrimitive of(double value) {
        return new JsonPrimitive(value);
    }

    public static JsonPrimitive of(long value) {
        return new JsonPrimitive(value);
    }

    public static JsonPrimitive of(boolean value) {
        return new JsonPrimitive(value);
    }

    public static JsonPrimitive nul() {
        return new JsonPrimitive(null);
    }

    public Object raw() {
        return value;
    }

    /** JSON null 判定。 */
    @Override
    public boolean isNull() {
        return value == null;
    }

    public String asString() {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double d) {
            return formatDouble(d);
        }
        return String.valueOf(value);
    }

    public double asDouble() {
        return asDoubleOr(Double.NaN);
    }

    public double asDoubleOr(double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0D : 0.0D;
        }
        if (value instanceof String s) {
            return parseDouble(s, fallback);
        }
        return fallback;
    }

    public long asLong() {
        return asLongOr(0L);
    }

    public long asLongOr(long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                double d = parseDouble(s, fallback);
                return Double.isNaN(d) ? fallback : (long) d;
            }
        }
        return fallback;
    }

    public boolean asBoolean() {
        return asBooleanOr(false);
    }

    public boolean asBooleanOr(boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.trim();
            if (t.equalsIgnoreCase("true") || t.equals("1") || t.equalsIgnoreCase("yes")) {
                return true;
            }
            if (t.equalsIgnoreCase("false") || t.equals("0") || t.equalsIgnoreCase("no")) {
                return false;
            }
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0D;
        }
        return fallback;
    }

    /**
     * 从「中文/混合」文本里抽出数字，例如 UApiPro 的 {@code "4级"} → 4。
     */
    public double extractNumber(double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (!(value instanceof String s)) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c == '-' && !started) || (c == '.' && started)) {
                sb.append(c);
                started = true;
            } else if (started) {
                break;
            }
        }
        return sb.length() == 0 ? fallback : parseDouble(sb.toString(), fallback);
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatDouble(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1.0E15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonPrimitive other)) {
            return false;
        }
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}

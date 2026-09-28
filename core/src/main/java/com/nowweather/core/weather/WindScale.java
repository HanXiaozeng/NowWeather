package com.nowweather.core.weather;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.text.Text;
import com.nowweather.core.util.MathUtil;

import java.util.Locale;
import java.util.Objects;

/** 蒲福风级与风速换算。 */
public final class WindScale {

    /** 各风级的下限风速（km/h），索引即风级 0~17。 */
    private static final double[] LOWER_BOUND_KMH = {
            0.0D, 1.0D, 6.0D, 12.0D, 20.0D, 29.0D, 39.0D, 50.0D, 62.0D, 75.0D,
            89.0D, 103.0D, 118.0D, 134.0D, 150.0D, 167.0D, 184.0D, 202.0D
    };

    private WindScale() {
    }

    public static double toKmh(int scale) {
        if (scale < 0) {
            return Double.NaN;
        }
        int clamped = MathUtil.clamp(scale, 0, LOWER_BOUND_KMH.length - 1);
        if (clamped >= LOWER_BOUND_KMH.length - 1) {
            return LOWER_BOUND_KMH[LOWER_BOUND_KMH.length - 1];
        }
        // 取该级区间中点
        return (LOWER_BOUND_KMH[clamped] + LOWER_BOUND_KMH[clamped + 1]) / 2.0D;
    }

    public static int fromKmh(double kmh) {
        if (Double.isNaN(kmh)) {
            return -1;
        }
        int result = 0;
        for (int i = 0; i < LOWER_BOUND_KMH.length; i++) {
            if (kmh >= LOWER_BOUND_KMH[i]) {
                result = i;
            }
        }
        return result;
    }

    /** 归一化风力 0~1（12 级及以上算满）。 */
    public static double toNormalized(int scale) {
        if (scale < 0) {
            return 0.3D;
        }
        return MathUtil.clamp(scale / 12.0D, 0.0D, 1.0D);
    }

    /**
     * 解析「4级」「4-5级」「四级」之类的文本。
     *
     * @return 风级，解析失败返回 -1
     */
    public static int parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() > 0) {
            try {
                return MathUtil.clamp(Integer.parseInt(digits.toString()), 0, 17);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        String zh = raw.trim().replace("级", "").replace("风", "");
        return switch (zh) {
            case "零", "无" -> 0;
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            default -> -1;
        };
    }

    /**
     * 风级的展示文本，例如「4 级」。
     *
     * <p>数字用 {@code Locale.ROOT} 格式化（不随语言变），只有文案走翻译缝。</p>
     */
    public static String describe(int scale) {
        if (scale < 0) {
            return Text.tr("nowweather.wind_scale.unknown");
        }
        return Text.tr("nowweather.wind_scale.value", Integer.toString(scale));
    }

    public static JsonObject toJsonExtra(int scale) {
        JsonObject json = new JsonObject();
        json.put("windScale", scale);
        json.put("windKmh", toKmh(scale));
        return json;
    }

    public static String formatKmh(double kmh) {
        if (Double.isNaN(kmh)) {
            return Text.tr("nowweather.wind_scale.unknown");
        }
        return String.format(Locale.ROOT, "%.0f km/h", kmh);
    }
}

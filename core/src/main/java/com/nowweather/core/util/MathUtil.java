package com.nowweather.core.util;

/** 数值工具。核心逻辑（温度曲线、随机噪声、插值）全部走这里，保证可测。 */
public final class MathUtil {

    private MathUtil() {
    }

    public static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    public static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    public static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    public static long clamp(long value, long min, long max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double inverseLerp(double a, double b, double value) {
        if (Math.abs(b - a) < 1.0E-9) {
            return 0.0D;
        }
        return clamp((value - a) / (b - a), 0.0D, 1.0D);
    }

    /** 平滑插值（3t²-2t³）。 */
    public static double smoothstep(double t) {
        double x = clamp(t, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    /** 永远返回非负余数。 */
    public static int floorMod(int value, int mod) {
        int r = value % mod;
        return r < 0 ? r + mod : r;
    }

    public static long floorMod(long value, long mod) {
        long r = value % mod;
        return r < 0 ? r + mod : r;
    }

    public static double round(double value, int decimals) {
        double factor = Math.pow(10.0D, decimals);
        return Math.round(value * factor) / factor;
    }

    /**
     * 确定性哈希（SplitMix64 的 finalizer），用于「世界种子 + 天数 + 生物群系」→ 可复现的随机流。
     * 服务端与客户端用同一个种子即可得到同一个预报，避免额外同步。
     */
    public static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long hash(String text) {
        long h = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            h = 31 * h + text.charAt(i);
        }
        return h;
    }

    /** 由若干整数/字符串组合出的确定性种子。 */
    public static long seedOf(Object... parts) {
        long h = 0xC0FFEE123456789L;
        for (Object part : parts) {
            if (part == null) {
                h = mix(h ^ 0x5DEECE66DL);
            } else if (part instanceof Number n) {
                h = mix(h ^ Double.doubleToLongBits(n.doubleValue()));
            } else {
                h = mix(h ^ hash(part.toString()));
            }
        }
        return h;
    }

    /**
     * 基于种子的 0..1 伪随机数（可复现，无状态）。
     *
     * @param seed  基础种子
     * @param salt  位置/序号盐值
     */
    public static double random01(long seed, long salt) {
        long m = mix(seed ^ mix(salt));
        // 取高 53 位映射到 [0,1)
        return (m >>> 11) * 0x1.0p-53;
    }

    public static double randomRange(long seed, long salt, double min, double max) {
        return min + (max - min) * random01(seed, salt);
    }

    /** 以给定概率返回 true（确定性）。 */
    public static boolean chance(long seed, long salt, double probability) {
        return random01(seed, salt) < clamp(probability, 0.0D, 1.0D);
    }
}

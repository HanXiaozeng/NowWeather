package com.nowweather.core.climate;

import com.nowweather.core.util.MathUtil;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 距海岸距离与「内陆度」修正后的季节温度振幅。
 *
 * <p>离线推算最大的误差来源是<b>季节振幅</b>：同一纬度上，海洋性气候的年较差可以只有
 * 大陆性气候的三分之一（实测：西雅图 47.6°N 距海 75km 振幅 7.4K，
 * 而同纬度的法戈 46.9°N 距海 1210km 振幅 17.1K）。只按纬度估振幅，
 * 实测 R² 仅 0.53；加入「距最近海洋的距离 d」后 R² 升到 0.84，
 * 留一交叉验证 RMSE 从 4.45K 降到 2.5K（约 40%）。</p>
 *
 * <p>因此本类做两件事：</p>
 * <ol>
 *   <li>用一张 <b>8.1 kB</b> 的 1° 陆海位掩码算出「到大圆上最近海洋格」的距离 {@code d}（单位 km）；</li>
 *   <li>按实测拟合式给出季节振幅 {@code A = sin(|lat|)·(9.1 + 18.1·d/(d+400))}。</li>
 * </ol>
 *
 * <p>掩码来源：Natural Earth 1:110m {@code ne_110m_land}（<b>公有领域</b>，可自由再分发）。
 * 数据格式、位序、许可与精度代价见
 * {@code core/src/main/resources/data/nowweather/geo/README.md}；
 * {@code d} 的规范性定义（D1~D10）与标定表见
 * {@code docs/research/offline-weather-synthesis.md} §2.4 方案 D 与
 * {@code docs/research/continentality-calibration.csv}。</p>
 *
 * <h2>已知失效区（不要把本模型当作到处都对）</h2>
 * <p>46 站标定的残差大多在 ±2K 内，但三类地方明显失效，测试里已把它们写成
 * 「<b>已知不允许通过</b>」的用例：</p>
 * <ul>
 *   <li><b>东亚季风型东岸</b>：东京 +4.7K、哈利法克斯 +4.8K —— 沿岸却有大年较差
 *       （冬季大陆冷气团），而模型只看「离海多远」，看不出这个是东岸；</li>
 *   <li><b>极端大陆性</b>：雅库茨克 +10.4K —— 西伯利亚冷极的大陆性远超 d 所暗示的程度；</li>
 *   <li><b>热带高原</b>：巴西利亚 −4.5K —— 低纬度下「内陆度」这个概念本身就开始失效。</li>
 * </ul>
 * <p>如果实现成本和收益允许，正确的下一步是让这些群系在气候数据里显式覆盖
 * {@code seasonalAmplitude}，而不是继续调这条公式。</p>
 */
public final class CoastDistance {

    /** 位掩码资源路径（1° / 180×360 / 8100 字节）。 */
    public static final String MASK_RESOURCE = "/data/nowweather/geo/landmask1deg.bin";

    /** 掩码分辨率（度）。 */
    public static final double CELL_DEGREES = 1.0D;

    /** 规范性步长：径向搜索每步 25 km（见定义 D4）。 */
    public static final double STEP_KM = 25.0D;

    /** 规范性方位数：72 个（0°, 5°, …, 355°）。 */
    public static final int BEARINGS = 72;

    /** 规范性半径上限：2500 km。南极内陆会取到这个值（有意为之）。 */
    public static final double MAX_KM = 2500.0D;

    /** 地球平均半径（WGS84），km。 */
    private static final double EARTH_RADIUS_KM = 6371.0088D;

    /** 拟合式系数（46 站留一交叉验证；两条独立推导路线给出的值一致到 0.25K 以内）。 */
    public static final double AMPLITUDE_BASE = 9.1D;
    public static final double AMPLITUDE_SCALE = 18.1D;

    /** {@code sat(d)} 的半饱和距离：d 远大于它之后继续远离海岸不会再增大振幅。 */
    public static final double SATURATION_KM = 400.0D;

    /** 南北极除外的最大 |lat|，避免 sin 在极点附近出现无意义的极值。 */
    private static final double MAX_ABS_LATITUDE = 89.5D;

    /** 距离缓存上限：超过就整体清空（离线推算的调用频率很低，不需要真正的 LRU）。 */
    private static final int CACHE_LIMIT = 8192;

    private static volatile byte[] mask;
    private static volatile boolean loadAttempted;
    private static final Map<Long, Double> DISTANCE_CACHE = new ConcurrentHashMap<>();

    private CoastDistance() {
    }

    /** 掩码是否可用（不可用时调用方应退回「只按纬度」的旧模型）。 */
    public static boolean isAvailable() {
        return loadMask() != null;
    }

    private static byte[] loadMask() {
        byte[] local = mask;
        if (local != null) {
            return local;
        }
        if (loadAttempted) {
            return mask;
        }
        synchronized (CoastDistance.class) {
            loadAttempted = true;
            try (InputStream in = CoastDistance.class.getResourceAsStream(MASK_RESOURCE)) {
                if (in == null) {
                    return null;
                }
                byte[] raw = in.readAllBytes();
                int expected = 180 * 360 / 8;
                if (raw.length != expected) {
                    // 尺寸不对说明资源被换过：宁可退回旧模型，也不要拿着一半的掩码算错距离
                    return null;
                }
                mask = raw;
                return raw;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 该格是否为陆地（定义 D2：行 0 在最北、列 0 在最西、字节内 MSB first）。 */
    public static boolean isLand(double latitude, double longitude) {
        byte[] raw = loadMask();
        if (raw == null) {
            return true;
        }
        int index = bitIndex(latitude, longitude);
        return ((raw[index >> 3] >> (7 - (index & 7))) & 1) != 0;
    }

    /** 坐标 → 位下标（定义 D2）。 */
    static int bitIndex(double latitude, double longitude) {
        int nlat = 180;
        int nlon = 360;
        int row = (int) Math.floor((90.0D - latitude) / CELL_DEGREES);
        double wrapped = ((longitude + 180.0D) % 360.0D + 360.0D) % 360.0D;
        int col = (int) Math.floor(wrapped / CELL_DEGREES);
        row = MathUtil.clamp(row, 0, nlat - 1);
        col = MathUtil.clamp(col, 0, nlon - 1);
        return row * nlon + col;
    }

    /**
     * 到最近海洋的距离（km），按规范 D1~D10 实现。
     *
     * <p>注意定义的是「探针点落入海洋格即停止」，也就是<b>到海洋格边缘</b>的距离，
     * 而不是「到最近海洋格中心」。后者会让一个真正临海的城市得到 d ≈ 111km，
     * 在纬度 45° 处凭空多出约 2.5K 的振幅。</p>
     *
     * @return 距离（km）；掩码不可用时返回 {@link Double#NaN}
     */
    public static double distanceToOceanKm(double latitude, double longitude) {
        if (!isAvailable()) {
            return Double.NaN;
        }
        double lat = MathUtil.clamp(latitude, -90.0D, 90.0D);
        long key = cacheKey(lat, longitude);
        Double cached = DISTANCE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        double result = computeDistance(lat, longitude);
        if (DISTANCE_CACHE.size() >= CACHE_LIMIT) {
            DISTANCE_CACHE.clear();
        }
        DISTANCE_CACHE.put(key, result);
        return result;
    }

    /** 缓存键：量化到 0.25°，避免「同一地点每次刷新都重算」。 */
    private static long cacheKey(double latitude, double longitude) {
        long latQ = Math.round((latitude + 90.0D) * 4.0D);
        long lonQ = Math.round(((longitude + 540.0D) % 360.0D) * 4.0D);
        return latQ * 100000L + lonQ;
    }

    private static double computeDistance(double latitude, double longitude) {
        if (!isLand(latitude, longitude)) {
            return 0.0D;
        }
        double phi1 = Math.toRadians(latitude);
        double lambda1 = Math.toRadians(longitude);
        double sinPhi1 = Math.sin(phi1);
        double cosPhi1 = Math.cos(phi1);
        for (double radius = STEP_KM; radius <= MAX_KM; radius += STEP_KM) {
            double delta = radius / EARTH_RADIUS_KM;
            double sinDelta = Math.sin(delta);
            double cosDelta = Math.cos(delta);
            for (int k = 0; k < BEARINGS; k++) {
                double bearing = Math.toRadians(360.0D * k / BEARINGS);
                double phi2 = Math.asin(sinPhi1 * cosDelta
                        + cosPhi1 * sinDelta * Math.cos(bearing));
                double lambda2 = lambda1 + Math.atan2(
                        Math.sin(bearing) * sinDelta * cosPhi1,
                        cosDelta - sinPhi1 * Math.sin(phi2));
                double probeLat = Math.toDegrees(phi2);
                double probeLon = (Math.toDegrees(lambda2) + 540.0D) % 360.0D - 180.0D;
                if (!isLand(probeLat, probeLon)) {
                    return radius;
                }
            }
        }
        return MAX_KM;
    }

    /** 饱和函数 {@code sat(d) = d/(d+400)}：d 很大之后继续远离海岸不再显著增大振幅。 */
    public static double saturation(double distanceKm) {
        double d = Math.max(0.0D, distanceKm);
        return d / (d + SATURATION_KM);
    }

    /**
     * 季节振幅（半振幅，单位 K）：{@code A = sin(|lat|)·(9.1 + 18.1·sat(d))}。
     *
     * @param absLatitude   纬度绝对值（度）
     * @param distanceKm    到最近海洋的距离（km）
     */
    public static double seasonalAmplitudeC(double absLatitude, double distanceKm) {
        double lat = MathUtil.clamp(absLatitude, 0.0D, MAX_ABS_LATITUDE);
        return Math.sin(Math.toRadians(lat)) * (AMPLITUDE_BASE + AMPLITUDE_SCALE * saturation(distanceKm));
    }

    /** 清空距离缓存（测试用）。 */
    public static void clearCache() {
        DISTANCE_CACHE.clear();
    }
}

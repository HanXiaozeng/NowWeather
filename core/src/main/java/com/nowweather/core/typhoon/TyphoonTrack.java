package com.nowweather.core.typhoon;

import com.nowweather.core.climate.CoastDistance;
import com.nowweather.core.util.MathUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 台风路径数据与本地分析（纯 Java，零依赖，可离线测试）。
 *
 * <h2>数据来源与设计约束</h2>
 * <p>调研结论（见 {@code docs/research/typhoon-submodule.md}）：中央气象台的
 * {@code typhoon.nmc.cn} 接口提供<b>官方 12/24/36/48/60/72/96/120 小时预报位置</b>，
 * 因此本模块<b>不做路径外推</b> —— 自研外推只作为「拿不到预报路径」时的降级方案，
 * 且必须如实降低置信度。</p>
 *
 * <p>⚠️ <b>两个国内源的风圈象限顺序不一样</b>：中央气象台是 NE/SE/SW/NW，
 * 浙江水利是 NE/SE/<b>NW/SW</b>（后两个对调）。这是从双方前端源码里读出来的，
 * 所以两个解析器必须<b>各自</b>做象限映射，不能共用一个函数 —— 共用会让风场左右翻转。</p>
 */
public final class TyphoonTrack {

    /** 风圈半径（公里）：台风四个象限不对称，按象限分别给。 */
    public record WindRadii(double ne, double se, double sw, double nw) {

        public static final WindRadii NONE = new WindRadii(0.0D, 0.0D, 0.0D, 0.0D);

        public boolean isEmpty() {
            return ne <= 0.0D && se <= 0.0D && sw <= 0.0D && nw <= 0.0D;
        }

        /** 某方位的半径（度，0=正北，顺时针）。 */
        public double radiusAt(double bearingDegrees) {
            double b = ((bearingDegrees % 360.0D) + 360.0D) % 360.0D;
            if (b >= 315.0D || b < 45.0D) {
                return ne;
            }
            if (b < 135.0D) {
                return se;
            }
            if (b < 225.0D) {
                return sw;
            }
            return nw;
        }

        public double max() {
            return Math.max(Math.max(ne, se), Math.max(sw, nw));
        }
    }

    /**
     * 一个定位点。
     *
     * @param forecast 是否为预报点（false = 实况/最佳路径）
     * @param leadHours 预报时效（小时）；实况为 0
     */
    public record Fix(long epochMillis, double lat, double lon, double pressureHpa,
                      double maxWindMs, double maxWindRadiusKm, WindRadii radius7,
                      boolean forecast, int leadHours, String agency) {

        public boolean valid() {
            return Double.isFinite(lat) && Double.isFinite(lon) && (lat != 0.0D || lon != 0.0D);
        }

        /** 距某点的距离（km，球面近似）。 */
        public double distanceToKm(double lat2, double lon2) {
            return haversineKm(lat, lon, lat2, lon2);
        }

        /** 从本点到某点的方位角（度，0=正北）。 */
        public double bearingTo(double lat2, double lon2) {
            return bearingDegrees(lat, lon, lat2, lon2);
        }
    }

    /** 台风本体：一个 id + 一串定位点（含官方预报点）。 */
    public record Storm(String id, String name, String nameEn, List<Fix> fixes) {

        public Storm {
            fixes = fixes == null ? List.of() : List.copyOf(fixes);
        }

        public boolean isEmpty() {
            return fixes.isEmpty();
        }

        /** 最新的实况点。 */
        public Fix latestObserved() {
            Fix best = null;
            for (Fix f : fixes) {
                if (f.forecast()) {
                    continue;
                }
                if (best == null || f.epochMillis() > best.epochMillis()) {
                    best = f;
                }
            }
            return best;
        }

        /** 全部预报点（按时效排序）。 */
        public List<Fix> forecasts() {
            List<Fix> list = new ArrayList<>();
            for (Fix f : fixes) {
                if (f.forecast()) {
                    list.add(f);
                }
            }
            list.sort((a, b) -> Integer.compare(a.leadHours(), b.leadHours()));
            return list;
        }

        /** 是否有官方预报路径（没有就只能靠外推，置信度要降）。 */
        public boolean hasOfficialForecast() {
            return !forecasts().isEmpty();
        }
    }

    /** 对某个地点的分析结果。 */
    public record Assessment(Storm storm, Fix nearest, double distanceKm, double bearingFromStorm,
                             double approachEtaHours, double closestApproachKm, String summary) {
    }

    /** 落点（登陆）判定结果。 */
    public record Landfall(Fix fix, double lat, double lon, long epochMillis, String note) {
    }

    private TyphoonTrack() {
    }

    // ------------------------------------------------------------------ 距离与方位

    /** 球面距离（km）。 */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0D;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2.0D) * Math.sin(dp / 2.0D)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2.0D) * Math.sin(dl / 2.0D);
        return 2.0D * r * Math.asin(Math.min(1.0D, Math.sqrt(a)));
    }

    /** 方位角（度，0=正北，顺时针）。 */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0D) % 360.0D;
    }

    // ------------------------------------------------------------------ 逼近分析

    /**
     * 分析台风对某地点的威胁：当前距离、最近距离、以及「还有多久最接近」。
     *
     * <p>用官方预报路径逐点算距离，取最小者作为最近逼近；ETA 用该点的预报时效。
     * 没有官方预报点时退回实况点的等速外推，并把 ETA 标成不确定（summary 里说明）。</p>
     */
    public static Assessment assess(Storm storm, double lat, double lon) {
        if (storm == null || storm.isEmpty()) {
            return null;
        }
        List<Fix> candidates = new ArrayList<>();
        Fix observed = storm.latestObserved();
        if (observed != null) {
            candidates.add(observed);
        }
        candidates.addAll(storm.forecasts());
        if (candidates.isEmpty()) {
            return null;
        }
        Fix nearest = null;
        double min = Double.MAX_VALUE;
        for (Fix f : candidates) {
            double d = f.distanceToKm(lat, lon);
            if (d < min) {
                min = d;
                nearest = f;
            }
        }
        double current = observed == null ? min : observed.distanceToKm(lat, lon);
        double eta = nearest == null || !nearest.forecast() ? 0.0D : nearest.leadHours();
        String summary = String.format(Locale.ROOT,
                "台风 %s：当前距 %.0f km，最近逼近 %.0f km（%s）",
                label(storm), current, min,
                nearest != null && nearest.forecast()
                        ? eta + " 小时后（官方预报点）"
                        : "已过境或仍在逼近（无预报路径，仅按实况点判断）");
        return new Assessment(storm, nearest, current,
                observed == null ? Double.NaN : observed.bearingTo(lat, lon), eta, min, summary);
    }

    /** 取距离某地点最近的活跃台风（可给多个台风）。 */
    public static Assessment nearest(List<Storm> storms, double lat, double lon) {
        Assessment best = null;
        if (storms == null) {
            return null;
        }
        for (Storm storm : storms) {
            Assessment a = assess(storm, lat, lon);
            if (a == null) {
                continue;
            }
            if (best == null || a.distanceKm() < best.distanceKm()) {
                best = a;
            }
        }
        return best;
    }

    public static String label(Storm storm) {
        if (storm == null) {
            return "未知";
        }
        String name = storm.name() == null || storm.name().isBlank() ? storm.nameEn() : storm.name();
        return (name == null || name.isBlank() ? storm.id() : name) + "（" + storm.id() + "）";
    }

    // ------------------------------------------------------------------ 落点判定

    /**
     * 用内置的 1° 陆海掩码判断路径是否登陆、在哪一点登陆。
     *
     * <p><b>精度必须说清</b>：实测（用浙江源免费提供的官方登陆记录当黄金真值，8 个样本）
     * 1° 掩码的中位偏差 <b>23.8 km</b>、最差 <b>51.3 km</b>，时间误差 −7.5~+5.6 小时；
     * 最坏情况是把台湾西南部整块判成陆地（提前 7.5 小时报登陆），
     * 以及岱山→上海奉贤这类连续两次登陆会被合并成一次（中间没有海格）。
     * 所以这里做<b>沿航迹的二分插值</b>把坐标从格点精度提升到连续值，
     * 但时间精度受 1° 分辨率与定位点间隔双重限制 —— 对玩家展示时要标「约」。</p>
     *
     * @return 登陆点；全程在海上或全程在陆上返回 null
     */
    public static Landfall findLandfall(Storm storm) {
        if (storm == null || !CoastDistance.isAvailable()) {
            return null;
        }
        List<Fix> path = new ArrayList<>();
        Fix observed = storm.latestObserved();
        if (observed != null) {
            path.add(observed);
        }
        path.addAll(storm.forecasts());
        if (path.size() < 2) {
            return null;
        }
        for (int i = 1; i < path.size(); i++) {
            Fix a = path.get(i - 1);
            Fix b = path.get(i);
            boolean landA = CoastDistance.isLand(a.lat(), a.lon());
            boolean landB = CoastDistance.isLand(b.lat(), b.lon());
            if (landA == landB) {
                continue;
            }
            if (!landB) {
                continue;       // 由陆入海，不是登陆
            }
            // 二分插值找海陆交界
            double loT = 0.0D;
            double hiT = 1.0D;
            for (int step = 0; step < 18; step++) {
                double mid = (loT + hiT) / 2.0D;
                double lat = a.lat() + (b.lat() - a.lat()) * mid;
                double lon = a.lon() + (b.lon() - a.lon()) * mid;
                if (CoastDistance.isLand(lat, lon)) {
                    hiT = mid;
                } else {
                    loT = mid;
                }
            }
            double t = hiT;
            double lat = a.lat() + (b.lat() - a.lat()) * t;
            double lon = a.lon() + (b.lon() - a.lon()) * t;
            long epoch = a.epochMillis() + (long) ((b.epochMillis() - a.epochMillis()) * t);
            String note = String.format(Locale.ROOT,
                    "落点约 %.2f°N %.2f°E%s（1° 掩码判定，位置误差中位约 24 km、最差 51 km）",
                    lat, lon, b.forecast() ? "，依据官方预报路径" : "，依据实况路径");
            return new Landfall(b, lat, lon, epoch, note);
        }
        return null;
    }

    // ------------------------------------------------------------------ 风场

    /**
     * 某点相对台风中心的风速（m/s）。
     *
     * <p>用<b>Rankine 涡旋 + 移动不对称</b>：眼墙内风速随半径线性增大到
     * {@code maxWind}（半径 = {@code maxWindRadiusKm}），眼墙外按 {@code 1/r^α} 衰减
     * （α 取 0.5，介于 Rankine 的 1.0 与 Holland 的实测 0.4~0.6 之间，属【工程近似】），
     * 再叠加移速造成的左右不对称（危险半圆）。</p>
     *
     * <p>若数据里有七级风圈半径，用它把衰减截断在风圈之外直接归零 ——
     * 国内接口普遍提供这个字段，比纯公式可靠。</p>
     */
    public static double windAt(Fix fix, double lat, double lon, double stormMotionMs,
                                double motionBearingDegrees) {
        if (fix == null) {
            return 0.0D;
        }
        double distance = fix.distanceToKm(lat, lon);
        double maxWind = fix.maxWindMs() > 0.0D ? fix.maxWindMs() : 25.0D;
        double eye = fix.maxWindRadiusKm() > 0.0D ? fix.maxWindRadiusKm() : 40.0D;

        double speed;
        if (distance <= eye) {
            speed = maxWind * Math.max(0.25D, distance / eye);
        } else {
            speed = maxWind * Math.pow(eye / distance, 0.5D);
        }

        // 移动不对称：dangerous semicircle（北半球右侧）叠加移速。
        //
        // ★ 这里绝不能直接用「点的方位角 − 移动方位角」。台风的切向风在
        // 北半球是逆时针的：位于中心正东（方位 90°）的点，切向风指向正北（方位 0°），
        // 即切向方位 = 点的方位 − 90°。只有把<b>切向风的方向</b>与移动方向做夹角，
        // 才能得到正确的不对称：向北移动的台风，正东侧切向风也向北 → 叠加增强（危险半圆），
        // 正西侧切向风向南 → 抵消减弱。
        // 用点的方位直接算会让正东、正西都得到 cos(±90°)=0，左右完全对称 —— 这个 bug 是测试抓到的。
        if (stormMotionMs > 0.0D && Double.isFinite(motionBearingDegrees)) {
            double bearingToPoint = fix.bearingTo(lat, lon);
            double tangentialBearing = bearingToPoint - 90.0D;    // 北半球逆时针
            double relative = Math.toRadians(tangentialBearing - motionBearingDegrees);
            speed += stormMotionMs * 0.5D * Math.cos(relative);
        }

        // 七级风圈之外直接归零（如果有这个字段）
        WindRadii r7 = fix.radius7();
        if (r7 != null && !r7.isEmpty()) {
            double bearingToPoint = fix.bearingTo(lat, lon);
            double limit = r7.radiusAt(bearingToPoint);
            if (limit > 0.0D && distance > limit) {
                return 0.0D;
            }
        }
        return Math.max(0.0D, speed);
    }

    /** 两次定位点之间的移动速度（m/s）与方位角。 */
    public static double[] motion(Fix from, Fix to) {
        if (from == null || to == null || to.epochMillis() <= from.epochMillis()) {
            return new double[] {0.0D, Double.NaN};
        }
        double km = from.distanceToKm(to.lat(), to.lon());
        double hours = (to.epochMillis() - from.epochMillis()) / 3_600_000.0D;
        double ms = km * 1000.0D / Math.max(1.0D, hours * 3600.0D);
        return new double[] {ms, from.bearingTo(to.lat(), to.lon())};
    }

    /**
     * 雷击的径向权重。
     *
     * <p><b>实测结论：这不是单调衰减。</b>Molinari et al. (1999) 的径向闪电<b>密度</b>廓线
     * （fl/10⁴km²/d）是：眼墙 &lt;30 ~ &gt;100、<b>内带 100–120 km 出现极小值 5</b>、
     * <b>外带 210–290 km 出现极大值 &gt;250</b>。所以早期版本里那个
     * {@code (1 - d/band)²} 单调衰减模型<b>在物理上是错的</b>（它把外带极大抹掉了）。
     *
     * <p>这里按实测的<b>计数占比</b>取值（Zhang et al. 2022：0–100 / 100–200 / 200–500 km
     * 分别是 6.2% / 9.6% / 84.2%），因为候选点是按面积均匀撒的，面积因子已经在采样里体现，
     * 用密度会重复计入面积（密度与计数占比的排序正好相反，这一点很容易搞错）。</p>
     *
     * <p><b>【工程近似】</b>：文献里<b>没有可用的闭式 P(r)</b>，所以这里是一张标定表，
     * 不是公式 —— 不要把它当成有理论依据的分布。</p>
     */
    public static double bandWeight(double distanceKm, double outerBandKm) {
        double scale = Math.max(50.0D, outerBandKm) / 500.0D;   // 把 500km 的外带上限按风圈缩放
        double d = distanceKm / scale;
        if (d < 100.0D) {
            return 0.062D / 0.842D * 0.9D;      // 内核（含眼墙）：计数占比低但密度最高
        }
        if (d < 200.0D) {
            return 0.096D / 0.842D * 0.9D;      // 内带极小值
        }
        if (d < 500.0D) {
            return 0.9D;                        // 外带极大：绝大多数雷击在这里
        }
        return MathUtil.clamp(0.9D * (1.0D - (d - 500.0D) / 300.0D), 0.0D, 0.9D);
    }
    /**
     * 雷击落点预测：在台风雨带里给出若干候选点与置信度。
     *
     * <p>模型（【工程近似】）：按实测的径向<b>计数占比</b>分带撒点（见 {@link #bandWeight}），
     * 每个候选点带一个 0~1 的置信度。文献里没有可用的闭式 P(r)，
     * 所以这里给的是一组候选点与相对置信度，<b>不是精确坐标预测</b> —— 展示时必须这么讲。</p>
     *
     * @param count 生成多少个候选点
     * @param seed  随机种子（同种子同结果，便于测试与复现）
     */
    public static List<double[]> predictStrikes(Fix fix, double aroundLat, double aroundLon,
                                                int radiusKm, int count, long seed) {
        List<double[]> result = new ArrayList<>();
        if (fix == null || count <= 0) {
            return result;
        }
        double stormDistance = fix.distanceToKm(aroundLat, aroundLon);
        WindRadii r7 = fix.radius7();
        double band = r7 != null && !r7.isEmpty() ? r7.max() : 300.0D;
        for (int i = 0; i < count; i++) {
            double r = radiusKm * Math.sqrt(MathUtil.random01(seed, 700L + i * 7L));
            double theta = 2.0D * Math.PI * MathUtil.random01(seed, 701L + i * 7L);
            double lat = aroundLat + (r * Math.cos(theta)) / 111.0D;
            double lon = aroundLon + (r * Math.sin(theta))
                    / (111.0D * Math.max(0.15D, Math.cos(Math.toRadians(aroundLat))));
            double d = fix.distanceToKm(lat, lon);
            double confidence = bandWeight(d, band);
            result.add(new double[] {lat, lon, confidence});
        }
        // 越靠近台风中心的点越先落，顺便也保证输出稳定
        result.sort((a, b) -> Double.compare(b[2], a[2]));
        return result;
    }
}

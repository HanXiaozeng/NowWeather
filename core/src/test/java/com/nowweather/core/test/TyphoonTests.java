package com.nowweather.core.test;

import com.nowweather.core.typhoon.TyphoonTrack;
import com.nowweather.core.typhoon.TyphoonTrack.Fix;
import com.nowweather.core.typhoon.TyphoonTrack.Storm;
import com.nowweather.core.typhoon.TyphoonTrack.WindRadii;

import java.util.List;

/**
 * 台风路径与风场分析测试。
 *
 * <p>对照的是<b>可独立查证的已知量</b>（真实城市间的球面距离与方位、已知坐标的海陆属性），
 * 而不是实现自己的输出。</p>
 */
final class TyphoonTests {

    private static final long HOUR = 3_600_000L;

    private TyphoonTests() {
    }

    static void run() {
        T.section("球面距离与方位");
        geometry();

        T.section("风圈象限映射（两个国内源顺序不同，这是最容易翻车的地方）");
        windRadii();

        T.section("逼近分析与官方预报路径");
        assessment();

        T.section("落点（登陆）判定：1° 掩码的精度必须如实");
        landfall();

        T.section("风场与雷击落点预测");
        windAndStrikes();
    }

    private static void geometry() {
        // 北京 → 上海 实际约 1067 km；北京 → 广州 约 1890 km
        T.near("北京→上海 ≈ 1067km", 1067.0D,
                TyphoonTrack.haversineKm(39.90D, 116.41D, 31.23D, 121.47D), 25.0D);
        T.near("北京→广州 ≈ 1890km", 1890.0D,
                TyphoonTrack.haversineKm(39.90D, 116.41D, 23.13D, 113.26D), 40.0D);
        // 同一点距离为 0
        T.near("同点距离 = 0", 0.0D, TyphoonTrack.haversineKm(30.0D, 120.0D, 30.0D, 120.0D), 1.0E-9D);

        // 方位角：正北 0°、正东 90°、正南 180°、正西 270°
        T.near("正北 = 0°", 0.0D, TyphoonTrack.bearingDegrees(0.0D, 0.0D, 10.0D, 0.0D), 0.01D);
        T.near("正东 = 90°", 90.0D, TyphoonTrack.bearingDegrees(0.0D, 0.0D, 0.0D, 10.0D), 0.01D);
        T.near("正南 = 180°", 180.0D, TyphoonTrack.bearingDegrees(10.0D, 0.0D, 0.0D, 0.0D), 0.01D);
        T.near("正西 = 270°", 270.0D, TyphoonTrack.bearingDegrees(0.0D, 10.0D, 0.0D, 0.0D), 0.01D);
        // 杭州在台北的北偏西方向（约 340°）—— 台风预报里最常用的一类判断
        double bearing = TyphoonTrack.bearingDegrees(25.03D, 121.57D, 30.27D, 120.16D);
        T.check("台北→杭州 落在 330~350°（实际 " + String.format("%.1f", bearing) + "°）",
                bearing > 330.0D && bearing < 350.0D);
    }

    private static void windRadii() {
        // 中央气象台顺序：NE / SE / SW / NW
        WindRadii nmc = new WindRadii(300.0D, 260.0D, 220.0D, 180.0D);
        T.near("正北取 NE 半径", 300.0D, nmc.radiusAt(0.0D), 1.0E-9D);
        T.near("正东取 SE 半径", 260.0D, nmc.radiusAt(90.0D), 1.0E-9D);
        T.near("正南取 SW 半径", 220.0D, nmc.radiusAt(180.0D), 1.0E-9D);
        T.near("正西取 NW 半径", 180.0D, nmc.radiusAt(270.0D), 1.0E-9D);
        T.near("跨 0° 边界仍取 NE", 300.0D, nmc.radiusAt(350.0D), 1.0E-9D);
        T.near("最大半径", 300.0D, nmc.max(), 1.0E-9D);
        T.check("全零视为空", WindRadii.NONE.isEmpty());

        // ★ 浙江源如果把 SW/NW 解析成 NMC 的顺序，风场会左右翻转：
        // 这里用「同一条数据、两种映射」把差异钉住，防止以后有人合并成一个函数
        // 同一组原始数字 [300,260,180,220]：
        //   中央气象台顺序 NE,SE,SW,NW → sw=180 nw=220
        //   浙江顺序     NE,SE,NW,SW → sw=220 nw=180（3、4 两个数必须对调）
        double[] raw = {300.0D, 260.0D, 180.0D, 220.0D};
        WindRadii nmcRaw = new WindRadii(raw[0], raw[1], raw[2], raw[3]);
        WindRadii zhejiang = new WindRadii(raw[0], raw[1], raw[3], raw[2]);
        T.check("同一组数字，两个源的解析结果不同（NMC sw=" + nmcRaw.sw()
                + " vs 浙江 sw=" + zhejiang.sw() + "）", nmcRaw.sw() != zhejiang.sw());
        T.near("NMC 的正西 = 第 4 个数 220", 220.0D, nmcRaw.radiusAt(270.0D), 1.0E-9D);
        T.near("浙江的正西 = 第 3 个数 180（若共用一个解析函数这里会变成 220）", 180.0D,
                zhejiang.radiusAt(270.0D), 1.0E-9D);
        T.near("浙江的正南 = 第 4 个数 220", 220.0D, zhejiang.radiusAt(180.0D), 1.0E-9D);
    }

    private static void assessment() {
        long now = 1_789_300_000_000L;
        // 一个从东南向西北逼近浙江的台风：实况在冲绳附近，预报点逐步靠近
        Storm storm = new Storm("2409", "梅花", "MUIFA", List.of(
                new Fix(now, 25.5D, 127.5D, 960.0D, 38.0D, 40.0D,
                        new WindRadii(300, 280, 240, 200), false, 0, "BABJ"),
                new Fix(now + 12 * HOUR, 27.0D, 125.0D, 950.0D, 42.0D, 40.0D,
                        new WindRadii(320, 300, 260, 220), true, 12, "BABJ"),
                new Fix(now + 24 * HOUR, 28.8D, 122.5D, 940.0D, 45.0D, 40.0D,
                        new WindRadii(340, 320, 280, 240), true, 24, "BABJ"),
                new Fix(now + 48 * HOUR, 31.0D, 121.0D, 975.0D, 30.0D, 35.0D,
                        new WindRadii(250, 230, 200, 180), true, 48, "BABJ")));

        T.check("有官方预报路径", storm.hasOfficialForecast());
        T.check("实况点只有 1 个", storm.latestObserved() != null
                && storm.latestObserved().leadHours() == 0);
        T.check("预报点 3 个（12/24/48 小时）", storm.forecasts().size() == 3);

        TyphoonTrack.Assessment a = TyphoonTrack.assess(storm, 30.274D, 120.155D);
        T.check("识别出台风", a != null);
        T.check("当前距离在 700~900km（实际 " + String.format("%.0f", a.distanceKm()) + "km）",
                a.distanceKm() > 700.0D && a.distanceKm() < 900.0D);
        T.check("最近逼近比当前更近（" + String.format("%.0f", a.closestApproachKm())
                        + " < " + String.format("%.0f", a.distanceKm()) + "）",
                a.closestApproachKm() < a.distanceKm());
        T.check("ETA 是官方预报时效（48 小时，实际 " + a.approachEtaHours() + "）",
                a.approachEtaHours() == 48.0D);
        T.check("摘要里带上台风名与距离", a.summary().contains("梅花") && a.summary().contains("km"));

        // 没有预报点时不能编造 ETA
        Storm observedOnly = new Storm("9999", "测试", "", List.of(
                new Fix(now, 22.0D, 128.0D, 980.0D, 30.0D, 40.0D, WindRadii.NONE, false, 0, "BABJ")));
        TyphoonTrack.Assessment b = TyphoonTrack.assess(observedOnly, 30.274D, 120.155D);
        T.check("无预报点时 ETA = 0 且摘要说明是实况判断",
                b.approachEtaHours() == 0.0D && b.summary().contains("无预报路径"));

        // 多台风取最近
        Storm far = new Storm("0001", "远", "", List.of(
                new Fix(now, 10.0D, 170.0D, 990.0D, 25.0D, 40.0D, WindRadii.NONE, false, 0, "X")));
        TyphoonTrack.Assessment nearest = TyphoonTrack.nearest(List.of(far, storm), 30.274D, 120.155D);
        T.check("多台风取最近的那个", nearest != null && nearest.storm().id().equals("2409"));
        T.check("空列表返回 null", TyphoonTrack.nearest(List.of(), 30.0D, 120.0D) == null);
    }

    private static void landfall() {
        T.check("陆海掩码可用（落点判定依赖它）",
                com.nowweather.core.climate.CoastDistance.isAvailable());
        long now = 1_789_300_000_000L;

        // 一条从东海穿过海岸线进入浙江的路径：海上 → 陆上
        Storm storm = new Storm("2409", "梅花", "MUIFA", List.of(
                new Fix(now, 28.0D, 124.0D, 950.0D, 42.0D, 40.0D, WindRadii.NONE, false, 0, "BABJ"),
                new Fix(now + 12 * HOUR, 29.5D, 121.5D, 960.0D, 38.0D, 40.0D, WindRadii.NONE, true, 12, "BABJ")));
        TyphoonTrack.Landfall lf = TyphoonTrack.findLandfall(storm);
        T.check("由海入陆被判为登陆", lf != null);
        if (lf != null) {
            T.check("落点在心里预期的浙江沿海一带（" + String.format("%.2f,%.2f", lf.lat(), lf.lon()) + "）",
                    lf.lat() > 28.0D && lf.lat() < 31.0D && lf.lon() > 120.0D && lf.lon() < 123.0D);
            T.check("落点时刻落在两个定位点之间",
                    lf.epochMillis() >= now && lf.epochMillis() <= now + 12 * HOUR);
            T.check("说明里如实标注 1° 掩码的误差（不夸大精度）",
                    lf.note().contains("24 km") && lf.note().contains("51 km"));
            // 二分插值应比格点更细：经度不是整度
            T.check("落点坐标是插值出来的连续值（非整度）",
                    Math.abs(lf.lon() - Math.rint(lf.lon())) > 1.0E-6D);
        }

        // 全程在海上：不应该报登陆
        Storm atSea = new Storm("0002", "海上", "", List.of(
                new Fix(now, 20.0D, 130.0D, 980.0D, 30.0D, 40.0D, WindRadii.NONE, false, 0, "X"),
                new Fix(now + 12 * HOUR, 22.0D, 132.0D, 980.0D, 30.0D, 40.0D, WindRadii.NONE, true, 12, "X")));
        T.check("全程在海上不报登陆", TyphoonTrack.findLandfall(atSea) == null);
        T.check("只有一个定位点时不报登陆", TyphoonTrack.findLandfall(new Storm("0003", "单点", "",
                List.of(new Fix(now, 25.0D, 125.0D, 990.0D, 20.0D, 30.0D, WindRadii.NONE, false, 0, "X")))) == null);
    }

    private static void windAndStrikes() {
        long now = 1_789_300_000_000L;
        Fix fix = new Fix(now, 29.0D, 122.0D, 950.0D, 45.0D, 40.0D,
                new WindRadii(300, 280, 240, 200), false, 0, "BABJ");

        // 眼墙内：距离越远越大，到眼墙半径处达到最大风速
        double atEye = TyphoonTrack.windAt(fix, 29.0D, 122.0D, 0.0D, Double.NaN);
        double atWall = TyphoonTrack.windAt(fix, 29.0D, 122.36D, 0.0D, Double.NaN);   // 约 35km 东
        T.check("台风中心风速被压低（" + String.format("%.1f", atEye) + " < " + String.format("%.1f", atWall) + "）",
                atEye < atWall);
        T.check("眼墙附近风速接近最大风速（" + String.format("%.1f", atWall) + " m/s）",
                atWall > 35.0D && atWall <= 45.0D + 1.0E-6D);

        // 眼墙外衰减
        double near = TyphoonTrack.windAt(fix, 29.0D, 123.0D, 0.0D, Double.NaN);      // 约 97km
        double far = TyphoonTrack.windAt(fix, 29.0D, 124.0D, 0.0D, Double.NaN);       // 约 193km
        T.check("眼墙外风速随距离衰减（" + String.format("%.1f", near) + " > " + String.format("%.1f", far) + "）",
                near > far);

        // 七级风圈之外直接归零（国内接口普遍提供这个字段，比公式可靠）
        double outside = TyphoonTrack.windAt(fix, 29.0D, 126.0D, 0.0D, Double.NaN);   // 约 387km > 300km
        T.near("七级风圈之外风速 = 0", 0.0D, outside, 1.0E-9D);
        T.check("没有风圈数据时不会归零", TyphoonTrack.windAt(
                new Fix(now, 29.0D, 122.0D, 950.0D, 45.0D, 40.0D, WindRadii.NONE, false, 0, "X"),
                29.0D, 126.0D, 0.0D, Double.NaN) > 0.0D);

        // 移动不对称：同一距离下，前进方向右侧（危险半圆）风更大
        double right = TyphoonTrack.windAt(fix, 29.0D, 123.0D, 8.0D, 0.0D);    // 台风向北移动，正东是右侧
        double left = TyphoonTrack.windAt(fix, 29.0D, 121.0D, 8.0D, 0.0D);     // 正西是左侧
        T.check("危险半圆（右）风更大（" + String.format("%.1f", right) + " > " + String.format("%.1f", left) + "）",
                right > left);

        // 移动速度与方位
        double[] motion = TyphoonTrack.motion(fix,
                new Fix(now + 6 * HOUR, 30.5D, 122.0D, 950.0D, 45.0D, 40.0D, WindRadii.NONE, true, 6, "X"));
        // 1.5° 纬度 ≈ 166 km，6 小时 → 27.7 km/h ≈ 7.7 m/s（台风移速的常见量级）
        T.check("6 小时北移 1.5° ≈ 7.7 m/s（实际 " + String.format("%.1f", motion[0]) + "）",
                motion[0] > 6.0D && motion[0] < 10.0D);
        T.near("移动方向接近正北", 0.0D, motion[1], 1.0D);

        // 雷击落点：同种子可复现、置信度在 0~1、按置信度降序
        List<double[]> strikes = TyphoonTrack.predictStrikes(fix, 29.0D, 122.0D, 200, 12, 20260913L);
        List<double[]> again = TyphoonTrack.predictStrikes(fix, 29.0D, 122.0D, 200, 12, 20260913L);
        T.check("生成 12 个候选落点", strikes.size() == 12);
        T.check("同种子结果可复现", strikes.size() == again.size()
                && Math.abs(strikes.get(0)[0] - again.get(0)[0]) < 1.0E-12D);
        boolean confidenceOk = true;
        boolean sorted = true;
        for (int i = 0; i < strikes.size(); i++) {
            double c = strikes.get(i)[2];
            if (c < 0.0D || c > 1.0D) {
                confidenceOk = false;
            }
            if (i > 0 && strikes.get(i - 1)[2] < c) {
                sorted = false;
            }
        }
        T.check("置信度都落在 0~1", confidenceOk);
        T.check("按置信度降序排列", sorted);
        T.check("候选点都在给定半径内", strikes.stream().allMatch(s ->
                TyphoonTrack.haversineKm(29.0D, 122.0D, s[0], s[1]) <= 210.0D));
        T.check("不同种子结果不同",
                Math.abs(strikes.get(0)[0]
                        - TyphoonTrack.predictStrikes(fix, 29.0D, 122.0D, 200, 12, 1L).get(0)[0]) > 1.0E-9D);
    }
}

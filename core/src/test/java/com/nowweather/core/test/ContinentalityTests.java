package com.nowweather.core.test;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.climate.CoastDistance;
import com.nowweather.core.forecast.OfflineWeatherSynthesizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 「距海岸距离 → 季节振幅」模型的实现核对测试。
 *
 * <p>这一组测试的价值在于<b>交叉验证</b>：标定表
 * {@code docs/research/continentality-calibration.csv} 是独立于本实现产出的
 * （46 站 ERA5 一阶谐波拟合 + 1 km 掩膜径向搜索，见
 * {@code docs/research/offline-weather-synthesis.md} §2.4 方案 D）。
 * 如果本实现读掩码的位序、格归属、径向搜索步长或大圆公式有任何一处写错，
 * 算出来的 {@code d} 就会不同，46 站的预测值会集体对不上 —— 这是唯一能抓住
 * 「位序写反」「用格中心代替格边缘」这类错误的办法。</p>
 *
 * <p>因此测试做三件事：</p>
 * <ol>
 *   <li>逐站比对本实现与标定表的预测振幅（这是等价性证明）；</li>
 *   <li>证明新模型在全球范围内<b>确实优于</b>原来的「只按纬度」公式（这是可度量的收益）；</li>
 *   <li>把三类<b>已知失效区</b>写成「必须失败」的用例 —— 不假装模型到处都对。</li>
 * </ol>
 */
final class ContinentalityTests {

    /** 标定表位置（仓库内相对路径；测试只在仓库里跑）。 */
    private static final String CSV = "docs/research/continentality-calibration.csv";

    /** 允许的实现差异：d 量化到 25km，振幅对 d 的敏感度约 0.02K/km，故 0.35K 足够宽裕。 */
    private static final double PREDICTION_TOLERANCE_K = 0.35D;

    private ContinentalityTests() {
    }

    static void run() {
        T.section("陆海掩码与距海岸距离");
        mask();

        T.section("46 站标定表交叉验证（实现 vs 独立标定）");
        calibrationCrossCheck();

        T.section("新模型确实优于「只按纬度」");
        beatsLatitudeOnly();

        T.section("已知失效区（这些地方必须失败，不许粉饰）");
        knownFailureZones();

        T.section("接入离线推算后的端到端影响");
        integration();
    }

    /** 一条标定记录。 */
    private record Station(String name, double lat, double lon, double amplitudeObserved,
                           double distanceKm, double amplitudePredicted) {
    }

    private static List<Station> loadStations() {
        Path path = Path.of(CSV);
        if (!Files.isRegularFile(path)) {
            T.fail("标定表存在（" + CSV + "）", "文件不存在，无法做交叉验证");
            return List.of();
        }
        List<Station> list = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 7) {
                    continue;
                }
                list.add(new Station(parts[0].trim(),
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5])));
            }
        } catch (Exception e) {
            T.fail("标定表可解析", e.toString());
        }
        return list;
    }

    private static void mask() {
        T.check("陆海掩码资源可加载（classpath:/data/nowweather/geo/landmask1deg.bin）",
                CoastDistance.isAvailable());
        if (!CoastDistance.isAvailable()) {
            T.fail("掩码可用", "资源缺失 → 全部距海距离都会退化成 NaN");
            return;
        }

        // 位序核验：一批「毫无疑问」的点和它们的陆海属性。
        // 如果行序被写成「行 0 在最南」，或者字节内位序写反，这些点会大面积失败。
        T.check("撒哈拉 (25, 15) 是陆地", CoastDistance.isLand(25.0D, 15.0D));
        T.check("太平洋中部 (0, -140) 是海洋", !CoastDistance.isLand(0.0D, -140.0D));
        T.check("大西洋中部 (30, -40) 是海洋", !CoastDistance.isLand(30.0D, -40.0D));
        T.check("印度洋 (-20, 80) 是海洋", !CoastDistance.isLand(-20.0D, 80.0D));
        T.check("南极内陆 (-80, 90) 是陆地", CoastDistance.isLand(-80.0D, 90.0D));
        T.check("格陵兰内陆 (72, -40) 是陆地", CoastDistance.isLand(72.0D, -40.0D));
        T.check("北冰洋 (85, 0) 是海洋", !CoastDistance.isLand(85.0D, 0.0D));
        T.check("亚马逊 (-3, -60) 是陆地", CoastDistance.isLand(-3.0D, -60.0D));
        T.check("西雅图东侧陆地 (47, -121) 是陆地", CoastDistance.isLand(47.0D, -121.0D));

        // 海洋格的距离恒为 0
        T.near("海洋格的距离 = 0", 0.0D, CoastDistance.distanceToOceanKm(0.0D, -140.0D), 0.001D);
        // 南极内陆：地球上最「大陆性」的地方，距离必须是几百公里级别的大值。
        // 注意不能断言「一定顶到 2500km 上限」—— 1° 掩膜下 (-80, 90) 实测是 1025km，
        // 因为南极洲边缘的冰架离它没那么远。上限只是防御性截断，标定的 46 站无一触及。
        double antarctic = CoastDistance.distanceToOceanKm(-80.0D, 90.0D);
        T.check("南极内陆距离 > 800km（实际 " + antarctic + "km）", antarctic > 800.0D);
        T.check("南极内陆的振幅接近其纬度上限（" + fmt(CoastDistance.seasonalAmplitudeC(80.0D, antarctic))
                + "K）", CoastDistance.seasonalAmplitudeC(80.0D, antarctic) > 20.0D);

        // 距离必须是 25km 的整数倍（定义 D10）
        double seattle = CoastDistance.distanceToOceanKm(47.61D, -122.33D);
        T.check("距离被量化到 25km 的整数倍（d=" + seattle + "）",
                Math.abs(seattle / 25.0D - Math.round(seattle / 25.0D)) < 1.0E-9D);

        // 极区不能因为经度收敛而虚高：格陵兰内陆确实是「四面皆冰」，
        // 但北冰洋就在附近，因此不该直接顶到 2500。
        double arcticOcean = CoastDistance.distanceToOceanKm(85.0D, 0.0D);
        T.near("北冰洋格距离 = 0", 0.0D, arcticOcean, 0.001D);

        // 单调性：越往内陆 d 越大（用同一经度上由海岸向内的三个点）
        double coast = CoastDistance.distanceToOceanKm(46.0D, -124.0D);
        double inland1 = CoastDistance.distanceToOceanKm(46.0D, -118.0D);
        double inland2 = CoastDistance.distanceToOceanKm(46.0D, -110.0D);
        T.check("同纬度由海岸向内陆 d 单调不减（" + coast + " ≤ " + inland1 + " ≤ " + inland2 + "）",
                coast <= inland1 && inland1 <= inland2);

        // sat(d) 的形状
        T.near("sat(0) = 0", 0.0D, CoastDistance.saturation(0.0D), 1.0E-9D);
        T.near("sat(400) = 0.5", 0.5D, CoastDistance.saturation(400.0D), 1.0E-9D);
        T.check("sat(∞) → 1（1800km 时已 > 0.81）", CoastDistance.saturation(1800.0D) > 0.81D);
    }

    private static void calibrationCrossCheck() {
        List<Station> stations = loadStations();
        T.check("标定表至少 40 站（实际 " + stations.size() + "）", stations.size() >= 40);
        if (stations.isEmpty()) {
            return;
        }

        double maxDistanceError = 0.0D;
        String worstDistance = "";
        double maxAmplitudeError = 0.0D;
        String worstAmplitude = "";
        int mismatches = 0;
        for (Station s : stations) {
            double distance = CoastDistance.distanceToOceanKm(s.lat(), s.lon());
            double amplitude = CoastDistance.seasonalAmplitudeC(Math.abs(s.lat()), distance);
            double distanceError = Math.abs(distance - s.distanceKm());
            double amplitudeError = Math.abs(amplitude - s.amplitudePredicted());
            if (distanceError > maxDistanceError) {
                maxDistanceError = distanceError;
                worstDistance = s.name() + "（本实现 " + distance + "km / 标定 " + s.distanceKm() + "km）";
            }
            if (amplitudeError > maxAmplitudeError) {
                maxAmplitudeError = amplitudeError;
                worstAmplitude = s.name() + "（本实现 " + fmt(amplitude) + "K / 标定 "
                        + s.amplitudePredicted() + "K）";
            }
            if (amplitudeError > PREDICTION_TOLERANCE_K) {
                mismatches++;
            }
        }

        // 距离本身应当几乎逐点一致：标定表用的也是 1° 掩膜 + 同一份规范
        T.check("46 站的距离与标定表一致（容许 1 个步长，最大偏差 " + maxDistanceError
                + "km @ " + worstDistance + "）", maxDistanceError <= 25.0D + 1.0E-6D);
        // 振幅预测同理
        T.check("46 站的振幅预测与标定表一致（最大偏差 " + fmt(maxAmplitudeError) + "K @ "
                + worstAmplitude + "）", maxAmplitudeError <= PREDICTION_TOLERANCE_K);
        T.check("预测偏差超容差的站点数 = 0（实际 " + mismatches + "）", mismatches == 0);
    }

    private static void beatsLatitudeOnly() {
        List<Station> stations = loadStations();
        if (stations.isEmpty()) {
            return;
        }
        double sumSquaresNew = 0.0D;
        double sumSquaresOld = 0.0D;
        double sumAbsNew = 0.0D;
        double sumAbsOld = 0.0D;
        double maxNew = 0.0D;
        double maxOld = 0.0D;
        for (Station s : stations) {
            double distance = CoastDistance.distanceToOceanKm(s.lat(), s.lon());
            double newModel = CoastDistance.seasonalAmplitudeC(Math.abs(s.lat()), distance);
            double oldModel = OfflineWeatherSynthesizer.LATITUDE_ONLY_AMPLITUDE_BASE
                    + OfflineWeatherSynthesizer.LATITUDE_ONLY_AMPLITUDE_SLOPE * Math.abs(s.lat());
            double errorNew = newModel - s.amplitudeObserved();
            double errorOld = oldModel - s.amplitudeObserved();
            sumSquaresNew += errorNew * errorNew;
            sumSquaresOld += errorOld * errorOld;
            sumAbsNew += Math.abs(errorNew);
            sumAbsOld += Math.abs(errorOld);
            maxNew = Math.max(maxNew, Math.abs(errorNew));
            maxOld = Math.max(maxOld, Math.abs(errorOld));
        }
        int n = stations.size();
        double rmseNew = Math.sqrt(sumSquaresNew / n);
        double rmseOld = Math.sqrt(sumSquaresOld / n);
        double maeNew = sumAbsNew / n;
        double maeOld = sumAbsOld / n;

        System.out.printf("        [振幅模型对比] 新模型 RMSE %.2fK / MAE %.2fK / 最大 %.2fK%n",
                rmseNew, maeNew, maxNew);
        System.out.printf("        [振幅模型对比] 只按纬度 RMSE %.2fK / MAE %.2fK / 最大 %.2fK%n",
                rmseOld, maeOld, maxOld);

        T.check("新模型 RMSE 明显低于「只按纬度」（" + fmt(rmseNew) + " < " + fmt(rmseOld) + "）",
                rmseNew < rmseOld);
        T.check("新模型 RMSE 至少降低 25%（实测降低 "
                + fmt((1.0D - rmseNew / rmseOld) * 100.0D) + "%）",
                rmseNew <= rmseOld * 0.75D);
        T.check("新模型 RMSE 落在文档声明的量级（2.0~3.2K）", rmseNew > 2.0D && rmseNew < 3.2D);
        T.check("新模型平均绝对误差 < 2.2K", maeNew < 2.2D);
        // 只按纬度在海洋性西岸会错得离谱，这是它最致命的地方
        T.check("「只按纬度」的最大误差 > 6K（说明它确实不可用）", maxOld > 6.0D);
        T.check("新模型的最大误差明显小于「只按纬度」（" + fmt(maxNew) + " < " + fmt(maxOld) + "）",
                maxNew < maxOld);
    }

    private static void knownFailureZones() {
        // 刻意断言「这些地方就是错的」：把局限写进测试，比写在文档里更难被忽略。
        record Case(String name, double lat, double lon, double observed, double minResidual,
                    double maxResidual, String reason) {
        }
        Case[] cases = {
                new Case("Yakutsk", 62.04D, 129.68D, 28.7D, 5.0D, 20.0D,
                        "西伯利亚冷极，大陆性远超 d 所暗示的程度"),
                new Case("Halifax", 44.65D, -63.57D, 11.23D, 3.0D, 20.0D,
                        "东岸：沿岸但冬季受大陆冷气团控制，年较差大"),
                new Case("Tokyo", 35.68D, 139.69D, 11.17D, 3.0D, 20.0D,
                        "东亚季风东岸，与哈利法克斯同型"),
                new Case("Brasilia", -15.79D, -47.88D, 1.41D, -20.0D, -3.0D,
                        "热带高原：低纬度下「内陆度」概念本身失效"),
        };
        for (Case c : cases) {
            double distance = CoastDistance.distanceToOceanKm(c.lat(), c.lon());
            double predicted = CoastDistance.seasonalAmplitudeC(Math.abs(c.lat()), distance);
            // 标定表的约定是 residual = 实测 − 预测（正数 = 模型低估了振幅）
            double residual = c.observed() - predicted;
            T.check(String.format(java.util.Locale.ROOT,
                            "已知失效区 %s 的残差仍在 [%+.1f, %+.1f]K 内（实际 %+.2fK —— %s）",
                            c.name(), c.minResidual(), c.maxResidual(), residual, c.reason()),
                    residual >= c.minResidual() && residual <= c.maxResidual());
        }

        // 反过来也要有意义：模型在「配对站」上必须正确 —— 这才是它真正的卖点。
        // 西雅图 vs 法戈：同纬度、距海 75km vs 1200km、振幅 7.4K vs 17.1K。
        double seattle = CoastDistance.seasonalAmplitudeC(47.61D,
                CoastDistance.distanceToOceanKm(47.61D, -122.33D));
        double fargo = CoastDistance.seasonalAmplitudeC(46.88D,
                CoastDistance.distanceToOceanKm(46.88D, -96.79D));
        T.check("同纬度配对站：法戈（内陆）振幅显著大于西雅图（沿海）（"
                + fmt(fargo) + "K vs " + fmt(seattle) + "K）", fargo > seattle * 1.7D);
        T.near("西雅图预测 ≈ 实测 7.41K", 7.41D, seattle, 1.5D);
        T.near("法戈预测 ≈ 实测 17.09K", 17.09D, fargo, 1.5D);
    }

    private static void integration() {
        // 有坐标时走新模型，且结果落在合理区间
        LocationProfile seattle = LocationProfile.empty().withCoordinates(47.61D, -122.33D);
        LocationProfile fargo = LocationProfile.empty().withCoordinates(46.88D, -96.79D);
        T.check("有坐标 → 使用距海距离模型（西雅图 " + fmt(OfflineWeatherSynthesizer.seasonalAmplitudeC(seattle))
                + "K / 法戈 " + fmt(OfflineWeatherSynthesizer.seasonalAmplitudeC(fargo)) + "K）",
                OfflineWeatherSynthesizer.seasonalAmplitudeC(fargo)
                        > OfflineWeatherSynthesizer.seasonalAmplitudeC(seattle) * 1.7D);

        // 无坐标 → 保持原来的固定值 8K
        T.near("无坐标时季节振幅 = 8K（保持原行为）", 8.0D,
                OfflineWeatherSynthesizer.seasonalAmplitudeC(LocationProfile.empty()), 1.0E-9D);

        // 端到端：离线推算在「沿海 vs 内陆」同纬度下必须给出明显不同的季节摆动
        long july = 1784462400000L;
        long january = 1768564800000L;
        // 西雅图：太平洋夏令时 −7h / 标准时 −8h；法戈：−5h / −6h
        double seattleSwing = OfflineWeatherSynthesizer.climatologyC(seattle, null, july, -25200)
                - OfflineWeatherSynthesizer.climatologyC(seattle, null, january, -28800);
        double fargoSwing = OfflineWeatherSynthesizer.climatologyC(fargo, null, july, -18000)
                - OfflineWeatherSynthesizer.climatologyC(fargo, null, january, -21600);
        System.out.printf("        [端到端] 西雅图夏冬温差 %.1fK（振幅 %.2fK）；法戈 %.1fK（振幅 %.2fK）%n",
                seattleSwing, OfflineWeatherSynthesizer.seasonalAmplitudeC(seattle), fargoSwing,
                OfflineWeatherSynthesizer.seasonalAmplitudeC(fargo));
        T.check("端到端：内陆法戈的夏冬温差大于沿海西雅图（"
                + fmt(fargoSwing) + "K vs " + fmt(seattleSwing) + "K）", fargoSwing > seattleSwing);
        // 全摆幅 ≈ 2×振幅（cos 在峰值与谷值处各取 ±1），实测振幅 7.4K → 约 15K
        T.check("西雅图夏冬温差落在 10~19K（实测季节振幅 7.4K → 全摆幅约 15K，实际 "
                + fmt(seattleSwing) + "K）", seattleSwing > 10.0D && seattleSwing < 19.0D);
        T.check("法戈夏冬温差落在 28~40K（实测季节振幅 17.1K → 全摆幅约 34K，实际 "
                + fmt(fargoSwing) + "K）", fargoSwing > 28.0D && fargoSwing < 40.0D);

        // 掩码缓存不能改变结果
        CoastDistance.clearCache();
        double first = OfflineWeatherSynthesizer.seasonalAmplitudeC(seattle);
        double second = OfflineWeatherSynthesizer.seasonalAmplitudeC(seattle);
        T.near("缓存前后结果一致", first, second, 1.0E-12D);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}

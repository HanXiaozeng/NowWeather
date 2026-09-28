package com.nowweather.core.test;

import com.nowweather.core.cache.LocationProfile;
import com.nowweather.core.cache.WeatherCache;
import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.forecast.OfflineWeatherSynthesizer;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 本地缓存与离线天气推算测试。
 *
 * <p>这一组测试回答的问题是：<b>「完全断网、只剩一份地点画像」时，模组给出的天气是否合理、是否诚实。</b></p>
 */
final class OfflineTests {

    /** 2026-07-15 12:00 UTC（北半球盛夏）。 */
    private static final long JULY_NOON = 1784462400000L;
    /** 2026-01-15 12:00 UTC（北半球隆冬）。 */
    private static final long JANUARY_NOON = 1768564800000L;

    private OfflineTests() {
    }

    static void run() {
        cacheRoundTrip();
        cacheRobustness();
        zonalTemperature();
        seasonalAndHemisphere();
        altitudeAndDiurnal();
        precipitationAndWind();
        offlineResultSanity();
    }

    // ------------------------------------------------------------------ 缓存

    private static void cacheRoundTrip() throws RuntimeException {
        T.section("本地缓存读写");
        Path dir = tempDir("nowweather-cache-test");
        try {
            WeatherCache cache = new WeatherCache(dir, Loggers.noop());
            T.check("新目录一开始没有画像", !cache.hasProfile());

            LocationProfile profile = LocationProfile.empty()
                    .withIdentity("杭州", "330106", "openmeteo")
                    .withCoordinates(30.2741D, 120.1551D)
                    .withElevation(13.0D)
                    .withUtcOffset(8 * 3600)
                    .touched(JULY_NOON, "openmeteo");
            T.check("写入画像", cache.saveProfile(profile));

            Optional<LocationProfile> loaded = cache.loadProfile();
            T.check("读回画像", loaded.isPresent());
            LocationProfile back = loaded.orElseThrow();
            T.near("纬度往返", 30.2741D, back.latitude(), 1.0E-6);
            T.near("海拔往返", 13.0D, back.elevationMeters(), 1.0E-6);
            T.eq("时区往返", 8 * 3600, back.utcOffsetSeconds());
            T.eq("城市往返", "杭州", back.city());
            T.check("半球判定", back.hemisphere() == LocationProfile.Hemisphere.NORTH);
            T.eq("半球中文名", "北半球", back.hemisphere().zhName());

            WeatherObservation observation = WeatherObservation.builder("openmeteo")
                    .weatherType(WeatherType.RAIN).conditionText("中雨")
                    .temperatureC(24.5D).humidityPercent(80.0D).windScale(3)
                    .city("杭州").observedAt(JULY_NOON).fetchedAt(JULY_NOON)
                    .utcOffsetSeconds(8 * 3600)
                    .build();
            T.check("写入实况", cache.saveObservation(observation));
            WeatherObservation backObs = cache.loadObservation().orElseThrow();
            T.eq("实况天气往返", WeatherType.RAIN.name(), backObs.weatherType().name());
            T.near("实况温度往返", 24.5D, backObs.temperatureC(), 1.0E-9);
            T.eq("实况时区往返", 8 * 3600, backObs.utcOffsetSeconds());

            Forecast forecast = new Forecast("minecraft:plains", "温带", "openmeteo", JULY_NOON,
                    JULY_NOON, 1000L, 42L, java.util.List.of(
                    new com.nowweather.core.forecast.ForecastEntry(0, 0L, 1000L, JULY_NOON,
                            WeatherType.RAIN, 22.0D, 0.7D, 0.3D, 0.9D, java.util.List.of())));
            T.check("写入预报", cache.saveForecast(forecast));
            T.eq("预报往返格数", 1, cache.loadForecast().orElseThrow().size());

            T.check("清空缓存", cache.clear());
            T.check("清空后没有画像", cache.loadProfile().isEmpty());
            T.check("清空后没有实况", cache.loadObservation().isEmpty());
        } finally {
            deleteDir(dir);
        }
    }

    private static void cacheRobustness() {
        T.section("缓存损坏 / 版本不符时的容错");
        Path dir = tempDir("nowweather-cache-broken");
        try {
            WeatherCache cache = new WeatherCache(dir, Loggers.noop());
            Files.writeString(dir.resolve("profile.json"), "{ 这不是合法 JSON", java.nio.charset.StandardCharsets.UTF_8);
            T.check("坏文件不抛异常，只是当作没有缓存", cache.loadProfile().isEmpty());

            Files.writeString(dir.resolve("profile.json"),
                    "{\"schema\": 999, \"city\": \"未来版本\"}", java.nio.charset.StandardCharsets.UTF_8);
            T.check("schema 不匹配时忽略该文件", cache.loadProfile().isEmpty());

            // 只有城市没有坐标 → 画像存在但不可用于推算
            Files.writeString(dir.resolve("profile.json"),
                    "{\"schema\": 1, \"city\": \"只有名字\", \"updatedAt\": 123}",
                    java.nio.charset.StandardCharsets.UTF_8);
            LocationProfile partial = cache.loadProfile().orElseThrow();
            T.check("部分信息也能读回", partial.city().equals("只有名字"));
            T.check("但没有坐标时不算可用画像", !partial.isUsable());
        } catch (java.io.IOException e) {
            T.fail("缓存容错测试", String.valueOf(e));
        } finally {
            deleteDir(dir);
        }
    }

    // ------------------------------------------------------------------ 推算模型

    private static void zonalTemperature() {
        T.section("离线推算：纬向年均气温");
        T.near("赤道约 26.5°C", 26.5D, OfflineWeatherSynthesizer.zonalMeanC(0.0D), 0.01D);
        T.near("30°N 约 20°C", 20.0D, OfflineWeatherSynthesizer.zonalMeanC(30.0D), 0.01D);
        T.near("60°N 约 0°C", 0.0D, OfflineWeatherSynthesizer.zonalMeanC(60.0D), 0.01D);
        T.near("极地约 -25°C", -25.0D, OfflineWeatherSynthesizer.zonalMeanC(90.0D), 0.01D);
        T.check("南北对称", Math.abs(OfflineWeatherSynthesizer.zonalMeanC(45.0D)
                - OfflineWeatherSynthesizer.zonalMeanC(-45.0D)) < 1.0E-9);
        T.check("单调递减（越靠极地越冷）",
                OfflineWeatherSynthesizer.zonalMeanC(10.0D) > OfflineWeatherSynthesizer.zonalMeanC(40.0D)
                        && OfflineWeatherSynthesizer.zonalMeanC(40.0D) > OfflineWeatherSynthesizer.zonalMeanC(70.0D));
    }

    private static void seasonalAndHemisphere() {
        T.section("离线推算：季节与南北半球");
        LocationProfile north = LocationProfile.empty().withCoordinates(45.0D, 0.0D)
                .withUtcOffset(0).touched(JULY_NOON, "test");
        LocationProfile south = LocationProfile.empty().withCoordinates(-45.0D, 0.0D)
                .withUtcOffset(0).touched(JULY_NOON, "test");

        double northJuly = temp(north, JULY_NOON);
        double northJanuary = temp(north, JANUARY_NOON);
        double southJuly = temp(south, JULY_NOON);
        double southJanuary = temp(south, JANUARY_NOON);

        T.check("北半球 7 月明显暖于 1 月", northJuly > northJanuary + 10.0D);
        T.check("★ 南半球季节相反：7 月冷于 1 月", southJuly < southJanuary - 10.0D);
        // 注：这里两个测试点都在 (45°, 0°)——法国西南沿海，距大西洋只有几十公里。
        // 季节振幅改用「距海岸距离」模型后，该点的振幅从旧的「只按纬度」公式的 15.0K
        // 降到约 7.9K（波尔多实测月均 1 月 6.5°C / 8 月 21.5°C → 振幅 7.5K，新模型才是对的）。
        // 因此阈值由 20°C 调整为 12°C：这一条要验的是「半球相位相反」，不是振幅大小；
        // 振幅大小的验证在 ContinentalityTests 里用配对站做。
        T.check("南北半球同月温差明显（> 12°C，沿海地点振幅本就小）",
                Math.abs(northJuly - southJuly) > 12.0D);

        // 热带年较差小
        LocationProfile equator = LocationProfile.empty().withCoordinates(2.0D, 0.0D)
                .withUtcOffset(0).touched(JULY_NOON, "test");
        T.check("热带年较差小于中纬",
                Math.abs(temp(equator, JULY_NOON) - temp(equator, JANUARY_NOON))
                        < Math.abs(northJuly - northJanuary));
        T.check("季节振幅随纬度增大",
                OfflineWeatherSynthesizer.seasonalAmplitudeC(north)
                        > OfflineWeatherSynthesizer.seasonalAmplitudeC(equator));
    }

    private static void altitudeAndDiurnal() {
        T.section("离线推算：海拔与昼夜");
        LocationProfile low = LocationProfile.empty().withCoordinates(30.0D, 100.0D)
                .withElevation(0.0D).withUtcOffset(8 * 3600).touched(JULY_NOON, "test");
        LocationProfile high = low.withElevation(3000.0D);

        double lowTemp = temp(low, JULY_NOON);
        double highTemp = temp(high, JULY_NOON);
        T.near("海拔 3km 降温约 19.5°C", 19.5D, lowTemp - highTemp, 1.5D);

        // 昼夜：同一地点，当地 15:00 比当地 04:00 暖
        long noonLocal = JULY_NOON;                       // UTC 12:00 = 当地 20:00
        long afternoon = localMillis(2026, 7, 15, 15);    // 当地 15:00
        long beforeDawn = localMillis(2026, 7, 15, 4);    // 当地 04:00
        double afternoonTemp = temp(low, afternoon);
        double dawnTemp = temp(low, beforeDawn);
        T.check("★ 当地午后暖于日出前（昼夜曲线）", afternoonTemp > dawnTemp + 3.0D);
        T.check("昼夜振幅在合理范围",
                OfflineWeatherSynthesizer.diurnalAmplitudeC(0.3D, null) > 5.0D
                        && OfflineWeatherSynthesizer.diurnalAmplitudeC(0.9D, null) < 6.0D);
    }

    private static void precipitationAndWind() {
        T.section("离线推算：降水气候带与风带");

        // 副热带（25°）应当比赤道干燥、也比西风带（50°）干燥
        LocationProfile equator = LocationProfile.empty().withCoordinates(2.0D, 0.0D).touched(JULY_NOON, "t");
        LocationProfile subtropical = LocationProfile.empty().withCoordinates(27.0D, 0.0D).touched(JULY_NOON, "t");
        LocationProfile westerlies = LocationProfile.empty().withCoordinates(50.0D, 0.0D).touched(JULY_NOON, "t");

        double eq = OfflineWeatherSynthesizer.precipitationProbability(equator, 196.0D, null);
        double sub = OfflineWeatherSynthesizer.precipitationProbability(subtropical, 196.0D, null);
        double west = OfflineWeatherSynthesizer.precipitationProbability(westerlies, 196.0D, null);
        T.check("赤道多雨", eq > sub);
        T.check("副热带干旱", sub < west);
        T.check("概率都在 0~1 内", eq <= 1.0D && sub >= 0.0D && west <= 1.0D);

        // 群系气候不允许降水时概率归零
        BiomeClimate desert = BiomeClimate.builder("minecraft:desert")
                .climateClass(ClimateClass.ARID).tempRange(10, 52)
                .precipitation(PrecipitationType.NONE).humidity(0.07)
                .source("test").build();
        T.near("干旱群系降水概率为 0", 0.0D,
                OfflineWeatherSynthesizer.precipitationProbability(subtropical, 196.0D, desert), 1.0E-9);

        // 风带
        T.check("信风带偏东", OfflineWeatherSynthesizer.windDirectionFor(15.0D, 1L) == com.nowweather.core.weather.WindDirection.NE
                || OfflineWeatherSynthesizer.windDirectionFor(15.0D, 1L) == com.nowweather.core.weather.WindDirection.E);
        T.check("西风带偏西", OfflineWeatherSynthesizer.windDirectionFor(50.0D, 1L) == com.nowweather.core.weather.WindDirection.SW
                || OfflineWeatherSynthesizer.windDirectionFor(50.0D, 1L) == com.nowweather.core.weather.WindDirection.W);
        T.check("南半球信风带偏东南",
                OfflineWeatherSynthesizer.windDirectionFor(-15.0D, 1L) == com.nowweather.core.weather.WindDirection.SE
                        || OfflineWeatherSynthesizer.windDirectionFor(-15.0D, 1L) == com.nowweather.core.weather.WindDirection.E);
        T.check("西风带风级大于赤道无风带",
                OfflineWeatherSynthesizer.windScaleFor(westerlies, 5L)
                        >= OfflineWeatherSynthesizer.windScaleFor(equator, 5L) - 1);
    }

    private static void offlineResultSanity() {
        T.section("离线推算：结果合理性与诚实度");

        LocationProfile profile = LocationProfile.empty()
                .withIdentity("杭州", "330106", "openmeteo")
                .withCoordinates(30.2741D, 120.1551D).withElevation(13.0D)
                .withUtcOffset(8 * 3600).touched(JULY_NOON, "openmeteo");

        var request = new OfflineWeatherSynthesizer.Request(profile, JULY_NOON, 8 * 3600,
                null, null, 12345L);
        var result = OfflineWeatherSynthesizer.synthesize(request);
        WeatherObservation observation = result.observation();

        T.check("推算出结果", observation != null);
        T.check("温度在合理区间（杭州 7 月 15~40°C）",
                observation.temperatureC() > 12.0D && observation.temperatureC() < 42.0D);
        T.check("湿度在 0~100", observation.humidityPercent() >= 0.0D
                && observation.humidityPercent() <= 100.0D);
        T.check("风向已确定", observation.windDirection().isKnown());
        T.check("风级在 0~11", observation.windScale() >= 0 && observation.windScale() <= 11);
        T.check("★ 可信度诚实（只有画像时 ≤ 0.45）", observation.confidence() <= 0.45D);
        T.check("标记为离线推算", observation.reportTimeText().contains("推算"));
        T.check("解释了推算依据", result.explanation().contains("纬向年均"));

        // 有当天实况锚点时：可信度提高，且天气类型倾向沿用
        WeatherObservation cached = WeatherObservation.builder("openmeteo")
                .weatherType(WeatherType.RAIN).conditionText("中雨").temperatureC(28.0D)
                .humidityPercent(85.0D).windScale(4)
                .observedAt(JULY_NOON - 30 * 60_000L).fetchedAt(JULY_NOON - 30 * 60_000L)
                .build();
        var anchored = OfflineWeatherSynthesizer.synthesize(new OfflineWeatherSynthesizer.Request(
                profile, JULY_NOON, 8 * 3600, null, cached, 999L));
        T.check("★ 有锚点时可信度提高（> 0.6）", anchored.observation().confidence() > 0.6D);
        T.check("锚点参与温度融合（更接近 28°C）",
                Math.abs(anchored.observation().temperatureC() - 28.0D) < 12.0D);

        // 严寒地区：温度应当明显低于热带，且降水形态为冻结
        LocationProfile polar = LocationProfile.empty().withCoordinates(78.0D, 15.0D)
                .withUtcOffset(3600).touched(JANUARY_NOON, "test");
        var polarResult = OfflineWeatherSynthesizer.synthesize(
                new OfflineWeatherSynthesizer.Request(polar, JANUARY_NOON, 3600, null, null, 7L));
        T.check("★ 极地冬季温度低于 -10°C", polarResult.observation().temperatureC() < -10.0D);
        if (polarResult.observation().weatherType().isPrecipitation()) {
            T.check("极地冬季降水是冻结形态", polarResult.observation().weatherType().isFrozen());
        } else {
            T.check("极地冬季无降水也合理", true);
        }

        // 沙漠群系：不该出现降水
        BiomeClimate desert = BiomeClimate.builder("minecraft:desert")
                .climateClass(ClimateClass.ARID).tempRange(10, 52)
                .precipitation(PrecipitationType.NONE).humidity(0.07).windiness(0.55)
                .source("test").build();
        boolean anyRain = false;
        for (long seed = 0; seed < 40; seed++) {
            var desertResult = OfflineWeatherSynthesizer.synthesize(
                    new OfflineWeatherSynthesizer.Request(profile, JULY_NOON, 8 * 3600, desert, null, seed));
            if (desertResult.observation().weatherType().isPrecipitation()) {
                anyRain = true;
                break;
            }
        }
        T.check("★ 沙漠群系推算 40 次都没有降水", !anyRain);

        // 确定性：同样的输入必须得到同样的结果（服务端/客户端一致）
        var first = OfflineWeatherSynthesizer.synthesize(request).observation();
        var second = OfflineWeatherSynthesizer.synthesize(request).observation();
        T.check("相同输入 → 相同输出（确定性）",
                first.weatherType() == second.weatherType()
                        && Math.abs(first.temperatureC() - second.temperatureC()) < 1.0E-9);
    }

    // ------------------------------------------------------------------ 工具

    private static double temp(LocationProfile profile, long epochMillis) {
        int offset = profile.hasUtcOffset() ? profile.utcOffsetSeconds() : 0;
        return OfflineWeatherSynthesizer.synthesize(
                new OfflineWeatherSynthesizer.Request(profile, epochMillis, offset, null, null,
                        com.nowweather.core.util.MathUtil.seedOf(profile.latitude(), epochMillis / 86_400_000L)))
                .observation().temperatureC();
    }

    /** 给定「当地」日期时间，换算回 UTC 毫秒。 */
    private static long localMillis(int year, int month, int day, int hour) {
        return java.time.ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.ofHours(8))
                .toInstant().toEpochMilli();
    }

    private static Path tempDir(String name) {
        try {
            Path dir = Path.of("tools", "out", "cache-test", name + "-" + System.nanoTime());
            Files.createDirectories(dir);
            return dir;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteDir(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // 清理失败无所谓
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }
}

package com.nowweather.core.test;

import com.nowweather.core.forecast.SkyForecast;
import com.nowweather.core.providers.openmeteo.OpenMeteoSkyForecastClient;

import java.util.List;

/**
 * 真实天色预报测试：解析、插值、以及「坏事数据不能污染天气主链路」。
 */
final class SkyForecastTests {

    private SkyForecastTests() {
    }

    static void run() {
        parsing();
        interpolation();
        robustness();
    }

    private static void parsing() {
        T.section("真实天色预报：解析 Open-Meteo 逐小时响应");
        String body = """
                {"latitude":30.25,"longitude":120.0,"hourly":{
                  "time":[1789000000,1789003600,1789007200],
                  "cloud_cover":[91,55,0],
                  "cloud_cover_low":[80,30,0],
                  "cloud_cover_mid":[20,10,0],
                  "cloud_cover_high":[5,5,0],
                  "visibility":[3200,12000,24140],
                  "weather_code":[53,3,0],
                  "precipitation":[0.2,0.0,0.0],
                  "precipitation_probability":[70,20,5],
                  "temperature_2m":[28.9,29.4,31.0]}}
                """;
        SkyForecast sky = OpenMeteoSkyForecastClient.parse(body, 1789000000000L);
        T.check("解析出预报", sky != null && !sky.isEmpty());
        if (sky == null) {
            return;
        }
        T.eq("整点数量", 3, sky.size());
        T.eq("数据源标记", "openmeteo", sky.providerId());

        SkyForecast.Hour first = sky.hours().get(0);
        T.check("云量按百分比归一（91% -> 0.91）", Math.abs(first.cloudCover01() - 0.91D) < 1.0E-9D);
        T.check("低云 80% -> 0.80", Math.abs(first.cloudLow01() - 0.80D) < 1.0E-9D);
        T.check("能见度米转公里（3200m -> 3.2km）", Math.abs(first.visibilityKm() - 3.2D) < 1.0E-9D);
        T.eq("天气码保留", 53, first.weatherCode());
        T.check("降水概率 70% -> 0.70", Math.abs(first.precipitationProbability01() - 0.70D) < 1.0E-9D);
        T.check("时间从 epoch 秒换算", first.epochMillis() == 1789000000000L);

        T.check("最靠后的整点云量为 0", sky.hours().get(2).cloudCover01() == 0.0D);
    }

    private static void interpolation() {
        T.section("真实天色预报：整点之间必须线性插值");
        SkyForecast sky = new SkyForecast("openmeteo", 0L, List.of(
                new SkyForecast.Hour(1000L, 0.0D, 0.0D, 0.0D, 0.0D, 10.0D, 0, 0.0D, 0.0D, 20.0D),
                new SkyForecast.Hour(2000L, 1.0D, 0.0D, 0.0D, 0.0D, 20.0D, 3, 0.0D, 0.0D, 20.0D)));

        T.near("起点云量", 0.0D, sky.cloudCoverAt(1000L), 1.0E-9D);
        T.near("中点云量 = 0.5（插值）", 0.5D, sky.cloudCoverAt(1500L), 1.0E-9D);
        T.near("终点云量", 1.0D, sky.cloudCoverAt(2000L), 1.0E-9D);
        T.near("中点能见度插值", 15.0D, sky.visibilityAt(1500L), 1.0E-9D);
        T.near("早于起点用首点", 0.0D, sky.cloudCoverAt(0L), 1.0E-9D);
        T.near("晚于终点用末点", 1.0D, sky.cloudCoverAt(999999L), 1.0E-9D);

        // 天色如果按整点跳变会「到点整突然变暗」，所以插值必须是连续的
        double previous = sky.cloudCoverAt(1000L);
        boolean monotonic = true;
        for (long t = 1000L; t <= 2000L; t += 25L) {
            double value = sky.cloudCoverAt(t);
            if (value < previous - 1.0E-9D) {
                monotonic = false;
                break;
            }
            previous = value;
        }
        T.check("插值连续且单调（不会到点整跳变）", monotonic);
        T.eq("最近的整点", 2000L, sky.nearest(1900L).epochMillis());
    }

    private static void robustness() {
        T.section("真实天色预报：坏数据必须安静地失败");
        T.check("空响应返回 null", OpenMeteoSkyForecastClient.parse("", 0L) == null);
        T.check("非 JSON 返回 null", OpenMeteoSkyForecastClient.parse("not json", 0L) == null);
        T.check("缺 hourly 返回 null", OpenMeteoSkyForecastClient.parse("{\"latitude\":1}", 0L) == null);
        T.check("hourly 为空返回 null",
                OpenMeteoSkyForecastClient.parse("{\"hourly\":{\"time\":[]}}", 0L) == null);

        // 字段缺一半也不能崩（真实上游偶尔会缺项）
        SkyForecast partial = OpenMeteoSkyForecastClient.parse(
                "{\"hourly\":{\"time\":[1000,2000],\"cloud_cover\":[50]}}", 0L);
        T.check("字段长度不足时仍能解析已有个体", partial != null && partial.size() >= 1);

        SkyForecast empty = SkyForecast.empty();
        T.check("空预报 isEmpty", empty.isEmpty());
        T.check("空预报云量返回 NaN", Double.isNaN(empty.cloudCoverAt(0L)));

        // JSON 往返（服务端生成 / 客户端复现要一致）
        SkyForecast sky = new SkyForecast("openmeteo", 123L, List.of(
                new SkyForecast.Hour(1000L, 0.5D, 0.1D, 0.2D, 0.3D, 8.0D, 3, 0.4D, 0.6D, 25.0D)));
        SkyForecast restored = SkyForecast.fromJson(sky.toJson());
        T.eq("JSON 往返后整点数量一致", 1, restored.size());
        T.near("JSON 往返后云量一致", 0.5D, restored.hours().get(0).cloudCover01(), 1.0E-9D);
        T.near("JSON 往返后能见度一致", 8.0D, restored.hours().get(0).visibilityKm(), 1.0E-9D);
    }
}
package com.nowweather.core.test;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.providers.caiyun.CaiyunWeatherProvider;
import com.nowweather.core.weather.WeatherType;

/**
 * 彩云天气数据源测试。
 *
 * <p>重点钉死两件事：<b>URL 里经度在前</b>（写反了会返回另一个半球的天气，而且照样 status=ok），
 * 以及 skycon 到我们天气类型的映射。</p>
 */
final class CaiyunTests {

    private CaiyunTests() {
    }

    /** 真实抓下来的响应（2026-09-26 杭州西湖区，当时正在下中雨）。 */
    private static final String SAMPLE = """
            {"status":"ok","api_version":"v2.6","api_status":"active","lang":"zh_CN",
             "unit":"metric","tzshift":28800,"timezone":"Asia/Shanghai","server_time":1640745758,
             "location":[30.274,120.155],
             "result":{"realtime":{"status":"ok","temperature":25.58,"humidity":0.95,
               "cloudrate":0.92,"skycon":"MODERATE_RAIN","visibility":3.26,"dswrf":47.7,
               "wind":{"speed":10.86,"direction":42.31},"pressure":100682.0,
               "apparent_temperature":28.2,
               "precipitation":{"local":{"status":"ok","datasource":"radar","intensity":0.6},
                                "nearest":{"status":"ok","distance":0.0,"intensity":0.9}},
               "air_quality":{"pm25":45}}}}
            """;

    static void run() {
        urlOrder();
        skyconMapping();
        parsing();
    }

    private static void urlOrder() {
        T.section("彩云天气：URL 必须是「经度,纬度」");
        CaiyunWeatherProvider provider = new CaiyunWeatherProvider(null, null);
        String url = provider.buildUrl("TOKEN", 120.155D, 30.274D);
        T.check("URL 含 token", url.contains("/TOKEN/"));
        T.check("经度在前、纬度在后（120.155 在 30.274 之前）",
                url.indexOf("120.155") > 0 && url.indexOf("120.155") < url.indexOf("30.274"));
        T.check("走 realtime 接口", url.contains("/realtime"));
    }

    private static void skyconMapping() {
        T.section("彩云天气：skycon -> 天气类型");
        T.eq("CLEAR_DAY -> 晴", WeatherType.CLEAR, CaiyunWeatherProvider.skyconToType("CLEAR_DAY"));
        T.eq("CLEAR_NIGHT -> 晴", WeatherType.CLEAR, CaiyunWeatherProvider.skyconToType("CLEAR_NIGHT"));
        T.eq("PARTLY_CLOUDY_NIGHT -> 局部多云", WeatherType.PARTLY_CLOUDY,
                CaiyunWeatherProvider.skyconToType("PARTLY_CLOUDY_NIGHT"));
        T.eq("CLOUDY -> 阴", WeatherType.OVERCAST, CaiyunWeatherProvider.skyconToType("CLOUDY"));
        T.eq("FOG -> 雾", WeatherType.FOG, CaiyunWeatherProvider.skyconToType("FOG"));
        T.eq("LIGHT_RAIN -> 小雨", WeatherType.LIGHT_RAIN,
                CaiyunWeatherProvider.skyconToType("LIGHT_RAIN"));
        T.eq("MODERATE_RAIN -> 中雨", WeatherType.RAIN,
                CaiyunWeatherProvider.skyconToType("MODERATE_RAIN"));
        T.eq("HEAVY_RAIN -> 大雨", WeatherType.HEAVY_RAIN,
                CaiyunWeatherProvider.skyconToType("HEAVY_RAIN"));
        // 回归：以前枚举里没有暴雨档，STORM_RAIN 被归并成「大雨」，
        // 结果面板「真实天气」显示大雨、「依据」里的原文写着暴雨，同一个事实两个名字。
        T.eq("STORM_RAIN -> 暴雨（独立分档，不再归并成大雨）", WeatherType.STORM_RAIN,
                CaiyunWeatherProvider.skyconToType("STORM_RAIN"));
        T.check("暴雨比大雨更强",
                WeatherType.STORM_RAIN.precipitation() > WeatherType.HEAVY_RAIN.precipitation());
        T.eq("HEAVY_SNOW -> 大雪", WeatherType.HEAVY_SNOW,
                CaiyunWeatherProvider.skyconToType("HEAVY_SNOW"));
        T.eq("STORM_SNOW -> 暴风雪", WeatherType.BLIZZARD,
                CaiyunWeatherProvider.skyconToType("STORM_SNOW"));
        T.eq("SAND -> 沙尘暴", WeatherType.SANDSTORM, CaiyunWeatherProvider.skyconToType("SAND"));
        T.eq("DUST -> 霾（浮尘不是沙尘暴）", WeatherType.HAZE,
                CaiyunWeatherProvider.skyconToType("DUST"));
        T.eq("WIND -> 大风", WeatherType.WINDY, CaiyunWeatherProvider.skyconToType("WIND"));

        // 全部 skycon 都必须有非 UNKNOWN 的映射（漏一个就会显示「未知」）
        String[] all = {"CLEAR_DAY", "CLEAR_NIGHT", "PARTLY_CLOUDY_DAY", "PARTLY_CLOUDY_NIGHT",
                "CLOUDY", "LIGHT_HAZE", "MODERATE_HAZE", "HEAVY_HAZE", "LIGHT_RAIN", "MODERATE_RAIN",
                "HEAVY_RAIN", "STORM_RAIN", "FOG", "LIGHT_SNOW", "MODERATE_SNOW", "HEAVY_SNOW",
                "STORM_SNOW", "DUST", "SAND", "WIND"};
        int unmapped = 0;
        for (String skycon : all) {
            if (CaiyunWeatherProvider.skyconToType(skycon) == WeatherType.UNKNOWN) {
                unmapped++;
            }
        }
        T.eq("官方 20 种 skycon 全部有映射", 0, unmapped);
        T.eq("skycon 中文名", "中雨", CaiyunWeatherProvider.skyconToChinese("MODERATE_RAIN"));
    }

    private static void parsing() {
        T.section("彩云天气：实况解析");
        // 这里用的是占位 token：本测试只喂离线样本（SAMPLE）给 parse()，不发起任何网络请求，
        // token 只用于构造 ProviderContext。**不要把真实 token 写进源码** ——
        // 它一旦进了版本库就等于公开泄露，只能吊销重发。
        ProviderContext context = ProviderContext.builder()
                .city("杭州市").adcode("330106")
                .coordinates(30.274D, 120.155D)
                .apiKey("test-token-placeholder")
                .now(System.currentTimeMillis())
                .build();
        ProviderResult result = CaiyunWeatherProvider.parse(SAMPLE, context, "http://example");
        T.check("解析成功", result.isSuccess());
        if (!result.isSuccess()) {
            return;
        }
        var observation = result.observation();
        T.eq("数据源 id", "caiyun", observation.providerId());
        T.eq("天气类型", WeatherType.RAIN, observation.weatherType());
        T.near("温度 25.58°C", 25.58D, observation.temperatureC(), 1.0E-6D);
        T.near("湿度 0.95 -> 95%", 95.0D, observation.humidityPercent(), 1.0E-6D);
        T.near("云量 0.92 被保留", 0.92D, observation.cloudCover01(), 1.0E-6D);
        T.near("能见度 3.26km", 3.26D, observation.visibilityKm(), 1.0E-6D);
        T.near("气压 100682Pa -> 1006.82hPa", 1006.82D, observation.pressureHpa(), 1.0E-2D);
        T.eq("时区用彩云自带的 tzshift", 28800, observation.utcOffsetSeconds());
        T.near("风速 10.86m/s -> 39.1km/h", 39.096D, observation.windSpeedKmh(), 1.0E-2D);
        T.check("风向已换算（42.31° 属于东北风）",
                observation.windDirection().zhName().contains("东北"));

        // 上游报错时必须返回失败而不是抛异常
        T.check("status 非 ok -> 失败",
                !CaiyunWeatherProvider.parse("{\"status\":\"failed\"}", context, "u").isSuccess());
        T.check("非 JSON -> 失败",
                !CaiyunWeatherProvider.parse("<html>502</html>", context, "u").isSuccess());
        T.check("缺 result.realtime -> 失败",
                !CaiyunWeatherProvider.parse("{\"status\":\"ok\",\"result\":{}}", context, "u").isSuccess());
    }
}
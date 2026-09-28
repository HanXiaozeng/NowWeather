package com.nowweather.core.test;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProviderRegistry;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder;
import com.nowweather.core.providers.openmeteo.OpenMeteoWeatherProvider;
import com.nowweather.core.providers.openmeteo.WmoWeatherCode;
import com.nowweather.core.providers.uapipro.UApiProWeatherProvider;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;

/** Open-Meteo 数据源测试（WMO 代码映射 / 响应解析 / 地理编码 / 回退链顺序）。 */
final class OpenMeteoTests {

    /**
     * 地形订正的关键一环：把目标海拔传给数据源。
     *
     * <p>实测（2026-09 对官方 API 逐项核对）Open-Meteo 的 {@code elevation} 参数会自己
     * 按 6.5 K/km 平移气温与露点、并按气压高度公式下推气压，拿到手的就是已订正到当地高度的数据。
     * 这比我们自己推更可信，也要求本地地形订正在这种情况下让路（否则会二次递减）。</p>
     */
    private static void elevationParameter() {
        T.section("地形订正：把目标海拔传给数据源");
        T.FakeTransport transport = T.fakeTransport(SAMPLE_CURRENT);
        new OpenMeteoWeatherProvider(transport).fetch(ProviderContext.builder()
                .coordinates(30.274D, 120.155D)
                .elevationMeters(1500.0D)
                .build());
        T.check("URL 带上了 elevation=1500", transport.lastUrl().contains("elevation=1500"));

        T.FakeTransport plain = T.fakeTransport(SAMPLE_CURRENT);
        new OpenMeteoWeatherProvider(plain).fetch(ProviderContext.builder()
                .coordinates(30.274D, 120.155D)
                .build());
        T.check("未指定海拔时不加 elevation 参数", !plain.lastUrl().contains("elevation="));

        // 多坐标请求的响应是顶层数组：旧实现当成对象解析会误报「解析失败」
        T.FakeTransport arrayTransport = T.fakeTransport("[" + SAMPLE_CURRENT + "]");
        ProviderResult arrayResult = new OpenMeteoWeatherProvider(arrayTransport)
                .fetch(ProviderContext.builder().coordinates(30.274D, 120.155D).build());
        T.check("顶层数组响应也能解析（多坐标请求的形态）", arrayResult.isSuccess());
    }

    /** 真实响应（2026-09-13 实测，杭州）。 */
    private static final String SAMPLE_CURRENT = """
            {"latitude":30.263618,"longitude":120.14051,"generationtime_ms":0.15,
             "utc_offset_seconds":28800,"timezone":"Asia/Shanghai","timezone_abbreviation":"GMT+8",
             "elevation":13.0,
             "current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C",
               "relative_humidity_2m":"%","precipitation":"mm","rain":"mm","snowfall":"cm",
               "weather_code":"wmo code","cloud_cover":"%","wind_speed_10m":"km/h",
               "wind_direction_10m":"°","surface_pressure":"hPa"},
             "current":{"time":"2026-09-13T18:15","interval":900,"temperature_2m":28.6,
               "relative_humidity_2m":55,"precipitation":0.00,"rain":0.00,"snowfall":0.00,
               "weather_code":0,"cloud_cover":19,"wind_speed_10m":8.2,"wind_direction_10m":68,
               "surface_pressure":1015.5}}
            """;

    /** 真实地理编码响应（中文城市名）。 */
    private static final String SAMPLE_GEOCODE = """
            {"results":[{"id":1808926,"name":"杭州","latitude":30.29365,"longitude":120.16142,
              "elevation":12.0,"feature_code":"PPLA","country_code":"CN",
              "timezone":"Asia/Shanghai","population":9236032,"country":"中国",
              "admin1":"浙江","admin2":"杭州市"}]}
            """;

    private OpenMeteoTests() {
    }

    static void run() {
        wmoMapping();
        parseCurrent();
        geocoding();
        providerBehaviour();
        fallbackOrder();
        elevationParameter();
    }

    private static void wmoMapping() {
        T.section("WMO 天气代码映射");
        T.eq("0 晴", WeatherType.CLEAR.name(), String.valueOf(WmoWeatherCode.toWeatherType(0)));
        T.eq("1 晴间多云", WeatherType.MAINLY_CLEAR.name(), String.valueOf(WmoWeatherCode.toWeatherType(1)));
        T.eq("2 局部多云", WeatherType.PARTLY_CLOUDY.name(), String.valueOf(WmoWeatherCode.toWeatherType(2)));
        T.eq("3 阴", WeatherType.OVERCAST.name(), String.valueOf(WmoWeatherCode.toWeatherType(3)));
        T.eq("45 雾", WeatherType.FOG.name(), String.valueOf(WmoWeatherCode.toWeatherType(45)));
        T.eq("51 毛毛雨", WeatherType.DRIZZLE.name(), String.valueOf(WmoWeatherCode.toWeatherType(51)));
        T.eq("61 小雨", WeatherType.LIGHT_RAIN.name(), String.valueOf(WmoWeatherCode.toWeatherType(61)));
        T.eq("63 中雨", WeatherType.RAIN.name(), String.valueOf(WmoWeatherCode.toWeatherType(63)));
        T.eq("65 大雨", WeatherType.HEAVY_RAIN.name(), String.valueOf(WmoWeatherCode.toWeatherType(65)));
        T.eq("66 冻雨", WeatherType.FREEZING_RAIN.name(),
                String.valueOf(WmoWeatherCode.toWeatherType(66)));
        T.eq("71 小雪", WeatherType.LIGHT_SNOW.name(), String.valueOf(WmoWeatherCode.toWeatherType(71)));
        T.eq("75 大雪", WeatherType.HEAVY_SNOW.name(), String.valueOf(WmoWeatherCode.toWeatherType(75)));
        T.eq("77 米雪", WeatherType.LIGHT_SNOW.name(), String.valueOf(WmoWeatherCode.toWeatherType(77)));
        T.eq("82 强阵雨", WeatherType.HEAVY_RAIN.name(),
                String.valueOf(WmoWeatherCode.toWeatherType(82)));
        T.eq("86 大阵雪", WeatherType.HEAVY_SNOW.name(),
                String.valueOf(WmoWeatherCode.toWeatherType(86)));
        T.eq("95 雷阵雨", WeatherType.THUNDERSTORM.name(),
                String.valueOf(WmoWeatherCode.toWeatherType(95)));
        T.eq("96 雷阵雨伴冰雹", WeatherType.HAIL.name(), String.valueOf(WmoWeatherCode.toWeatherType(96)));
        T.eq("99 雷阵雨伴大冰雹", WeatherType.HAIL.name(), String.valueOf(WmoWeatherCode.toWeatherType(99)));
        T.check("未知代码返回 null", WmoWeatherCode.toWeatherType(1234) == null);
        T.check("中文描述可用", WmoWeatherCode.describe(95).contains("雷"));
        T.check("降水判定", WmoWeatherCode.isPrecipitation(65) && !WmoWeatherCode.isPrecipitation(0));
    }

    private static void parseCurrent() {
        T.section("Open-Meteo 实况解析（真实响应）");
        JsonObject root = JsonValue.parse(SAMPLE_CURRENT).asObject();
        WeatherObservation observation = OpenMeteoWeatherProvider.parseObservation(root,
                "浙江杭州", ProviderContext.of("杭州"));

        T.check("解析成功", observation != null);
        if (observation == null) {
            return;
        }
        T.eq("数据源 id", OpenMeteoWeatherProvider.ID, observation.providerId());
        T.eq("天气类型（代码 0）", WeatherType.CLEAR.name(), observation.weatherType().name());
        T.eq("天气文案", "晴", observation.conditionText());
        T.near("温度 28.6°C", 28.6D, observation.temperatureC(), 1.0E-9);
        T.near("湿度 55%", 55.0D, observation.humidityPercent(), 1.0E-9);
        T.near("气压 1015.5hPa", 1015.5D, observation.pressureHpa(), 1.0E-9);
        T.near("风速 8.2km/h", 8.2D, observation.windSpeedKmh(), 1.0E-9);
        T.check("风向 68° → 东北偏东", observation.windDirection() == WindDirection.ENE);
        T.check("风级由风速推导", observation.windScale() >= 0 && observation.windScale() <= 4);
        T.eq("报告时间", "2026-09-13T18:15", observation.reportTimeText());
        T.check("位置展示含杭州", observation.locationDisplay().contains("杭州"));
        T.check("可信度较高", observation.confidence() >= 0.9D);

        // WMO 报「晴」但实测有降水 → 以实测为准
        JsonObject mismatch = JsonValue.parse(SAMPLE_CURRENT.replace("\"precipitation\":0.00",
                "\"precipitation\":3.20")).asObject();
        WeatherObservation fixed = OpenMeteoWeatherProvider.parseObservation(mismatch, "杭州",
                ProviderContext.of("杭州"));
        T.check("代码与实测降水冲突时以实测为准", fixed != null && fixed.weatherType().isPrecipitation());

        // 下雪且温度低于冰点 → 冻结形态
        JsonObject snowy = JsonValue.parse(SAMPLE_CURRENT
                .replace("\"weather_code\":0", "\"weather_code\":71")
                .replace("\"temperature_2m\":28.6", "\"temperature_2m\":-3.5")
                .replace("\"snowfall\":0.00", "\"snowfall\":1.20")).asObject();
        WeatherObservation snow = OpenMeteoWeatherProvider.parseObservation(snowy, "哈尔滨",
                ProviderContext.of("哈尔滨"));
        T.check("雪天被识别为冻结形态", snow != null && snow.weatherType().isFrozen());
    }

    private static void geocoding() {
        T.section("Open-Meteo 地理编码");
        OpenMeteoGeocoder.Result result = OpenMeteoGeocoder.parse(SAMPLE_GEOCODE);
        T.check("地理编码解析成功", result != null && result.isValid());
        if (result == null) {
            return;
        }
        T.near("纬度", 30.29365D, result.latitude(), 1.0E-6);
        T.near("经度", 120.16142D, result.longitude(), 1.0E-6);
        T.eq("城市名", "杭州", result.name());
        T.check("展示名含省份", result.display().contains("浙江"));
        T.check("展示名含城市", result.display().contains("杭州"));

        // 直接写坐标
        OpenMeteoGeocoder.Result direct = OpenMeteoGeocoder.tryParseCoordinates("39.9,116.4");
        T.check("支持「纬度,经度」直接写法", direct != null && Math.abs(direct.latitude() - 39.9D) < 1.0E-9);
        T.check("非法坐标返回 null", OpenMeteoGeocoder.tryParseCoordinates("abc,def") == null);
        T.check("越界坐标返回 null", OpenMeteoGeocoder.tryParseCoordinates("200,300") == null);
        T.check("空地理编码响应返回 null", OpenMeteoGeocoder.parse("{\"results\":[]}") == null);
    }

    private static void providerBehaviour() {
        T.section("Open-Meteo 数据源行为");
        T.FakeTransport transport = T.fakeTransport(SAMPLE_CURRENT);
        OpenMeteoWeatherProvider provider = new OpenMeteoWeatherProvider(transport);

        // 有坐标：直接用
        ProviderResult byCoordinates = provider.fetch(ProviderContext.builder()
                .coordinates(30.2741D, 120.1551D).build());
        T.check("按坐标查询成功", byCoordinates.isSuccess());
        T.check("请求 URL 带 latitude", transport.requestedUrls().stream()
                .anyMatch(url -> url.contains("latitude=30.2741")));
        T.check("请求 URL 带 current 字段", transport.requestedUrls().stream()
                .anyMatch(url -> url.contains("weather_code")));
        T.check("诊断里记录了坐标", byCoordinates.diagnostics().containsKey("latitude"));

        // 既没有坐标也没有城市：交给下一个数据源
        ProviderResult noLocation = provider.fetch(ProviderContext.builder().build());
        T.eq("无坐标无城市 → NO_LOCATION", ProviderStatus.NO_LOCATION.name(), noLocation.status().name());
        T.check("NO_LOCATION 属于永久失败（不重试）", noLocation.status().isPermanentFailure());

        // 刷新间隔：20 分钟（与上游 15 分钟更新节奏匹配，也避免浪费免费配额）
        T.eq("最小刷新间隔 20 分钟", 20L * 60L * 1000L, provider.minIntervalMillis());
        T.eq("过期阈值 45 分钟", 45L * 60L * 1000L, provider.stalenessMillis());

        // 网络失败
        var failing = new OpenMeteoWeatherProvider(T.fakeTransport("")
                .failingWith(new com.nowweather.core.http.HttpException("超时")));
        T.eq("网络失败 → NETWORK_ERROR", ProviderStatus.NETWORK_ERROR.name(),
                failing.fetch(ProviderContext.builder().coordinates(1, 1).build()).status().name());

        // 上游 429
        var limited = new OpenMeteoWeatherProvider(T.fakeTransport("").status(429, "{}"));
        T.eq("429 → RATE_LIMITED", ProviderStatus.RATE_LIMITED.name(),
                limited.fetch(ProviderContext.builder().coordinates(1, 1).build()).status().name());
    }

    private static void fallbackOrder() {
        T.section("默认回退链顺序（Open-Meteo → UApiPro）");
        T.check("Open-Meteo 优先级高于 UApiPro",
                OpenMeteoWeatherProvider.PRIORITY < UApiProWeatherProvider.PRIORITY);

        WeatherProviderRegistry registry = new WeatherProviderRegistry();
        registry.register(new OpenMeteoWeatherProvider(T.fakeTransport(SAMPLE_CURRENT)));
        registry.register(new UApiProWeatherProvider(T.fakeTransport(ProviderTests.SAMPLE_RESPONSE)));
        T.eq("列表第一个是 Open-Meteo", OpenMeteoWeatherProvider.ID, registry.all().get(0).id());
        T.eq("列表第二个是 UApiPro", UApiProWeatherProvider.ID, registry.all().get(1).id());

        // 只给城市名（没有坐标）：Open-Meteo 要地理编码，这里没有网络 → 失败并回退到 UApiPro
        ProviderContext cityOnly = ProviderContext.of("北京");
        WeatherProviderRegistry.Attempt attempt = registry.attempt(cityOnly, null);
        T.check("Open-Meteo 拿不到坐标时回退到 UApiPro",
                attempt.hasObservation() && UApiProWeatherProvider.ID.equals(attempt.successProviderId()));
    }
}

package com.nowweather.core.test;

import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.api.ProviderStatus;
import com.nowweather.core.api.WeatherProvider;
import com.nowweather.core.api.WeatherProviderRegistry;
import com.nowweather.core.http.HttpException;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.providers.uapipro.UApiProConditionMapper;
import com.nowweather.core.providers.uapipro.UApiProWeatherProvider;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;
import com.nowweather.core.weather.WindDirection;
import com.nowweather.core.weather.WindScale;

/** UApiPro 数据源与回退链测试。 */
final class ProviderTests {

    /** 真实接口返回示例（2026 年实测）。 */
    static final String SAMPLE_RESPONSE = """
            {"province":"北京市","city":"北京","adcode":"110000","weather":"晴","weather_icon":"100",
             "temperature":30,"wind_direction":"西风","wind_power":"4级","humidity":16,"report_time":"11 分钟前发布"}
            """;

    private ProviderTests() {
    }

    static void run() {
        conditionMapping();
        windScaleParsing();
        parseSample();
        urlBuilding();
        errorHandling();
        fallbackChain();
    }

    private static void conditionMapping() {
        T.section("UApiPro 天气文本/图标映射");
        T.eq("晴 → CLEAR", WeatherType.CLEAR.name(),
                UApiProConditionMapper.map("晴", "100").name());
        T.eq("雷阵雨 → 雷暴", WeatherType.THUNDERSTORM.name(),
                UApiProConditionMapper.map("雷阵雨", "302").name());
        T.eq("强雷阵雨 → 强雷暴", WeatherType.SEVERE_THUNDERSTORM.name(),
                UApiProConditionMapper.map("强雷阵雨", "303").name());
        T.eq("暴雨 → 大雨", WeatherType.HEAVY_RAIN.name(),
                UApiProConditionMapper.map("暴雨", "310").name());
        T.eq("小雪 → 小雪", WeatherType.LIGHT_SNOW.name(),
                UApiProConditionMapper.map("小雪", "400").name());
        T.eq("暴雪 → 暴风雪", WeatherType.BLIZZARD.name(),
                UApiProConditionMapper.map("暴雪", "403").name());
        T.eq("雨夹雪 → 雨夹雪", WeatherType.SLEET.name(),
                UApiProConditionMapper.map("雨夹雪", "404").name());
        T.eq("冻雨 → 冻雨", WeatherType.FREEZING_RAIN.name(),
                UApiProConditionMapper.map("冻雨", "313").name());
        T.eq("霾 → 霾", WeatherType.HAZE.name(),
                UApiProConditionMapper.map("霾", "502").name());
        T.eq("浮尘 → 霾", WeatherType.HAZE.name(),
                UApiProConditionMapper.map("浮尘", "504").name());
        T.eq("沙尘暴 → 沙尘暴", WeatherType.SANDSTORM.name(),
                UApiProConditionMapper.map("沙尘暴", "507").name());
        T.eq("雾 → 雾", WeatherType.FOG.name(),
                UApiProConditionMapper.map("雾", "501").name());
        T.eq("阴 → 阴", WeatherType.OVERCAST.name(),
                UApiProConditionMapper.map("阴", "104").name());
        T.eq("晴间多云 → 晴间多云", WeatherType.MAINLY_CLEAR.name(),
                UApiProConditionMapper.map("晴间多云", "103").name());
        T.eq("仅有文本也能判断(中雨)", WeatherType.RAIN.name(),
                UApiProConditionMapper.map("中雨", "").name());
        T.eq("仅有图标也能判断(冰雹)", WeatherType.HAIL.name(),
                UApiProConditionMapper.map("", "304").name());
        T.eq("无法识别时回退 UNKNOWN", WeatherType.UNKNOWN.name(),
                UApiProConditionMapper.map("外星天气", "99999").name());
    }

    private static void windScaleParsing() {
        T.section("风力解析");
        T.eq("「4级」→ 4", 4, WindScale.parse("4级"));
        T.eq("「4-5级」→ 4", 4, WindScale.parse("4-5级"));
        T.eq("「七级」→ 7", 7, WindScale.parse("七级"));
        T.eq("空值 → -1", -1, WindScale.parse(""));
        T.check("4 级风速在合理区间", WindScale.toKmh(4) > 20 && WindScale.toKmh(4) < 39);
        T.near("归一化风级满值", 1.0D, WindScale.toNormalized(12), 1.0E-9);
        T.check("风向解析(西北风)", WindDirection.parse("西北风") == WindDirection.NW);
        T.check("风向解析(东风)", WindDirection.parse("东风") == WindDirection.E);
        T.check("风向解析(未知)", WindDirection.parse("上风口") == WindDirection.UNKNOWN);
    }

    private static void parseSample() {
        T.section("UApiPro 响应解析");
        JsonObject json = JsonValue.parse(SAMPLE_RESPONSE).asObject();
        WeatherObservation obs = UApiProWeatherProvider.parseObservation(json, ProviderContext.of("北京"));
        T.check("解析成功", obs != null);
        if (obs == null) {
            return;
        }
        T.eq("数据源 id", "uapipro", obs.providerId());
        T.eq("城市", "北京", obs.city());
        T.eq("省份", "北京市", obs.province());
        T.eq("adcode", "110000", obs.adcode());
        T.near("温度", 30.0D, obs.temperatureC(), 1.0E-9);
        T.near("湿度", 16.0D, obs.humidityPercent(), 1.0E-9);
        T.eq("天气类型", WeatherType.CLEAR.name(), obs.weatherType().name());
        T.eq("原始天气文本", "晴", obs.conditionText());
        T.eq("风级", 4, obs.windScale());
        T.check("风向", obs.windDirection() == WindDirection.W);
        T.check("归一化风速在 0~1", obs.windiness01() > 0 && obs.windiness01() < 1);
        T.check("位置展示", obs.locationDisplay().contains("北京"));
        T.check("面向玩家的描述含温度", obs.describeForPlayer().contains("30"));

        // 缺字段的响应
        WeatherObservation partial = UApiProWeatherProvider.parseObservation(
                JsonValue.parse("{\"weather\":\"多云\"}").asObject(), ProviderContext.of("上海"));
        T.check("部分字段也能解析", partial != null);
        if (partial != null) {
            T.eq("部分字段天气类型", WeatherType.PARTLY_CLOUDY.name(), partial.weatherType().name());
            T.check("缺温度时标记为未知", !partial.hasTemperature());
            T.check("缺湿度时归一化取 0.5", Math.abs(partial.humidity01() - 0.5D) < 1.0E-9);
        }

        T.check("完全无关的响应返回 null", UApiProWeatherProvider.parseObservation(
                JsonValue.parse("{\"code\":200}").asObject(), ProviderContext.of("北京")) == null);
    }

    private static void urlBuilding() {
        T.section("UApiPro 请求构造");
        UApiProWeatherProvider provider = new UApiProWeatherProvider(T.fakeTransport(SAMPLE_RESPONSE));

        String byAdcode = provider.buildUrl(ProviderContext.builder().adcode("330106").city("杭州").build());
        T.check("优先使用 adcode", byAdcode.contains("adcode=330106"));

        String byCity = provider.buildUrl(ProviderContext.of("北京"));
        T.check("城市名做 URL 编码", byCity.contains("city=%E5%8C%97%E4%BA%AC"));

        String noLocation = provider.buildUrl(ProviderContext.builder().build());
        T.check("无位置时走 IP 定位（不带参数）", noLocation != null && !noLocation.contains("?"));

        UApiProWeatherProvider noIp = new UApiProWeatherProvider(T.fakeTransport(SAMPLE_RESPONSE),
                "https://uapis.cn/api/v1/misc/weather", false, true);
        T.check("关闭 IP 定位后返回 null", noIp.buildUrl(ProviderContext.builder().build()) == null);
    }

    private static void errorHandling() {
        T.section("UApiPro 错误处理");
        UApiProWeatherProvider provider = new UApiProWeatherProvider(T.fakeTransport(SAMPLE_RESPONSE));

        // 网络失败
        var networkFail = new UApiProWeatherProvider(
                T.fakeTransport("").failingWith(new HttpException("连接超时")),
                "https://uapis.cn/api/v1/misc/weather", true, true);
        ProviderResult network = networkFail.fetch(ProviderContext.of("北京"));
        T.eq("网络失败状态", ProviderStatus.NETWORK_ERROR.name(), network.status().name());
        T.check("网络失败可重试", network.status().isRetryable());
        T.check("网络失败无观测", network.observation() == null);

        // 限流
        var rateLimited = new UApiProWeatherProvider(
                T.fakeTransport("").status(429, "{\"code\":429,\"msg\":\"请求过于频繁\"}"),
                "https://uapis.cn/api/v1/misc/weather", true, true);
        ProviderResult limited = rateLimited.fetch(ProviderContext.of("北京"));
        T.eq("429 → RATE_LIMITED", ProviderStatus.RATE_LIMITED.name(), limited.status().name());
        T.check("限流给出重试时间", limited.retryAfterMillis() > 0);

        // 上游业务错误
        var bizError = new UApiProWeatherProvider(
                T.fakeTransport("{\"code\":400,\"msg\":\"城市不存在\"}"),
                "https://uapis.cn/api/v1/misc/weather", true, true);
        ProviderResult biz = bizError.fetch(ProviderContext.of("不存在的城市"));
        T.eq("业务错误 → NO_DATA", ProviderStatus.NO_DATA.name(), biz.status().name());
        T.check("业务错误带原因", biz.message().contains("城市不存在"));

        // 非法 JSON
        var badJson = new UApiProWeatherProvider(T.fakeTransport("not json at all"),
                "https://uapis.cn/api/v1/misc/weather", true, true);
        ProviderResult bad = badJson.fetch(ProviderContext.of("北京"));
        T.eq("非法 JSON → PARSE_ERROR", ProviderStatus.PARSE_ERROR.name(), bad.status().name());

        // 正常
        ProviderResult ok = provider.fetch(ProviderContext.of("北京"));
        T.check("正常返回 OK", ok.isSuccess());
        T.check("诊断包含 URL", ok.diagnostics().containsKey("url"));
        T.check("URL 中不泄露密钥", !ok.toJson().toJson().contains("uapi-"));
    }

    private static void fallbackChain() {
        T.section("数据源回退链");
        WeatherProviderRegistry registry = new WeatherProviderRegistry();

        registry.register(new FailingProvider("primary", 10, ProviderStatus.NETWORK_ERROR));
        registry.register(new FailingProvider("secondary", 50, ProviderStatus.NO_DATA));
        registry.register(new SucceedingProvider("tertiary", 200, WeatherType.RAIN));

        ProviderContext ctx = ProviderContext.of("北京");
        WeatherProviderRegistry.Attempt attempt = registry.attempt(ctx, null);
        T.check("回退链最终成功", attempt.hasObservation());
        T.eq("成功来源为最低优先级的可用源", "tertiary", attempt.successProviderId());
        T.eq("记录了 3 次尝试", 3, attempt.attempts().size());
        T.eq("第一次尝试的状态", ProviderStatus.NETWORK_ERROR.name(), attempt.attempts().get(0).status().name());
        T.check("失败摘要包含全部数据源", attempt.failureSummary().contains("primary")
                && attempt.failureSummary().contains("secondary"));

        // 全部失败
        WeatherProviderRegistry allFail = new WeatherProviderRegistry();
        allFail.register(new FailingProvider("only", 10, ProviderStatus.HTTP_ERROR));
        WeatherProviderRegistry.Attempt none = allFail.attempt(ctx, null);
        T.check("全部失败时无观测", !none.hasObservation());

        // 空注册表
        WeatherProviderRegistry empty = new WeatherProviderRegistry();
        T.check("空注册表提示", empty.attempt(ctx, null).failureSummary().contains("没有注册"));

        // 优先级排序
        WeatherProviderRegistry ordered = new WeatherProviderRegistry();
        ordered.register(new SucceedingProvider("z", 900, WeatherType.CLEAR));
        ordered.register(new SucceedingProvider("a", 10, WeatherType.CLEAR));
        T.eq("按优先级排序", "a", ordered.all().get(0).id());

        // 禁用状态跳过
        WeatherProviderRegistry disabled = new WeatherProviderRegistry();
        disabled.register(new WeatherProvider() {
            @Override
            public String id() {
                return "off";
            }

            @Override
            public String displayName() {
                return "off";
            }

            @Override
            public boolean isEnabled(ProviderContext context) {
                return false;
            }

            @Override
            public ProviderResult fetch(ProviderContext context) {
                throw new AssertionError("被禁用的 provider 不应被调用");
            }
        });
        T.eq("禁用数据源状态", ProviderStatus.DISABLED.name(),
                disabled.attempt(ctx, null).attempts().get(0).status().name());
    }

    // ------------------------------------------------------------------ 测试替身

    private static final class FailingProvider implements WeatherProvider {
        private final String id;
        private final int priority;
        private final ProviderStatus status;

        private FailingProvider(String id, int priority, ProviderStatus status) {
            this.id = id;
            this.priority = priority;
            this.status = status;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ProviderResult fetch(ProviderContext context) {
            return ProviderResult.failure(id, status, "模拟失败");
        }
    }

    private static final class SucceedingProvider implements WeatherProvider {
        private final String id;
        private final int priority;
        private final WeatherType type;

        private SucceedingProvider(String id, int priority, WeatherType type) {
            this.id = id;
            this.priority = priority;
            this.type = type;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ProviderResult fetch(ProviderContext context) {
            return ProviderResult.ok(id, WeatherObservation.builder(id)
                    .weatherType(type)
                    .temperatureC(18.0D)
                    .humidityPercent(60.0D)
                    .windScale(3)
                    .city("北京")
                    .build());
        }
    }
}

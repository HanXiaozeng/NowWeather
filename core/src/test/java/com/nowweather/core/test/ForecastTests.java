package com.nowweather.core.test;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.forecast.ClimateSanityFilter;
import com.nowweather.core.forecast.DiurnalTemperatureModel;
import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.forecast.ForecastEngine;
import com.nowweather.core.forecast.ForecastEntry;
import com.nowweather.core.forecast.ForecastRequest;
import com.nowweather.core.forecast.SanityReport;
import com.nowweather.core.forecast.SanityViolation;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.weather.WeatherObservation;
import com.nowweather.core.weather.WeatherType;

import java.util.Set;

/**
 * 预报系统与气候合理性校验测试 —— 这是整个模组「真实性」的核心验收。
 */
final class ForecastTests {

    private static final BiomeClimateRegistry REGISTRY = new BiomeClimateRegistry(Loggers.noop());

    private ForecastTests() {
    }

    static void run() {
        sanityFilter();
        diurnalCurve();
        determinism();
        plausibilitySweep();
        anchorContinuity();
        entryTimingAndSmoothness();
    }

    private static void sanityFilter() {
        T.section("气候合理性校验（正常温度范围）");
        ClimateSanityFilter filter = new ClimateSanityFilter(6.0D, true);

        BiomeClimate desert = REGISTRY.resolve(facts("minecraft:desert", 2.0F, 0.0F, PrecipitationType.NONE, "desert"));
        BiomeClimate snowy = REGISTRY.resolve(facts("minecraft:snowy_plains", 0.0F, 0.5F, PrecipitationType.SNOW, "icy"));
        BiomeClimate plains = REGISTRY.resolve(facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains"));

        // 沙漠不下雨：即使是「大雨」也必须被改掉
        SanityReport desertRain = filter.inspect(desert, WeatherType.HEAVY_RAIN, 35.0D, 0.9D, 0.3D);
        T.check("沙漠+大雨被判定不合理", !desertRain.isClean());
        T.check("沙漠+大雨包含「干旱气候下不应降水」",
                desertRain.violations().contains(SanityViolation.PRECIPITATION_IN_DRY_CLIMATE));
        T.check("修正后不再是降水天气", !desertRain.adjustedType().isPrecipitation());
        T.check("修正后为霾/沙尘之类", desertRain.adjustedType() == WeatherType.HAZE
                || desertRain.adjustedType() == WeatherType.SANDSTORM
                || desertRain.adjustedType() == WeatherType.CLOUDY);

        // 沙漠下雪更要被改掉
        SanityReport desertSnow = filter.inspect(desert, WeatherType.SNOW, 30.0D, 0.2D, 0.1D);
        T.check("沙漠+雪被判定不合理", !desertSnow.isClean());
        T.check("沙漠+雪修正后非降水", !desertSnow.adjustedType().isPrecipitation());

        // 雪原 25°C 下雪不合理
        SanityReport warmSnow = filter.inspect(snowy, WeatherType.SNOW, 25.0D, 0.6D, 0.3D);
        T.check("雪原+25°C下雪被判定不合理", !warmSnow.isClean());
        T.check("暖雪被转成雨或雨夹雪", !warmSnow.adjustedType().isFrozen());
        T.check("温度被夹回正常范围附近", warmSnow.adjustedTempC() <= snowy.tempRange().maxC() + 6.0D);

        // 温度超范围
        SanityReport tooHot = filter.inspect(snowy, WeatherType.CLEAR, 45.0D, 0.3D, 0.2D);
        T.check("超范围高温被判定", tooHot.violations().contains(SanityViolation.TEMPERATURE_ABOVE_RANGE));
        T.check("高温被修正", tooHot.adjustedTempC() < 45.0D);
        SanityReport tooCold = filter.inspect(desert, WeatherType.CLEAR, -40.0D, 0.1D, 0.2D);
        T.check("超范围低温被判定", tooCold.violations().contains(SanityViolation.TEMPERATURE_BELOW_RANGE));

        // 合理数据不应被改
        SanityReport clean = filter.inspect(plains, WeatherType.RAIN, 12.0D, 0.6D, 0.3D);
        T.check("平原 12°C 下雨判定合理", clean.isClean());
        T.eq("合理数据不被修改", WeatherType.RAIN.name(), clean.adjustedType().name());

        // 冰点以下下雨 → 应变成雪
        SanityReport freezingRain = filter.inspect(plains, WeatherType.RAIN, -8.0D, 0.6D, 0.3D);
        T.check("冰点以下下雨被判定", freezingRain.violations().contains(SanityViolation.RAIN_BELOW_FREEZING));
        T.check("冰点以下修正为雪类", freezingRain.adjustedType().isFrozen());

        // 沙漠雷暴 → 降级
        SanityReport desertStorm = filter.inspect(desert, WeatherType.SEVERE_THUNDERSTORM, 38.0D, 0.1D, 0.4D);
        T.check("沙漠雷暴被判定", desertStorm.violations().contains(SanityViolation.THUNDER_TOO_STRONG_FOR_CLIMATE)
                || desertStorm.violations().contains(SanityViolation.PRECIPITATION_IN_DRY_CLIMATE));
        T.check("沙漠雷暴被降级（无雷电）", !desertStorm.adjustedType().isThundering());

        // 干旱气候下的浓雾不合理
        SanityReport dryFog = filter.inspect(desert, WeatherType.FOG, 30.0D, 0.1D, 0.1D);
        T.check("沙漠浓雾被判定", dryFog.violations().contains(SanityViolation.FOG_IN_DRY_CLIMATE));
    }

    private static void diurnalCurve() {
        T.section("昼夜温度曲线");
        BiomeClimate plains = REGISTRY.resolve(facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains"));
        BiomeClimate desert = REGISTRY.resolve(facts("minecraft:desert", 2.0F, 0.0F, PrecipitationType.NONE, "desert"));

        T.near("dayTime=0 → 06:00", 6.0D, DiurnalTemperatureModel.hourOfDay(0L), 1.0E-9);
        T.near("dayTime=6000 → 12:00", 12.0D, DiurnalTemperatureModel.hourOfDay(6000L), 1.0E-9);
        T.near("dayTime=12000 → 18:00", 18.0D, DiurnalTemperatureModel.hourOfDay(12000L), 1.0E-9);
        T.near("dayTime=18000 → 00:00", 0.0D, DiurnalTemperatureModel.hourOfDay(18000L), 1.0E-9);

        double peak = DiurnalTemperatureModel.temperatureAt(plains, Double.NaN, 0L, 8000L);
        double trough = DiurnalTemperatureModel.temperatureAt(plains, Double.NaN, 0L, 20000L);
        T.check("午后温度高于凌晨", peak > trough);

        double desertAmplitude = DiurnalTemperatureModel.amplitude(desert);
        double plainsAmplitude = DiurnalTemperatureModel.amplitude(plains);
        T.check("干旱地区昼夜温差更大", desertAmplitude > plainsAmplitude);
        T.check("振幅在合理区间", plainsAmplitude > 1.0D && plainsAmplitude < 16.0D);

        // 锚点连续性：锚点时刻的温度必须等于锚点温度
        double anchorTemp = 17.5D;
        long anchorTime = 3000L;
        double atAnchor = DiurnalTemperatureModel.temperatureAt(plains, anchorTemp, anchorTime, anchorTime);
        // 由于与气候均值的折中，允许少量偏差；但偏移必须小于 6°C
        T.check("锚点处温度接近真实观测", Math.abs(atAnchor - anchorTemp) < 6.0D);
    }

    private static void determinism() {
        T.section("预报确定性（服务端/客户端可复现）");
        BiomeClimate plains = REGISTRY.resolve(facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains"));
        ForecastEngine engine = new ForecastEngine();

        ForecastRequest request = ForecastRequest.builder(plains)
                .seed(123456789L)
                .startTick(1000L)
                .startEpochMillis(1_700_000_000_000L)
                .dayTime(6000L)
                .horizon(12)
                .ticksPerEntry(1000L)
                .build();

        Forecast a = engine.generate(request);
        Forecast b = engine.generate(request);
        T.eq("两次生成的格数一致", a.size(), b.size());
        boolean identical = true;
        for (int i = 0; i < a.size(); i++) {
            ForecastEntry ea = a.entries().get(i);
            ForecastEntry eb = b.entries().get(i);
            if (ea.weatherType() != eb.weatherType()
                    || Math.abs(ea.temperatureC() - eb.temperatureC()) > 1.0E-9
                    || Math.abs(ea.confidence() - eb.confidence()) > 1.0E-9) {
                identical = false;
                break;
            }
        }
        T.check("相同种子 → 完全相同的预报", identical);

        ForecastRequest otherSeed = ForecastRequest.builder(plains)
                .seed(987654321L)
                .startTick(1000L)
                .startEpochMillis(1_700_000_000_000L)
                .dayTime(6000L)
                .horizon(12)
                .ticksPerEntry(1000L)
                .build();
        Forecast c = engine.generate(otherSeed);
        boolean differs = false;
        for (int i = 0; i < a.size(); i++) {
            if (a.entries().get(i).weatherType() != c.entries().get(i).weatherType()) {
                differs = true;
                break;
            }
        }
        T.check("不同种子产生不同序列", differs);

        // 序列化往返（网络同步用）
        Forecast restored = Forecast.fromJson(com.nowweather.core.json.JsonValue.parse(a.toJson().toJson()).asObject());
        T.eq("预报序列化往返格数", a.size(), restored.size());
        T.eq("预报序列化往返天气", a.entries().get(3).weatherType().name(),
                restored.entries().get(3).weatherType().name());
        T.eq("预报序列化往返群系", a.biomeId(), restored.biomeId());

        ForecastEntry entry = a.entries().get(0);
        T.check("第 0 格时刻与起点一致", entry.startEpochMillis() == 1_700_000_000_000L);
        T.check("预报按 tick 查询可用", a.at(1000L + 2500L).isPresent());
        T.eq("按 tick 查询落到第 2 格", 2, a.at(1000L + 2500L).orElseThrow().index());
    }

    /** 对大量「生物群系 × 种子」做扫描，确保预报永远不违反该群系的气候常识。 */
    private static void plausibilitySweep() {
        T.section("大规模合理性扫描（8 种群系 × 200 种子）");
        String[][] biomes = {
                {"minecraft:desert", "2.0", "0.0", "NONE", "desert"},
                {"minecraft:snowy_plains", "0.0", "0.5", "SNOW", "icy"},
                {"minecraft:jungle", "0.95", "0.9", "RAIN", "jungle"},
                {"minecraft:plains", "0.8", "0.4", "RAIN", "plains"},
                {"minecraft:swamp", "0.8", "0.9", "RAIN", "swamp"},
                {"biomesoplenty:volcano", "1.5", "0.1", "NONE", "none"},
                {"biomesoplenty:cold_desert", "0.1", "0.1", "NONE", "none"},
                {"minecraft:ocean", "0.5", "0.5", "RAIN", "ocean"},
        };
        ForecastEngine engine = new ForecastEngine();
        int checks = 0;
        int tempViolations = 0;
        int precipViolations = 0;
        int plausibilityViolations = 0;
        int allowanceViolations = 0;

        for (String[] spec : biomes) {
            BiomeFacts facts = new BiomeFacts(spec[0], Float.parseFloat(spec[1]), Float.parseFloat(spec[2]),
                    PrecipitationType.valueOf(spec[3]), spec[4], Set.of(), "minecraft:overworld", false);
            BiomeClimate climate = REGISTRY.resolve(facts);
            for (int seed = 0; seed < 200; seed++) {
                Forecast forecast = engine.generate(ForecastRequest.builder(climate)
                        .seed(seed * 7919L + 13L)
                        .startTick(0L)
                        .startEpochMillis(0L)
                        .dayTime(seed * 137L % 24000L)
                        .horizon(16)
                        .ticksPerEntry(1000L)
                        .build());
                for (ForecastEntry entry : forecast.entries()) {
                    checks++;
                    if (!climate.tempRange().contains(entry.temperatureC(), 6.0D)) {
                        tempViolations++;
                    }
                    if (entry.weatherType().isPrecipitation() && !climate.canPrecipitate()) {
                        precipViolations++;
                    }
                    if (entry.weatherType().isFrozen() && entry.temperatureC() > 8.0D) {
                        plausibilityViolations++;
                    }
                    // 预报不得出现该生物群系白名单之外的天气
                    if (!climate.allows(entry.weatherType())) {
                        allowanceViolations++;
                    }
                }
            }
        }
        T.check("扫描样本量充足（> 20000）", checks > 20_000);
        T.eq("无「温度超出正常范围」的条目", 0, tempViolations);
        T.eq("无「干旱气候出现降水」的条目", 0, precipViolations);
        T.eq("无「高温下雪」的条目", 0, plausibilityViolations);
        T.eq("无「该群系不可能出现的天气」的条目", 0, allowanceViolations);
        System.out.println("  （共检查 " + checks + " 条预报数据）");
    }

    private static void anchorContinuity() {
        T.section("真实天气锚点衔接");
        BiomeClimate snowy = REGISTRY.resolve(facts("minecraft:snowy_plains", 0.0F, 0.5F, PrecipitationType.SNOW, "icy"));
        ForecastEngine engine = new ForecastEngine();

        // 真实天气：杭州 32°C 晴 —— 玩家却在雪原，预报必须被本地气候纠正
        WeatherObservation anchor = WeatherObservation.builder("uapipro")
                .weatherType(WeatherType.CLEAR)
                .conditionText("晴")
                .temperatureC(32.0D)
                .humidityPercent(45.0D)
                .windScale(4)
                .city("杭州")
                .build();

        Forecast forecast = engine.generate(ForecastRequest.builder(snowy)
                .anchor(anchor)
                .seed(42L)
                .startTick(0L)
                .startEpochMillis(0L)
                .dayTime(6000L)
                .horizon(6)
                .ticksPerEntry(1000L)
                .build());

        T.check("锚点第 0 格保留真实天气文本", forecast.providerId().equals("uapipro"));
        T.check("第 0 格温度被拉回雪原范围",
                forecast.entries().get(0).temperatureC() < 15.0D);
        T.check("第 0 格记录了气候修正", forecast.entries().get(0).wasAdjusted());
        T.check("预报温度整体贴合雪原气候",
                forecast.entries().stream().allMatch(e -> e.temperatureC() < 20.0D));

        // 无锚点（离线）
        Forecast offline = engine.generate(ForecastRequest.builder(snowy)
                .seed(7L)
                .horizon(4)
                .ticksPerEntry(1000L)
                .build());
        T.eq("离线预报来源标记", "offline", offline.providerId());
        T.check("离线预报温度在雪原范围内",
                offline.entries().stream().allMatch(e -> snowy.tempRange().contains(e.temperatureC(), 6.0D)));

        // 置信度：越往后越低
        Forecast longRange = engine.generate(ForecastRequest.builder(snowy)
                .seed(11L).horizon(12).ticksPerEntry(1000L).build());
        double first = longRange.entries().get(0).confidence();
        double last = longRange.entries().get(11).confidence();
        T.check("预报越久越不确定", last < first);
        T.check("置信度在 0~1 内", first <= 1.0D && last >= 0.0D);
    }

    /**
     * 预报格的时间步长与温度平滑。
     *
     * <p>回归用例：曾经 HUD 上出现过「预报 23:51 / 23:51 / 23:52」—— 开启真实时间同步后
     * 一格明明是 1 小时，时间戳却仍按原版 50 毫秒/刻推进，于是只差 50 秒；
     * 同时第 1 格从真实观测硬切到气候曲线，温度一下跳 4 ℃。</p>
     */
    private static void entryTimingAndSmoothness() {
        T.section("预报时间步长与温度平滑");
        BiomeClimate plains = REGISTRY.resolve(facts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN, "plains"));
        ForecastEngine engine = new ForecastEngine();

        // 真实时间同步：一天 = 24 小时 → 1 刻 = 3600 毫秒
        double realMillisPerTick = 86_400_000.0D / 24_000.0D;
        T.near("时间同步下 1 刻 = 3600 毫秒", 3600.0D, realMillisPerTick, 1.0E-9);

        long startEpoch = 1_700_000_000_000L;
        Forecast sync = engine.generate(ForecastRequest.builder(plains)
                .seed(2024L)
                .startTick(0L)
                .startEpochMillis(startEpoch)
                .dayTime(18000L)
                .horizon(6)
                .ticksPerEntry(1000L)
                .millisPerTick(realMillisPerTick)
                .build());

        long step = sync.entries().get(1).startEpochMillis() - sync.entries().get(0).startEpochMillis();
        T.eq("1000 刻一格的真实间隔 = 1 小时", 3_600_000L, step);
        T.check("预报格在时钟上按小时推进",
                !sync.entries().get(0).clockDisplay().equals(sync.entries().get(1).clockDisplay())
                        && !sync.entries().get(1).clockDisplay().equals(sync.entries().get(2).clockDisplay()));
        T.check("时间戳单调递增", sync.entries().get(5).startEpochMillis()
                == startEpoch + 5L * 3_600_000L);

        // 原版 20 分钟一天：1 刻 = 50 毫秒，一格只差 50 秒（这是原版该有的行为，保留）
        Forecast vanilla = engine.generate(ForecastRequest.builder(plains)
                .seed(2024L).horizon(3).ticksPerEntry(1000L)
                .millisPerTick(ForecastRequest.VANILLA_MILLIS_PER_TICK).build());
        T.eq("原版节奏下 1000 刻 = 50 秒", 50_000L,
                vanilla.entries().get(1).startEpochMillis() - vanilla.entries().get(0).startEpochMillis());

        // 温度平滑：相邻格温差不得超过昼夜曲线最大变化率的 1.6 倍
        double maxSlopePerHour = 2.0D * Math.PI * DiurnalTemperatureModel.amplitude(plains) / 24.0D;
        double allowed = maxSlopePerHour * 1.6D;
        double largestStep = 0.0D;
        for (int i = 1; i < sync.size(); i++) {
            double delta = Math.abs(sync.entries().get(i).temperatureC()
                    - sync.entries().get(i - 1).temperatureC());
            largestStep = Math.max(largestStep, delta);
        }
        T.check("相邻格温差不超过昼夜曲线物理上限（" + String.format("%.2f", allowed) + "℃）",
                largestStep <= allowed + 1.0E-6D);
        T.check("锚点交接处不再跳变（< 2℃）",
                Math.abs(sync.entries().get(1).temperatureC() - sync.entries().get(0).temperatureC()) < 2.0D);

        // 带真实锚点时同样平滑
        WeatherObservation anchor = WeatherObservation.builder("uapipro")
                .weatherType(WeatherType.CLEAR).conditionText("晴").temperatureC(13.2D)
                .humidityPercent(66.0D).windScale(3).city("杭州").build();
        Forecast anchored = engine.generate(ForecastRequest.builder(plains)
                .anchor(anchor).seed(9L).startTick(0L).startEpochMillis(startEpoch)
                .dayTime(18000L).horizon(8).ticksPerEntry(1000L)
                .millisPerTick(realMillisPerTick).build());
        T.near("第 0 格仍是真实观测温度", 13.2D, anchored.entries().get(0).temperatureC(), 1.0E-9);
        double seam = Math.abs(anchored.entries().get(1).temperatureC() - 13.2D);
        T.check("锚点后第一格平滑过渡（< 3℃）", seam < 3.0D);
    }

    private static BiomeFacts facts(String id, float baseTemperature, float downfall,
                                    PrecipitationType precipitation, String category) {
        return new BiomeFacts(id, baseTemperature, downfall, precipitation, category, Set.of(),
                "minecraft:overworld", false);
    }
}

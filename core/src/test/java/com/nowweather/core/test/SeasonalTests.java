package com.nowweather.core.test;

import com.nowweather.core.climate.BiomeClimate;
import com.nowweather.core.climate.BiomeClimateRegistry;
import com.nowweather.core.climate.BiomeFacts;
import com.nowweather.core.climate.ClimateClass;
import com.nowweather.core.climate.ClimateModifier;
import com.nowweather.core.climate.PrecipitationType;
import com.nowweather.core.climate.SeasonalTemperatureCurve;
import com.nowweather.core.forecast.ClimateSanityFilter;
import com.nowweather.core.forecast.SanityReport;
import com.nowweather.core.log.Loggers;
import com.nowweather.core.weather.WeatherType;

import java.util.Set;

/**
 * 季节联动测试：把「一年中的进度」换算成温度平移，并验证它确实改变了「可能出现的天气」。
 *
 * <p>纯计算部分（{@link SeasonalTemperatureCurve}）与 Minecraft 无关，所以能完整离线验证。</p>
 */
final class SeasonalTests {

    private SeasonalTests() {
    }

    static void run() {
        curve();
        whitelistShiftsWithSeason();
        registryIntegration();
    }

    private static void curve() {
        T.section("季节温度曲线");

        double midSummer = SeasonalTemperatureCurve.offsetFor(1, 0.5D, null);
        double midWinter = SeasonalTemperatureCurve.offsetFor(3, 0.5D, null);
        double startSpring = SeasonalTemperatureCurve.offsetFor(0, 0.0D, null);

        T.check("盛夏最热（+6 以上）", midSummer > 6.0D);
        T.check("隆冬最冷（-12 以下）", midWinter < -12.0D);
        // 锚点代表「季节中点」：仲春 ≈ 基准 0，而季节起点是相邻两个季节中点的中间值（入春仍偏冷）
        T.check("仲春接近基准（±1 以内）",
                Math.abs(SeasonalTemperatureCurve.offsetFor(0, 0.5D, null)) < 1.0D);
        T.check("入春仍在冰点偏冷侧（冬春之间）", startSpring < -4.0D && startSpring > midWinter);
        T.check("冬夏温差足够大（> 18°C）", midSummer - midWinter > 18.0D);

        // 平滑性：相邻采样点的差值不应出现跳变（换季那一刻尤其重要）
        double maxJump = 0.0D;
        double previous = SeasonalTemperatureCurve.offsetFor(0, 0.0D, null);
        for (int i = 1; i <= 400; i++) {
            double scaled = i / 100.0D;              // 0~4，跨四个季节
            int ordinal = (int) Math.floor(scaled) % 4;
            double progress = scaled - Math.floor(scaled);
            double current = SeasonalTemperatureCurve.offsetFor(ordinal, progress, null);
            maxJump = Math.max(maxJump, Math.abs(current - previous));
            previous = current;
        }
        T.check("曲线连续（相邻 1% 季节进度变化 < 0.5°C）", maxJump < 0.5D);

        // 跨年回绕：冬 → 春 也要连续
        double lateWinter = SeasonalTemperatureCurve.offsetFor(3, 0.99D, null);
        double earlySpring = SeasonalTemperatureCurve.offsetFor(0, 0.01D, null);
        T.check("冬末与初春衔接自然", Math.abs(lateWinter - earlySpring) < 2.0D);

        T.near("按季节名换算（WINTER 中点）", midWinter,
                SeasonalTemperatureCurve.offsetForSeasonName("WINTER", 0.5D, null), 1.0E-9);
        T.near("按季节名换算（SUMMER 中点）", midSummer,
                SeasonalTemperatureCurve.offsetForSeasonName("SUMMER", 0.5D, null), 1.0E-9);

        // 自定义锚点
        double[] custom = {0.0D, 20.0D, 0.0D, -20.0D};
        T.check("自定义锚点生效",
                SeasonalTemperatureCurve.offsetFor(1, 0.5D, custom) > 15.0D);
    }

    private static void whitelistShiftsWithSeason() {
        T.section("季节确实改变了「可能出现的天气」");

        BiomeClimate plains = BiomeClimate.builder("minecraft:plains")
                .climateClass(ClimateClass.TEMPERATE)
                .tempRange(-16, 36)
                .precipitation(PrecipitationType.RAIN)
                .humidity(0.52).windiness(0.45).thunderBias(1.05)
                .source("test")
                .build();

        double summer = SeasonalTemperatureCurve.offsetFor(1, 0.5D, null);
        double winter = SeasonalTemperatureCurve.offsetFor(3, 0.5D, null);

        BiomeClimate summerClimate = SeasonalTemperatureCurve.apply(plains, summer, "test:SUMMER");
        BiomeClimate winterClimate = SeasonalTemperatureCurve.apply(plains, winter, "test:WINTER");

        T.check("夏天温度范围上移", summerClimate.tempRange().midpoint() > plains.tempRange().midpoint() + 5.0D);
        T.check("冬天温度范围下移", winterClimate.tempRange().midpoint() < plains.tempRange().midpoint() - 8.0D);

        T.check("★ 夏天不再可能下雪", !summerClimate.allows(WeatherType.SNOW)
                && !summerClimate.allows(WeatherType.HEAVY_SNOW));
        T.check("★ 冬天可能下雪", winterClimate.allows(WeatherType.SNOW));
        T.check("夏天温度下限高于冰点", summerClimate.tempRange().minC() > 1.0D);
        T.check("冬天温度下限低于冰点", winterClimate.tempRange().minC() < -5.0D);
        T.check("source 标记了季节来源", summerClimate.source().contains("test:SUMMER"));

        // 端到端：夏天灌入一场雪 → 必须被换掉；冬天灌入同一场雪 → 应当保留
        ClimateSanityFilter filter = new ClimateSanityFilter(6.0D, true);
        SanityReport summerSnow = filter.inspect(summerClimate, WeatherType.SNOW, 18.0D, 0.6D, 0.3D);
        SanityReport winterSnow = filter.inspect(winterClimate, WeatherType.SNOW, -8.0D, 0.6D, 0.3D);
        T.check("夏天灌雪被替换掉", summerSnow.adjustedType() != WeatherType.SNOW);
        T.check("冬天灌雪被保留", winterSnow.adjustedType().isFrozen());
        T.check("夏天灌雪后仍在白名单内", summerClimate.allows(summerSnow.adjustedType()));
        T.check("冬天灌雪后仍在白名单内", winterClimate.allows(winterSnow.adjustedType()));

        // 沙漠：无论哪个季节都不该下雪
        BiomeClimate desert = BiomeClimate.builder("minecraft:desert")
                .climateClass(ClimateClass.ARID).tempRange(10, 52)
                .precipitation(PrecipitationType.NONE).humidity(0.07).windiness(0.55).thunderBias(0.15)
                .source("test").build();
        BiomeClimate desertWinter = SeasonalTemperatureCurve.apply(desert, winter, "test:WINTER");
        T.check("沙漠冬天也不下雪", !desertWinter.allows(WeatherType.SNOW));
        T.check("沙漠冬天温度范围仍高于冰点", desertWinter.tempRange().minC() > 1.0D);

        // 雪原：夏天也不该变成温带草原
        BiomeClimate snowy = BiomeClimate.builder("minecraft:snowy_plains")
                .climateClass(ClimateClass.POLAR).tempRange(-38, 5)
                .precipitation(PrecipitationType.SNOW).humidity(0.60).windiness(0.62).thunderBias(0.5)
                .source("test").build();
        BiomeClimate snowySummer = SeasonalTemperatureCurve.apply(snowy, summer, "test:SUMMER");
        T.check("雪原夏天依然可能下雪", snowySummer.allows(WeatherType.SNOW));
    }

    private static void registryIntegration() {
        T.section("修正器接入解析链");

        BiomeClimateRegistry registry = new BiomeClimateRegistry(Loggers.noop());
        registry.loadJson("测试", com.nowweather.core.json.JsonValue.parse("""
                {"biomes":[{"id":"minecraft:plains","climateClass":"TEMPERATE","tempMinC":-16,"tempMaxC":36,
                  "precipitation":"RAIN","possibleMcWeather":["CLEAR","RAIN","THUNDER"]}]}
                """));
        BiomeFacts facts = new BiomeFacts("minecraft:plains", 0.8F, 0.4F, PrecipitationType.RAIN,
                "plains", Set.of(), "minecraft:overworld", false);

        T.check("没有修正器时用数据包里的显式条目（不走启发式）",
                !registry.resolve(facts).source().startsWith("heuristic"));

        // 注册一个「冬天」修正器
        ClimateModifier winterModifier = new ClimateModifier() {
            @Override
            public String id() {
                return "test:winter";
            }

            @Override
            public BiomeClimate modify(BiomeFacts facts, BiomeClimate base) {
                return SeasonalTemperatureCurve.apply(base, -14.0D, "test:WINTER");
            }
        };
        registry.addModifier(winterModifier);

        BiomeClimate modified = registry.resolve(facts);
        T.check("修正器在显式配置之后生效（这是关键：内置数据不能截断季节联动）",
                modified.source().contains("test:WINTER"));
        T.check("修正后温度范围下移", modified.tempRange().maxC() < 36.0D);
        T.check("修正后允许下雪", modified.allows(WeatherType.SNOW));

        // 修正器可以被移除，恢复正常
        registry.removeModifier(winterModifier);
        BiomeClimate restored = registry.resolve(facts);
        T.check("移除修正器后恢复", !restored.source().contains("test:WINTER"));

        // 修正器抛异常不能拖垮解析
        registry.addModifier(new ClimateModifier() {
            @Override
            public String id() {
                return "test:broken";
            }

            @Override
            public BiomeClimate modify(BiomeFacts facts, BiomeClimate base) {
                throw new IllegalStateException("故意炸");
            }
        });
        BiomeClimate survived = registry.resolve(facts);
        T.check("修正器异常被兜住，解析仍返回结果", survived != null && survived.biomeId().equals("minecraft:plains"));
    }
}

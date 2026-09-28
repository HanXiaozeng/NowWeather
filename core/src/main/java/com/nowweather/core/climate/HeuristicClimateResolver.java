package com.nowweather.core.climate;

import com.nowweather.core.util.MathUtil;

import java.util.Optional;

/**
 * 启发式气候解析器 —— 面对「没写过配置的模组生物群系」时的兜底大脑。
 *
 * <p>它只依赖 Minecraft 本身就能提供的事实（基础温度、降水量、降水类型、分类、标签、维度），
 * 加上 {@link ClimateMath} 的关键词表，因此<b>对任意模组都有效</b>，包括超多生物群系里
 * 那些名字千奇百怪的群系。</p>
 */
public final class HeuristicClimateResolver implements ClimateResolver {

    /** 以原版温度为中心的默认半径（摄氏度）。 */
    public static final double DEFAULT_SPREAD = 8.0D;

    private final double spread;
    private final String sourceName;

    public HeuristicClimateResolver() {
        this(DEFAULT_SPREAD, "heuristic");
    }

    public HeuristicClimateResolver(double spread, String sourceName) {
        this.spread = spread;
        this.sourceName = sourceName;
    }

    @Override
    public String id() {
        return "heuristic";
    }

    @Override
    public int priority() {
        return 10_000;
    }

    @Override
    public Optional<BiomeClimate> resolve(BiomeFacts facts) {
        ClimateMath.Classification classification = ClimateMath.classify(facts);
        ClimateClass climateClass = classification.climateClass();
        TempRange range = ClimateMath.heuristicRange(climateClass, facts, spread, classification.keywordHit());

        // 湿度：气候大类默认值与原版降水量各占一半
        double downfall = MathUtil.clamp(facts.downfall(), 0.0D, 1.0D);
        double humidity = MathUtil.clamp(climateClass.humidity() * 0.5D + downfall * 0.5D, 0.0D, 1.0D);

        PrecipitationType precipitation = facts.precipitation();
        if (climateClass == ClimateClass.CAVE) {
            // 洞穴里没有天气系统：即使数据包给某洞穴群系标了 rain，也不该允许降水
            precipitation = PrecipitationType.NONE;
        }
        if (precipitation == PrecipitationType.MIXED) {
            precipitation = climateClass.precipitation();
        }
        if (facts.isSnowy() && precipitation == PrecipitationType.RAIN) {
            // 原版说下雨但基础温度在冰点以下：以温度为准确认雪
            precipitation = PrecipitationType.MIXED;
        }

        double thunderBias = switch (climateClass) {
            case TROPICAL, WETLAND -> 1.4D;
            case SUBTROPICAL, OCEANIC -> 1.15D;
            case ARID, VOLCANIC, NETHER, END, VOID -> 0.15D;
            // 极地：原版雪原确实会「雷打雪」，但绝不会出现强雷暴，所以给一个低但不为零的倾向
            case POLAR, SUBPOLAR -> 0.5D;
            case CAVE -> 0.3D;
            default -> 1.0D;
        };

        Confidence confidence;
        if (climateClass == ClimateClass.UNKNOWN) {
            confidence = Confidence.GUESS;
        } else if (classification.keywordHit()) {
            confidence = Confidence.MEDIUM;
        } else {
            confidence = Confidence.LOW;
        }
        // 干旱 / 极端气候的关键词命中度更高，这里不额外提升，保持保守

        BiomeClimate climate = BiomeClimate.builder(facts.id())
                .climateClass(climateClass)
                .tempRange(range)
                .baseTemperature(facts.baseTemperature())
                .precipitation(precipitation)
                .humidity(humidity)
                .windiness(climateClass.windiness())
                .thunderBias(thunderBias)
                .source(sourceName + ":" + classification.via())
                .confidence(confidence)
                .build();
        return Optional.of(climate);
    }
}

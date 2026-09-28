package com.nowweather.core.forecast;

import com.nowweather.core.weather.WeatherType;

import java.util.List;

/**
 * 气候合理性校验的结果。
 *
 * @param adjustedType   修正后的天气类型
 * @param adjustedTempC  修正后的温度（已夹进正常温度范围 + 容差）
 * @param violations     发现的违规项（空表示原始数据完全合理）
 */
public record SanityReport(WeatherType adjustedType, double adjustedTempC, List<SanityViolation> violations) {

    public SanityReport {
        violations = List.copyOf(violations);
    }

    public static SanityReport clean(WeatherType type, double tempC) {
        return new SanityReport(type, tempC, List.of());
    }

    public boolean isClean() {
        return violations.isEmpty();
    }

    public boolean changedType() {
        return !violations.isEmpty();
    }

    public String summarize() {
        if (violations.isEmpty()) {
            return "合理";
        }
        StringBuilder sb = new StringBuilder();
        for (SanityViolation v : violations) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(v.zhDescription());
        }
        return sb.toString();
    }
}

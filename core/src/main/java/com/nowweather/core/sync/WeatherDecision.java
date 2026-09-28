package com.nowweather.core.sync;

import com.nowweather.core.api.PlatformWeather;
import com.nowweather.core.forecast.SanityViolation;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherType;

import java.util.List;

/**
 * 一条「真实天气 → Minecraft 天气」的决策结果。
 *
 * <p>除了「要落地成什么天气」，它还完整记录了这个决定是怎么来的：
 * 采用的是哪种天气、温度是多少、有没有被本地气候修正、修正的理由是什么。
 * 命令 {@code /nowweather status}、自检命令与诊断日志都靠它还原现场。</p>
 *
 * @param target             要落地的 MC 天气
 * @param durationTicks      该天气持续刻数
 * @param clearDurationTicks 晴朗段时长（原版接口需要）
 * @param temperatureC       决策时采用的气温
 * @param weatherType        决策时采用的标准化天气
 * @param climateAdjusted    是否因为「不符合本地气候」而被修正过
 * @param reason             人类可读的决策理由（命令 / 日志用）
 * @param decidedAtTick      决策时的世界总刻
 * @param violations         本次决策中被判定「不符合本地气候」的具体项（空表示数据本身合理）
 */
public record WeatherDecision(
        PlatformWeather target,
        int durationTicks,
        int clearDurationTicks,
        double temperatureC,
        WeatherType weatherType,
        boolean climateAdjusted,
        String reason,
        long decidedAtTick,
        List<SanityViolation> violations) {

    public WeatherDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public boolean isChangeFrom(PlatformWeather current) {
        return current == null || current != target;
    }

    /** 是否被本地气候修正过（等价于 {@link #violations()} 非空，保留是为了可读性）。 */
    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    /** 违规项的简短描述，例如「干旱气候下不应出现降水、雪在冰点以上」。 */
    public String violationsSummary() {
        if (violations.isEmpty()) {
            return Text.tr("nowweather.decision.no_violations");
        }
        StringBuilder sb = new StringBuilder();
        for (SanityViolation violation : violations) {
            if (sb.length() > 0) {
                sb.append(Text.tr("nowweather.list.separator"));
            }
            sb.append(violation.zhDescription());
        }
        return sb.toString();
    }

    public String describe() {
        return Text.tr("nowweather.decision.line",
                target.zhName(), weatherType.zhName(), Integer.toString(durationTicks),
                climateAdjusted ? Text.tr("nowweather.decision.climate_adjusted") : "");
    }
}

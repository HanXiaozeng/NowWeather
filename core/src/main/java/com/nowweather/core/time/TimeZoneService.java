package com.nowweather.core.time;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 所在地时区解析 —— 三个来源按优先级取用。
 *
 * <ol>
 *   <li><b>手动配置</b>（{@code timezoneOffsetMinutes}）：最可靠，服主说是什么就是什么；</li>
 *   <li><b>天气接口返回</b>：Open-Meteo 的响应里带 {@code utc_offset_seconds} / {@code timezone}，
 *       这是<b>配置城市所在地</b>的真实时区 —— 对专用服务器来说这才是对的
 *       （服务器的系统时区往往在机房所在地，和玩家/城市不一致）；</li>
 *   <li><b>JVM 默认时区</b>：单机游戏时正好是玩家本机时区，作为最后的兜底。</li>
 * </ol>
 *
 * <p>解析结果会被写进本地缓存，因此<b>离线时也能保持正确时区</b> —— 这正是「离线也要能推算天气」的前提。</p>
 */
public final class TimeZoneService {

    /** 未设置手动偏移的哨兵值。 */
    public static final int UNSET = Integer.MIN_VALUE;

    private volatile int manualOffsetSeconds = UNSET;
    private volatile int apiOffsetSeconds = UNSET;
    private volatile String apiTimeZoneId = "";
    private volatile String apiLocation = "";

    public TimeZoneService() {
        this(UNSET);
    }

    public TimeZoneService(int manualOffsetSeconds) {
        this.manualOffsetSeconds = manualOffsetSeconds;
    }

    /** 手动设置偏移（分钟）；传 {@link #UNSET} 表示清除手动值。 */
    public void setManualOffsetMinutes(int minutes) {
        this.manualOffsetSeconds = minutes == UNSET ? UNSET : minutes * 60;
    }

    public boolean hasManualOffset() {
        return manualOffsetSeconds != UNSET;
    }

    /**
     * 记录天气接口给出的时区信息（每次成功获取实况时调用）。
     *
     * @param offsetSeconds UTC 偏移（秒）；负数或不明时传 {@link #UNSET}
     * @param timeZoneId    IANA 时区名（如 Asia/Shanghai），可为空
     * @param location      对应的地点展示名（仅用于诊断）
     */
    public void applyFromProvider(int offsetSeconds, String timeZoneId, String location) {
        if (offsetSeconds == UNSET) {
            return;
        }
        this.apiOffsetSeconds = offsetSeconds;
        this.apiTimeZoneId = timeZoneId == null ? "" : timeZoneId;
        this.apiLocation = location == null ? "" : location;
    }

    /** 缓存恢复时用（离线启动后仍然知道正确时区）。 */
    public void restoreFromCache(int offsetSeconds, String timeZoneId, String location) {
        applyFromProvider(offsetSeconds, timeZoneId, location);
    }

    /** 当前生效的 UTC 偏移（秒）。 */
    public int effectiveOffsetSeconds() {
        if (manualOffsetSeconds != UNSET) {
            return manualOffsetSeconds;
        }
        if (apiOffsetSeconds != UNSET) {
            return apiOffsetSeconds;
        }
        return systemOffsetSeconds();
    }

    /** 当前生效的偏移来自哪里（诊断用）。 */
    public String source() {
        if (manualOffsetSeconds != UNSET) {
            return "手动配置";
        }
        if (apiOffsetSeconds != UNSET) {
            return "天气接口" + (apiLocation.isEmpty() ? "" : "（" + apiLocation + "）");
        }
        return "系统时区";
    }

    /** 时区名（能用 IANA 名就用，否则用 GMT±x 形式）。 */
    public String zoneName() {
        if (apiOffsetSeconds != UNSET && !apiTimeZoneId.isEmpty()) {
            return apiTimeZoneId;
        }
        if (manualOffsetSeconds != UNSET) {
            return formatOffset(manualOffsetSeconds);
        }
        return ZoneId.systemDefault().getId();
    }

    public int apiOffsetSeconds() {
        return apiOffsetSeconds;
    }

    public String apiTimeZoneId() {
        return apiTimeZoneId;
    }

    /** 由经纬度粗略估算时区偏移（离线且没有任何时区信息时的最后兜底）：每 15° 经度 = 1 小时。 */
    public static int estimateOffsetSecondsFromLongitude(double longitude) {
        if (Double.isNaN(longitude)) {
            return UNSET;
        }
        double hours = Math.round(longitude / 15.0D);
        return (int) Math.round(hours * 3600.0D);
    }

    /** 本机系统时区偏移（秒）。 */
    public static int systemOffsetSeconds() {
        try {
            ZonedDateTime now = ZonedDateTime.now();
            return now.getOffset().getTotalSeconds();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static String formatOffset(int offsetSeconds) {
        int totalMinutes = offsetSeconds / 60;
        int hours = totalMinutes / 60;
        int minutes = Math.abs(totalMinutes % 60);
        return String.format(java.util.Locale.ROOT, "GMT%s%d:%02d", hours >= 0 ? "+" : "-",
                Math.abs(hours), minutes);
    }

    @Override
    public String toString() {
        return zoneName() + "（" + formatOffset(effectiveOffsetSeconds()) + "，来源：" + source() + "）";
    }
}

package com.nowweather.core.api;

/** 一次天气查询的结果状态。 */
public enum ProviderStatus {
    /** 拿到了可用数据。 */
    OK,
    /** Provider 主动禁用（配置关闭 / 缺少 API Key）。 */
    DISABLED,
    /** 没有可查询的位置（服务器没配置城市，也拿不到坐标）。 */
    NO_LOCATION,
    /** 请求成功但数据为空 / 城市无法识别。 */
    NO_DATA,
    /** 被限流，建议等 {@code retryAfterMillis} 再试。 */
    RATE_LIMITED,
    /** HTTP 层面失败（4xx/5xx）。 */
    HTTP_ERROR,
    /** 网络层面失败（超时 / DNS / TLS）。 */
    NETWORK_ERROR,
    /** 返回内容无法解析。 */
    PARSE_ERROR,
    /** 该 provider 在当前环境不可用（例如依赖的模组没装）。 */
    NOT_AVAILABLE;

    public boolean isSuccess() {
        return this == OK;
    }

    /** 是否值得立刻重试（网络抖动）。 */
    public boolean isRetryable() {
        return this == NETWORK_ERROR || this == RATE_LIMITED;
    }

    public boolean isPermanentFailure() {
        return this == DISABLED || this == NO_LOCATION || this == NOT_AVAILABLE;
    }
}

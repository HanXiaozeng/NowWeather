package com.nowweather.core.http;

import java.util.Map;

/** 一次 HTTP 响应的结果。 */
public final class HttpResult {

    private final int statusCode;
    private final String body;
    private final Map<String, String> headers;

    public HttpResult(int statusCode, String body, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public String toString() {
        return "HttpResult[" + statusCode + ", " + body.length() + " chars]";
    }
}

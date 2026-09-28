package com.nowweather.core.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一次 HTTP 请求的描述（不依赖任何 HTTP 客户端实现）。 */
public final class HttpRequestSpec {

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final Duration timeout;

    private HttpRequestSpec(Builder builder) {
        this.method = builder.method;
        this.url = Objects.requireNonNull(builder.url, "url");
        this.headers = Map.copyOf(builder.headers);
        this.body = builder.body;
        this.timeout = builder.timeout;
    }

    public static Builder get(String url) {
        return new Builder("GET", url);
    }

    public static Builder post(String url) {
        return new Builder("POST", url);
    }

    public static Builder builder(String method, String url) {
        return new Builder(method, url);
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String body() {
        return body;
    }

    public Duration timeout() {
        return timeout;
    }

    public static final class Builder {
        private final String method;
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;
        private Duration timeout = Duration.ofSeconds(10);

        private Builder(String method, String url) {
            this.method = method;
            this.url = url;
        }

        public Builder header(String name, String value) {
            if (value != null && !value.isBlank()) {
                headers.put(name, value);
            }
            return this;
        }

        public Builder bearerToken(String token) {
            if (token != null && !token.isBlank()) {
                headers.put("Authorization", "Bearer " + token.trim());
            }
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout != null) {
                this.timeout = timeout;
            }
            return this;
        }

        public HttpRequestSpec build() {
            return new HttpRequestSpec(this);
        }
    }
}

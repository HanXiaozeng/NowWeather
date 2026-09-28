package com.nowweather.core.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDK 自带 {@link java.net.http.HttpClient} 的传输实现（Java 11+，因此 1.18 及以上版本都能用）。
 *
 * <p>刻意不引入 OkHttp：模组多一个第三方 HTTP 库就多一份 jar-in-jar 与冲突风险。
 * 由于 JDK 客户端连接失败时抛的是 {@link IOException}（乃至 SSL  revocation 检查失败），
 * 这里统一包装成 {@link HttpException}，并由上层决定是否降级到离线天气。</p>
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final String DEFAULT_USER_AGENT = "NowWeather/0.1 (+https://github.com/NowWeather)";

    private final HttpClient client;
    private final String userAgent;
    private final Duration defaultTimeout;

    public JdkHttpTransport() {
        this(DEFAULT_USER_AGENT, Duration.ofSeconds(10));
    }

    public JdkHttpTransport(String userAgent, Duration defaultTimeout) {
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent;
        this.defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(10) : defaultTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(this.defaultTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String name() {
        return "jdk-http";
    }

    @Override
    public HttpResult execute(HttpRequestSpec spec) throws HttpException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(spec.url()))
                    .timeout(spec.timeout() == null ? defaultTimeout : spec.timeout())
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Charset", "utf-8");
        } catch (IllegalArgumentException e) {
            throw new HttpException("非法的请求地址: " + spec.url(), e);
        }

        spec.headers().forEach(builder::header);

        String method = spec.method() == null ? "GET" : spec.method().toUpperCase(Locale.ROOT);
        String body = spec.body();
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        if (body != null && !spec.headers().containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json; charset=utf-8");
        }

        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Charset charset = charsetOf(response).orElse(StandardCharsets.UTF_8);
            String text = new String(response.body(), charset);
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> headers.put(k, String.join("; ", v)));
            return new HttpResult(response.statusCode(), text, headers);
        } catch (IOException e) {
            throw new HttpException("网络请求失败: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpException("网络请求被中断", e);
        }
    }

    private static Optional<Charset> charsetOf(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").flatMap(JdkHttpTransport::parseCharset);
    }

    static Optional<Charset> parseCharset(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) {
            return Optional.empty();
        }
        String value = contentType.substring(idx + "charset=".length()).trim();
        int semi = value.indexOf(';');
        if (semi >= 0) {
            value = value.substring(0, semi);
        }
        value = value.replace("\"", "").trim();
        try {
            return Optional.of(Charset.forName(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}

package com.nowweather.core.http;

/** HTTP 层异常。 */
public class HttpException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public HttpException(String message) {
        this(message, -1, null);
    }

    public HttpException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public HttpException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP 状态码；{-1} 表示请求根本没成功（连接/超时/DNS 等）。 */
    public int statusCode() {
        return statusCode;
    }

    public boolean isNetworkLevel() {
        return statusCode < 0;
    }
}

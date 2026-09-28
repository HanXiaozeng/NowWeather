package com.nowweather.core.http;

/**
 * HTTP 传输层抽象。
 *
 * <p>把网络调用抽出来有两个目的：
 * 1) 核心可以在没有任何真实网络的情况下被完整测试（注入假实现）；
 * 2) 不同加载器可以换成各自合规/带缓存的实现。</p>
 */
public interface HttpTransport {

    HttpResult execute(HttpRequestSpec spec) throws HttpException;

    /** 供日志/诊断展示的实现名。 */
    default String name() {
        return getClass().getSimpleName();
    }
}

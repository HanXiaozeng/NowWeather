package com.nowweather.core.test;

import java.util.ArrayList;
import java.util.List;

/** 极简断言与报告工具 —— 让核心自测不依赖 JUnit（离线也能跑）。 */
public final class T {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed;
    private static String section = "";

    private T() {
    }

    public static void section(String name) {
        section = name;
        System.out.println("\n=== " + name + " ===");
    }

    public static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + what);
        } else {
            FAILURES.add(section + " / " + what);
            System.out.println("  [FAIL] " + what);
        }
    }

    public static void eq(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            FAILURES.add(section + " / " + what + "（期望 " + expected + "，实际 " + actual + "）");
            System.out.println("  [FAIL] " + what + " → 期望 " + expected + "，实际 " + actual);
        } else {
            passed++;
            System.out.println("  [PASS] " + what + " = " + actual);
        }
    }

    public static void near(String what, double expected, double actual, double tolerance) {
        boolean ok = Math.abs(expected - actual) <= tolerance;
        if (!ok) {
            FAILURES.add(section + " / " + what + "（期望 " + expected + "±" + tolerance + "，实际 " + actual + "）");
            System.out.println("  [FAIL] " + what + " → 期望 " + expected + "±" + tolerance + "，实际 " + actual);
        } else {
            passed++;
            System.out.println("  [PASS] " + what + " = " + actual);
        }
    }

    public static void fail(String what, String detail) {
        FAILURES.add(section + " / " + what + "：" + detail);
        System.out.println("  [FAIL] " + what + "：" + detail);
    }

    /** 断言某段代码抛出指定异常。 */
    public static void throwsException(String what, Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            fail(what, "没有抛出 " + type.getSimpleName());
        } catch (Throwable t) {
            check(what + "（抛出 " + t.getClass().getSimpleName() + "）", type.isInstance(t));
        }
    }

    public static int summary() {
        System.out.println("\n================ 测试汇总 ================");
        System.out.println("通过: " + passed + "，失败: " + FAILURES.size());
        if (!FAILURES.isEmpty()) {
            System.out.println("失败清单:");
            FAILURES.forEach(f -> System.out.println("  - " + f));
        }
        return FAILURES.size();
    }

    public static int passedCount() {
        return passed;
    }

    /** 构造一个返回固定响应的假 HTTP 传输。 */
    public static FakeTransport fakeTransport(String body) {
        return new FakeTransport(body);
    }

    /** 一个可复用的假 HTTP 传输实现。 */
    public static final class FakeTransport implements com.nowweather.core.http.HttpTransport {
        private final List<String> requestedUrls = new ArrayList<>();
        private final List<String> requestBodies = new ArrayList<>();
        private String responseBody;
        private int statusCode = 200;
        private com.nowweather.core.http.HttpException failure;
        private int callCount;

        public FakeTransport(String responseBody) {
            this.responseBody = responseBody;
        }

        public FakeTransport failingWith(com.nowweather.core.http.HttpException failure) {
            this.failure = failure;
            return this;
        }

        public FakeTransport status(int code, String body) {
            this.statusCode = code;
            this.responseBody = body;
            return this;
        }

        public List<String> requestedUrls() {
            return List.copyOf(requestedUrls);
        }

        /** 最近一次请求的 URL（没有请求过则返回空串）。 */
        /** 最近一次请求的正文（没有请求过则返回空串）。 */
        public String lastRequestBody() {
            return requestBodies.isEmpty() ? "" : requestBodies.get(requestBodies.size() - 1);
        }

        public String lastUrl() {
            return requestedUrls.isEmpty() ? "" : requestedUrls.get(requestedUrls.size() - 1);
        }

        public int callCount() {
            return callCount;
        }

        @Override
        public com.nowweather.core.http.HttpResult execute(com.nowweather.core.http.HttpRequestSpec spec)
                throws com.nowweather.core.http.HttpException {
            callCount++;
            requestedUrls.add(spec.url());
            if (spec.body() != null) { requestBodies.add(spec.body()); }
            if (failure != null) {
                throw failure;
            }
            return new com.nowweather.core.http.HttpResult(statusCode, responseBody, java.util.Map.of());
        }
    }
}

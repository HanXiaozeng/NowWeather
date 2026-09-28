package com.nowweather.core.log;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 日志工具与若干内置实现。 */
public final class Loggers {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Loggers() {
    }

    /** 极简 {@code {}} 占位符格式化，避免核心依赖任何日志框架。 */
    public static String format(String message, Object... args) {
        if (args == null || args.length == 0 || message == null) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message.length() + 32);
        int argIndex = 0;
        int i = 0;
        while (i < message.length()) {
            char c = message.charAt(i);
            if (c == '{' && i + 1 < message.length() && message.charAt(i + 1) == '}' && argIndex < args.length) {
                sb.append(stringify(args[argIndex++]));
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        if (argIndex < args.length) {
            sb.append(" [");
            for (int k = argIndex; k < args.length; k++) {
                if (k > argIndex) {
                    sb.append(", ");
                }
                sb.append(stringify(args[k]));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return String.valueOf(value);
    }

    /** 打印到标准输出的日志器，用于核心自测与命令行工具。 */
    public static SyncLogger console(SyncLogger.Level minimum) {
        return console(minimum, System.out, System.err);
    }

    public static SyncLogger console(SyncLogger.Level minimum, PrintStream out, PrintStream err) {
        Objects.requireNonNull(minimum, "minimum");
        return (level, message, throwable) -> {
            if (level.ordinal() < minimum.ordinal()) {
                return;
            }
            PrintStream target = level.ordinal() >= SyncLogger.Level.WARN.ordinal() ? err : out;
            target.println("[" + TIME.format(LocalTime.now()) + "] [" + level + "] " + message);
            if (throwable != null) {
                throwable.printStackTrace(target);
            }
        };
    }

    /** 丢弃全部日志的日志器（测试用）。 */
    public static SyncLogger noop() {
        return (level, message, throwable) -> {
        };
    }

    /**
     * 收集日志到内存中的日志器，便于自测断言。
     */
    public static final class Recording implements SyncLogger {
        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void log(Level level, String message, Throwable throwable) {
            lines.add(level + " " + message + (throwable == null ? "" : " | " + throwable));
        }

        public java.util.List<String> lines() {
            return java.util.List.copyOf(lines);
        }

        public boolean contains(String fragment) {
            return lines.stream().anyMatch(l -> l.contains(fragment));
        }

        public void clear() {
            lines.clear();
        }
    }
}

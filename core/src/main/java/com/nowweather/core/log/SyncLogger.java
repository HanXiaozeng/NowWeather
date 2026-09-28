package com.nowweather.core.log;

/**
 * 极简日志抽象。
 *
 * <p>核心模块不依赖 SLF4J / Log4j，加载器只需要提供一个适配器实现，
 * 就能把核心的日志接进游戏日志系统。</p>
 */
public interface SyncLogger {

    enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    void log(Level level, String message, Throwable throwable);

    default void trace(String message, Object... args) {
        log(Level.TRACE, Loggers.format(message, args), null);
    }

    default void debug(String message, Object... args) {
        log(Level.DEBUG, Loggers.format(message, args), null);
    }

    default void info(String message, Object... args) {
        log(Level.INFO, Loggers.format(message, args), null);
    }

    default void warn(String message, Object... args) {
        log(Level.WARN, Loggers.format(message, args), null);
    }

    default void warn(Throwable cause, String message, Object... args) {
        log(Level.WARN, Loggers.format(message, args), cause);
    }

    default void error(String message, Object... args) {
        log(Level.ERROR, Loggers.format(message, args), null);
    }

    default void error(Throwable cause, String message, Object... args) {
        log(Level.ERROR, Loggers.format(message, args), cause);
    }

    /** 生成一个在给定日志器前加上前缀的包装日志器（用于子系统区分日志来源）。 */
    default SyncLogger withPrefix(String prefix) {
        SyncLogger parent = this;
        return (level, message, throwable) -> parent.log(level, "[" + prefix + "] " + message, throwable);
    }
}

package com.nowweather.forge;

import com.nowweather.core.log.SyncLogger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 把核心的日志接到 Forge 的日志系统（log4j2）上。 */
public final class NowWeatherLog implements SyncLogger {

    public static final Logger LOGGER = LogManager.getLogger("nowweather");

    public static final NowWeatherLog INSTANCE = new NowWeatherLog();

    private NowWeatherLog() {
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        String text = message == null ? "" : message;
        switch (level) {
            case TRACE -> LOGGER.trace(text, throwable);
            case DEBUG -> LOGGER.debug(text, throwable);
            case INFO -> LOGGER.info(text, throwable);
            case WARN -> LOGGER.warn(text, throwable);
            case ERROR -> LOGGER.error(text, throwable);
        }
    }
}

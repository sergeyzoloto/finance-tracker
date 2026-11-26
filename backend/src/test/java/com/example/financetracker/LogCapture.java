package com.example.financetracker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures what a logger logs, by a logback appender on that logger. Spring Boot's OutputCaptureExtension swaps
 * System.out, but logback's console appender keeps the stream it had when logback started, so after any Spring
 * context has started in the JVM the captured output is empty. This helper does not depend on the console.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final boolean levelChanged;

    private LogCapture(Logger logger, Level level) {
        this.logger = logger;
        this.previousLevel = logger.getLevel();
        this.levelChanged = level != null;
        if (levelChanged) {
            logger.setLevel(level);
        }
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * Captures every event that reaches logback (the root logger and so every logger, at the level it is configured
     * to) until {@link #close()}. The root's level is not changed.
     */
    public static LogCapture ofRoot() {
        return new LogCapture((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME), null);
    }

    /** Captures the logger of the class at INFO and above until {@link #close()}. */
    public static LogCapture of(Class<?> type) {
        return new LogCapture((Logger) LoggerFactory.getLogger(type), Level.INFO);
    }

    /** Captures the named logger (and its children) at the given level and above until {@link #close()}. */
    public static LogCapture of(String loggerName, Level level) {
        return new LogCapture((Logger) LoggerFactory.getLogger(loggerName), level);
    }

    /** The formatted messages captured so far. */
    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * One entry per event: logger name, level and formatted message, followed by the full throwable text (message,
     * stack and causes) when the event has one.
     */
    public List<String> lines() {
        return appender.list.stream().map(event -> {
            String line = event.getLoggerName() + " " + event.getLevel() + " " + event.getFormattedMessage();
            return event.getThrowableProxy() == null ? line
                    : line + "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
        }).toList();
    }

    /** Detaches the appender, stops it and restores the logger's previous level. */
    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        if (levelChanged) {
            logger.setLevel(previousLevel);
        }
    }
}

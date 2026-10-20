// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what one class logs during a test, for tests that make the code fail on purpose.
 *
 * <p>Why: much of the plug-in catches a failure, logs it and carries on (a failed buffer switch, a
 * replay that could not be typed). A test of such a path used to print the warning and its stack
 * trace to the console, which buried real problems in the test output. Inside
 * {@code try (ExpectedLog log = ExpectedLog.of(...))} the class's log records go to this object
 * instead of the console, and the test can check that the failure was reported, and how:
 *
 * <pre>
 * try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) {
 *     push.start(...);
 *     log.assertLogged(Level.SEVERE, "Could not switch Neovim");
 * }
 * </pre>
 *
 * <p>By default only what the logger passes at its usual level is captured: INFO and above, so
 * {@code FINE} details are not. {@link #of(Class, Level)} lowers the level for the block, to check
 * a {@code FINE} record too ("Response 42 came after its caller stopped waiting; dropped").
 *
 * <p>Only that one logger is captured, only while the block runs: anything else that logs still
 * prints, so an unexpected warning stays visible. Uses {@code java.util.logging}, which the plug-in
 * logs through. Shared by the test modules (each adds {@code tests/shared/src} as a test source).
 */
public final class ExpectedLog implements AutoCloseable {

    /** Held so the logger, and the settings changed on it, are not garbage-collected meanwhile. */
    private final Logger logger;
    private final boolean usedParentHandlers;
    /** The logger's own level before the block; {@code null} means "inherited from its parent". */
    private final Level usedLevel;
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            synchronized (records) {
                records.add(logRecord);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private ExpectedLog(Logger logger, Level capture) {
        this.logger = logger;
        this.usedParentHandlers = logger.getUseParentHandlers();
        this.usedLevel = logger.getLevel();
        if (capture != null) {
            logger.setLevel(capture);
        }
        logger.setUseParentHandlers(false); // not to the console (the root logger's handler)
        logger.addHandler(handler);
    }

    /** Starts capturing what {@code type} logs (its logger is named after the class). */
    public static ExpectedLog of(Class<?> type) {
        return new ExpectedLog(Logger.getLogger(type.getName()), null);
    }

    /**
     * Like {@link #of(Class)}, and also captures the records below INFO down to {@code capture}, for
     * example {@code Level.FINE}. The logger's level is restored on {@link #close()}.
     */
    public static ExpectedLog of(Class<?> type, Level capture) {
        return new ExpectedLog(Logger.getLogger(type.getName()), capture);
    }

    /** Fails unless a record at {@code level} whose message contains {@code messagePart} was logged. */
    public void assertLogged(Level level, String messagePart) {
        List<String> seen = new ArrayList<>();
        synchronized (records) {
            for (LogRecord logRecord : records) {
                if (logRecord.getLevel().equals(level) && String.valueOf(logRecord.getMessage()).contains(messagePart)) {
                    return;
                }
                seen.add(logRecord.getLevel() + " " + logRecord.getMessage());
            }
        }
        fail("expected a " + level + " log containing \"" + messagePart + "\"; logged: " + seen);
    }

    /**
     * Fails if anything was logged at {@code level} or above. Example: a normal shutdown must log
     * nothing at {@code WARNING}, only at {@code FINE}.
     */
    public void assertNothingAtOrAbove(Level level) {
        List<String> seen = new ArrayList<>();
        synchronized (records) {
            for (LogRecord logRecord : records) {
                if (logRecord.getLevel().intValue() >= level.intValue()) {
                    seen.add(logRecord.getLevel() + " " + logRecord.getMessage());
                }
            }
        }
        if (!seen.isEmpty()) {
            fail("expected nothing logged at " + level + " or above; logged: " + seen);
        }
    }

    /** Stops capturing; the logger prints to the console again. */
    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(usedParentHandlers);
        logger.setLevel(usedLevel);
    }
}

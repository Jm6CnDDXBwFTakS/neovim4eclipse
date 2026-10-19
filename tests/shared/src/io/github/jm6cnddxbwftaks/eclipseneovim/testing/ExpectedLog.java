// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.testing;

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
 * <p>Only that one logger is captured, only while the block runs: anything else that logs still
 * prints, so an unexpected warning stays visible. Uses {@code java.util.logging}, which the plug-in
 * logs through. Shared by the test modules (each adds {@code tests/shared/src} as a test source).
 */
public final class ExpectedLog implements AutoCloseable {

    /** Held so the logger, and the settings changed on it, are not garbage-collected meanwhile. */
    private final Logger logger;
    private final boolean usedParentHandlers;
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

    private ExpectedLog(Logger logger) {
        this.logger = logger;
        this.usedParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false); // not to the console (the root logger's handler)
        logger.addHandler(handler);
    }

    /** Starts capturing what {@code type} logs (its logger is named after the class). */
    public static ExpectedLog of(Class<?> type) {
        return new ExpectedLog(Logger.getLogger(type.getName()));
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

    /** Stops capturing; the logger prints to the console again. */
    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(usedParentHandlers);
    }
}

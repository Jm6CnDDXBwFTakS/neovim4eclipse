// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link RepeatedFailures}: the first failure in full, then a count at 10, 100, ...; the rest at FINE. */
class RepeatedFailuresTest {

    private final Logger logger = Logger.getLogger(RepeatedFailuresTest.class.getName() + ".log");
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    RepeatedFailuresTest() {
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void removeHandler() {
        logger.removeHandler(handler);
    }

    @Test
    void theSameFailure250TimesGivesThreeWarnings() {
        RepeatedFailures failures = new RepeatedFailures(logger);
        for (int i = 0; i < 250; i++) {
            failures.log("Notification redraw from Neovim failed", new NoSuchMethodError("stale"));
        }
        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertEquals(3, warnings.size(), "the first, then 10 and 100");
        assertNotNull(warnings.get(0).getThrown(), "the first in full");
        assertNull(warnings.get(1).getThrown());
        assertTrue(warnings.get(2).getMessage().contains("100 times so far"), warnings.get(2).getMessage());
        assertEquals(247, records.stream().filter(r -> r.getLevel() == Level.FINE).count());
    }

    @Test
    void anotherExceptionOrHandlerIsLoggedInFullAgain() {
        RepeatedFailures failures = new RepeatedFailures(logger);
        assertEquals(1, failures.log("Notification redraw from Neovim failed", new NoSuchMethodError()));
        assertEquals(1, failures.log("Notification redraw from Neovim failed", new IllegalStateException()));
        assertEquals(1, failures.log("Notification mode from Neovim failed", new NoSuchMethodError()));
        assertEquals(2, failures.log("Notification redraw from Neovim failed", new NoSuchMethodError()));
        assertEquals(3, records.stream().filter(r -> r.getLevel() == Level.WARNING).count());
    }

    @Test
    void powersOfTen() {
        assertFalse(RepeatedFailures.isPowerOfTen(1));
        assertTrue(RepeatedFailures.isPowerOfTen(10));
        assertTrue(RepeatedFailures.isPowerOfTen(1000));
        assertFalse(RepeatedFailures.isPowerOfTen(20));
        assertFalse(RepeatedFailures.isPowerOfTen(110));
    }
}

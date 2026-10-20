// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code -Declipseneovim.logLevel} switch. The loggers are global (the same ones the plug-in
 * uses), so each test puts back what it changed.
 */
class LogLevelSwitchTest {

    /** The plug-in ID, the parent of all the plug-in's loggers. */
    private static final String PARENT = "io.github.jm6cnddxbwftaks.eclipseneovim";

    /** Collects the records it gets, as a stand-in for the Error Log bridge or the root console. */
    private static final class Collector extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        Collector(Level level) {
            setLevel(level);
        }

        @Override
        public void publish(LogRecord record) {
            if (isLoggable(record)) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private final Logger parent = Logger.getLogger(PARENT);
    private final Logger child = Logger.getLogger(PARENT + ".editor.KeyContextDiagnostics");
    private final Logger root = Logger.getLogger("");
    private final LogLevelSwitch logLevelSwitch = new LogLevelSwitch();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    private final Collector rootConsole = new Collector(Level.ALL);
    private Level rootLevel;

    @BeforeEach
    void listenOnTheRoot() {
        rootLevel = root.getLevel();
        root.addHandler(rootConsole);
    }

    @AfterEach
    void restore() {
        logLevelSwitch.remove();
        root.removeHandler(rootConsole);
        root.setLevel(rootLevel);
    }

    private String printed() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void parseTakesTheLevelNamesInAnyCase() {
        for (String name : LogLevelSwitch.LEVEL_NAMES) {
            assertEquals(Optional.of(Level.parse(name)), LogLevelSwitch.parse(name), name);
        }
        assertEquals(Optional.of(Level.FINE), LogLevelSwitch.parse("fine"));
        assertEquals(Optional.of(Level.FINEST), LogLevelSwitch.parse(" Finest "));
    }

    @Test
    void parseOfNothingIsNoSetting() {
        assertEquals(Optional.empty(), LogLevelSwitch.parse(null));
        assertEquals(Optional.empty(), LogLevelSwitch.parse(""));
        assertEquals(Optional.empty(), LogLevelSwitch.parse("  "));
    }

    /** Numbers would pass {@link Level#parse}, but the switch is documented as level names only. */
    @Test
    void parseRejectsAnUnknownValueAndListsTheValidNames() {
        for (String value : new String[] { "verbose", "500", "FINE!" }) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LogLevelSwitch.parse(value));
            assertTrue(e.getMessage().contains("'" + value + "'"), e.getMessage());
            assertTrue(e.getMessage().contains("-Declipseneovim.logLevel"), e.getMessage());
            assertTrue(e.getMessage().contains("OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL"),
                    e.getMessage());
        }
    }

    @Test
    void atFineAChildsFineRecordIsPrintedOnOneLine() {
        logLevelSwitch.apply(parent, Level.FINE, out);
        child.fine("Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command");
        child.finer("not at FINER");

        String line = printed();
        assertTrue(line.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} FINE KeyContextDiagnostics: "
                + "Ctrl-V \\(M1\\+V\\) resolves to the Neovim Ctrl-key command\\R"), line);
    }

    @Test
    void parametersAndTheStackTraceArePrinted() {
        logLevelSwitch.apply(parent, Level.FINE, out);
        child.log(Level.FINE, "attached {0}", "a.txt");
        child.log(Level.WARNING, "failed", new IllegalStateException("boom"));

        assertTrue(printed().contains("FINE KeyContextDiagnostics: attached a.txt"), printed());
        assertTrue(printed().contains("WARNING KeyContextDiagnostics: failed"), printed());
        assertTrue(printed().contains("java.lang.IllegalStateException: boom"), printed());
        assertTrue(printed().contains("\tat "), "the stack trace: " + printed());
    }

    @Test
    void anInfoRecordIsPrintedOnceNotAgainThroughTheRootHandler() {
        logLevelSwitch.apply(parent, Level.FINE, out);
        child.info("Started Neovim");

        assertEquals(1, printed().split("Started Neovim", -1).length - 1, printed());
        assertTrue(rootConsole.records.isEmpty(), "reached the root handler too");
    }

    @Test
    void atOffNothingIsPrintedButWarningsStillReachTheErrorLogBridge() {
        Collector errorLog = new Collector(Level.WARNING);
        parent.addHandler(errorLog);
        try {
            logLevelSwitch.apply(parent, Level.OFF, out);
            child.warning("Could not set Neovim's tab and indent options");
            child.info("routine");

            assertEquals("", printed());
            assertEquals(1, errorLog.records.size());
            assertEquals("Could not set Neovim's tab and indent options", errorLog.records.get(0).getMessage());
        } finally {
            parent.removeHandler(errorLog);
        }
    }

    @Test
    void removeTakesTheHandlerAwayAndRestoresTheLogger() {
        Level before = parent.getLevel();
        boolean useParentHandlers = parent.getUseParentHandlers();
        int handlers = parent.getHandlers().length;

        logLevelSwitch.apply(parent, Level.FINEST, out);
        assertEquals(Level.FINEST, parent.getLevel());
        assertFalse(parent.getUseParentHandlers());
        assertEquals(handlers + 1, parent.getHandlers().length);

        logLevelSwitch.remove();
        assertEquals(before, parent.getLevel());
        assertEquals(useParentHandlers, parent.getUseParentHandlers());
        assertEquals(handlers, parent.getHandlers().length);
        child.finest("after remove"); // printed before remove
        assertEquals("", printed());
        logLevelSwitch.remove(); // a second remove does nothing
    }
}

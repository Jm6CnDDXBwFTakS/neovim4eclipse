// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The one switch that sets the plug-in's log level: the JVM system property {@value #PROPERTY},
 * for example {@code -Dneovim4eclipse.logLevel=FINE} after {@code -vmargs} in {@code eclipse.ini}.
 * The plug-in's activator reads it when the plug-in starts (README, "If {@code Ctrl-V} does
 * nothing"; design in {@code docs/design/LOG_LEVEL.md}).
 *
 * <h2>How java.util.logging decides what is printed</h2>
 * Every logger of the plug-in is named after its class, so all of them sit below one parent, the
 * plug-in ID: {@code ...neovim4eclipse.editor.KeyContextDiagnostics} is a child of
 * {@code ...neovim4eclipse}. A record passes two gates: the level of the logger (or of the nearest
 * parent that has one; the JDK's root logger is at INFO), then the level of each handler it reaches,
 * walking up the parents. So {@link #apply} sets the parent's level and adds a handler there:
 *
 * <pre>
 *   switch  parent logger  this handler  printed here   Error Log (WARNING handler)
 *   FINE    FINE           FINE          FINE and up    WARNING and up
 *   OFF     WARNING        OFF           nothing        WARNING and up
 * </pre>
 *
 * The parent never goes above WARNING, since its level gates every handler on it, including the
 * plug-in's Error Log bridge. And the plug-in's records stop going on to the root logger's console
 * handler, which would print INFO lines a second time.
 *
 * <p>JUL only, no Eclipse, so it is unit-tested; the activator is the glue.
 */
public final class LogLevelSwitch {

    /** The system property, without the {@code -D}. */
    public static final String PROPERTY = "neovim4eclipse.logLevel";

    /** The names the switch accepts, in any case. (Plain {@link Level#parse} also takes numbers.) */
    public static final List<String> LEVEL_NAMES =
            List.of("OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL");

    /** Held so that JUL, which keeps loggers only weakly, does not drop the logger and its level. */
    private Logger logger;
    private Handler handler;
    private Level savedLevel;
    private boolean savedUseParentHandlers;

    /**
     * The level a property value names: {@code "fine"} is {@link Level#FINE}; empty for
     * {@code null} or blank (the switch is not set).
     *
     * @throws IllegalArgumentException for any other value; the message lists the valid names
     */
    public static Optional<Level> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = value.strip().toUpperCase(Locale.ROOT);
        if (!LEVEL_NAMES.contains(name)) {
            throw new IllegalArgumentException("Unknown log level '" + value.strip() + "' in -D" + PROPERTY
                    + "; use one of " + String.join(", ", LEVEL_NAMES));
        }
        return Optional.of(Level.parse(name));
    }

    /**
     * Prints the records of {@code parent} and its children at {@code level} and above to
     * {@code out}, one line each (see {@link LineFormatter}). Undo with {@link #remove}.
     */
    public void apply(Logger parent, Level level, PrintStream out) {
        remove();
        logger = parent;
        savedLevel = parent.getLevel();
        savedUseParentHandlers = parent.getUseParentHandlers();
        handler = new PrintStreamHandler(out);
        handler.setLevel(level);
        parent.setLevel(level.intValue() < Level.WARNING.intValue() ? level : Level.WARNING);
        parent.setUseParentHandlers(false);
        parent.addHandler(handler);
    }

    /** Removes the handler and restores the logger as it was (the plug-in stops). */
    public void remove() {
        if (logger != null) {
            logger.removeHandler(handler);
            logger.setLevel(savedLevel);
            logger.setUseParentHandlers(savedUseParentHandlers);
            logger = null;
            handler = null;
        }
    }

    /** Writes each record at once, and never closes the stream (it is {@code System.err}). */
    private static final class PrintStreamHandler extends Handler {

        private final PrintStream out;

        PrintStreamHandler(PrintStream out) {
            this.out = out;
            setFormatter(new LineFormatter());
        }

        @Override
        public void publish(LogRecord record) {
            if (isLoggable(record)) {
                out.print(getFormatter().format(record));
                out.flush();
            }
        }

        @Override
        public void flush() {
            out.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }

    /**
     * One line per record, then the stack trace if there is one. For example:
     *
     * <pre>
     * 06:50:12.345 FINE KeyContextDiagnostics: Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command
     * </pre>
     *
     * The time is the local time; the source is the last part of the logger name, which is the
     * class's simple name.
     */
    static final class LineFormatter extends Formatter {

        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            StringBuilder line = new StringBuilder();
            line.append(TIME.format(LocalTime.ofInstant(record.getInstant(), ZoneId.systemDefault())));
            line.append(' ').append(record.getLevel().getName());
            String source = record.getLoggerName();
            if (source != null) {
                line.append(' ').append(source.substring(source.lastIndexOf('.') + 1)).append(':');
            }
            line.append(' ').append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * The plug-in's last {@value #CAPACITY} warnings and errors of this run, for the problem report
 * ({@link ProblemReport}): what went wrong just before someone opened <i>Report a Problem…</i>,
 * without asking them to find the Error Log or start Eclipse with a log option.
 *
 * <p>A {@code java.util.logging} handler on the plug-in's root logger (the activator installs it),
 * so every class's records pass it; it keeps only {@code WARNING} and above, each as one line
 * formatted when it arrives. Example:
 *
 * <pre>
 * 14:03:12 WARNING TextRepairs: Neovim's copy of a .txt file in the Text Editor differed from …
 * 14:05:40 SEVERE BufferSynchronizer: Failed to push document to Neovim (IOException: Broken pipe)
 * </pre>
 *
 * <p>Only the message and the exception's own message, never the stack trace: a report is pasted
 * into an issue, and the lines should be few and short. Thread-safe: records come from every
 * thread.
 */
public final class RecentWarnings extends Handler {

    /** How many lines are kept: the newest replace the oldest. */
    public static final int CAPACITY = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Deque<String> lines = new ArrayDeque<>();
    /** Fills in a message's {@code {0}} parameters, as the Error Log shows the message. */
    private final SimpleFormatter formatter = new SimpleFormatter();

    public RecentWarnings() {
        setLevel(Level.WARNING);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        String line = line(record, formatter.formatMessage(record));
        synchronized (lines) {
            if (lines.size() == CAPACITY) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
    }

    /** The lines kept, oldest first. */
    public List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    /** One record as one line: time, level, the logger's class name without its package, message, exception. */
    static String line(LogRecord record, String message) {
        String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
        String name = logger.substring(logger.lastIndexOf('.') + 1);
        StringBuilder line = new StringBuilder(TIME.format(Instant.ofEpochMilli(record.getMillis()))).append(' ')
                .append(record.getLevel().getName()).append(' ').append(name).append(": ")
                .append(message == null ? "" : message.replace('\n', ' '));
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            line.append(" (").append(thrown.getClass().getSimpleName());
            if (thrown.getMessage() != null) {
                line.append(": ").append(thrown.getMessage().replace('\n', ' '));
            }
            line.append(')');
        }
        return line.toString();
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        synchronized (lines) {
            lines.clear();
        }
    }
}

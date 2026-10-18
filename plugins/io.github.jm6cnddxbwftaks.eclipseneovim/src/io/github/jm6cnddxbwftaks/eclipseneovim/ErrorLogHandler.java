// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Copies the plug-in's warnings and errors to Eclipse's Error Log.
 *
 * <p><b>Two logging systems.</b> The plug-in logs with {@code java.util.logging} (JUL), because
 * {@code core/} and {@code util/} must work without Eclipse (they are unit-tested outside it).
 * JUL writes to the console of the Eclipse process only. The Error Log view (<em>Window › Show
 * View › Error Log</em>) shows Eclipse's own log, {@link ILog}. This JUL handler, installed on the
 * plug-in's top logger, bridges the two for {@link Level#WARNING} and {@link Level#SEVERE}; INFO and
 * below stay on the console, so the Error Log is not filled with routine messages.
 *
 * <p>Example: {@code LOG.warning("Could not set Neovim's tab and indent options")} in any plug-in
 * class appears in the Error Log as a warning of this plug-in.
 */
final class ErrorLogHandler extends Handler {

    private final ILog log;
    private final SimpleFormatter formatter = new SimpleFormatter();
    /** Held so that JUL, which keeps loggers only weakly, does not drop the logger and its handler. */
    private Logger installedOn;

    ErrorLogHandler(ILog log) {
        this.log = log;
        setLevel(Level.WARNING);
    }

    /** Adds this handler to the logger named {@code loggerName} and the loggers below it. */
    void install(String loggerName) {
        installedOn = Logger.getLogger(loggerName);
        installedOn.addHandler(this);
    }

    /** Removes this handler again (the plug-in stops). */
    void uninstall() {
        if (installedOn != null) {
            installedOn.removeHandler(this);
            installedOn = null;
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        try {
            int severity = record.getLevel().intValue() >= Level.SEVERE.intValue() ? IStatus.ERROR : IStatus.WARNING;
            String message = formatter.formatMessage(record);
            String source = record.getLoggerName();
            if (source != null) {
                message = message + " (" + source.substring(source.lastIndexOf('.') + 1) + ")";
            }
            log.log(new Status(severity, Activator.PLUGIN_ID, message, record.getThrown()));
        } catch (RuntimeException e) {
            reportError("Could not write to the Error Log", e, java.util.logging.ErrorManager.WRITE_FAILURE);
        }
    }

    @Override
    public void flush() {
        // ILog writes at once
    }

    @Override
    public void close() {
        uninstall();
    }
}

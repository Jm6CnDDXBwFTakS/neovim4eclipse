// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.RepairReport;

/**
 * Reports a push that repaired Neovim's buffer: a difference from the document in a line no
 * recorded change explains ({@link ChangedLines.Known#firstUnexplained}).
 *
 * <p>Only pushes, which write Eclipse's lines into Neovim. The other way, a read of Neovim's whole
 * buffer (at the start of an insert, after a {@code :} command, {@code StateSync.syncBuffer}),
 * is not checked: that read is also how some of Neovim's own changes reach the document (a macro
 * replayed with {@code @a}, a script's {@code :edit!}), so a line it brings that no change
 * explains is not a drift there: checked, those would be false alarms.
 *
 * <h2>Why</h2>
 * A push of a document of up to {@link ChangedLines#COMPARE_WHOLE_UP_TO_LINES} lines compares
 * Neovim's whole buffer and writes every difference into it: a safety net, which makes the two
 * copies equal again whatever made them differ (a lost line event, a bug elsewhere). Its repairs
 * were silent, so a bug that made the copies drift stayed hidden: in the meantime, a Neovim
 * command run on its stale copy could change the wrong text. Example: Eclipse shows
 * {@code ["a", "b"]}, Neovim's buffer holds {@code ["a", "X"]}; {@code yy} on line 2 copies
 * {@code "X"}, and {@code p} pastes a line the user never saw. The repair at the next {@code Esc}
 * then puts {@code "b"} back into Neovim, and nothing tells anyone.
 *
 * <h2>What it does</h2>
 * The first repair of a document is a WARNING, so it reaches Eclipse's Error Log, where a user can
 * copy it into an issue ({@link RepairReport}: where the copies differed, when, what kind of file,
 * the versions); the editor then tells the user once per Neovim session
 * ({@link RepairReporting#reported}). Later repairs of the same document are logged at
 * {@code FINE}: one report says what is needed, and a warning on every {@code Esc} would bury the
 * Error Log. The UI tests capture this logger at every level and fail on any record
 * ({@code EndToEndTestBase}).
 *
 * <p>The message holds no text of the file, nor its name: a log is shared. The two differing lines
 * travel in fields of the record's own class ({@link RepairRecord}), for a test's failure message.
 * Not as the record's parameters: Java's {@code XMLFormatter} (a {@code FileHandler}'s default)
 * writes every parameter of a message without placeholders, so a user logging to a file would
 * have file text in it.
 *
 * <p>Which pushes are checked: those at {@code Esc} and at a sync point, the push of Eclipse's own
 * changes in normal mode (a format, a refactoring, Eclipse's undo), the comparison after a save,
 * and the one when an editor comes back whose document did not change meanwhile. Not the push when
 * an editor attaches to a changed document or Neovim mode comes back on: that one carries what
 * changed while Neovim was not following, which no record covers.
 */
public final class TextRepairs {

    private static final Logger LOG = Logger.getLogger(TextRepairs.class.getName());

    private TextRepairs() {
    }

    /**
     * A repair's log record, with the two lines that differed in fields no log formatter knows of:
     * only code that looks for this class reads them (the UI tests' failure message).
     */
    public static final class RepairRecord extends LogRecord {
        private static final long serialVersionUID = 1L;
        /** The line as Neovim had it, or {@code "(no such line)"}. */
        private final transient String neovimLine;
        /** The line as the document has it, or {@code "(no such line)"}. */
        private final transient String documentLine;

        RepairRecord(Level level, String message, String neovimLine, String documentLine) {
            super(level, message);
            this.neovimLine = neovimLine;
            this.documentLine = documentLine;
            setLoggerName(LOG.getName());
        }

        public String neovimLine() {
            return neovimLine;
        }

        public String documentLine() {
            return documentLine;
        }
    }

    /**
     * Reports a repair if the push found a line no recorded change explains.
     *
     * @param known     what the document's {@link ChangedLines} knew when the pushed lines were read
     * @param neovim    Neovim's buffer as the push found it
     * @param pushed    the lines the push wrote
     * @param when      when the push ran, for the message ({@code "at Esc or a sync point"})
     * @param reporting the editor's part of the report
     * @param first     whether this is the document's first repair (a WARNING); claims it when it is
     */
    static void check(ChangedLines.Known known, List<String> neovim, List<String> pushed, String when,
            RepairReporting reporting, BooleanSupplier first) {
        ChangedLines.Unexplained found = known.firstUnexplained(neovim, pushed);
        if (found == null) {
            return;
        }
        boolean report = first.getAsBoolean();
        Level level = report ? Level.WARNING : Level.FINE;
        if (LOG.isLoggable(level)) {
            String message = new RepairReport(reporting.fileKind(), when, found.neovimLine(), found.pushedLine(),
                    neovim.size(), pushed.size(), reporting.neovimVersion(), reporting.pluginVersion()).message();
            LOG.log(new RepairRecord(level, message, lineOrNone(neovim, found.neovimLine()),
                    lineOrNone(pushed, found.pushedLine())));
        }
        if (report) {
            reporting.reported();
        }
    }

    private static String lineOrNone(List<String> lines, int line) {
        return line < lines.size() ? lines.get(line) : "(no such line)";
    }
}

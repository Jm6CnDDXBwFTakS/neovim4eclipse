// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;

/**
 * Reports a push that repaired Neovim's buffer: a difference from the document in a line no
 * recorded change explains ({@link ChangedLines.Known#firstUnexplained}).
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
 * Logs each repair at {@code FINE} on this class's logger, so that users see nothing (FINE does
 * not reach the Error Log), and the UI tests, which capture this logger for every test, fail on it
 * ({@code EndToEndTestBase}). The message names the buffer and the line numbers, never any text:
 * a log may be shared. The two differing lines travel in fields of the record's own class
 * ({@link RepairRecord}), for a test's failure message. Not as the record's parameters: Java's
 * {@code XMLFormatter} (a {@code FileHandler}'s default) writes every parameter of a message without
 * placeholders, so a user logging at {@code FINE} to a file would have file text in it.
 *
 * <p>Which pushes are checked: those at {@code Esc} and at a sync point, and the push of Eclipse's
 * own changes in normal mode (a format, a refactoring, Eclipse's undo). Not the push when an editor
 * attaches or Neovim mode comes back on: that one carries what changed while Neovim was not
 * following, which no record covers.
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

        RepairRecord(String message, String neovimLine, String documentLine) {
            super(Level.FINE, message);
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
     * Logs a repair if the push found a line no recorded change explains.
     *
     * @param known  what the document's {@link ChangedLines} knew when the pushed lines were read
     * @param neovim Neovim's buffer as the push found it
     * @param pushed the lines the push wrote
     * @param what   which buffer, for the message (example: {@code "buffer 3"})
     * @return whether a repair was logged
     */
    static boolean check(ChangedLines.Known known, List<String> neovim, List<String> pushed, String what) {
        ChangedLines.Unexplained found = known.firstUnexplained(neovim, pushed);
        if (found == null) {
            return false;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(new RepairRecord("A push repaired Neovim's " + what + ": its line "
                    + (found.neovimLine() + 1) + " differed from the document's line " + (found.pushedLine() + 1)
                    + ", which no change explains (Neovim had " + neovim.size() + " lines, the document "
                    + pushed.size() + ")", lineOrNone(neovim, found.neovimLine()), lineOrNone(pushed, found.pushedLine())));
        }
        return true;
    }

    private static String lineOrNone(List<String> lines, int line) {
        return line < lines.size() ? lines.get(line) : "(no such line)";
    }

    /**
     * The lines Neovim's buffer should hold for {@code document}, as a push would write them, without
     * deciding its final newline (a read only). For the tests' check at the end of each test. UI thread.
     */
    public static List<String> linesNeovimShouldHave(IDocument document) {
        return FinalNewlines.linesForNeovim(document);
    }
}

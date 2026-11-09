// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferChange;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineRangeEdit;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineRangeEdit.Edit;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineTextUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TextDiff;

/**
 * Keeps one Eclipse {@link IDocument} in sync with the Neovim buffer.
 *
 * <p>Direction of flow:
 * <ul>
 *   <li><b>Eclipse to Neovim</b>: only whole-document pushes ({@link #pushDocumentToNeovim()}),
 *       on attach, when Neovim mode is re-enabled, when leaving insert mode, and after a change
 *       Eclipse makes by itself outside insert mode ({@link #pushLocalChanges}: a format, a
 *       refactoring, Eclipse's undo, a revert). While in insert mode Eclipse owns typing and Neovim
 *       learns about it at the push. A push changes only the lines that differ, so the buffer's
 *       undo history and marks survive it.</li>
 *   <li><b>Neovim to Eclipse</b>: line-range events from {@code nvim_buf_lines_event}, applied as
 *       minimal edits on the UI thread. A full buffer ({@code lastLine == -1}) is applied as the
 *       smallest edit that makes the texts equal.</li>
 * </ul>
 *
 * <p><b>Ordering:</b> call {@link #pushDocumentToNeovim()} <em>before</em>
 * {@link #startRemoteListening()} on attach, so that the echo of the initial {@code set_lines}
 * cannot rewrite the Eclipse document.
 *
 * <p>All document mutation happens on the UI thread; {@link #isApplyingRemote()} lets UI listeners
 * tell a Neovim-driven change (caret moves, etc.) from a user-driven one.
 */
public final class BufferSynchronizer implements AutoCloseable, DocumentPush.Mirror {

    private static final Logger LOG = Logger.getLogger(BufferSynchronizer.class.getName());

    private final IDocument document;
    private final BufferPort session;
    private final UiExecutor ui;
    private final AtomicBoolean applyingRemote = new AtomicBoolean(false);
    /** Document changes seen while one Neovim change was applied (more than 1: see {@link #applyChange}). */
    private int changesWhileApplying;
    /**
     * Eclipse changed the document in reaction to a Neovim change, and Neovim has not been sent
     * the result yet. Until then, Neovim's full-text syncs are out of date and are not applied.
     */
    private volatile boolean neovimBehind;
    private final Consumer<BufferChange> bufferChangeListener = this::onNeovimBufferChange;
    /** Groups the changes of one Neovim command into one Eclipse undo step. UI thread. */
    private final UndoSteps undoSteps;
    /** Neovim has handled a key: the undo step of its changes ends (on the UI thread, after them). */
    private final Runnable inputHandledListener = this::closeUndoStepSoon;

    private volatile boolean listening;
    private volatile boolean closed;
    /** Called on the UI thread after each remote mutation, to put the caret back where Neovim has it. */
    private volatile Runnable caretRestorer;
    /** When Eclipse's own document changes are pushed (see {@link #pushLocalChanges}); {@code null}: never. */
    private volatile BooleanSupplier localPushGate;
    /** Runs on the session's input thread after each such push (see {@link #pushLocalChanges}). */
    private volatile Runnable afterLocalPush;
    /** A push of Eclipse's own changes is queued and has not read the document yet. */
    private final AtomicBoolean localPushPending = new AtomicBoolean(false);
    private final IDocumentListener localChangeListener = new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            if (applyingRemote.get()) {
                changesWhileApplying++;
                return;
            }
            onLocalChange();
        }
    };

    /**
     * The lines that may differ from Neovim's buffer since a push last made it equal
     * ({@link #pushChangedLinesToNeovim}). Every change counts, Neovim's applied ones too. UI thread.
     */
    private final ChangedLines changedLines = new ChangedLines();
    /**
     * Neovim's lines that the document shows as more than one (a {@code \r} inside them), until a
     * push gives Neovim the document's lines: Neovim's line events are translated through it. Cleared
     * with {@link #changedLines} when a push reads the document. UI thread.
     */
    private final SplitLines splitLines = new SplitLines();
    /** The document's buffer, or {@code null} for the attached one (see the constructor). */
    private final DocumentBuffer buffer;
    private final IDocumentListener changedLinesListener = new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
            try {
                int offset = event.getOffset();
                changedLines.record(document.getLineOfOffset(offset),
                        document.getLineOfOffset(offset + event.getLength()), document.getNumberOfLines());
            } catch (BadLocationException e) {
                changedLines.forget(); // cannot tell where: the next push writes everything
            }
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            // the lines before the change are what count
        }
    };

    /**
     * For a document with no buffer of its own (Neovim's home buffer), or a test of its sync.
     *
     * @param ui the UI thread, where the document is changed ({@code SwtUiExecutor} in the editor)
     */
    public BufferSynchronizer(IDocument document, BufferPort session, UiExecutor ui) {
        this(document, null, session, ui);
    }

    /**
     * @param buffer the document's buffer in Neovim, which work queued for later acts on (the split
     *               at carriage returns) even if another editor is attached by then; {@code null}
     *               for the attached buffer
     * @param ui     the UI thread, where the document is changed ({@code SwtUiExecutor} in the editor)
     */
    public BufferSynchronizer(IDocument document, DocumentBuffer buffer, BufferPort session, UiExecutor ui) {
        this.document = document;
        this.buffer = buffer;
        this.session = session;
        this.ui = ui;
        this.undoSteps = new UndoSteps(document);
        document.addDocumentListener(changedLinesListener);
    }

    public void setCaretRestorer(Runnable caretRestorer) {
        this.caretRestorer = caretRestorer;
    }

    /** @return {@code true} while a Neovim-originated change is being written into the document */
    public boolean isApplyingRemote() {
        return applyingRemote.get();
    }

    /**
     * Pushes every change Eclipse makes to the document by itself to Neovim, while {@code when} is
     * true. Without this, a format (Ctrl+Shift+F) or a refactoring in normal mode would leave
     * Neovim's buffer behind, and Neovim's next edit would be applied at stale line numbers.
     *
     * <p>Changes applied from Neovim are not pushed back ({@link #isApplyingRemote()}). Several
     * changes in a row (a refactoring makes many) are coalesced: one push is queued on the session's
     * input thread, and it reads the document when it runs. Being queued there also orders it
     * before any key typed afterward.
     *
     * <p>After the push, {@code afterPush} runs on the input thread. The editor uses it to move
     * Neovim's cursor to Eclipse's caret: Eclipse's undo puts the caret on the line it brings back,
     * but inserting that line above Neovim's cursor moves the cursor one line down.
     *
     * @param when      evaluated on the UI thread at each change; the editor passes "Neovim mode is on
     *                  and Neovim is not in insert mode" (typing in insert mode is pushed when it ends)
     * @param afterPush run on the session's input thread after each push; may block on RPC
     */
    public void pushLocalChanges(BooleanSupplier when, Runnable afterPush) {
        localPushGate = when;
        afterLocalPush = afterPush;
        document.addDocumentListener(localChangeListener);
    }

    private void onLocalChange() {
        BooleanSupplier gate = localPushGate;
        if (closed || gate == null || !gate.getAsBoolean()) {
            return;
        }
        if (!localPushPending.compareAndSet(false, true)) {
            return; // the queued push has not read the document yet: it will include this change
        }
        session.runAsync("push Eclipse's change", () -> {
            localPushPending.set(false); // before reading: a change from now on queues another push
            if (closed) {
                return;
            }
            // Neovim echoes the push as line events; applying them could undo a newer Eclipse change.
            boolean wasListening = listening;
            stopRemoteListening();
            try {
                pushWhole(true); // Eclipse's own change: a difference elsewhere is a repair
            } finally {
                if (wasListening) {
                    startRemoteListening();
                }
            }
            Runnable after = afterLocalPush;
            if (after != null && !closed) {
                try {
                    after.run();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "after-push action failed", e);
                }
            }
        });
    }

    /** Starts applying Neovim to Eclipse changes. Call only after the initial push. */
    @Override
    public void startRemoteListening() {
        if (listening || closed) {
            return;
        }
        listening = true;
        session.addBufferChangeListener(bufferChangeListener);
        session.addInputHandledListener(inputHandledListener);
        LOG.fine("BufferSynchronizer: remote listening enabled");
    }

    /**
     * Stops applying Neovim changes to Eclipse. Call before {@link #pushDocumentToNeovim()}
     * when re-syncing an already attached editor, then call {@link #startRemoteListening()} again.
     *
     * <p>Why: Neovim echoes a push back as a line event, which is applied with {@code syncExec}
     * on the UI thread. If the UI thread is itself waiting for the push, that deadlocks.
     */
    @Override
    public void stopRemoteListening() {
        listening = false;
        session.removeBufferChangeListener(bufferChangeListener);
        session.removeInputHandledListener(inputHandledListener);
        closeUndoStepSoon(); // no more changes from Neovim for now: their step ends
    }

    /**
     * Makes the Neovim buffer equal to the Eclipse document, changing only the lines that differ.
     * Blocking RPC: not for the UI thread.
     *
     * <p>The document's text is read on the UI thread ({@link #onUi}), the buffer
     * written here. A JFace document is not thread-safe: read from this thread while the UI thread
     * edits it (linked mode, a formatter, save actions), it could return torn text or throw.
     */
    @Override
    public void pushDocumentToNeovim() {
        pushWhole(false);
    }

    /** The document's lines for a whole push, and what {@link #changedLines} knew as they were read. */
    private record WholePush(List<String> lines, ChangedLines.Known known) {
    }

    /**
     * {@link #pushDocumentToNeovim}, and with {@code checkRepair} also reports a difference the push
     * repaired in a line no recorded change explains ({@link TextRepairs}). Only a push whose
     * {@link #changedLines} covers every change since the buffer last equaled the document can
     * tell: the push at {@code Esc} or a sync point and that of Eclipse's own changes. Not the push
     * at attach, which carries what changed while Neovim was not followed.
     */
    private void pushWhole(boolean checkRepair) {
        if (closed) {
            return;
        }
        try {
            WholePush push = onUi(() -> {
                // Read in the same step as the lines, before the clear: no edit falls between. After
                // linesToPush, which decides the final newline, so that FinalNewlines.of does not
                // read the whole text a second time.
                List<String> lines = FinalNewlines.linesToPush(document);
                ChangedLines.Known known = checkRepair
                        ? changedLines.known(endsWithLineBreak() && FinalNewlines.of(document))
                        : null;
                changedLines.clear();
                splitLines.clear(); // Neovim gets the document's lines, split as Eclipse splits them
                return new WholePush(lines, known);
            });
            if (push == null) {
                return; // the UI is gone or the editor closed meanwhile
            }
            List<String> lines = push.lines();
            List<String> before = session.setBufferLines(lines);
            if (push.known() != null && before != null) {
                TextRepairs.check(push.known(), before, lines.isEmpty() ? List.of("") : lines,
                        buffer != null ? "buffer " + buffer.bufnr() : "attached buffer");
            }
            neovimBehind = false;
            LOG.fine(() -> "Pushed " + lines.size() + " lines to Neovim");
        } catch (Exception e) {
            // Neovim lacks the document now (an empty buffer, if this was the first load), so
            // Neovim's whole-buffer text must not replace it: the next push writes everything
            // instead. Without this, a failed first load (before 0.2.1, a file of 33,000 lines did
            // that) let the next full-buffer sync from Neovim empty the Eclipse document.
            neovimBehind = true;
            onUi(() -> {
                changedLines.forget();
                return null;
            });
            LOG.log(Level.SEVERE, "Failed to push document to Neovim", e);
        }
    }

    /** See {@link ChangedLines#COMPARE_WHOLE_UP_TO_LINES}; tests lower it to see the region push on short documents. */
    private int compareWholeUpToLines = ChangedLines.COMPARE_WHOLE_UP_TO_LINES;

    /** Sets {@link #compareWholeUpToLines} (tests). */
    void setCompareWholeUpToLines(int lines) {
        compareWholeUpToLines = lines;
    }

    /**
     * Makes the Neovim buffer equal to the document by writing only the lines changed since the
     * last push ({@link ChangedLines}): at {@code Esc} and at a sync point, the lines typed in insert
     * mode, not the whole document. Example: one line typed into a file of 100k lines reads and
     * writes that line, where {@link #pushDocumentToNeovim} reads Neovim's whole buffer (19 ms) to
     * find it. The whole document is pushed (and compared, which repairs any difference) when it has
     * at most {@link ChangedLines#COMPARE_WHOLE_UP_TO_LINES} lines, when the change may reach the
     * last line (its final line break), when nothing is known yet, or when Neovim's buffer cannot be
     * written in part.
     * Blocking RPC: not for the UI thread.
     */
    public void pushChangedLinesToNeovim() {
        if (closed) {
            return;
        }
        try {
            RegionPush push = onUi(() -> {
                if (document.getNumberOfLines() <= compareWholeUpToLines) {
                    return null; // cheap enough to compare all of it, which repairs any difference
                }
                ChangedLines.Region region = changedLines.region(document.getNumberOfLines(), endsWithLineBreak()
                        && FinalNewlines.of(document));
                if (region == null) {
                    return null;
                }
                changedLines.clear();
                // The region holds every split line: Neovim's applied changes count among the
                // changed lines, and a line above the region has the same number on both sides.
                splitLines.clear();
                return new RegionPush(region, region.isEmpty() ? List.of() : linesOf(region.first(), region.end()));
            });
            if (push == null) {
                pushWhole(true); // the whole document, or the UI is gone (then it does nothing)
                return;
            }
            if (!push.region().isEmpty() && !session.setBufferRegion(push.region().first(),
                    push.region().unchangedAtEnd(), push.lines())) {
                pushDocumentToNeovim(); // the record was cleared with the region read: nothing to check
                return;
            }
            neovimBehind = false;
            LOG.fine(() -> "Pushed lines " + push.region() + " to Neovim");
        } catch (Exception e) {
            onUi(() -> {
                changedLines.forget();
                return null;
            });
            LOG.log(Level.SEVERE, "Failed to push the changed lines to Neovim", e);
        }
    }

    /** Whether the document's last character is a line break ({@code \n} or {@code \r}); cheap, unlike {@code get()}. UI thread. */
    private boolean endsWithLineBreak() {
        int length = document.getLength();
        try {
            char last = length == 0 ? 0 : document.getChar(length - 1);
            return last == '\n' || last == '\r';
        } catch (BadLocationException e) {
            return false;
        }
    }

    /** What {@link #pushChangedLinesToNeovim} writes: the region and the document's lines in it. */
    private record RegionPush(ChangedLines.Region region, List<String> lines) {
    }

    /** The document's lines {@code first .. end - 1}, without their line delimiters. UI thread. */
    private List<String> linesOf(int first, int end) {
        List<String> lines = new ArrayList<>(end - first);
        try {
            for (int line = first; line < end; line++) {
                IRegion info = document.getLineInformation(line);
                lines.add(document.get(info.getOffset(), info.getLength()));
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException("line out of the document", e);
        }
        return lines;
    }


    /**
     * {@code read}'s result, read on the UI thread, where the document changes, so between two of
     * its events. Waits for the UI thread when called from another one; the UI thread never waits
     * for the session's threads, so this cannot deadlock.
     *
     * @return the result, or {@code null} if there is no UI any more or the synchronizer was closed
     */
    private <T> T onUi(Supplier<T> read) {
        if (ui.isUiThread()) {
            return read.get();
        }
        if (!ui.isAvailable()) {
            return null;
        }
        List<T> result = new ArrayList<>(1);
        ui.syncExec(() -> {
            if (!closed) {
                result.add(read.get());
            }
        });
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Eclipse document to Neovim lines. The empty "line" after a final line break is not a
     * Neovim line, and a final newline is assumed. For reading a document's lines (its indentation);
     * a push uses {@link FinalNewlines#linesToPush}, which knows the document's final newline.
     */
    public static List<String> documentToLines(IDocument document) {
        return LineTextUtil.splitDocumentLines(document.get());
    }

    // ---- Neovim to Eclipse ----

    private void onNeovimBufferChange(BufferChange change) {
        if (closed) {
            return;
        }
        if (change.changesNothing()) {
            runOnUi(() -> runAfterApplyOnceSplit(change)); // in order with the changes before it
            return;
        }
        if (!listening) {
            // A Neovim change the document does not get: lines may differ anywhere. Posted, not
            // waited for: while not listening, the UI thread may itself wait for a push (see
            // stopRemoteListening), and waiting for it here would deadlock.
            if (ui.isAvailable()) {
                ui.asyncExec(changedLines::forget);
            }
            return;
        }
        runOnUi(() -> applyChange(change));
    }

    private void applyChange(BufferChange change) {
        if (!listening) {
            changedLines.forget(); // as above
            return;
        }
        applyingRemote.set(true);
        changesWhileApplying = 0;
        try {
            boolean fullBuffer = change.lastLine() < 0;
            boolean newline = FinalNewlines.of(document);
            String delimiter = lineDelimiter(document);
            if (fullBuffer && neovimBehind) {
                changedLines.forget(); // Neovim's whole buffer is not applied: the next push writes everything
            }
            Edit edit;
            int linesBefore = document.getNumberOfLines();
            int eclipseSpan; // how many document lines Neovim's new lines take, before the edit
            if (fullBuffer) {
                // Neovim's text as it is, carriage returns too: right even while lines are split.
                edit = neovimBehind ? null : fullBufferEdit(document, change.newLines(), newline);
                splitLines.replacedAll(change.newLines(), delimiter);
                eclipseSpan = UnattachedDocumentSync.neovimLineCount(document, newline);
            } else {
                // Neovim's line numbers, turned into the document's while a line is split (SplitLines).
                int first = splitLines.eclipseLine(change.firstLine());
                int last = splitLines.eclipseLine(change.lastLine());
                // Lines the document has already, but with other line breaks, stay: Neovim's split
                // of "\ra" into "" and "a" (BufferPort.splitLineBreaks) would otherwise turn the
                // document's "\r" into its delimiter, and a save would write another byte.
                edit = hasLines(first, last, change.newLines()) ? null
                        : rangeEdit(first, last, change.newLines(), delimiter, newline);
                splitLines.replaced(change.firstLine(), change.lastLine(), change.newLines(), delimiter);
                eclipseSpan = last - first;
            }
            if (edit != null) {
                undoSteps.beforeRemoteChange(); // one undo step per Neovim command (see UndoSteps)
                document.replace(edit.offset(), edit.length(), edit.text());
            }
            // The lines the new text takes now, as the document counts them (SplitLines.correct).
            if (!(fullBuffer && neovimBehind)) { // a skipped full sync says nothing about the document
                splitLines.correct(fullBuffer ? 0 : change.firstLine(), change.newLines().size(),
                        eclipseSpan + document.getNumberOfLines() - linesBefore);
            }
        } catch (Exception e) {
            changedLines.forget(); // the document lacks Neovim's change, wherever it was
            LOG.log(Level.WARNING, "Error applying Neovim change " + change, e);
        } finally {
            applyingRemote.set(false);
            restoreCaret();
        }
        // More than one change: Eclipse reacted to Neovim's edit with edits of its own, inside the
        // same replace. In a linked rename (Alt+Shift+R), "x" on the name is copied to every other
        // occurrence; Neovim must get those copies, or its next edit would hit stale text. The
        // full-text sync Neovim sends after the key would also undo them, so those are skipped
        // until the push (in insert mode, the one when insert mode ends).
        //
        if (changesWhileApplying > 1) {
            neovimBehind = true;
            onLocalChange();
        } else if (!splitLines.isEmpty()) {
            // A line of Neovim's holds a line break of Eclipse's: Neovim's "\ra" (Ctrl-V Ctrl-M, or
            // :s with nr2char(13)) is one line there and two here (SplitLines). Neovim splits its
            // line the same way (BufferPort.splitLineBreaks), so both count the same lines again;
            // the document keeps the "\r", so a save writes Neovim's bytes.
            //
            // Neovim is not marked behind: its text is right, only its line numbers differ, and
            // SplitLines translates them, so its full syncs still apply. The split runs on the input
            // thread, behind the keys sent so far, and needs no gate: it is one step in Neovim
            // between two keys, and its line events come in Neovim's order. Example: qa i Ctrl-V
            // Ctrl-M b Esc q, where Neovim types the whole insert (it is recording): each key's
            // line event lands on the right lines until the split, and the split's own events
            // change nothing here.
            requestSplit();
        }
        runAfterApplyOnceSplit(change);
    }

    /**
     * The edit for Neovim's lines replacing the document's lines {@code first .. last - 1} (the
     * document's numbers). As {@link LineRangeEdit}, except for one line holding a carriage return:
     * that one replaces the line with its line break, not its text only.
     *
     * <p>Why: a line of Neovim's that ends in {@code \r}, before the document's {@code \n}, is one
     * line here ending in {@code \r\n} ({@link SplitLines#breaksIn}). Example: Ctrl-V Ctrl-M at the
     * end of "beta1", then "2". The document reads "beta1\r\n"; Neovim sends "beta1\r2". Replacing
     * the line's text "beta1" kept the "\r\n" after it: "beta1\r2\r\n", one carriage return too many.
     * Replacing "beta1\r\n" with "beta1\r2\n" gives Neovim's text. UI thread.
     */
    private Edit rangeEdit(int first, int last, List<String> lines, String delimiter, boolean finalNewline) {
        if (lines.size() == 1 && last == first + 1 && lines.get(0).indexOf('\r') >= 0
                && last < document.getNumberOfLines()) {
            try {
                int start = document.getLineOffset(first);
                int end = document.getLineOffset(last);
                Edit inSpan = TextDiff.minimalEdit(document.get(start, end - start), lines.get(0) + delimiter);
                return inSpan == null ? null : new Edit(start + inSpan.offset(), inSpan.length(), inSpan.text());
            } catch (BadLocationException e) {
                // the general rule below
            }
        }
        return LineRangeEdit.compute(new DocumentLineSource(document), first, last, lines, delimiter, finalNewline);
    }

    /**
     * Whether the document's lines {@code first .. last - 1} read {@code lines}, line delimiters
     * aside. Only when their count matches and they are inside the document. UI thread.
     */
    private boolean hasLines(int first, int last, List<String> lines) {
        if (last - first != lines.size() || lines.isEmpty() || last > document.getNumberOfLines()) {
            return false;
        }
        try {
            for (int i = 0; i < lines.size(); i++) {
                IRegion line = document.getLineInformation(first + i);
                if (!document.get(line.getOffset(), line.getLength()).equals(lines.get(i))) {
                    return false;
                }
            }
            return true;
        } catch (BadLocationException e) {
            return false;
        }
    }

    /** A split is queued on the input thread and has not run yet (see {@link #requestSplit}). */
    private final AtomicBoolean splitPending = new AtomicBoolean(false);

    /** Queues Neovim's split of its lines at their carriage returns, once for several changes. UI thread. */
    private void requestSplit() {
        if (closed || !splitPending.compareAndSet(false, true)) {
            return;
        }
        session.runAsync("split Neovim's lines at carriage returns", () -> {
            splitPending.set(false);
            if (closed) {
                return;
            }
            try {
                // Read when it runs, on the UI thread: the lines events applied since are in it.
                Map<Integer, List<String>> parts = onUi(this::splitParts);
                if (parts != null && !parts.isEmpty()) {
                    session.splitLineBreaks(buffer, parts); // this document's, even if no longer attached
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not split Neovim's lines at their carriage returns; "
                        + "Neovim gets Eclipse's lines instead", e);
                neovimBehind = true;
                onLocalChange(); // a push, once the gate allows it
            }
        });
    }

    /**
     * For each split Neovim line (0-based), the document's lines it is shown as: what Neovim's line
     * becomes. Example: Neovim's line 1 "beta1\r2x" is the document's lines 1 and 2, "beta1" and
     * "2x". Read from the document, not cut at each "\r", since the document decides: a "\r" typed
     * before a "\n" stays a line of its own there ({@link SplitLines#correct}). UI thread.
     */
    private Map<Integer, List<String>> splitParts() {
        Map<Integer, List<String>> parts = new TreeMap<>();
        for (Map.Entry<Integer, Integer> split : splitLines.splits().entrySet()) {
            int first = splitLines.eclipseLine(split.getKey());
            if (first + split.getValue() >= document.getNumberOfLines()) {
                continue; // not in the document (it changed since): left to a later change
            }
            parts.put(split.getKey(), linesOf(first, first + split.getValue() + 1));
        }
        return parts;
    }

    /**
     * Runs the change's after-apply action, or, while Neovim's lines are split differently, once
     * Neovim has split them. The action lets Eclipse type (the handshake at an insert's start and at
     * a hand-back); Eclipse's caret must first be where Neovim's cursor is in Eclipse's lines.
     * Example: Ctrl-R Ctrl-R a, register a holding "1\r2", in an insert Eclipse types: the hand-back
     * sync has "beta1\r2", Neovim's cursor is after the 2 on its line 2, but that is Eclipse's line 3.
     * Behind the split, the caret goes to the cursor the split reports, and then Eclipse types.
     */
    private void runAfterApplyOnceSplit(BufferChange change) {
        if (change.afterApply() == null || (splitLines.isEmpty() && !splitPending.get())) {
            runAfterApply(change);
            return;
        }
        session.runAsync("let Eclipse type after the split", () -> {
            if (!closed && ui.isAvailable()) {
                ui.asyncExec(() -> {
                    restoreCaret();
                    runAfterApply(change);
                });
            }
        });
    }

    private void runAfterApply(BufferChange change) {
        if (change.afterApply() != null && listening && !closed) {
            change.afterApply().run(); // e.g. the session lets Eclipse type once insert mode's sync has landed
        }
    }

    /**
     * The edit that makes {@code document} read {@code newLines} (Neovim's whole buffer), or
     * {@code null} when it already does. Also used by {@link UnattachedDocumentSync}.
     *
     * @param finalNewline whether the text ends with a final newline after the last line, as
     *                     remembered for the document ({@link FinalNewlines})
     */
    static Edit fullBufferEdit(IDocument document, List<String> newLines, boolean finalNewline) {
        String current = document.get();
        String text = LineTextUtil.linesToDocumentText(newLines, lineDelimiter(document), finalNewline);
        return TextDiff.minimalEdit(current, text); // null when nothing changed: no spurious dirty flag
    }

    /** The edit that applies Neovim's line-range change to {@code document}; see {@link #fullBufferEdit}. */
    static Edit rangeEdit(IDocument document, BufferChange change, boolean finalNewline) {
        return LineRangeEdit.compute(new DocumentLineSource(document), change.firstLine(), change.lastLine(),
                change.newLines(), lineDelimiter(document), finalNewline);
    }

    private void restoreCaret() {
        Runnable restorer = caretRestorer;
        if (restorer != null && !closed) {
            try {
                restorer.run();
            } catch (Exception e) {
                LOG.log(Level.FINE, "caretRestorer failed", e);
            }
        }
    }

    /** Read on the UI thread (waiting for it when called from another), as the push reads the lines. */
    @Override
    public long documentStamp() {
        Long stamp = onUi(() -> stampOf(document));
        return stamp == null ? DocumentBuffer.NO_STAMP : stamp;
    }

    /** {@code document}'s modification stamp, or {@code DocumentBuffer.NO_STAMP} if it keeps none. */
    static long stampOf(IDocument document) {
        if (document instanceof IDocumentExtension4 ext4) {
            long stamp = ext4.getModificationStamp();
            return stamp == IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP ? DocumentBuffer.NO_STAMP : stamp;
        }
        return DocumentBuffer.NO_STAMP;
    }

    /** How far {@link #lineDelimiter} looks past a lone {@code \r} for the file's real delimiter. */
    private static final int DELIMITER_SEARCH_LINES = 1000;

    /**
     * The delimiter that ends the document's lines, for the lines Neovim's changes add.
     *
     * <p>A JFace document's default delimiter is its first line's. A {@code \r} that Neovim keeps
     * inside a line ({@link SplitLines}) also ends a line here, so after {@code Ctrl-V Ctrl-M} on
     * line 1 of a {@code \n} file that first delimiter is {@code \r}, and Neovim's next full sync
     * would join every line with {@code \r}. So a lone {@code \r} counts only when no line below
     * (in the next {@value #DELIMITER_SEARCH_LINES}) ends in {@code \n} or {@code \r\n}: a file
     * that really uses {@code \r} alone (classic Mac OS) still gets it.
     */
    static String lineDelimiter(IDocument document) {
        if (document instanceof IDocumentExtension4 ext4) {
            String delim = ext4.getDefaultLineDelimiter();
            if ("\r".equals(delim)) {
                return delimiterPastCarriageReturns(document);
            }
            if (delim != null && !delim.isEmpty()) {
                return delim;
            }
        }
        return delimiterPastCarriageReturns(document);
    }

    /** The first line delimiter other than {@code \r}, else {@code \r}, else the platform's. */
    private static String delimiterPastCarriageReturns(IDocument document) {
        try {
            int lines = Math.min(document.getNumberOfLines(), DELIMITER_SEARCH_LINES);
            boolean carriageReturn = false;
            for (int line = 0; line < lines; line++) {
                String delim = document.getLineDelimiter(line);
                if ("\r".equals(delim)) {
                    carriageReturn = true;
                } else if (delim != null && !delim.isEmpty()) {
                    return delim;
                }
            }
            if (carriageReturn) {
                return "\r";
            }
        } catch (BadLocationException ignored) {
            // fall through to the platform default
        }
        return LineTextUtil.platformLineDelimiter();
    }

    /**
     * Runs on the UI thread. From other threads this uses {@code syncExec} on purpose: the
     * document must reflect a change before the next key is processed, otherwise a following
     * range event would be applied against stale line numbers.
     */
    /**
     * Ends the undo step of Neovim's latest changes, on the UI thread, without waiting for it: a key
     * must not wait for the UI. Changes applied before (with {@code syncExec}) are in the step; the
     * UI thread runs this after them.
     */
    private void closeUndoStepSoon() {
        if (ui.isUiThread()) {
            undoSteps.close();
        } else if (ui.isAvailable()) {
            ui.asyncExec(undoSteps::close);
        }
    }

    private void runOnUi(Runnable r) {
        if (!ui.isAvailable()) {
            return;
        }
        if (ui.isUiThread()) {
            r.run();
        } else {
            ui.syncExec(() -> {
                if (!closed) {
                    r.run();
                }
            });
        }
    }

    @Override
    public void close() {
        closed = true;
        listening = false;
        session.removeBufferChangeListener(bufferChangeListener);
        session.removeInputHandledListener(inputHandledListener);
        document.removeDocumentListener(localChangeListener);
        document.removeDocumentListener(changedLinesListener);
        closeUndoStepSoon();
    }
}

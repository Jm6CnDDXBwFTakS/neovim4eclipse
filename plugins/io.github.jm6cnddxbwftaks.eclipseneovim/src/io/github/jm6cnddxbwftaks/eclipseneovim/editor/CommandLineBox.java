// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandHistory;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Kind;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Submission;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.ListingCommand;

/**
 * The inline command line for {@code :}, {@code /} and {@code ?}: one text box near the bottom edge
 * of the editor, as wide as the editor minus a small padding, in the editor's own font. It has a
 * thin border and a background tinted slightly toward gray, both derived from the editor's colors
 * ({@link OverlayFrame}), and sits about half its own height above the bottom edge so that it does
 * not crowd the horizontal scroll bar.
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>The prefix character ({@code :}, {@code /}, {@code ?}) is the first character of the box and
 *       cannot be edited. Backspace on an otherwise empty box cancels, as in Vim.</li>
 *   <li>Enter runs the command; Esc cancels. Up/Down walk a history that is kept separately for
 *       commands and searches.</li>
 *   <li>Clicking elsewhere (anything that takes keyboard focus away from the box) cancels it.</li>
 *   <li>{@code :} typed while a command's output is shown opens the box below that output; the output
 *       stays until the new command is run or canceled.</li>
 *   <li>{@code :} in visual mode first leaves visual mode and pre-fills {@code '<,'>}, as Vim does.</li>
 *   <li>File and tab commands ({@code :w}, {@code :wa}, {@code :q}, {@code :e}, {@code :tabn}, ...)
 *       are executed by Eclipse ({@link HostCommands}). Commands that only display something ({@code :ls},
 *       {@code :marks}, ... see {@link ListingCommand}) are run with output capture and their output is
 *       shown in an {@link OutputPopup}. Everything else is typed into Neovim.</li>
 * </ul>
 *
 * <h2>Implementation notes</h2>
 * The box (inside its {@link OverlayFrame}) is a child of the editor's text widget, positioned by hand. It is not modal and takes
 * keyboard focus away from the editor while it has focus, which also switches off the Vim key bindings
 * (so Ctrl-V pastes into the box). It is repositioned on resize and on every repaint, so scrolling
 * cannot leave it behind. UI thread only.
 */
final class CommandLineBox {

    private static final Logger LOG = Logger.getLogger(CommandLineBox.class.getName());

    /** Shared by all editors for the lifetime of the IDE session. */
    private static final CommandHistory HISTORY = new CommandHistory(100);

    /** Gap between the box and the output popup above it. */
    private static final int PADDING = 3;

    /**
     * The key that opened the box can still deliver its character to the box (on some platforms the
     * character event follows the key-down after focus moved). Such an echo is ignored for this long.
     */
    private static final long ECHO_GUARD_MS = 150;

    private final StyledText host;
    private final NeovimSession session;
    private final ITextEditor editor;
    private final OutputPopup output;

    private OverlayFrame<Text> frame;
    /** The text field inside {@link #frame}; {@code null} while closed. */
    private Text box;
    private Kind kind;
    /** True while the program itself sets the text (prefill, history), which bypasses the prefix guard. */
    private boolean programmatic;
    private long openedAtMillis;
    private int prefillLength;

    private final PaintListener repositionOnPaint = event -> reposition();
    private final ControlListener repositionOnResize = new ControlListener() {
        @Override
        public void controlMoved(ControlEvent e) {
            reposition();
        }

        @Override
        public void controlResized(ControlEvent e) {
            reposition();
        }
    };

    CommandLineBox(StyledText host, NeovimSession session, ITextEditor editor) {
        this.host = host;
        this.session = session;
        this.editor = editor;
        this.output = new OutputPopup(host, () -> open(Kind.EX, false));
    }

    boolean isOpen() {
        return box != null && !box.isDisposed();
    }

    /**
     * Opens the box. Asynchronous: the key that triggered it is still being processed, and moving
     * focus in the middle of that can misdirect the key's character event.
     *
     * @param fromVisualMode whether Neovim is in visual mode (see the class comment)
     */
    void open(Kind kind, boolean fromVisualMode) {
        Display display = host.getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> openNow(kind, fromVisualMode));
    }

    private void openNow(Kind newKind, boolean fromVisualMode) {
        if (host.isDisposed()) {
            return;
        }
        if (isOpen()) {
            if (newKind == kind && !fromVisualMode) {
                box.setFocus(); // still open (e.g. focus returned to the editor window): keep what was typed
                return;
            }
            close(false);
        }
        if (fromVisualMode && newKind == Kind.EX) {
            session.inputAsync("<Esc>"); // leave visual mode so that '< and '> exist for the range
        }
        kind = newKind;
        frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE)); // border drawn by the frame
        box = frame.content();

        String prefill = CommandLine.prefill(newKind, fromVisualMode);
        prefillLength = prefill.length();
        setBoxText(prefill);

        box.addVerifyListener(this::protectPrefix);
        // macOS selects a text field's whole content when it gains focus, so the next key would
        // replace the prefix as well. Put the caret back at the end once focus has arrived.
        box.addFocusListener(FocusListener.focusGainedAdapter(e -> moveCaretToEndLater()));
        box.addKeyListener(KeyListener.keyPressedAdapter(this::onKey));
        // Clicking elsewhere cancels.
        box.addFocusListener(FocusListener.focusLostAdapter(e -> close(false)));
        host.addPaintListener(repositionOnPaint);
        host.addControlListener(repositionOnResize);

        openedAtMillis = System.currentTimeMillis();
        reposition();
        // ':' typed over a command's output: keep the output visible above the box until Enter or cancel.
        output.makeRoomBelow(frame.control().getBounds().height + PADDING);
        box.setFocus();
        moveCaretToEndLater();
    }

    /**
     * Keeps the prefix character ({@code :}, {@code /}, {@code ?}) and drops an echo of the opening key.
     *
     * <p>An edit that reaches into the prefix (typing over a selection that includes it) is not
     * simply refused, which would swallow the key: the part after the prefix is applied instead. A
     * verify listener may not change the text itself, so that happens just afterward.
     */
    private void protectPrefix(VerifyEvent event) {
        if (programmatic) {
            return;
        }
        if (isEchoOfOpeningKey(event.text)) {
            event.doit = false;
            return;
        }
        if (event.start >= prefixEnd()) {
            return; // an ordinary edit after the prefix
        }
        event.doit = false;
        if (event.text.isEmpty() && event.end <= prefixEnd()) {
            return; // deleting the prefix itself: Backspace on it is handled in onKey
        }
        int start = prefixEnd();
        int end = Math.max(start, event.end);
        String text = event.text;
        host.getDisplay().asyncExec(() -> {
            if (isOpen()) {
                programmatic = true;
                try {
                    box.setSelection(start, Math.min(end, box.getText().length()));
                    box.insert(text);
                } finally {
                    programmatic = false;
                }
            }
        });
    }

    /** Index just after the prefix character. */
    private int prefixEnd() {
        return Math.min(1, box.getText().length());
    }

    /** Puts the caret after the text once pending focus events have run (see the focus listener). */
    private void moveCaretToEndLater() {
        host.getDisplay().asyncExec(() -> {
            if (isOpen() && box.getSelectionCount() > 0) {
                box.setSelection(box.getText().length());
            }
        });
    }

    private boolean isEchoOfOpeningKey(String typed) {
        return typed != null && typed.length() == 1 && typed.charAt(0) == kind.prefix()
                && box.getText().length() <= prefillLength
                && System.currentTimeMillis() - openedAtMillis < ECHO_GUARD_MS;
    }

    private void onKey(KeyEvent e) {
        if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR || e.character == SWT.CR) {
            e.doit = false;
            submit();
        } else if (e.keyCode == SWT.ESC || e.character == SWT.ESC) {
            e.doit = false;
            HISTORY.resetNavigation(kind);
            close(true);
        } else if (e.keyCode == SWT.ARROW_UP) {
            e.doit = false;
            showHistoryEntry(HISTORY.previous(kind, currentBody()));
        } else if (e.keyCode == SWT.ARROW_DOWN) {
            e.doit = false;
            showHistoryEntry(HISTORY.next(kind));
        } else if (e.keyCode == SWT.BS && box.getSelectionCount() == 0 && box.getCaretPosition() <= 1) {
            e.doit = false;
            if (box.getText().length() <= prefillLength) {
                HISTORY.resetNavigation(kind);
                close(true); // Backspace over the prefix leaves the command line, as in Vim
            }
        }
    }

    private String currentBody() {
        String text = box.getText();
        return text.isEmpty() ? "" : text.substring(1);
    }

    private void showHistoryEntry(String entry) {
        if (entry != null) {
            setBoxText(kind.prefix() + entry);
        }
    }

    private void setBoxText(String text) {
        programmatic = true;
        try {
            box.setText(text);
            box.setSelection(text.length());
        } finally {
            programmatic = false;
        }
    }

    private void submit() {
        Kind submittedKind = kind;
        Submission submission = CommandLine.parse(submittedKind, box.getText());
        close(true);
        if (submission == null) {
            return;
        }
        HISTORY.add(submittedKind, submission.body());
        run(submission);
    }

    private void run(Submission submission) {
        try {
            if (submission.kind() == Kind.EX) {
                if (editor != null && HostCommands.tryHandleEx(submission.body(), editor)) {
                    LOG.info("Ex handled by Eclipse: " + submission.body());
                    return;
                }
                if (ListingCommand.isListing(submission.body())) {
                    String title = ":" + submission.body();
                    session.captureOutputAsync(submission.body(), text -> showOutput(title, text));
                    return;
                }
                session.commandAsync(submission.body());
            } else {
                session.search(submission.body(), submission.kind() == Kind.SEARCH_BACKWARD);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Command line failed: " + submission, e);
        }
    }

    /** Called on a session thread with what a listing command printed. */
    private void showOutput(String title, String text) {
        Display display = host.isDisposed() ? null : host.getDisplay();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> output.show(title, text));
        }
    }

    /** Places the box near the bottom edge of the editor ({@link OverlayFrame#bottomGap}). */
    private void reposition() {
        if (!isOpen() || host.isDisposed()) {
            return;
        }
        Rectangle area = host.getClientArea();
        int height = OverlayFrame.outerHeight(box.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
        int width = Math.max(10, area.width - 2 * OverlayFrame.SIDE_PADDING);
        int x = area.x + OverlayFrame.SIDE_PADDING;
        int y = area.y + area.height - height - OverlayFrame.bottomGap(host);
        Rectangle current = frame.control().getBounds();
        if (current.x != x || current.y != y || current.width != width || current.height != height) {
            frame.control().setBounds(x, y, width, height);
        }
    }

    /**
     * Closes the box.
     *
     * @param refocusEditor whether to give focus back to the editor
     */
    void close(boolean refocusEditor) {
        if (box == null) {
            return;
        }
        output.close(false); // output kept for this command line goes with it (Enter, Esc, click away)
        OverlayFrame<Text> old = frame;
        frame = null;
        box = null;
        if (!host.isDisposed()) {
            host.removePaintListener(repositionOnPaint);
            host.removeControlListener(repositionOnResize);
        }
        old.dispose(); // disposes the text field too
        if (refocusEditor && !host.isDisposed()) {
            host.setFocus();
        }
    }

    /** Called when the editor goes away. */
    void dispose() {
        close(false);
        output.close(false);
    }
}

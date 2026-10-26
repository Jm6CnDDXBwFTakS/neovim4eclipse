// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CandidateLine;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;

/**
 * The editor's view of Neovim's command line ({@code :}, {@code /}, {@code ?}, and prompts such as
 * {@code input()}): one text box near the bottom edge of the editor, as wide as the editor minus a
 * small padding, in the editor's own font. It has a thin border and a background tinted slightly
 * toward gray, both derived from the editor's colors ({@link OverlayFrame}), and sits about half its
 * own height above the bottom edge so that it does not crowd the horizontal scroll bar.
 *
 * <h2>Neovim owns the line</h2>
 * The box edits nothing itself. Every key typed in it goes to Neovim ({@link Commands#type}), and the
 * box shows what Neovim reports ({@link #show}, from {@link CmdlineState}): the text, the cursor, a
 * pending {@code <C-r>}, the completion candidates. So the command line is exactly Neovim's:
 * <ul>
 *   <li>Up and Down walk Neovim's history (kept across sessions in ShaDa, shared with {@code q:}'s
 *       history); Tab completes as {@code 'wildmode'} says, and the candidates are listed in one
 *       line above the box with the selected one highlighted ({@link CandidateLine});
 *       {@code <C-r>a} inserts register a; command-line mappings and abbreviations apply.</li>
 *   <li>The box opens when Neovim opens a line, however it was opened: {@code :} or {@code /} typed
 *       in the editor, a mapping ({@code nnoremap ; :}), a count ({@code 2:} gives {@code :.,.+1}),
 *       {@code :} in visual mode ({@code :'<,'>}), an operator's {@code d/}, or a prompt
 *       ({@code input("Name: ")}, {@code confirm()}'s {@code [Y]es, (N)o:}). It closes when Neovim
 *       closes the line: Enter, Esc, Backspace on an empty line.</li>
 *   <li>Enter is Neovim's too; on Enter the session first offers a {@code :} line to Eclipse, which
 *       runs file and tab commands itself ({@code :w}, {@code :q}, {@code :e}, {@code :tabn}; see
 *       {@code HostCommands}). What a command prints comes back as messages: one line on the
 *       message line, longer output in the {@link OutputPopup} ({@link #showOutput}).</li>
 * </ul>
 *
 * <h2>What the box keeps</h2>
 * Only what Neovim cannot do: drawing, and text that does not come from a key. Pasting
 * (Ctrl+V, Cmd+V, Shift+Insert, a middle click) and an input method's composed text (IME) reach the
 * box as an edit, which is refused and sent to Neovim as a paste ({@link Commands#paste}); Ctrl+C with
 * text selected in the box copies it. Clicking elsewhere (anything that takes the focus away)
 * abandons the line in Neovim ({@code <C-c>}), as before.
 *
 * <p>Example: {@code :} in the editor goes to Neovim; Neovim reports the line {@code ":"} and the
 * box opens with it. {@code s}, {@code e}, {@code t}, Space, Tab go to Neovim one by one; after Tab
 * Neovim reports {@code ":set all"} and the candidates {@code all aleph allowrevins ...}, which the
 * box shows. Enter goes to Neovim, which runs the line and closes it; the box closes.
 *
 * <h2>Implementation notes</h2>
 * The box (inside its {@link OverlayFrame}) is a child of the editor's text widget, positioned by
 * hand. It is not modal and takes keyboard focus away from the editor while it has focus, which also
 * switches off the Vim key bindings. It is repositioned on resize and on every repaint, so scrolling
 * cannot leave it behind. UI thread only.
 *
 * <p>Neovim is reached only through {@link Commands}, so the box needs only SWT, and
 * {@code tests/swt} tests it with a fake that records the keys sent, without a workbench or Neovim.
 */
final class CommandLineBox {

    /**
     * What the box sends to Neovim. {@code NeovimEditorController} implements it on the session;
     * tests use a fake. Called on the UI thread; must not block (the session only queues the work,
     * in order with the editor's own keys).
     */
    interface Commands {
        /** Types {@code keys} (Neovim notation: {@code a}, {@code <lt>}, {@code <Tab>}, {@code <C-r>}). */
        void type(String keys);

        /** Pastes {@code text} into Neovim's line (a paste, an input method's committed text). */
        void paste(String text);
    }

    /** Gap between the box and the candidate list or output popup above it. */
    private static final int PADDING = 3;

    private final StyledText host;
    private final Commands commands;
    private final OutputPopup output;

    private OverlayFrame<Text> frame;
    /** The text field inside {@link #frame}; {@code null} while closed. */
    private Text box;
    /** True while the program itself sets the text, which the edit guard lets through. */
    private boolean programmatic;
    /**
     * True after the focus left the box and the line was abandoned in Neovim, until Neovim reports
     * it closed: views of the old line still on their way must not open the box again.
     */
    private boolean abandoned;
    /** The candidate list above the box; {@code null} while hidden. */
    private OverlayFrame<StyledText> listFrame;

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

    CommandLineBox(StyledText host, Commands commands) {
        this.host = host;
        this.commands = commands;
        // ':' typed in the popup starts the next command, as in Vim: it goes to Neovim like any ':'.
        this.output = new OutputPopup(host, () -> commands.type(":"));
    }

    boolean isOpen() {
        return box != null && !box.isDisposed();
    }

    /**
     * Shows Neovim's command line: opens the box if needed, sets its text and caret and the
     * candidate list, or closes the box when Neovim's line is closed. UI thread.
     */
    void show(CmdlineState.View view) {
        if (host.isDisposed()) {
            return;
        }
        if (!view.open()) {
            abandoned = false;
            close(true);
            return;
        }
        if (abandoned) {
            return; // the line the user clicked away from; Neovim's hide is on its way
        }
        if (!isOpen()) {
            openBox();
        }
        programmatic = true;
        try {
            box.setText(view.text());
            box.setSelection(Math.min(view.caret(), view.text().length()));
        } finally {
            programmatic = false;
        }
        showList(view);
        reposition();
    }

    private void openBox() {
        frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE)); // border drawn by the frame
        box = frame.content();
        box.addVerifyListener(this::onEdit);
        box.addKeyListener(KeyListener.keyPressedAdapter(this::onKey));
        box.addTraverseListener(this::onTraverse);
        box.addFocusListener(FocusListener.focusLostAdapter(e -> abandon()));
        host.addPaintListener(repositionOnPaint);
        host.addControlListener(repositionOnResize);
        reposition();
        // ':' typed over a command's output: keep the output visible above the box until the line closes.
        output.makeRoomBelow(roomBelowOutput());
        box.setFocus();
    }

    /**
     * Every edit the platform would make itself (a paste, an input method's composed text, a drop)
     * is refused: the text goes to Neovim as a paste, and Neovim's report shows it.
     */
    private void onEdit(VerifyEvent event) {
        if (programmatic) {
            return;
        }
        event.doit = false;
        if (event.text != null && !event.text.isEmpty()) {
            commands.paste(event.text);
        }
    }

    /** A key goes to Neovim, in Neovim's notation, except copying a selection and pasting. */
    private void onKey(KeyEvent e) {
        if (e.keyCode == SWT.TAB) {
            e.doit = false; // sent by onTraverse; no tab character in the box
            return;
        }
        if (isCopyOrPaste(e)) {
            return; // the platform copies, or pastes into onEdit
        }
        e.doit = false;
        if ((e.stateMask & SWT.COMMAND) != 0) {
            return; // other Cmd shortcuts (macOS) mean nothing on Neovim's command line
        }
        String key = KeyMapping.toNeovimKey(e.keyCode, e.stateMask, e.character);
        if (key != null) {
            commands.type(key);
        }
    }

    private boolean isCopyOrPaste(KeyEvent e) {
        boolean mod1 = (e.stateMask & SWT.MOD1) != 0;
        int letter = Character.toLowerCase(e.keyCode);
        boolean copy = mod1 && letter == 'c' && box.getSelectionCount() > 0;
        boolean paste = mod1 && letter == 'v' || (e.stateMask & SWT.SHIFT) != 0 && e.keyCode == SWT.INSERT;
        return copy || paste;
    }

    /**
     * Tab and Shift+Tab. SWT reports them as a traversal, which would move the focus to the next
     * control and so close the box; {@code doit = false} keeps the focus here, and the key goes to
     * Neovim, which completes.
     */
    private void onTraverse(TraverseEvent e) {
        if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS) {
            e.doit = false;
            commands.type(e.detail == SWT.TRAVERSE_TAB_PREVIOUS ? "<S-Tab>" : "<Tab>");
        }
    }

    /** The focus left the box (a click elsewhere): Neovim's line is abandoned, as Vim's Ctrl-C does. */
    private void abandon() {
        if (!isOpen()) {
            return;
        }
        abandoned = true;
        commands.type("<C-c>");
        close(false);
    }

    /** Shows the candidates in one line above the box, with the selected one highlighted. */
    private void showList(CmdlineState.View view) {
        if (view.candidates().isEmpty()) {
            hideList();
            return;
        }
        if (listFrame == null) {
            listFrame = new OverlayFrame<>(host, parent -> new StyledText(parent, SWT.SINGLE | SWT.READ_ONLY));
            reposition(); // the list's width is needed to fit the candidates
        }
        StyledText list = listFrame.content();
        CandidateLine.ListLine line = CandidateLine.fit(view.candidates(), view.selected(), columnsOf(list));
        list.setText(line.text());
        if (line.highlightStart() >= 0) {
            Display display = host.getDisplay();
            list.setStyleRange(new StyleRange(line.highlightStart(), line.highlightLength(),
                    display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT),
                    display.getSystemColor(SWT.COLOR_LIST_SELECTION)));
        }
        reposition();
        output.makeRoomBelow(roomBelowOutput()); // ':' over a command's output: keep the output above the list
    }

    /** How many average characters fit into the list (exact for the monospaced fonts editors use). */
    private static int columnsOf(StyledText list) {
        GC gc = new GC(list);
        try {
            double charWidth = Math.max(1, gc.getFontMetrics().getAverageCharacterWidth());
            return Math.max(1, (int) (list.getClientArea().width / charWidth) - 1);
        } finally {
            gc.dispose();
        }
    }

    private void hideList() {
        if (listFrame == null) {
            return;
        }
        listFrame.dispose();
        listFrame = null;
        if (isOpen()) {
            output.makeRoomBelow(roomBelowOutput());
        }
    }

    /**
     * Shows output in the popup (see {@link OutputPopup#show}). While a command line is open the
     * popup does not take the focus, which would abandon the line. UI thread.
     *
     * @param title the command line that printed it ({@code :ls}), or ""
     */
    void showOutput(String title, String text) {
        if (isOpen()) {
            output.show(title, text, false);
            output.makeRoomBelow(roomBelowOutput());
        } else {
            output.show(title, text);
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
        setBoundsIfChanged(frame, x, y, width, height);
        if (listFrame != null) {
            StyledText list = listFrame.content();
            int listHeight = OverlayFrame.outerHeight(list.computeSize(width - 2 * OverlayFrame.BORDER, SWT.DEFAULT).y);
            setBoundsIfChanged(listFrame, x, y - PADDING - listHeight, width, listHeight);
        }
    }

    private static void setBoundsIfChanged(OverlayFrame<?> overlay, int x, int y, int width, int height) {
        Rectangle current = overlay.control().getBounds();
        if (current.x != x || current.y != y || current.width != width || current.height != height) {
            overlay.control().setBounds(x, y, width, height);
        }
    }

    /** Space the output popup leaves free below it: the box, and the candidate list if shown. */
    private int roomBelowOutput() {
        int room = frame.control().getBounds().height + PADDING;
        if (listFrame != null) {
            room += listFrame.control().getBounds().height + PADDING;
        }
        return room;
    }

    /**
     * Closes the box (not Neovim's line: that is closed, or {@link #abandon} closes it).
     *
     * @param refocusEditor whether to give focus back to the editor
     */
    private void close(boolean refocusEditor) {
        if (box == null) {
            return;
        }
        output.close(false); // output kept for this command line goes with it
        if (listFrame != null) {
            listFrame.dispose();
            listFrame = null;
        }
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

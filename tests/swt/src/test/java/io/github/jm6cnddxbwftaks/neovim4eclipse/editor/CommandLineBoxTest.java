// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState.View;

/**
 * The {@code :} box on a real SWT text field, as a view of Neovim's command line: it shows the
 * views it is given and sends every key, paste and click-away to Neovim, editing nothing itself.
 * Neovim is a fake {@link CommandLineBox.Commands} that records what was sent; a test plays Neovim's
 * part by passing the next view. No workbench or Neovim is needed.
 */
class CommandLineBoxTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    /** Records what the box sends, e.g. {@code "type a"}, {@code "paste text"}. */
    private static final class FakeCommands implements CommandLineBox.Commands {
        final List<String> calls = new ArrayList<>();
        /** False plays Neovim in Ex mode ({@code gQ}), where {@code <C-c>} does nothing. */
        boolean cancellable = true;

        @Override
        public boolean cancellable() {
            return cancellable;
        }

        @Override
        public void type(String keys) {
            calls.add("type " + keys);
        }

        @Override
        public void paste(String text) {
            calls.add("paste " + text);
        }
    }

    private Shell shell;
    private StyledText host;
    private final FakeCommands commands = new FakeCommands();
    private MessageLine messageLine;
    private CommandLineBox commandLine;
    /** How often the box reported a change of its state (open, closed, focus). */
    private int stateChanges;
    /** macOS's press-and-hold as the box sees it: off, unless a test turns it on (not the Mac's own). */
    private boolean pressAndHold;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, 400, 300);
        shell.open();
        KeyboardFocus.require(host, TIMEOUT_MS); // the box abandons its line on focus loss: start in the active window
        messageLine = new MessageLine(host, () -> false);
        commandLine = new CommandLineBox(host, commands, messageLine, () -> stateChanges++, () -> pressAndHold);
    }

    @AfterEach
    void dispose() {
        commandLine.dispose();
        messageLine.dispose();
        shell.dispose();
    }

    private static View line(String text, int caret) {
        return new View(true, text.substring(0, 1), text, caret, List.of(), -1);
    }

    /** Shows {@code view}, as the controller does for Neovim's report, and waits for the focus. */
    private Text show(View view) {
        commandLine.show(view);
        Text box = findText(host);
        assertNotNull(box, "the box is open");
        KeyboardFocus.require(box, TIMEOUT_MS);
        return box;
    }

    private static void key(Text box, int keyCode, char character, int stateMask) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.stateMask = stateMask;
        event.doit = true;
        box.notifyListeners(SWT.KeyDown, event);
    }

    /** A press at {@code time} (ms): the accent menu's rules go by the time between presses. */
    private static void keyAt(Text box, int keyCode, char character, int time) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.time = time;
        event.doit = true;
        box.notifyListeners(SWT.KeyDown, event);
    }

    private static void keyUp(Text box, int keyCode) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.doit = true;
        box.notifyListeners(SWT.KeyUp, event);
    }

    /** E held from {@code time}, as macOS sends it: a press, repeats with no release, the release. */
    private static void holdE(Text box, int time) {
        keyAt(box, 'e', 'e', time);
        keyAt(box, 'e', 'e', time + 502);
        keyAt(box, 'e', 'e', time + 585);
        keyUp(box, 'e');
    }

    private static Text findText(Control control) {
        if (control instanceof Text t && !t.isDisposed()) {
            return t;
        }
        if (control instanceof Composite c) {
            for (Control child : c.getChildren()) {
                Text found = findText(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The one-line candidate list above the box, or {@code null}. */
    private static StyledText findList(Control control) {
        if (control instanceof StyledText t && (t.getStyle() & SWT.SINGLE) != 0) {
            return t;
        }
        if (control instanceof Composite c) {
            for (Control child : c.getChildren()) {
                StyledText found = findList(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Runs the event loop until {@code condition} holds. */
    private static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getDefault();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + TIMEOUT_MS + " ms waiting for: " + what);
            }
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }
        }
    }

    // What it shows

    @Test
    void neovimsLineOpensTheBoxWithItsTextAndCaret() {
        Text box = show(line(":echo", 3));
        assertEquals(":echo", box.getText());
        assertEquals(3, box.getCaretPosition());
        assertTrue(commandLine.isOpen());
    }

    @Test
    void theNextViewRedrawsTheSameBox() {
        Text box = show(line(":e", 2));
        assertEquals(box, show(line(":ec", 3)));
        assertEquals(":ec", box.getText());
    }

    @Test
    void aClosedLineClosesTheBoxAndTheEditorGetsTheFocus() {
        show(line(":x", 2));
        commandLine.show(View.CLOSED);
        assertFalse(commandLine.isOpen());
        waitUntil("the editor's focus", host::isFocusControl);
        assertEquals(List.of(), commands.calls, "closing because Neovim closed sends nothing");
    }

    @Test
    void theCandidatesAreListedAboveWithTheSelectedOneHighlighted() {
        show(new View(true, ":", ":colo blue", 10, List.of("blue", "darkblue", "default"), 0));
        StyledText list = findList(host);
        assertNotNull(list, "the candidate list");
        assertEquals("blue  darkblue  default", list.getText());
        StyleRange[] ranges = list.getStyleRanges();
        assertEquals(1, ranges.length);
        assertEquals(0, ranges[0].start);
        assertEquals(4, ranges[0].length);
        show(line(":colo blue", 10));
        assertNull(findList(host), "no candidates: no list");
    }

    // What it sends

    /** Typed characters go to Neovim; the box does not insert them itself. */
    @Test
    void typedCharactersGoToNeovimAndAreNotInserted() {
        Text box = show(line(":", 1));
        key(box, 'a', 'a', 0);
        key(box, '<', '<', SWT.SHIFT);
        assertEquals(List.of("type a", "type <lt>"), commands.calls);
        assertEquals(":", box.getText(), "Neovim's next view shows them");
    }

    @Test
    void editingKeysGoToNeovim() {
        Text box = show(line(":abc", 4));
        key(box, SWT.BS, SWT.BS, 0);
        key(box, SWT.ARROW_LEFT, (char) 0, 0);
        key(box, SWT.ARROW_UP, (char) 0, 0);
        key(box, 'r', (char) 0x12, SWT.CTRL);
        key(box, SWT.CR, SWT.CR, 0);
        key(box, SWT.ESC, SWT.ESC, 0);
        assertEquals(List.of("type <BS>", "type <Left>", "type <Up>", "type <C-r>", "type <CR>", "type <Esc>"),
                commands.calls);
    }

    /** Tab completes in Neovim and must not move the focus away (which would close the box). */
    @Test
    void tabAndShiftTabGoToNeovimAndKeepTheFocus() {
        Text box = show(line(":e ", 3));
        box.traverse(SWT.TRAVERSE_TAB_NEXT);
        box.traverse(SWT.TRAVERSE_TAB_PREVIOUS);
        assertEquals(List.of("type <Tab>", "type <S-Tab>"), commands.calls);
        assertTrue(box.isFocusControl());
    }

    /** A paste (or an input method's text) reaches the box as an edit: refused, sent as a paste. */
    @Test
    void anEditOfThePlatformIsSentAsAPaste() {
        Text box = show(line(":", 1));
        box.insert("echo \"<x>\"");
        assertEquals(List.of("paste echo \"<x>\""), commands.calls);
        assertEquals(":", box.getText());
    }

    /** Ctrl+V and Ctrl+C with a selection are the platform's paste and copy, not Neovim's keys. */
    @Test
    void pasteAndCopyAreLeftToThePlatform() {
        Text box = show(line(":echo", 5));
        key(box, 'v', (char) 0x16, SWT.MOD1);
        box.setSelection(1, 5);
        key(box, 'c', (char) 0x03, SWT.MOD1);
        assertEquals(List.of(), commands.calls);
    }

    /** Without a selection, Ctrl+C is Neovim's: it abandons the line. */
    @Test
    void ctrlCWithoutASelectionGoesToNeovim() {
        Text box = show(line(":echo", 5));
        key(box, 'c', (char) 0x03, SWT.CTRL);
        assertEquals(List.of("type <C-c>"), commands.calls);
    }

    /** Ctrl keys the workbench delivers (the Vim key contexts) go to Neovim's line. */
    @Test
    void aVimCtrlKeyGoesToNeovim() {
        show(line(":abc", 4));
        commandLine.ctrlKey("<C-r>");
        commandLine.ctrlKey("<C-u>");
        assertEquals(List.of("type <C-r>", "type <C-u>"), commands.calls);
    }

    /**
     * Ctrl+V from the workbench pastes where Ctrl is the paste modifier (Windows, Linux); on macOS it
     * is Vim's {@code <C-v>}, and Cmd+V pastes through Eclipse's Paste command.
     */
    @Test
    void ctrlVFromTheWorkbenchPastesWhereCtrlIsThePasteModifier() {
        show(line(":e ", 3));
        Clipboard clipboard = new Clipboard(host.getDisplay());
        try {
            clipboard.setContents(new Object[] { "a.txt" }, new Transfer[] { TextTransfer.getInstance() });
            commandLine.ctrlKey("<C-v>");
        } finally {
            clipboard.dispose();
        }
        assertEquals(List.of(SWT.MOD1 == SWT.CTRL ? "paste a.txt" : "type <C-v>"), commands.calls);
    }

    /** Ctrl+C from the workbench copies a selection where Ctrl is the copy modifier, else it is Neovim's. */
    @Test
    void ctrlCFromTheWorkbenchCopiesASelectionOnly() {
        Text box = show(line(":echo", 5));
        commandLine.ctrlKey("<C-c>"); // nothing selected
        box.setSelection(1, 5);
        commandLine.ctrlKey("<C-c>");
        List<String> expected = SWT.MOD1 == SWT.CTRL ? List.of("type <C-c>") : List.of("type <C-c>", "type <C-c>");
        assertEquals(expected, commands.calls);
    }

    /** The controller hears when the box opens and closes (it switches key contexts and handlers then). */
    @Test
    void openingAndClosingAreReported() {
        show(line(":", 1));
        assertTrue(stateChanges > 0, "opened");
        int whileOpen = stateChanges;
        commandLine.show(View.CLOSED);
        assertTrue(stateChanges > whileOpen, "closed");
    }

    /** A click elsewhere abandons the line in Neovim; its views still on their way do not reopen it. */
    @Test
    void anotherControlTakingTheFocusAbandonsTheLine() {
        show(line(":abc", 4));
        // What a click in the editor delivers (moving the real focus is unreliable under a virtual display).
        host.notifyListeners(SWT.FocusIn, new Event());
        waitUntil("the box to close", () -> !commandLine.isOpen());
        assertEquals(List.of("type <C-c>"), commands.calls);

        commandLine.show(line(":abc", 4)); // a view sent before Neovim got the <C-c>
        assertFalse(commandLine.isOpen());
        commandLine.show(View.CLOSED);
        show(line(":", 1)); // the next line opens again
        assertTrue(commandLine.isOpen());
    }

    /**
     * Neovim mode switched off with the box open: the line is abandoned and the box closes, as no
     * view of Neovim's reaches it any more. The next line (Neovim mode on again) opens it, though
     * Neovim's "closed" report never came.
     */
    @Test
    void eclipseModeClosesTheBoxAndTheNextLineOpensItAgain() {
        show(line(":abc", 4));
        commandLine.closeForEclipseMode();
        assertFalse(commandLine.isOpen());
        assertNull(findText(host), "the box is gone");
        assertEquals(List.of("type <C-c>"), commands.calls);
        waitUntil("the editor's focus", host::isFocusControl);

        show(line(":", 1));
        assertTrue(commandLine.isOpen());
    }

    /** After Enter, or in Ex mode, switching to Eclipse mode closes the box but sends no {@code <C-c>}. */
    @Test
    void eclipseModeAfterEnterOrInExModeSendsNothing() {
        Text box = show(line(":e x", 4));
        key(box, SWT.CR, '\r', 0);
        commandLine.closeForEclipseMode();
        assertFalse(commandLine.isOpen());
        commands.cancellable = false;
        show(line(":abc", 4));
        commandLine.closeForEclipseMode();
        assertFalse(commandLine.isOpen());
        assertEquals(List.of("type <CR>"), commands.calls);
    }

    /**
     * After Enter the line is Neovim's to close. A command can move the focus before Neovim reports
     * the line closed: {@code :e new.txt} opens another editor, which takes it. That is not a click
     * away, so no {@code <C-c>}; on a loaded machine one used to reach Neovim back in normal mode,
     * where it shows "Type :qa and press <Enter> to exit Nvim" in place of the command's message.
     */
    @Test
    void theFocusMovingAfterEnterDoesNotAbandonTheLine() {
        Text box = show(line(":e new.txt", 10));
        key(box, SWT.CR, '\r', 0);
        host.notifyListeners(SWT.FocusIn, new Event()); // the new editor taking the focus
        assertEquals(List.of("type <CR>"), commands.calls);

        commandLine.show(View.CLOSED); // Neovim's report arrives
        assertFalse(commandLine.isOpen());
    }

    /**
     * A command run with Enter that gives the focus to another control ({@code :vsplit} makes the
     * new editor beside this one the active one) keeps it there when Neovim then reports the line
     * closed: the box does not hand it back to its own editor, which would make that one active
     * again.
     */
    @Test
    void theFocusAnotherControlTookAfterEnterStaysThereWhenTheBoxCloses() {
        StyledText other = new StyledText(shell, SWT.MULTI); // the split's new editor
        other.setBounds(0, 250, 400, 50);
        Text box = show(line(":vsplit", 7));
        key(box, SWT.CR, '\r', 0);
        KeyboardFocus.require(other, TIMEOUT_MS);

        commandLine.show(View.CLOSED); // Neovim's report arrives
        assertFalse(commandLine.isOpen());
        while (Display.getDefault().readAndDispatch()) {
            // the focus events of the box going away
        }
        assertTrue(other.isFocusControl(), "the new editor keeps the focus");
        assertEquals(List.of("type <CR>"), commands.calls);
    }

    /** The editor going away while its box is open ({@code :q} typed in the box) closes the box without a fault. */
    @Test
    void theEditorGoingAwayWithTheBoxOpenClosesIt() {
        StyledText other = new StyledText(shell, SWT.MULTI); // where the focus goes as the editor closes
        other.setBounds(0, 250, 400, 50);
        show(line(":q", 2));
        KeyboardFocus.require(other, TIMEOUT_MS);
        host.dispose();
        commandLine.dispose();
        assertFalse(commandLine.isOpen());
    }

    /** A key typed after Enter (the line stayed open, as after {@code <C-v><CR>}) makes a click away abandon it again. */
    @Test
    void aKeyAfterEnterMakesAClickAwayAbandonTheLineAgain() {
        Text box = show(line(":abc", 4));
        key(box, SWT.CR, '\r', 0);
        key(box, 'x', 'x', 0);
        host.notifyListeners(SWT.FocusIn, new Event());
        waitUntil("the box to close", () -> !commandLine.isOpen());
        assertEquals(List.of("type <CR>", "type x", "type <C-c>"), commands.calls);
    }

    /**
     * Ex mode ({@code gQ}): Neovim ignores {@code <C-c>}, so a click away must not abandon the line.
     * Before, the box closed and ignored Neovim's line, which stayed open: the user typed blind.
     */
    @Test
    void inExModeAnotherControlTakingTheFocusKeepsTheLine() {
        commands.cancellable = false;
        Text box = show(line(":abc", 4));
        // What the editor taking the focus delivers (moving the real focus is unreliable under a virtual display).
        host.notifyListeners(SWT.FocusIn, new Event());
        assertTrue(commandLine.isOpen());
        assertEquals(List.of(), commands.calls, "no <C-c>");
        commandLine.show(line(":abcd", 5)); // Neovim's line still shows
        assertEquals(":abcd", box.getText());
        KeyboardFocus.require(box, TIMEOUT_MS); // back in the editor, the box has the keys again
    }

    /**
     * Ex mode: a click in the editor stays with the box, which keeps the focus; the editor sees no
     * press, so neither its caret nor Neovim's line moves (Neovim took a click as one on its line).
     */
    @Test
    void inExModeAClickInTheEditorKeepsTheFocusInTheBox() {
        commands.cancellable = false;
        Text box = show(line(":abc", 4));
        host.setCaretOffset(0);
        Event click = new Event();
        click.button = 1;
        click.x = 50;
        click.y = 5;
        host.notifyListeners(SWT.MouseDown, click);
        assertEquals(SWT.None, click.type, "swallowed");
        assertTrue(commandLine.isOpen());
        KeyboardFocus.require(box, TIMEOUT_MS);
        assertEquals(0, host.getCaretOffset());
        assertEquals(List.of(), commands.calls);
    }

    /**
     * The focus leaving Eclipse (another application, an input method's switcher such as Super+Space
     * on Linux) keeps the line: only a control of Eclipse's own taking the focus abandons it.
     */
    @Test
    void focusLeavingEclipseKeepsTheLine() {
        Text box = show(line(":abc", 4));
        box.notifyListeners(SWT.FocusOut, new Event()); // no FocusIn elsewhere in this application
        assertTrue(commandLine.isOpen());
        box.notifyListeners(SWT.FocusIn, new Event()); // Eclipse's window active again
        assertTrue(commandLine.isOpen());
        assertEquals(List.of(), commands.calls);
    }

    // With the message line

    /** A message that arrives while a line is typed (a timer's :echo) shows above the box, not over it. */
    @Test
    void aMessageWhileTheBoxIsOpenShowsAboveIt() {
        Text box = show(line(":ec", 3));
        messageLine.show("hello", false);
        Rectangle message = messageLine.frame().control().getBounds();
        Rectangle boxFrame = box.getParent().getBounds();
        assertTrue(message.y + message.height <= boxFrame.y, message + " above " + boxFrame);
        assertTrue(commandLine.isOpen());

        commandLine.show(View.CLOSED);
        Rectangle after = messageLine.frame().control().getBounds();
        assertTrue(after.y > message.y, "back at the bottom edge once the box closes: " + after);
    }

    // With the output popup

    /** Output that arrives while a line is typed (a timer's message) must not take the focus. */
    @Test
    void outputWhileTheBoxIsOpenLeavesItTheFocus() {
        Text box = show(line(":ec", 3));
        commandLine.showOutput("", "one\ntwo");
        waitUntil("the popup", () -> host.getChildren().length >= 2);
        assertTrue(box.isFocusControl(), "the box keeps the focus");
        assertTrue(commandLine.isOpen());
        assertEquals(List.of(), commands.calls);
    }

    /** A field standing for another part of Eclipse (a view, a dialog), with the keyboard focus. */
    private Text focusElsewhere() {
        Text elsewhere = new Text(shell, SWT.SINGLE);
        elsewhere.setBounds(0, 0, 100, 20);
        elsewhere.moveAbove(host);
        KeyboardFocus.require(elsewhere, TIMEOUT_MS);
        return elsewhere;
    }

    /**
     * Output that comes while the user types elsewhere in Eclipse leaves the focus there, unlike Vim's
     * prompt: Eclipse has more places to type. Coming back to the editor does not move the focus to
     * the popup either: the popup behaves like the message line (see the next tests).
     */
    @Test
    void outputWhileTheUserTypesElsewhereLeavesTheFocusThere() {
        Text elsewhere = focusElsewhere();
        commandLine.showOutput(":ls", "  1 a.txt\n  2 b.txt");
        waitUntil("the popup", () -> popupText() != null);
        assertTrue(elsewhere.isFocusControl(), "the focus stays where the user types");
        KeyboardFocus.require(elsewhere, TIMEOUT_MS); // the window active again, if a window manager took it
        // As Eclipse does when the editor is activated. Composite.setFocus offers the focus to the
        // children first, the popup's text among them, which must refuse it (NO_FOCUS).
        host.setFocus();
        waitUntil("the focus to come back to the editor or the popup",
                () -> host.isFocusControl() || popupText() != null && popupText().isFocusControl());
        assertTrue(host.isFocusControl(), "the editor has the focus, not the popup");
        Display display = shell.getDisplay();
        while (display.readAndDispatch()) {
            // run what the focus change posted
        }
        // Not "the editor still has it": on Linux the window manager may deactivate the test's window
        // now and then (KeyboardFocus), and then nothing has the focus. The popup must never take it.
        assertNotNull(popupText(), "the popup is still shown");
        assertFalse(popupText().isFocusControl(), "the popup did not take the focus later");
    }

    /** A click into such a popup gives it the focus, to scroll it; then it closes as any popup does. */
    @Test
    void aClickIntoAPopupThatOpenedWithoutTheFocusGivesItTheFocus() {
        focusElsewhere();
        commandLine.showOutput(":ls", "  1 a.txt\n  2 b.txt");
        waitUntil("the popup", () -> popupText() != null);
        Event click = new Event();
        click.button = 1;
        popupText().notifyListeners(SWT.MouseDown, click);
        waitUntil("the popup's focus", () -> popupText() != null && popupText().isFocusControl());
    }

    /** Such a popup closes with the next key in the editor, which goes on to Neovim as usual. */
    @Test
    void aKeyInTheEditorClosesAPopupThatOpenedWithoutTheFocus() {
        focusElsewhere();
        commandLine.showOutput(":ls", "  1 a.txt\n  2 b.txt");
        waitUntil("the popup", () -> popupText() != null);
        KeyboardFocus.require(host, TIMEOUT_MS);
        Event key = new Event();
        key.character = 'j';
        key.keyCode = 'j';
        key.doit = true;
        host.notifyListeners(SWT.KeyDown, key);
        waitUntil("the popup to close", () -> popupText() == null);
        assertTrue(host.isFocusControl(), "the editor keeps the focus");
    }

    /** ... and with a click in the editor, as the message line does. */
    @Test
    void aClickInTheEditorClosesAPopupThatOpenedWithoutTheFocus() {
        focusElsewhere();
        commandLine.showOutput(":ls", "  1 a.txt\n  2 b.txt");
        waitUntil("the popup", () -> popupText() != null);
        Event click = new Event();
        click.button = 1;
        host.notifyListeners(SWT.MouseDown, click);
        waitUntil("the popup to close", () -> popupText() == null);
    }

    /** The output popup's text: a {@link StyledText} inside the host, or {@code null}. */
    private StyledText popupText() {
        return popupTextIn(host);
    }

    private StyledText popupTextIn(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof StyledText text && text != host) {
                return text;
            }
            if (child instanceof Composite composite) {
                StyledText found = popupTextIn(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@code :} in the popup starts the next command in Neovim, as {@code :} anywhere does. */
    @Test
    void colonInThePopupGoesToNeovim() {
        commandLine.showOutput(":ls", "  1 a.txt");
        waitUntil("the popup's focus", () -> shell.getDisplay().getFocusControl() instanceof StyledText focused
                && focused != host);
        StyledText popup = (StyledText) shell.getDisplay().getFocusControl();
        Event colon = new Event();
        colon.character = ':';
        colon.keyCode = ':';
        colon.doit = true;
        popup.notifyListeners(SWT.KeyDown, colon);
        assertEquals(List.of("type :"), commands.calls);
    }

    // macOS's accent menu (press-and-hold): the box's part of PressAndHold, with the sequences
    // SWT's macOS port sends. Only there: on Linux and Windows a held key just repeats.

    private void assumeMacWithPressAndHold() {
        assumeTrue("cocoa".equals(SWT.getPlatform()), "the accent menu is macOS's");
        pressAndHold = true;
    }

    /** E held, 2: the repeats are not sent, and the pick replaces the e. */
    @Test
    void aDigitPickFromTheAccentMenuReplacesTheLetter() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        holdE(box, 1000);
        keyAt(box, '2', '2', 3000);
        keyAt(box, '2', '\u00e9', 3000); // the pick: the digit's key code, carrying é
        keyUp(box, '2');
        assertEquals(List.of("type e", "type <BS>", "type \u00e9"), commands.calls);
    }

    /**
     * ABC – AZERTY: E held, Shift, then Shift+2, which SWT reports as the é key carrying 2, then the
     * pick on the same key. Before, Shift closed the menu and the 2 was taken by its key, no digit:
     * {@code :e2}.
     */
    @Test
    void aDigitThatNeedsShiftPicksFromTheAccentMenu() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        holdE(box, 1000);
        keyAt(box, SWT.SHIFT, '\0', 2900);
        keyAt(box, '\u00e9', '2', 3000);
        keyAt(box, '\u00e9', '\u00e8', 3000); // the pick: the same key, carrying è
        keyUp(box, '\u00e9');
        assertEquals(List.of("type e", "type <BS>", "type \u00e8"), commands.calls);
    }

    /**
     * QWERTY: E held, then Shift+2, which types {@code @}, no digit. As in TextEdit, the menu closes
     * and {@code @} follows the e ({@code e@}); the next Esc is Neovim's.
     */
    @Test
    void aShiftedDigitKeyThatTypesNoDigitClosesTheAccentMenu() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        holdE(box, 1000);
        keyAt(box, SWT.SHIFT, '\0', 2900);
        keyAt(box, '2', '@', 3000);
        keyUp(box, '2');
        keyAt(box, SWT.ESC, SWT.ESC, 4000);
        assertEquals(List.of("type e", "type @", "type <Esc>"), commands.calls);
    }

    /**
     * A letter is typed by the field too, so that macOS may open the menu over it (on ABC – AZERTY
     * only over a letter the field typed itself): its press keeps doit, and its edit is let through,
     * not sent again. With press-and-hold off the box types nothing itself, as before.
     */
    @Test
    void aLetterIsTypedByTheFieldTooWithPressAndHoldOn() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        assertTrue(typeE(box), "the field types the e");
        assertEquals(":e", box.getText());
        assertEquals(List.of("type e"), commands.calls, "sent once, no paste");
        box = show(line(":e", 2)); // Neovim's view, with the e
        keyUp(box, 'e');
        assertEquals(":e", box.getText());
        pressAndHold = false;
        commands.calls.clear();
        assertFalse(typeE(box), "Neovim's view shows it");
        assertEquals(":e", box.getText());
        assertEquals(List.of("type e"), commands.calls);
    }

    /**
     * {@code cnoremap e <Nop>}: Neovim's line does not change, so no view comes for the letter the
     * field typed. When it is let go, the box shows Neovim's line again, without the letter.
     */
    @Test
    void aLetterNeovimDoesNotTakeGoesWhenLetGo() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        assertTrue(typeE(box));
        assertEquals(":e", box.getText(), "typed by the field");
        keyUp(box, 'e');
        assertEquals(":", box.getText(), "Neovim's line again");
    }

    /**
     * A capital E with Shift let go first: Shift's release is not the letter's, so the E stays until
     * Neovim's view comes (it did flicker away and back). The E's own release still shows the line
     * again when no view came.
     */
    @Test
    void shiftLetGoBeforeTheLetterKeepsTheLetter() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        Event press = new Event();
        press.keyCode = 'e';
        press.character = 'E';
        press.stateMask = SWT.SHIFT;
        press.doit = true;
        box.notifyListeners(SWT.KeyDown, press);
        assertTrue(press.doit, "the field types the E");
        box.insert("E");
        keyUp(box, SWT.SHIFT);
        assertEquals(":E", box.getText(), "Shift's release is not the E's");
        keyUp(box, 'e');
        assertEquals(":", box.getText(), "no view came: Neovim's line again");
    }

    /**
     * E pressed: whether the field may type it; if so, the field's own insert, which SWT makes
     * within the press (through the verify listener, as macOS's does).
     */
    private static boolean typeE(Text box) {
        Event press = new Event();
        press.keyCode = 'e';
        press.character = 'e';
        press.doit = true;
        box.notifyListeners(SWT.KeyDown, press);
        if (press.doit) {
            box.insert("e");
        }
        return press.doit;
    }

    /** A digit after a held letter that picked nothing (no menu) is sent when let go. */
    @Test
    void aHeldBackDigitIsSentWhenLetGo() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        holdE(box, 1000);
        keyAt(box, '2', '2', 3000);
        assertEquals(List.of("type e"), commands.calls, "held back");
        keyUp(box, '2');
        assertEquals(List.of("type e", "type 2"), commands.calls);
    }

    /** Tab while a digit is held back: the digit first, as typed. */
    @Test
    void aHeldBackDigitGoesBeforeTab() {
        assumeMacWithPressAndHold();
        Text box = show(line(":e ", 3));
        holdE(box, 1000);
        keyAt(box, '2', '2', 3000);
        box.traverse(SWT.TRAVERSE_TAB_NEXT);
        keyUp(box, '2');
        assertEquals(List.of("type e", "type 2", "type <Tab>"), commands.calls);
    }

    /**
     * A click on é in the menu: an edit with no key code over the e before the caret. Neovim
     * deletes the e and gets é; the click closed the menu, so the next Esc is Neovim's.
     */
    @Test
    void aClickOnAnAccentReplacesTheLetterAndClosesTheMenu() {
        assumeMacWithPressAndHold();
        Text box = show(line(":", 1));
        holdE(box, 1000);
        box = show(line(":e", 2)); // Neovim's view with the e
        commands.calls.clear();
        Event click = new Event();
        click.text = "\u00e9";
        click.start = 1;
        click.end = 2;
        click.doit = true;
        box.notifyListeners(SWT.Verify, click);
        keyAt(box, SWT.ESC, SWT.ESC, 4000);
        assertEquals(List.of("type <BS>", "paste \u00e9", "type <Esc>"), commands.calls);
    }

    /** With press-and-hold off (as many Vim users set it), a held letter repeats. */
    @Test
    void withPressAndHoldOffAHeldLetterRepeats() {
        assumeTrue("cocoa".equals(SWT.getPlatform()), "the accent menu is macOS's");
        Text box = show(line(":", 1));
        holdE(box, 1000);
        keyAt(box, SWT.ESC, SWT.ESC, 2000);
        assertEquals(List.of("type e", "type e", "type e", "type <Esc>"), commands.calls);
    }

    /**
     * macOS, Japanese input: while text is being composed, Tab picks the input method's prediction,
     * so neither its traversal nor its press reaches Neovim, which would complete the line behind
     * the box. (MarkedTextInputTest.tabPicksAPredictionAsMarkedText has the input method's side.)
     */
    @Test
    void tabWhileComposingIsTheInputMethods() {
        assumeTrue("cocoa".equals(SWT.getPlatform()), "marked text is macOS's");
        Text box = show(line(":", 1));
        Event marked = new Event();
        marked.keyCode = 'k';
        marked.text = "k";
        marked.start = 1;
        marked.end = 1;
        marked.doit = true;
        box.notifyListeners(SWT.Verify, marked); // k as marked text: composing
        box.traverse(SWT.TRAVERSE_TAB_NEXT);
        key(box, SWT.TAB, '\t', 0);
        assertEquals(List.of(), commands.calls);
        assertTrue(box.isFocusControl(), "the box keeps the focus");
    }
}

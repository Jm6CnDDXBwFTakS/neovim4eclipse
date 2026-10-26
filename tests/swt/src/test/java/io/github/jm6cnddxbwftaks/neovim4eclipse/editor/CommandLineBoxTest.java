// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState.View;

/**
 * The {@code :} box on a real SWT text field, as a view of Neovim's command line: it shows the
 * views it is given and sends every key, paste and click-away to Neovim, editing nothing itself.
 * Neovim is a fake {@link CommandLineBox.Commands} that records what was sent; a test plays Neovim's
 * part by passing the next view. No workbench or Neovim is needed.
 */
class CommandLineBoxTest {

    private static final long TIMEOUT_MS = 5000;

    /** Records what the box sends, e.g. {@code "type a"}, {@code "paste text"}. */
    private static final class FakeCommands implements CommandLineBox.Commands {
        final List<String> calls = new ArrayList<>();

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
    private CommandLineBox commandLine;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, 400, 300);
        shell.open();
        KeyboardFocus.require(host, TIMEOUT_MS); // the box abandons its line on focus loss: start in the active window
        commandLine = new CommandLineBox(host, commands);
    }

    @AfterEach
    void dispose() {
        commandLine.dispose();
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

    /** A click elsewhere abandons the line in Neovim; its views still on their way do not reopen it. */
    @Test
    void losingTheFocusAbandonsTheLine() {
        Text box = show(line(":abc", 4));
        // What a click elsewhere delivers (moving the real focus is unreliable under a virtual display).
        box.notifyListeners(SWT.FocusOut, new Event());
        waitUntil("the box to close", () -> !commandLine.isOpen());
        assertEquals(List.of("type <C-c>"), commands.calls);

        commandLine.show(line(":abc", 4)); // a view sent before Neovim got the <C-c>
        assertFalse(commandLine.isOpen());
        commandLine.show(View.CLOSED);
        show(line(":", 1)); // the next line opens again
        assertTrue(commandLine.isOpen());
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
}

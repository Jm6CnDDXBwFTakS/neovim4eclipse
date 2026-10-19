// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
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

import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Kind;

/**
 * The {@code :}, {@code /} and {@code ?} box on a real SWT text field: it keeps its prefix whatever
 * the platform does with the selection, and Enter sends each kind of line to the right place. Neovim
 * and the IDE are a fake {@link CommandLineBox.Commands} that records the calls, so no workbench or
 * Neovim is needed.
 */
class CommandLineBoxTest {

    private static final long TIMEOUT_MS = 5000;

    /** Records each call as text, e.g. {@code "capture ls"}; {@code :w} and {@code :q} count as IDE commands. */
    private static final class FakeCommands implements CommandLineBox.Commands {
        final List<String> calls = new ArrayList<>();

        @Override
        public void leaveVisualMode() {
            calls.add("leave visual mode");
        }

        @Override
        public boolean runInIde(String exCommand) {
            boolean ide = exCommand.equals("w") || exCommand.equals("q");
            if (ide) {
                calls.add("ide " + exCommand);
            }
            return ide;
        }

        @Override
        public void capture(String exCommand, Consumer<String> onOutput) {
            calls.add("capture " + exCommand);
        }

        @Override
        public void run(String exCommand, Consumer<String> onOutput) {
            calls.add("run " + exCommand);
        }

        @Override
        public void search(String pattern, boolean backward) {
            calls.add((backward ? "search back " : "search ") + pattern);
        }
    }

    private Shell shell;
    private StyledText host;
    private final FakeCommands commands = new FakeCommands();
    private CommandLineBox commandLine;
    private Text box;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, 400, 300);
        shell.open();
        commandLine = new CommandLineBox(host, commands);
    }

    @AfterEach
    void dispose() {
        commandLine.dispose();
        shell.dispose();
    }

    /** Opens the box and waits for it; then lets the guard against an echo of the opening key pass. */
    private void open(Kind kind, boolean fromVisualMode) {
        commandLine.open(kind, fromVisualMode);
        waitUntil("the box to open", () -> findText(host) != null);
        box = findText(host);
        spin(200);
    }

    private void typeAndPressEnter(String text) {
        box.insert(text);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
    }

    private static Text findText(Control control) {
        if (control instanceof Text t) {
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

    /** Runs the event loop until {@code condition} holds (the box opens and edits asynchronously). */
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

    /** Runs the event loop for a while (lets queued work finish). */
    private static void spin(long millis) {
        long end = System.currentTimeMillis() + millis;
        waitUntil("time to pass", () -> System.currentTimeMillis() >= end);
    }

    // The prefix

    @Test
    void startsWithThePrefix() {
        open(Kind.EX, false);
        assertEquals(":", box.getText());
    }

    @Test
    void typingAfterThePrefixWorks() {
        open(Kind.EX, false);
        box.insert("w");
        assertEquals(":w", box.getText());
    }

    @Test
    void typingOverASelectionThatIncludesThePrefixKeepsThePrefix() {
        open(Kind.EX, false);
        box.selectAll(); // what macOS does when the field gains focus
        box.insert("w");
        spin(100); // the redirected edit is applied just after the verify listener
        assertEquals(":w", box.getText());
    }

    @Test
    void deletingEverythingLeavesThePrefix() {
        open(Kind.EX, false);
        box.insert("wq");
        box.selectAll();
        box.insert("");
        spin(100);
        assertEquals(":", box.getText());
    }

    // Where Enter sends the line

    @Test
    void aFileCommandIsRunByTheIdeAndNotByNeovim() {
        open(Kind.EX, false);
        typeAndPressEnter("w");
        assertEquals(List.of("ide w"), commands.calls);
    }

    @Test
    void aListingIsCapturedForThePopup() {
        open(Kind.EX, false);
        typeAndPressEnter("ls");
        assertEquals(List.of("capture ls"), commands.calls);
    }

    @Test
    void anyOtherCommandIsRunInNeovim() {
        open(Kind.EX, false);
        typeAndPressEnter("set ts=4"); // sets an option; :echo and :set ts? are listings
        assertEquals(List.of("run set ts=4"), commands.calls);
    }

    @Test
    void slashAndQuestionMarkSearchForwardAndBackward() {
        open(Kind.SEARCH_FORWARD, false);
        typeAndPressEnter("foo");
        waitUntil("the box to close", () -> !commandLine.isOpen());
        open(Kind.SEARCH_BACKWARD, false);
        typeAndPressEnter("bar");
        assertEquals(List.of("search foo", "search back bar"), commands.calls);
    }

    @Test
    void colonInVisualModeLeavesItAndPrefillsTheRange() {
        open(Kind.EX, true);
        assertEquals(":'<,'>", box.getText());
        typeAndPressEnter("s/a/b/");
        assertEquals(List.of("leave visual mode", "run '<,'>s/a/b/"), commands.calls);
    }

    @Test
    void enterOnTheBarePrefixRunsNothingAndCloses() {
        open(Kind.EX, false);
        typeAndPressEnter("");
        assertEquals(List.of(), commands.calls);
        assertFalse(commandLine.isOpen());
    }
}

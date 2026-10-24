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
import java.util.function.Consumer;

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

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandLine.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Completion;

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

        /** The callbacks of the completions asked for, oldest first; a test answers them. */
        final List<Consumer<Completion>> pendingCompletions = new ArrayList<>();

        @Override
        public void complete(String line, String steps, Consumer<Completion> onResult) {
            calls.add("complete " + line + " " + steps);
            pendingCompletions.add(onResult);
        }

        @Override
        public void answerSearch(String pattern, boolean backward) {
            calls.add((backward ? "answer search back " : "answer search ") + pattern);
        }

        @Override
        public void cancelNeovimLine() {
            calls.add("cancel Neovim's line");
        }
    }

    private Shell shell;
    private StyledText host;
    private final FakeCommands commands = new FakeCommands();
    private CommandLineBox commandLine;
    /** The open box's text field; use {@link #box()}, which notices when it closed on focus loss. */
    private Text openBox;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, 400, 300);
        shell.open();
        KeyboardFocus.require(host, TIMEOUT_MS); // the box closes on focus loss: start in the active window
        commandLine = new CommandLineBox(host, commands);
    }

    @AfterEach
    void dispose() {
        commandLine.dispose();
        shell.dispose();
    }

    /**
     * Opens the box and waits until it has keyboard focus; then lets the guard against an echo of the
     * opening key pass. The box takes the focus when it opens and closes when it loses it, so a box
     * without focus would close at the next focus event.
     */
    private void open(Kind kind, boolean fromVisualMode) {
        commandLine.open(kind, fromVisualMode);
        awaitOpen();
    }

    /** As {@link #open}, for a command line the user opened in Neovim (see {@link CommandLineBox#openFromNeovim}). */
    private void openFromNeovim(Kind kind, String content, boolean answering) {
        commandLine.openFromNeovim(kind, content, answering);
        awaitOpen();
    }

    private void awaitOpen() {
        waitUntil("the box to open", () -> findText(host) != null);
        openBox = findText(host);
        KeyboardFocus.require(openBox, TIMEOUT_MS);
        spin(200);
    }

    /**
     * The open box's text field. Fails with "lost keyboard focus" if it closed although the test did
     * not close it: {@link CommandLineBox} closes the box whenever it loses focus (a click elsewhere,
     * another window activated), which is what happens when someone works in another window while
     * the tests run. Without this check the next call fails with a bare "Widget is disposed".
     */
    private Text box() {
        if (openBox == null || openBox.isDisposed()) {
            fail("The : box closed before the test was done: it lost keyboard focus; "
                    + KeyboardFocus.report(shell.getDisplay(), shell));
        }
        return openBox;
    }

    private void typeAndPressEnter(String text) {
        box().insert(text);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box().notifyListeners(SWT.KeyDown, enter);
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
        assertEquals(":", box().getText());
    }

    @Test
    void typingAfterThePrefixWorks() {
        open(Kind.EX, false);
        box().insert("w");
        assertEquals(":w", box().getText());
    }

    @Test
    void typingOverASelectionThatIncludesThePrefixKeepsThePrefix() {
        open(Kind.EX, false);
        box().selectAll(); // what macOS does when the field gains focus
        box().insert("w");
        spin(100); // the redirected edit is applied just after the verify listener
        assertEquals(":w", box().getText());
    }

    @Test
    void deletingEverythingLeavesThePrefix() {
        open(Kind.EX, false);
        box().insert("wq");
        box().selectAll();
        box().insert("");
        spin(100);
        assertEquals(":", box().getText());
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

    // A command line the user opened in Neovim (a mapping such as <Space>s -> :%s/)

    @Test
    void neovimsTextIsPrefilledAndEditable() {
        openFromNeovim(Kind.EX, "%s/", false);
        assertEquals(":%s/", box().getText());
        typeAndPressEnter("a/b/");
        assertEquals(List.of("run %s/a/b/"), commands.calls, "a : line Neovim closed runs as usual");
    }

    @Test
    void neovimsSearchLineIsAnsweredNotSearchedAgain() {
        openFromNeovim(Kind.SEARCH_FORWARD, "", true);
        typeAndPressEnter("foo");
        assertEquals(List.of("answer search foo"), commands.calls);
    }

    @Test
    void escCancelsNeovimsSearchLine() {
        openFromNeovim(Kind.SEARCH_BACKWARD, "fo", true);
        assertEquals("?fo", box().getText());
        Event esc = new Event();
        esc.keyCode = SWT.ESC;
        esc.character = SWT.ESC;
        esc.doit = true;
        box().notifyListeners(SWT.KeyDown, esc);
        waitUntil("the box to close", () -> !commandLine.isOpen());
        assertEquals(List.of("cancel Neovim's line"), commands.calls);
    }

    @Test
    void anEmptyAnswerCancelsNeovimsSearchLine() {
        openFromNeovim(Kind.SEARCH_FORWARD, "", true);
        typeAndPressEnter("");
        assertEquals(List.of("cancel Neovim's line"), commands.calls);
    }

    @Test
    void aBoxOpenedHereCancelsNothingInNeovim() {
        open(Kind.SEARCH_FORWARD, false);
        commandLine.close(true);
        assertEquals(List.of(), commands.calls);
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
        assertEquals(":'<,'>", box().getText());
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

    // Tab completion

    /** Presses Tab (or Shift+Tab), which SWT reports as a traversal first. */
    private void pressTab(boolean backward) {
        Event traverse = new Event();
        traverse.detail = backward ? SWT.TRAVERSE_TAB_PREVIOUS : SWT.TRAVERSE_TAB_NEXT;
        traverse.keyCode = SWT.TAB;
        traverse.character = SWT.TAB;
        traverse.doit = true;
        box().notifyListeners(SWT.Traverse, traverse);
        assertFalse(traverse.doit, "Tab must not move the focus out of the box");
    }

    /** Answers the oldest completion asked for, as Neovim would on a session thread. */
    private void answer(String line, String... candidates) {
        Consumer<Completion> onResult = commands.pendingCompletions.remove(0);
        Thread session = new Thread(() -> onResult.accept(new Completion(line, List.of(candidates), candidates.length)));
        session.start();
        try {
            session.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        spin(100);
    }

    /** The candidate list above the box, or {@code null} while it is hidden. */
    private StyledText findList() {
        for (Control child : host.getChildren()) {
            if (child instanceof Composite c && c.getChildren().length == 1
                    && c.getChildren()[0] instanceof StyledText list && !list.isDisposed()) {
                return list;
            }
        }
        return null;
    }

    @Test
    void tabCompletesTheLineInNeovimAndShowsTheCandidates() {
        open(Kind.EX, false);
        box().insert("tabn");
        pressTab(false);
        assertEquals(List.of("complete tabn T"), commands.calls);
        answer("tabnew", "tabNext", "tabnew", "tabnext");
        assertEquals(":tabnew", box().getText());
        assertEquals(box().getText().length(), box().getCaretPosition());

        StyledText list = findList();
        assertNotNull(list, "the candidate list");
        assertEquals("tabNext  tabnew  tabnext", list.getText());
        StyleRange[] highlighted = list.getStyleRanges();
        assertEquals(1, highlighted.length);
        assertEquals("tabnew", list.getText(highlighted[0].start, highlighted[0].start + highlighted[0].length - 1));
    }

    @Test
    void eachTabContinuesTheCycleAndShiftTabGoesBack() {
        open(Kind.EX, false);
        box().insert("tabn");
        pressTab(false);
        answer("tabNext", "tabNext", "tabnew");
        pressTab(false);
        answer("tabnew", "tabNext", "tabnew");
        pressTab(true);
        assertEquals(List.of("complete tabn T", "complete tabn TT", "complete tabn TTP"), commands.calls);
        assertTrue(commandLine.isOpen());
    }

    @Test
    void typingEndsTheCycleHidesTheListAndDropsALateAnswer() {
        open(Kind.EX, false);
        box().insert("tabn");
        pressTab(false);
        answer("tabnew", "tabnew", "tabnext");
        assertNotNull(findList());

        box().insert(" 2"); // an edit: the list goes, the next Tab starts over
        spin(50);
        assertNull(findList());
        pressTab(false);
        box().insert("x"); // typed before the answer came
        answer("tabnew 2 something");
        assertEquals(":tabnew 2x", box().getText());
        assertEquals("complete tabnew 2 T", commands.calls.get(1));
    }

    @Test
    void anAnswerWithNoCandidatesShowsNoList() {
        open(Kind.EX, false);
        box().insert("zzz");
        pressTab(false);
        answer("zzz");
        assertEquals(":zzz", box().getText());
        assertNull(findList());
    }

    @Test
    void tabInASearchBoxDoesNothingAndKeepsTheBoxOpen() {
        open(Kind.SEARCH_FORWARD, false);
        box().insert("foo");
        pressTab(false);
        spin(50);
        assertTrue(commandLine.isOpen());
        assertEquals("/foo", box().getText());
        assertEquals(List.of(), commands.calls);
    }

    @Test
    void closingTheBoxRemovesTheListAndIgnoresALateAnswer() {
        open(Kind.EX, false);
        box().insert("tabn");
        pressTab(false);
        answer("tabnew", "tabnew", "tabnext");
        pressTab(false);
        commandLine.close(false);
        assertNull(findList());
        answer("tabnext", "tabnew", "tabnext"); // must not fail or reopen anything
        assertFalse(commandLine.isOpen());
    }
}

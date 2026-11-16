// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Neovim's insert-mode completion through a real session with its UI attached
 * (docs/ARCHITECTURE.md, "The completion list"): the keys reach Neovim after the text Eclipse
 * typed, Neovim types on while it completes, and the list reaches the listeners with its entries
 * and the place of the word. How the list is laid out is {@code CompletionMenuTest}; what the Lua
 * sends, {@code completion_menu_spec.lua}; the hand-back's steps with a fake Neovim,
 * {@code KeyInputTest}. Here: that Neovim's events and modes are what those tests assume.
 *
 * <p>The buffer is three words and two empty lines; "Eclipse typed {@code al}" is the document
 * push a sync point makes, as the editor's is.
 */
@Tag("integration")
class NeovimCompletionIntegrationTest {

    private static final List<String> WORDS = List.of("alpha", "alphabet", "alpine", "", "");
    private static final String BEFORE = "alpha\nalphabet\nalpine\n\n\n";
    /** Offset of the fourth line, where the typing happens. */
    private static final int LINE_4 = "alpha\nalphabet\nalpine\n".length();

    private static NeovimSession start(String... extraArgs) throws Exception {
        TestNeovim.require();
        List<String> args = new ArrayList<>(List.of("--clean"));
        args.addAll(List.of(extraArgs));
        return NeovimSession.start(TestNeovim.PATH, args);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private static void keys(NeovimSession s, String... keys) throws Exception {
        for (String key : keys) {
            s.input(key);
        }
    }

    private static String line(NeovimSession s, int line1) {
        try {
            return s.getBufferLines().get(line1 - 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mode(NeovimSession s) {
        try {
            return String.valueOf(s.lua("return vim.api.nvim_get_mode().mode"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The words in the buffer, the cursor on the fourth line in an insert Eclipse types, and
     * {@code typed} typed there by Eclipse: handed to Neovim at the next sync point.
     *
     * @return the sync point for a key pressed now
     */
    private static InsertReplay.SyncPoint eclipseTyped(NeovimSession s, String typed) throws Exception {
        s.setBufferLines(WORDS);
        keys(s, "4", "G", "i");
        assertFalse(s.isNeovimDrivenInsert(), "Eclipse types this insert");
        List<String> document = new ArrayList<>(WORDS);
        document.set(3, typed);
        s.setLeaveInsertPush(() -> {
            try {
                s.setBufferLines(document);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return InsertReplay.syncPoint(BEFORE, BEFORE.substring(0, LINE_4) + typed + BEFORE.substring(LINE_4), LINE_4,
                LINE_4 + typed.length());
    }

    private static List<String> words(CompletionMenu.View view) {
        return view.items().stream().map(CompletionMenu.Item::word).toList();
    }

    /**
     * {@code al} typed by Eclipse, then Ctrl-N: Neovim gets the text, completes it to the first
     * match and shows its list; Neovim types from here. Ctrl-N again moves on; a typed bracket
     * ends the completion, and Neovim inserts it.
     */
    @Test
    void ctrlNCompletesWhatEclipseTypedAndTheListIsReported() throws Exception {
        try (NeovimSession s = start()) {
            assertTrue(s.completesInNeovim());
            List<CompletionMenu.View> reports = new CopyOnWriteArrayList<>();
            s.addCompletionMenuListener(reports::add);

            s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
            assertTrue(s.isCompleting(), "from the moment the key is routed");
            waitFor(() -> s.completionMenu().isShowing(), "the list");
            CompletionMenu.View list = s.completionMenu();
            assertEquals(List.of("alpha", "alphabet", "alpine"), words(list));
            assertEquals(0, list.selected());
            assertEquals(4, list.line1());
            assertEquals(5, list.col0(), "the cursor after the inserted match");
            assertEquals(5, list.cellsBack(), "the word starts five cells before it");
            assertEquals("alpha", line(s, 4));
            assertEquals("ic", mode(s));
            assertTrue(s.isNeovimDrivenInsert(), "Neovim types while it completes");
            assertTrue(s.isCompleting());

            s.inputAsync("<C-n>");
            waitFor(() -> s.completionMenu().selected() == 1, "the second entry selected");
            waitFor(() -> "alphabet".equals(line(s, 4)), "the second match inserted");
            assertTrue(s.isCompleting(), "still: the next key is the completion's too");

            s.inputAsync("(");
            waitFor(() -> !s.completionMenu().isShowing(), "the list closed by the typed character");
            waitFor(() -> "alphabet(".equals(line(s, 4)), "Neovim inserted the character");
            waitFor(() -> !s.isCompleting(), "the completion over");
            assertEquals("i", mode(s));
            assertEquals(CompletionMenu.View.HIDDEN, reports.get(reports.size() - 1));
        }
    }

    /** Ctrl-E takes the completion back to what was typed; Ctrl-Y keeps the selected match. Both close the list. */
    @Test
    void ctrlETakesTheMatchBackAndCtrlYKeepsIt() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
            waitFor(() -> s.completionMenu().isShowing(), "the list");
            s.inputAsync("<C-e>");
            waitFor(() -> !s.completionMenu().isShowing(), "the list closed");
            waitFor(() -> "al".equals(line(s, 4)), "the typed text back");
            waitFor(() -> !s.isCompleting(), "the completion over");

            s.inputAsync("<C-p>"); // in the same insert, now Neovim's until it hands back: the last match
            waitFor(() -> s.completionMenu().isShowing(), "the list again");
            assertEquals(2, s.completionMenu().selected(), "Ctrl-P starts at the end");
            s.inputAsync("<C-y>");
            waitFor(() -> !s.completionMenu().isShowing(), "the list closed");
            waitFor(() -> !s.isCompleting(), "the completion over");
            assertEquals("alpine", line(s, 4));
            assertEquals("i", mode(s));
        }
    }

    /**
     * One match: Neovim inserts it and shows no list, but it is still completing (Ctrl-N again
     * would take the match back), so the next key is Neovim's. No list event tells that; the mode
     * does.
     */
    @Test
    void aSingleMatchShowsNoListButNeovimStillCompletes() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "alpi"), "<C-n>");
            waitFor(() -> "alpine".equals(line(s, 4)), "the only match inserted");
            waitFor(() -> "ic".equals(mode(s)), "completion mode");
            NeovimBarrier.pass(s);
            assertFalse(s.completionMenu().isShowing(), "no list for a single match");
            assertTrue(s.isCompleting());
            assertTrue(s.isNeovimDrivenInsert());

            s.inputAsync(" ");
            waitFor(() -> "alpine ".equals(line(s, 4)), "the space typed by Neovim");
            waitFor(() -> !s.isCompleting(), "the completion over");
        }
    }

    /** No match at all: nothing changes, Neovim says so, and the next key still ends a completion. */
    @Test
    void noMatchLeavesTheTextAndTheNextKeyEndsTheCompletion() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "zz"), "<C-n>");
            waitFor(() -> "ic".equals(mode(s)), "completion mode");
            assertEquals("zz", line(s, 4));
            s.inputAsync("x");
            waitFor(() -> "zzx".equals(line(s, 4)), "the letter typed by Neovim");
            waitFor(() -> !s.isCompleting(), "the completion over");
        }
    }

    /** With a number column both the list's column and the cursor's cell move right: the word's start does not. */
    @Test
    void aNumberColumnDoesNotMoveTheWord() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.wo.number = true vim.wo.numberwidth = 6");
            s.completeInNeovimAsync(eclipseTyped(s, "  al"), "<C-n>");
            waitFor(() -> s.completionMenu().isShowing(), "the list");
            assertEquals(7, s.completionMenu().col0(), "two spaces and alpha");
            assertEquals(5, s.completionMenu().cellsBack());
        }
    }

    /** Ctrl-X Ctrl-L, Vim's whole-line completion, sent as one: the lines that begin as this one does. */
    @Test
    void ctrlXCtrlLCompletesWholeLines() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "alph"), "<C-x><C-l>");
            waitFor(() -> s.completionMenu().isShowing(), "the list of lines");
            assertEquals(List.of("alpha", "alphabet"), words(s.completionMenu()));
            assertTrue(s.isCompleting());
        }
    }

    /**
     * A click into the text while the list is open: the completion ends, the text stays as it is
     * (the match that was inserted), and Neovim is in plain insert mode again.
     */
    @Test
    void endingTheCompletionKeepsTheTextAndClosesTheList() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
            waitFor(() -> s.completionMenu().isShowing(), "the list");
            s.endCompletionAsync();
            waitFor(() -> !s.completionMenu().isShowing(), "the list closed");
            waitFor(() -> "i".equals(mode(s)), "plain insert mode");
            assertEquals("alpha", line(s, 4));
            s.endCompletionAsync(); // none going on: nothing happens
            NeovimBarrier.pass(s);
            assertEquals("i", mode(s));
            assertEquals("alpha", line(s, 4));
        }
    }

    /**
     * The whole insert is one for Neovim: after Esc, {@code .} repeats what Eclipse typed and the
     * completed word together, and one {@code u} takes it back.
     */
    @Test
    void dotRepeatsTheTypedTextWithTheCompletedWordAndUndoTakesItBack() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
            waitFor(() -> s.completionMenu().isShowing(), "the list");
            s.inputAsync("<C-y>");
            waitFor(() -> !s.isCompleting(), "the completion over");
            keys(s, "<Esc>");
            assertEquals("alpha", line(s, 4));
            keys(s, "j", ".");
            assertEquals("alpha", line(s, 5), "the repeat: the typed al and its completion");
            keys(s, "u");
            assertEquals("", line(s, 5));
            keys(s, "u");
            assertEquals("", line(s, 4), "one undo for the insert: typing and completion");
        }
    }

    /**
     * While a macro is recorded Neovim types the insert to its end: the list is drawn, but the
     * plug-in keeps out (no key of its own may land in the macro), and the Ctrl Keys page's choice
     * of letters stands.
     */
    @Test
    void whileRecordingTheListIsDrawnAndThePlugInKeepsOut() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(WORDS);
            keys(s, "4", "G", "q", "q", "i");
            assertTrue(s.isNeovimDrivenInsert());
            keys(s, "a", "l", "<C-n>");
            waitFor(() -> s.completionMenu().isShowing(), "the list");
            assertEquals(List.of("alpha", "alphabet", "alpine"), words(s.completionMenu()));
            assertFalse(s.isCompleting(), "Neovim owns this insert");
            s.endCompletionAsync();
            NeovimBarrier.pass(s);
            assertEquals("ic", mode(s), "not ended by the plug-in");
            keys(s, "<C-y>", "<Esc>", "q");
            assertEquals("ial\u000e\u0019\u001b", String.valueOf(s.lua("return vim.fn.getreg('q')")),
                    "the macro holds the keys typed, and none of the plug-in's");
        }
    }

    /**
     * A list nobody asked for is not drawn: in an insert Eclipse types, something in Neovim opens
     * a completion (a config that completes as text arrives). Eclipse goes on typing; the list
     * would hang where Neovim's cursor is, which is not where the user types.
     */
    @Test
    void aListOpenedBehindAnInsertEclipseTypesIsNotDrawn() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(WORDS);
            keys(s, "4", "G", "i");
            assertFalse(s.isNeovimDrivenInsert());
            s.lua("vim.fn.complete(1, { 'alpha', 'alphabet' })");
            waitFor(() -> "ic".equals(mode(s)), "Neovim's completion going on");
            NeovimBarrier.pass(s);
            assertFalse(s.completionMenu().isShowing());
            assertFalse(s.isCompleting(), "Eclipse types: the keys stay its own");
        }
    }

    /**
     * {@code vim.g.neovim4eclipse_completion_menu = 0} gives the keys back to Eclipse's Word
     * Completion and draws nothing.
     */
    @Test
    void theListIsOffWhenTurnedOff() throws Exception {
        try (NeovimSession s = start("--cmd", "let g:neovim4eclipse_completion_menu = 0")) {
            assertFalse(s.completesInNeovim());
            s.setBufferLines(WORDS);
            keys(s, "4", "G", "q", "q", "i", "a", "l", "<C-n>"); // recorded: Neovim types and completes
            waitFor(() -> "ic".equals(mode(s)), "Neovim's completion going on");
            NeovimBarrier.pass(s);
            assertFalse(s.completionMenu().isShowing());
        }
    }

    /**
     * The list does not need the viewport ({@code vim.g.neovim4eclipse_viewport = 0}: Neovim keeps
     * a screen of its own, 120 cells wide). Neovim keeps the cursor's line on that screen, so the
     * list is reported as with it: on a line far down, and for a word Neovim wraps, which starts
     * on the screen row before the cursor's.
     */
    @Test
    void theListIsDrawnWithoutTheViewportToo() throws Exception {
        try (NeovimSession s = start("--cmd", "let g:neovim4eclipse_viewport = 0")) {
            assertFalse(s.viewportEnabled());
            assertTrue(s.completesInNeovim());
            List<String> lines = new ArrayList<>(List.of("alpha", "alphabet", "alpine"));
            for (int i = 0; i < 300; i++) {
                lines.add("");
            }
            s.setBufferLines(lines);
            keys(s, "2", "2", "0", "G", "q", "q", "i", "a", "l", "<C-n>"); // recorded: Neovim types and completes
            waitFor(() -> s.completionMenu().isShowing(), "the list on line 220");
            assertEquals(220, s.completionMenu().line1());
            assertEquals(5, s.completionMenu().cellsBack());

            keys(s, "<C-e>", "<Esc>", "q", "u");
            lines.set(3, "x".repeat(117) + " "); // the word starts in cell 118 of 120
            s.setBufferLines(lines);
            keys(s, "4", "G", "q", "q", "A", "a", "l", "<C-n>");
            waitFor(() -> s.completionMenu().isShowing() && s.completionMenu().line1() == 4, "the list on the long line");
            assertEquals(123, s.completionMenu().col0());
            assertEquals(5, s.completionMenu().cellsBack(), "one screen row's width added: the word starts on the row before");
        }
    }
}

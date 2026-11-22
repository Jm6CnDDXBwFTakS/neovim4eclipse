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
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Neovim's insert-mode completion through a real session with its UI attached: the keys reach
 * Neovim after the text Eclipse typed, Neovim types on while it completes, and Neovim's popup menu
 * (the float with no window, -1) reaches the floats' listeners to be drawn. The hand-back's steps
 * with a fake Neovim are {@code KeyInputTest}; which floats are drawn, {@code GridsTest}. Here:
 * that Neovim's events and modes are what those tests assume.
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

    /** Neovim's popup menu as last reported to be drawn, or null while none is. */
    private static FloatWindow menu(NeovimSession s) {
        for (FloatWindow f : s.floatingWindows()) {
            if (f.grid().window() == -1) {
                return f;
            }
        }
        return null;
    }

    private static boolean menuShown(NeovimSession s) {
        return menu(s) != null;
    }

    /** The words of the popup menu drawn now, its rows' text stripped. */
    private static List<String> words(NeovimSession s) {
        FloatWindow menu = menu(s);
        List<String> words = new ArrayList<>();
        for (int r = 0; menu != null && r < menu.grid().height(); r++) {
            words.add(menu.grid().row(r).strip());
        }
        return words;
    }

    /** The entry selected in Neovim's completion, 0-based; -1 for none. */
    private static long selected(NeovimSession s) {
        try {
            return ((Number) s.lua("return vim.fn.complete_info({ 'selected' }).selected")).longValue();
        } catch (Exception e) {
            return -2;
        }
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
            List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
            s.addFloatingWindowListener(reports::add);

            s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
            assertTrue(s.isCompleting(), "from the moment the key is routed");
            waitFor(() -> menuShown(s), "the list");
            assertEquals(List.of("alpha", "alphabet", "alpine"), words(s));
            assertEquals(0, selected(s));
            assertEquals("alpha", line(s, 4));
            assertEquals("ic", mode(s));
            assertTrue(s.isNeovimDrivenInsert(), "Neovim types while it completes");
            assertTrue(s.isCompleting());

            s.inputAsync("<C-n>");
            waitFor(() -> selected(s) == 1, "the second entry selected");
            waitFor(() -> "alphabet".equals(line(s, 4)), "the second match inserted");
            assertTrue(s.isCompleting(), "still: the next key is the completion's too");

            s.inputAsync("(");
            waitFor(() -> !menuShown(s), "the list closed by the typed character");
            waitFor(() -> "alphabet(".equals(line(s, 4)), "Neovim inserted the character");
            waitFor(() -> !s.isCompleting(), "the completion over");
            assertEquals("i", mode(s));
            assertTrue(reports.get(reports.size() - 1).stream().noneMatch(f -> f.grid().window() == -1));
        }
    }

    /** Ctrl-E takes the completion back to what was typed; Ctrl-Y keeps the selected match. Both close the list. */
    @Test
    void ctrlETakesTheMatchBackAndCtrlYKeepsIt() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
            waitFor(() -> menuShown(s), "the list");
            s.inputAsync("<C-e>");
            waitFor(() -> !menuShown(s), "the list closed");
            waitFor(() -> "al".equals(line(s, 4)), "the typed text back");
            waitFor(() -> !s.isCompleting(), "the completion over");

            s.inputAsync("<C-p>"); // in the same insert, now Neovim's until it hands back: the last match
            waitFor(() -> menuShown(s), "the list again");
            assertEquals(2, selected(s), "Ctrl-P starts at the end");
            s.inputAsync("<C-y>");
            waitFor(() -> !menuShown(s), "the list closed");
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
            assertFalse(menuShown(s), "no list for a single match");
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

    /** Ctrl-X Ctrl-L, Vim's whole-line completion, sent as one: the lines that begin as this one does. */
    @Test
    void ctrlXCtrlLCompletesWholeLines() throws Exception {
        try (NeovimSession s = start()) {
            s.completeInNeovimAsync(eclipseTyped(s, "alph"), "<C-x><C-l>");
            waitFor(() -> menuShown(s), "the list of lines");
            assertEquals(List.of("alpha", "alphabet"), words(s));
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
            waitFor(() -> menuShown(s), "the list");
            s.endCompletionAsync();
            waitFor(() -> !menuShown(s), "the list closed");
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
            waitFor(() -> menuShown(s), "the list");
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
            waitFor(() -> menuShown(s), "the list");
            assertEquals(List.of("alpha", "alphabet", "alpine"), words(s));
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
            assertFalse(menuShown(s), "not drawn where Neovim's cursor is: Eclipse types elsewhere");
            assertFalse(s.isCompleting(), "Eclipse types: the keys stay its own");
        }
    }
}

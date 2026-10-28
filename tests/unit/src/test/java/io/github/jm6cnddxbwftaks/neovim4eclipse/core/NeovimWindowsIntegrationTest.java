// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * A Neovim window per Eclipse editor (docs/design/WINDOWS.md) through the session, against a real
 * {@code nvim}: what only the whole round trip shows, the cursor reports filtered by window, the
 * travel on {@code Ctrl-O}, the safety net and a restart. The Lua side alone is windows_spec.lua,
 * the bookkeeping EditorWindowsTest.
 */
@Tag("integration")
class NeovimWindowsIntegrationTest {

    private static final String NVIM = TestNeovim.PATH;

    private static NeovimSession start() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        return NeovimSession.start(NVIM, List.of("--clean"));
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    /** Waits for Neovim's or the session's state, read with RPC calls that may fail while it changes. */
    private static void waitFor(Check condition, String what) throws Exception {
        BooleanSupplier quiet = () -> {
            try {
                return condition.holds();
            } catch (Exception e) {
                return false;
            }
        };
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!quiet.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private static int number(NeovimSession s, String lua) throws Exception {
        return ((Number) s.lua(lua)).intValue();
    }

    private static int currentWin(NeovimSession s) throws Exception {
        return number(s, "return vim.api.nvim_get_current_win()");
    }

    private static int cursorLine(NeovimSession s) throws Exception {
        return number(s, "return vim.api.nvim_win_get_cursor(0)[1]");
    }

    private static List<String> lines(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> "line " + i).toList();
    }

    @Test
    void eachEditorGetsItsOwnWindowInItsOwnTabAndShowingItAgainReusesIt() throws Exception {
        try (NeovimSession s = start()) {
            int home = currentWin(s);
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(doc, a, "");
            assertTrue(a.isOpenIn(s));
            assertNotEquals(home, a.winid());
            assertEquals(a.winid(), currentWin(s));
            assertEquals(a.winid(), s.getAttachedWinid());
            s.showWindow(doc, b, "");
            assertNotEquals(a.winid(), b.winid());
            assertEquals(3, number(s, "return #vim.api.nvim_list_tabpages()"), "the home tab and one per editor");
            int first = a.winid();
            s.showWindow(doc, a, "");
            assertEquals(first, a.winid(), "the same window again");
            assertEquals(3, number(s, "return #vim.api.nvim_list_tabpages()"));
            s.showBuffer(doc);
            assertEquals(home, currentWin(s), "without a window of its own: the home window, as before windows");
        }
    }

    /** Two editors on one file (E3): a cursor each, one text and one undo history. */
    @Test
    void twoEditorsOnOneFileKeepTheirOwnCursorsAndShareTheUndo() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(doc, a, "");
            s.setBufferLines(lines(10));
            s.input("3G");
            s.showWindow(doc, b, "");
            s.input("8G");
            s.showWindow(doc, a, "");
            assertEquals(3, cursorLine(s), "A's cursor stayed where A left it");
            s.showWindow(doc, b, "");
            assertEquals(8, cursorLine(s));
            s.input("d");
            s.input("d");
            s.showWindow(doc, a, "");
            assertEquals(9, s.getBufferLines().size(), "one text");
            s.input("u");
            assertEquals(lines(10), s.getBufferLines(), "one undo history: A undoes B's dd");
        }
    }

    /** With two editors on one file the buffer is the same; only the window tells them apart. */
    @Test
    void cursorReportsFromAnotherEditorsWindowAreNotTheCaret() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(doc, b, "");
            s.showWindow(doc, a, "");
            s.setBufferLines(lines(10));
            List<CursorPos> cursors = new CopyOnWriteArrayList<>();
            s.addCursorListener(cursors::add);
            // As the SETUP autocommand sends them: line, column, buffer, top line, window.
            String report = "vim.rpcnotify(0, 'eclipse_cursor', %d, 0, " + doc.bufnr() + ", 1, %d)";
            s.lua(report.formatted(7, b.winid()));
            s.lua(report.formatted(5, a.winid()));
            waitFor(() -> !cursors.isEmpty(), "the attached window's report");
            NeovimBarrier.pass(s);
            assertEquals(List.of(new CursorPos(5, 0)), cursors, "B's report is dropped");
        }
    }

    /** {@code :lcd} once per window, so a folder the user sets stays with that editor. */
    @Test
    void theFolderIsSetWhenTheWindowOpensAndATypedLcdSurvivesAnEditorSwitch(@TempDir Path dir) throws Exception {
        Path projectA = Files.createDirectories(dir.resolve("a"));
        Path projectB = Files.createDirectories(dir.resolve("b"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        try (NeovimSession s = start()) {
            DocumentBuffer docA = new DocumentBuffer("");
            DocumentBuffer docB = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(docA, a, projectA.toString());
            assertEquals(projectA.toRealPath(), cwd(s));
            s.command("lcd " + elsewhere);
            s.showWindow(docB, b, projectB.toString());
            assertEquals(projectB.toRealPath(), cwd(s));
            s.showWindow(docA, a, projectA.toString());
            assertEquals(elsewhere.toRealPath(), cwd(s), "the typed :lcd, not the project again");
        }
    }

    private static Path cwd(NeovimSession s) throws Exception {
        return Path.of((String) s.lua("return vim.fn.getcwd()")).toRealPath();
    }

    /**
     * Ctrl-O across files (E2): A's window jumps into B's file and travels to B; B's old window gets
     * A's file at A's cursor. The jump history stays whole, so Ctrl-O in B goes back to a.
     */
    @Test
    void aWindowTravelsToTheEditorOfTheFileItJumpedInto() throws Exception {
        try (NeovimSession s = start()) {
            List<Integer> entered = new CopyOnWriteArrayList<>();
            s.setBufferEnterHandler(entered::add);
            DocumentBuffer docA = new DocumentBuffer("");
            DocumentBuffer docB = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(docB, b, "");
            s.setBufferLines(lines(30));
            s.showWindow(docA, a, "");
            s.setBufferLines(lines(30));
            s.input("10G");
            s.input("20G");
            waitFor(() -> s.getLastCursor().line1Based() == 20, "A's cursor report");
            int aWin = a.winid();
            int bWin = b.winid();

            s.command("buffer " + docB.bufnr()); // the jump, in A's window
            waitFor(() -> entered.contains(docB.bufnr()), "the jump to be reported");
            s.travelWindow(a, b, docA);
            s.showWindow(docB, b, "");

            assertEquals(aWin, b.winid(), "B has A's window, which jumped");
            assertEquals(bWin, a.winid(), "A has B's old one");
            assertEquals(aWin, currentWin(s));
            assertEquals(docA.bufnr(), number(s, "return vim.api.nvim_win_get_buf(" + bWin + ")"));
            assertEquals(20, number(s, "return vim.api.nvim_win_get_cursor(" + bWin + ")[1]"), "at A's cursor");

            entered.clear();
            s.input("<C-o>");
            waitFor(() -> entered.contains(docA.bufnr()), "Ctrl-O in B back into a");
            assertEquals(20, cursorLine(s), "the jump history came along");
        }
    }

    /** The editor jumped into was never attached in this session: it simply takes the window. */
    @Test
    void anEditorWithoutAWindowYetTakesTheOneThatJumped() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferEnterHandler(bufnr -> { });
            DocumentBuffer docA = new DocumentBuffer("");
            DocumentBuffer docB = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(docB, a, ""); // B's buffer exists, as after :badd or a closed editor's
            s.showWindow(docA, a, "");
            int aWin = a.winid();
            s.command("buffer " + docB.bufnr());
            s.travelWindow(a, b, docA);
            s.showWindow(docB, b, "");
            assertEquals(aWin, b.winid());
            assertFalse(a.isOpenIn(s));
            s.showWindow(docA, a, "");
            assertNotEquals(aWin, a.winid(), "a new one for A");
        }
    }

    /**
     * An untitled document has no folder of its own: its window keeps the folder of the editor
     * attached before (copied by Neovim), or gets the home folder when there was none.
     */
    @Test
    void anUntitledDocumentTakesTheFolderOfTheEditorBeforeOrTheHomeFolder(@TempDir Path dir) throws Exception {
        try (NeovimSession s = start()) {
            EditorWindow first = new EditorWindow();
            s.showWindow(new DocumentBuffer(""), first, null);
            assertEquals(Path.of(System.getProperty("user.home")).toRealPath(), cwd(s), "no editor before it");
            EditorWindow project = new EditorWindow();
            s.showWindow(new DocumentBuffer(""), project, dir.toString());
            EditorWindow untitled = new EditorWindow();
            s.showWindow(new DocumentBuffer(""), untitled, null);
            assertEquals(dir.toRealPath(), cwd(s), "the folder of the editor attached before");
        }
    }

    @Test
    void closingAnEditorsWindowClosesItsTabAndKeepsTheBuffer() throws Exception {
        try (NeovimSession s = start()) {
            int home = currentWin(s);
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            s.showWindow(doc, a, "");
            int win = a.winid();
            s.closeWindowAsync(a);
            waitFor(() -> !a.isOpenIn(s), "the window to close");
            waitFor(() -> number(s, "return #vim.api.nvim_list_tabpages()") == 1, "its tab to close");
            assertEquals(home, currentWin(s));
            assertEquals(home, s.getAttachedWinid());
            assertFalse(Boolean.TRUE.equals(s.lua("return vim.api.nvim_win_is_valid(" + win + ")")));
            assertTrue(doc.isOpenIn(s), "the buffer stays for the document's other editors");
        }
    }

    /** The safety net: a window closed in Neovim by itself is forgotten and opened again at the next show. */
    @Test
    void aWindowClosedInNeovimIsOpenedAgainAtTheNextShow() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer docA = new DocumentBuffer("");
            DocumentBuffer docB = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(docA, a, "");
            s.showWindow(docB, b, "");
            int old = a.winid();
            s.lua("vim.api.nvim_win_close(" + old + ", true)"); // as a plug-in might
            waitFor(() -> !a.isOpenIn(s), "the WinClosed notice");
            s.showWindow(docA, a, "");
            assertTrue(a.isOpenIn(s));
            assertNotEquals(old, a.winid());
            assertEquals(a.winid(), currentWin(s));
        }
    }

    /**
     * {@code :tabclose} in the attached editor's window: Neovim enters another window, which is not
     * the attached one, so the session goes back to the attached buffer, in the home window.
     */
    @Test
    void closingTheAttachedWindowInNeovimFallsBackToTheHomeWindow() throws Exception {
        try (NeovimSession s = start()) {
            int home = currentWin(s);
            DocumentBuffer docA = new DocumentBuffer("");
            DocumentBuffer docB = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(docB, b, "");
            s.showWindow(docA, a, "");
            s.command("tabclose");
            waitFor(() -> currentWin(s) == home && s.getAttachedWinid() == home, "the home window");
            waitFor(() -> number(s, "return vim.api.nvim_get_current_buf()") == docA.bufnr(), "A's buffer in it");
            assertFalse(a.isOpenIn(s));
            assertTrue(b.isOpenIn(s), "B's window is not touched");
        }
    }

    @Test
    void wipingABufferClosesTheWindowsShowingIt() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            DocumentBuffer other = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            EditorWindow c = new EditorWindow();
            s.showWindow(other, c, "");
            s.showWindow(doc, b, "");
            s.showWindow(doc, a, "");
            s.closeBufferAsync(doc);
            waitFor(() -> !doc.isOpenIn(s), "the buffer to be wiped");
            waitFor(() -> !a.isOpenIn(s) && !b.isOpenIn(s), "its windows to be forgotten");
            assertTrue(c.isOpenIn(s));
            assertEquals(2, number(s, "return #vim.api.nvim_list_tabpages()"), "the home tab and C's");
        }
    }

    /** Restart Neovim: a token of the old session holds no window in the new one, which opens one. */
    @Test
    void afterARestartEachEditorGetsANewWindowAtItsNextShow() throws Exception {
        DocumentBuffer doc = new DocumentBuffer("");
        EditorWindow a = new EditorWindow();
        try (NeovimSession first = start()) {
            first.showWindow(doc, a, "");
            assertTrue(a.isOpenIn(first));
        }
        try (NeovimSession second = start()) {
            assertFalse(a.isOpenIn(second));
            second.showWindow(doc, a, "");
            assertTrue(a.isOpenIn(second));
            assertTrue(doc.isOpenIn(second));
            assertEquals(a.winid(), currentWin(second));
        }
    }
}

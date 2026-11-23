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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Grids;
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
        TestNeovim.require();
        return NeovimSession.start(NVIM, List.of("--clean"));
    }

    /**
     * A float running a terminal is how fzf-lua draws its picker, and it closes that float with
     * {@code 'eventignore'} set, so Neovim leaves Terminal mode without a {@code ModeChanged}: Eclipse
     * would keep showing {@code t} until the next key. Here {@code ModeChanged} stays ignored until
     * the check, so only the read at the float's closing can bring the mode back. It closes as the
     * keyboard leaves the editor, or by the plug-in's own action.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { true, false })
    void theModeIsRightAfterATerminalFloatClosesWithoutAKey(boolean byLeaving) throws Exception {
        TestNeovim.require();
        try (NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"))) {
            s.lua("""
                    local buf = vim.api.nvim_create_buf(false, true)
                    vim.g.term_float = vim.api.nvim_open_win(buf, true,
                      { relative = 'editor', row = 1, col = 1, width = 30, height = 5 })
                    vim.fn.jobstart(vim.o.shell, { term = true })
                    vim.cmd.startinsert()
                    """);
            waitFor(() -> "t".equals(s.getMode()) && s.inPluginFloat(), "Terminal mode in the float");
            s.lua("vim.o.eventignore = 'ModeChanged'");
            if (byLeaving) {
                s.leaveFloatAsync();
            } else {
                s.lua("vim.api.nvim_win_close(vim.g.term_float, true)");
            }
            waitFor(() -> !s.inPluginFloat(), "the float closed");
            waitFor(() -> "n".equals(s.getMode()), "Eclipse's mode back to Normal, as Neovim's is");
            s.lua("vim.o.eventignore = ''");
        }
    }

    /**
     * A plug-in that closes its float and opens a file split without {@code :stopinsert}: Neovim
     * would go on in Insert mode in the editor's window, and the next keys would type into the
     * document. The insert ends with the hand-over, and the editor's cursor stays where it was.
     */
    @Test
    void anInsertBegunInAFloatEndsWhenItsFileWindowIsHandedOver(@org.junit.jupiter.api.io.TempDir Path folder)
            throws Exception {
        TestNeovim.require();
        Path file = java.nio.file.Files.writeString(folder.resolve("other.txt"), "one\ntwo\n");
        try (NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"))) {
            s.lua("""
                    vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'document' })
                    vim.api.nvim_win_set_cursor(0, { 1, 3 })
                    vim.g.editor_win = vim.api.nvim_get_current_win()
                    vim.g.plain_float = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), true,
                      { relative = 'editor', row = 1, col = 1, width = 30, height = 5 })
                    vim.cmd.startinsert()
                    """);
            waitFor(() -> "i".equals(s.lua("return vim.api.nvim_get_mode().mode")), "Insert mode in the float");
            s.lua("vim.schedule(function() vim.api.nvim_win_close(vim.g.plain_float, true)"
                    + " vim.cmd.vsplit(vim.fn.fnameescape([[" + file + "]])) end)");
            waitFor(() -> plainWindows(s) == 1
                    && number(s, "return vim.g.editor_win") == number(s, "return vim.api.nvim_get_current_win()"),
                    "the split handed over");
            waitFor(() -> "n".equals(s.lua("return vim.api.nvim_get_mode().mode")), "the insert ended");
            assertEquals(3, number(s, "return vim.api.nvim_win_get_cursor(0)[2]"), "the editor's cursor stays");
            assertEquals("document", s.lua("return vim.api.nvim_get_current_line()"));
        }
    }

    /**
     * {@code :noautocmd split} fires no {@code WinNew}: without the check as Neovim is about to wait
     * for a key, the keys would go on in a window Eclipse does not draw.
     */
    @Test
    void aSplitMadeWithNoautocmdIsClosedBeforeTheNextKey() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.g.editor_win = vim.api.nvim_get_current_win() vim.cmd('noautocmd split')");
            waitFor(() -> plainWindows(s) == 1
                    && number(s, "return vim.g.editor_win") == number(s, "return vim.api.nvim_get_current_win()"),
                    "the split closed, Neovim back in the editor's window");
        }
    }

    /**
     * A click in the text while Neovim waits for a key is dropped, by either route: Neovim would
     * answer only after that key, and a press held until then would act on what the key began. Here
     * {@code Ctrl-W} is pending in a float: held, a press would close the float once {@code :} ended
     * the wait, and the rest of the line would run in the editor, on the document.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { true, false })
    void aClickIsDroppedWhileNeovimWaitsForAKey(boolean leftToEclipse) throws Exception {
        long limit = TestWait.millis();
        try (NeovimSession s = start();
                io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog log =
                        io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog.of(InputThread.class,
                                java.util.logging.Level.FINE)) {
            floatOverThreeLines(s);
            s.inputAsync("<C-w>");
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (leftToEclipse) {
                s.leaveFloatWithLineAsync();
            } else {
                s.leaveFloatAsync();
            }
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.assertNotLoggedAtOrAbove(java.util.logging.Level.FINE, " failed");
            s.inputAsync("<Esc>:let g:after = 1<CR>");
            waitFor(() -> Long.valueOf(1).equals(longOrNull(s.lua("return vim.g.after"))), "the keys ran");
            assertEquals(Boolean.TRUE, s.lua("return vim.api.nvim_win_is_valid(vim.g.plain_float)"),
                    "the click was dropped: the float stays");
            assertEquals(List.of("one", "two", "three"),
                    s.lua("return vim.api.nvim_buf_get_lines(vim.g.doc_buf, 0, -1, false)"));
        }
    }

    /**
     * Leaving the editor during a half-typed command sends Neovim no key, as losing the focus sends
     * none in a terminal: {@code <C-c>} would be typed into the text after {@code r} or {@code
     * Ctrl-V}. The float stays, and the next key finishes the command on the document.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "vlr,QQe", "r<C-v>,Qne", "i<C-v>,Qone" })
    void leavingDuringAHalfTypedCommandSendsNoKey(String pending, String expected) throws Exception {
        long limit = TestWait.millis();
        try (NeovimSession s = start();
                io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog log =
                        io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog.of(InputThread.class,
                                java.util.logging.Level.FINE)) {
            s.lua("""
                    vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'one' })
                    vim.g.doc_buf = vim.api.nvim_get_current_buf()
                    vim.g.plain_float = vim.api.nvim_open_win(vim.g.doc_buf, true,
                      { relative = 'editor', row = 1, col = 1, width = 30, height = 5 })
                    vim.api.nvim_win_set_cursor(0, { 1, 0 })
                    """);
            waitFor(s::inPluginFloat, "the float has the cursor");
            s.inputAsync(pending);
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            s.leaveFloatAsync();
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.assertNotLoggedAtOrAbove(java.util.logging.Level.FINE, " failed");
            s.inputAsync("Q"); // not x or X, which after Ctrl-V begin a hex code
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            waitFor(() -> List.of(expected).equals(s.lua("return vim.api.nvim_buf_get_lines(vim.g.doc_buf, 0, -1, false)")),
                    "the next key finished the command, with nothing else typed");
            assertEquals(Boolean.TRUE, s.lua("return vim.api.nvim_win_is_valid(vim.g.plain_float)"), "the float stays");
        }
    }

    /**
     * A {@code :} line is abandoned by a click or the keyboard leaving, but not while Neovim waits
     * for the rest of {@code Ctrl-V} there: {@code <C-c>} would be typed into the line.
     */
    @Test
    void aLineIsAbandonedUnlessNeovimWaitsForAKeyOnIt() throws Exception {
        long limit = TestWait.millis();
        try (NeovimSession s = start()) {
            s.inputAsync(":abc<C-v>");
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            s.abandonLineAsync();
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            s.inputAsync("Q"); // not x or X, which after Ctrl-V begin a hex code
            waitFor(() -> "abcQ".equals(s.lua("return vim.fn.getcmdline()")), "the next key finished Ctrl-V");
            s.abandonLineAsync(); // no longer waiting: now it goes
            waitFor(() -> "n".equals(s.lua("return vim.api.nvim_get_mode().mode")), "the line abandoned");
        }
    }

    /** A click on a float's grid during a half-typed command is dropped, not replayed after the key. */
    @Test
    void aGridClickIsDroppedWhileNeovimWaitsForAKey() throws Exception {
        long limit = TestWait.millis();
        try (NeovimSession s = start()) {
            floatOverThreeLines(s);
            s.lua("vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'one' }) vim.api.nvim_win_set_cursor(0, { 1, 0 })");
            s.inputAsync("r");
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            s.floatMouseAsync(0, "left", "press", "", 2, 2); // 0: any grid; sent, it would end the wait
            s.queuedWorkDone().get(limit, java.util.concurrent.TimeUnit.MILLISECONDS);
            s.inputAsync("Q"); // still the r's character
            waitFor(() -> "Qne".equals(s.lua("return vim.api.nvim_get_current_line()")),
                    "the key finished r: the click did not reach Neovim");
        }
    }

    /** A document of three lines, and a plug-in's float over it with the cursor. */
    private static void floatOverThreeLines(NeovimSession s) throws Exception {
        s.lua("""
                vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'one', 'two', 'three' })
                vim.g.doc_buf = vim.api.nvim_get_current_buf()
                vim.g.plain_float = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), true,
                  { relative = 'editor', row = 1, col = 1, width = 30, height = 5 })
                """);
        waitFor(s::inPluginFloat, "the float has the cursor");
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /** The windows of the current tab page but floats: ui2's command line and messages are floats too. */
    private static int plainWindows(NeovimSession s) throws Exception {
        return number(s, "return #vim.tbl_filter(function(w) return vim.api.nvim_win_get_config(w)"
                + ".relative == '' end, vim.api.nvim_tabpage_list_wins(0))");
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

    /** The message closing lazy's window as Neovim's start ends, from the module's own text. */
    private static String startMessage(NeovimSession s) throws Exception {
        return (String) s.lua("local w = package.loaded['neovim4eclipse.windows']"
                + " return w.float_closed_message('lazy', w.AT_START)");
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

    /** A config's command opening lazy.nvim's installer as it does: a float with the focus. */
    private static final String OPEN_START_FLOAT = "lua local b = vim.api.nvim_create_buf(false, true); vim.bo[b].filetype = 'lazy';"
            + " vim.g.n4e_start_float = vim.api.nvim_open_win(b, true, {relative = 'editor', row = 1, col = 1, width = 20, height = 3})";

    /**
     * The float the config left the focus in at start (lazy.nvim's installer) is closed as Neovim
     * starts, and Neovim says why (ui2, started before the message): entered later in the first
     * editor, it would take the keys typed while Neovim started. A plain window is current, the
     * home window with the home buffer.
     */
    @Test
    void aFloatFocusedAtTheStartIsClosedAndNeovimSaysWhy() throws Exception {
        TestNeovim.require();
        try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean", "-c", OPEN_START_FLOAT))) {
            // Closed at once, or at UIEnter should Neovim's start not be over yet.
            waitFor(() -> number(s, "return vim.api.nvim_win_is_valid(vim.g.n4e_start_float) and 1 or 0") == 0, "the float closed");
            assertEquals("", s.lua("return vim.api.nvim_win_get_config(0).relative"), "a plain window is current");
            assertEquals(currentWin(s), s.getAttachedWinid(), "and it is the home window");
            assertEquals(number(s, "return package.loaded['neovim4eclipse.windows'].home_buffer()"),
                    number(s, "return vim.api.nvim_get_current_buf()"), "it shows the home buffer");
            waitFor(() -> Ui2Text.shown(s).contains(startMessage(s)),
                    "the message about the float, shown by Neovim: " + Ui2Text.shown(s));
        }
    }

    /**
     * Opens a float on a scratch buffer of filetype {@code picker}, entered, as a picker opens its
     * prompt; {@code config} adds to its window config (a Lua table's fields). Answers its window.
     */
    private static int openFocusedFloat(NeovimSession s, int width, int height, String config) throws Exception {
        return number(s, "local buf = vim.api.nvim_create_buf(false, true); vim.bo[buf].filetype = 'picker';"
                + " return vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = "
                + width + ", height = " + height + config + " })");
    }

    private static boolean valid(NeovimSession s, int win) throws Exception {
        return number(s, "return vim.api.nvim_win_is_valid(" + win + ") and 1 or 0") == 1;
    }

    /** The one check, end to end: a float its plug-in hides while Neovim is in it is closed. */
    @Test
    void aFloatItsPlugInHidesWhileNeovimIsInItIsClosed() throws Exception {
        try (NeovimSession s = start()) {
            int float_ = openFocusedFloat(s, 5, 1, "");
            waitFor(() -> s.floatingWindows().stream().anyMatch(f -> f.grid().window() == float_), "the float drawn");
            assertTrue(valid(s, float_), "drawn: it stays");
            s.lua("vim.api.nvim_win_set_config(" + float_ + ", { hide = true })");
            waitFor(() -> !valid(s, float_), "the hidden float closed");
            waitFor(() -> Ui2Text.shown(s).contains("Closed the floating window of picker: it is hidden"),
                    "the reason, shown by Neovim: " + Ui2Text.shown(s));
        }
    }

    /** As above: a float with Neovim in it too large to draw, the limits worded from their one source. */
    @Test
    void aFloatNeovimIsInPastTheLimitsIsClosed() throws Exception {
        try (NeovimSession s = start()) {
            int width = 1000;
            int float_ = openFocusedFloat(s, width, Grids.MAX_FLOAT_CELLS / width + 1, ""); // a row past the cells
            waitFor(() -> !valid(s, float_), "the float too large to draw closed");
            waitFor(() -> Ui2Text.shown(s).contains("the editor cannot draw it (" + Grids.PAST_LIMITS + ")"),
                    "the reason, shown by Neovim: " + Ui2Text.shown(s));
        }
    }

    /**
     * Lua that sets lazy.nvim up (the pinned copy at {@code lazy}) with the plug-ins {@code specs}
     * (a Lua table), its own folders under {@code tmp} (the rest of Neovim's are this module's,
     * under {@code target/}: the root pom.xml), nothing checked or watched, no
     * {@code .lazy.lua} looked for in the folders above (it would run one, or ask whether to
     * trust it, and the test would wait), and missing plug-ins installed only if {@code install}.
     */
    private static String lazySetup(Path lazy, String tmp, String specs, boolean install) {
        return PinnedPlugins.onRuntimePath(lazy) + " local tmp = [==[" + tmp + "]==]"
                + " require('lazy').setup(" + specs + ", { root = tmp .. '/plugins', lockfile = tmp .. '/lazy-lock.json',"
                + "   state = tmp .. '/state.json', readme = { enabled = false }, pkg = { cache = tmp .. '/pkg-cache.lua' },"
                + "   rocks = { enabled = false }, install = { missing = " + install + " }, checker = { enabled = false },"
                + "   change_detection = { enabled = false }, local_spec = false,"
                + "   performance = { cache = { enabled = false }, reset_packpath = false, rtp = { reset = false } } })";
    }

    /** The windows the editor draws over the text, by window ID. */
    private static List<Integer> drawnFloats(NeovimSession s) {
        return s.floatingWindows().stream().map(f -> f.grid().window()).toList();
    }

    /** The window showing a buffer of {@code filetype}, or 0. */
    private static int windowOf(NeovimSession s, String filetype) throws Exception {
        return number(s, "for _, w in ipairs(vim.api.nvim_list_wins()) do"
                + " if vim.bo[vim.api.nvim_win_get_buf(w)].filetype == '" + filetype + "' then return w end end return 0");
    }

    /**
     * lazy.nvim's backdrop, the window it opens under {@code :Lazy}'s to dim the text (with
     * {@code 'termguicolors'} on and a {@code Normal} background, as LazyVim's colorschemes have):
     * every cell blank and blended, so the editor leaves it out (util/Grids' dimsOnly), and draws
     * {@code :Lazy}'s window over the text, not a block of the backdrop's color over all of it.
     */
    @Test
    void lazysBackdropIsLeftOutAndItsWindowDrawnOverTheText(@TempDir Path dir) throws Exception {
        TestNeovim.require();
        Optional<Path> lazy = PinnedPlugins.find("lazy");
        assumeTrue(lazy.isPresent(), "lazy is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        String tmp = dir.toAbsolutePath().toString();
        assumeTrue(!tmp.contains("]==]"), "a folder Lua cannot be given in a long string");
        try (NeovimSession s = start()) {
            s.showWindow(new DocumentBuffer(""), new EditorWindow(), "");
            s.setBufferLines(List.of("some text"));
            s.lua("vim.o.termguicolors = true vim.api.nvim_set_hl(0, 'Normal', { fg = '#c0c0c0', bg = '#202030' })");
            s.lua(lazySetup(lazy.get(), tmp, "{}", false));
            waitFor(() -> number(s, "return vim.fn.exists(':Lazy') == 2 and 1 or 0") == 1, ":Lazy defined");
            s.command("Lazy");
            int window = windowOf(s, "lazy");
            int backdrop = windowOf(s, "lazy_backdrop");
            assertNotEquals(0, backdrop, "lazy.nvim made its backdrop");
            waitFor(() -> drawnFloats(s).contains(window), ":Lazy's window drawn");
            assertFalse(drawnFloats(s).contains(backdrop), "the backdrop left out: " + drawnFloats(s));
        }
    }

    /** Runs git in {@code dir} with a placeholder identity, no signing; whether it worked. */
    private static boolean git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=test", "-c", "user.email=test@example.org",
                "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor() == 0;
        } catch (java.io.IOException noGit) {
            return false;
        }
    }

    /**
     * lazy.nvim installing a missing plug-in as Neovim starts, as on LazyVim's first start (here
     * from a git repository of the test's own, so nothing is downloaded): its window opens in the
     * home window's tab page before any editor is there, what Neovim draws of it before the
     * session listens is kept (core/EarlyIncoming), and the editor shown brings it along
     * (WINDOWS' bring_start_floats), so it is drawn over the editor while the configuration still
     * runs. The key typed meanwhile waits; when the start is over the window is closed, with the
     * message, and the key goes to the document. The configuration waits after the install until
     * the test says so, never on a clock. Skipped without lazy.nvim fetched or without git.
     */
    @Test
    void lazysInstallerIsDrawnOverTheEditorWhileNeovimStarts(@TempDir Path dir) throws Exception {
        TestNeovim.require();
        Optional<Path> lazy = PinnedPlugins.find("lazy");
        assumeTrue(lazy.isPresent(), "lazy is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        String tmp = dir.toAbsolutePath().toString();
        assumeTrue(!tmp.contains("]==]"), "a folder Lua cannot be given in a long string");
        Path repo = dir.resolve("demo");
        Files.createDirectories(repo.resolve("plugin"));
        Files.writeString(repo.resolve("plugin/demo.lua"), "vim.g.demo_loaded = 1\n");
        assumeTrue(git(repo, "init", "-q") && git(repo, "add", ".") && git(repo, "commit", "-q", "-m", "demo"),
                "git is not there to make the plug-in's repository");
        Path config = dir.resolve("install.lua");
        Files.writeString(config, lazySetup(lazy.get(), tmp, "{ { url = tmp .. '/demo', name = 'demo' } }", true)
                + "\nvim.wait(600000, function() return vim.g.installer_go == 1 end, 5)\n");
        try (NeovimSession s = NeovimSession.start(NVIM,
                List.of("--clean", "-c", "lua dofile([==[" + config + "]==])"))) {
            assertEquals(0, number(s, "return vim.v.vim_did_enter"), "the session is ready before Neovim's start is over");
            EditorWindow editor = new EditorWindow();
            s.showWindow(new DocumentBuffer(""), editor, "");
            s.setBufferLines(List.of("abc"));
            // The install may still be going on (the session is ready first), its window open by then.
            waitFor(() -> windowOf(s, "lazy") != 0, "lazy.nvim's window open");
            int installer = windowOf(s, "lazy");
            waitFor(() -> drawnFloats(s).contains(installer), "lazy.nvim's window drawn over the editor: " + drawnFloats(s));
            assertEquals(0, number(s, "return vim.v.vim_did_enter"), "while Neovim still starts");
            s.inputAsync("x");
            s.runAsync("the start's end", () -> { // on the input thread after the x
                try {
                    s.lua("vim.g.installer_go = 1");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            waitFor(() -> List.of("bc").equals(s.getBufferLines()), "the key went to the document");
            assertEquals(editor.winid(), currentWin(s), "Neovim in the editor's window");
            assertEquals(0, windowOf(s, "lazy"), "lazy.nvim's window closed");
            waitFor(() -> Ui2Text.shown(s).contains(startMessage(s)), "the message, shown by Neovim: " + Ui2Text.shown(s));
            assertEquals(1, number(s, "return vim.g.demo_loaded or 0"), "lazy.nvim installed the plug-in and loaded it");
        }
    }

    /**
     * lazy.nvim's {@code :Lazy}, a real plug-in's window that takes the keyboard in Normal mode, at
     * the commit pinned and vetted in {@code releng/test-plugins.properties}: it stays open with
     * Neovim in it, its own {@code q} closes it, and opened again, turning Neovim mode off closes it
     * ({@link NeovimSession#leaveFloatAsync}); the document keeps its text. lazy.nvim is set up
     * with no plug-ins ({@link #lazySetup}). Skipped when it is not fetched.
     */
    @Test
    void lazysWindowTakesTheKeysAndCloses(@TempDir Path dir) throws Exception {
        TestNeovim.require();
        Optional<Path> lazy = PinnedPlugins.find("lazy");
        assumeTrue(lazy.isPresent(), "lazy is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        String tmp = dir.toAbsolutePath().toString();
        assumeTrue(!tmp.contains("]==]"), "a folder Lua cannot be given in a long string");
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one"));
            s.lua(lazySetup(lazy.get(), tmp, "{}", false) + " vim.keymap.set('n', 'gl', '<Cmd>Lazy<CR>') return 0");
            // lazy.nvim makes :Lazy from a vim.schedule after its setup.
            waitFor(() -> number(s, "return vim.fn.exists(':Lazy') == 2 and 1 or 0") == 1, ":Lazy defined");
            KeyRouter router = new KeyRouter(s);
            String lazyWindow = "return vim.bo[vim.api.nvim_get_current_buf()].filetype == 'lazy' and 1 or 0";
            for (int round = 0; round < 2; round++) {
                assertTrue(router.route("g"));
                assertTrue(router.route("l"));
                waitFor(() -> number(s, lazyWindow) == 1 && s.inPluginFloat(), "Neovim in :Lazy's window, a plug-in's float");
                assertEquals("n", s.getMode(), "in Normal mode: the keys are Neovim's, as in any window");
                if (round == 0) {
                    assertTrue(router.route("q")); // lazy.nvim's own key
                } else {
                    s.leaveFloatAsync(); // as turning Neovim mode off
                }
                waitFor(() -> number(s, lazyWindow) == 0 && !s.inPluginFloat(), round == 0 ? "q closed it" : "Eclipse mode closed it");
                assertEquals(s.getAttachedWinid(), currentWin(s), "Neovim back in the editor's window");
                assertEquals(List.of("one"), s.getBufferLines(), "the document keeps its text");
            }
        }
    }

    /**
     * A config whose init goes on while Eclipse starts its session and attaches an editor, as
     * lazy.nvim's does while it installs missing plug-ins, and this config does as lazy.nvim
     * does: it opens its window with the focus, has VimEnter enter that window again (Neovim does
     * not keep a window entered before its start is over), and waits with vim.wait, in rounds,
     * each round showing the window again (made anew if it was closed). The session's start is
     * over before Neovim's, so the keys a user types meanwhile reach Neovim while that window has
     * the focus; they wait in Neovim's input, read only once the start is over. By then the window
     * is closed and Neovim is in the editor's window (WINDOWS' close_start_floats), whatever the
     * config's VimEnter did: the keys go to the document, and Neovim says why. The window stays
     * in the tab page it opened in, not the editor's, and its VimEnter takes Neovim there. With
     * lateVimEnter, round 3 also sets up a VimEnter of its own that opens another such window
     * with the focus, as a plug-in loaded meanwhile can: set up after the plug-in's check, it
     * runs after every VimEnter set up before. The rounds go on when the test says so, never on a
     * clock.
     */
    @ParameterizedTest(name = "a later VimEnter opening a window: {0}")
    @ValueSource(booleans = { false, true })
    void keysTypedWhileAConfigStillStartsGoToTheDocumentNotItsWindows(boolean lateVimEnter, @TempDir Path dir)
            throws Exception {
        TestNeovim.require();
        Path config = dir.resolve("rounds.lua");
        Files.writeString(config, "local LATE = " + lateVimEnter + "\n" + """
                local win
                local function show(round)
                  if win and vim.api.nvim_win_is_valid(win) then
                    vim.api.nvim_buf_set_lines(vim.api.nvim_win_get_buf(win), 0, -1, false, { 'round ' .. round })
                    return
                  end
                  local b = vim.api.nvim_create_buf(false, true)
                  vim.bo[b].filetype = 'lazy'
                  win = vim.api.nvim_open_win(b, true, { relative = 'editor', row = 1, col = 1, width = 20, height = 3 })
                  local w = win
                  vim.api.nvim_create_autocmd('VimEnter', { once = true, callback = function()
                    if vim.api.nvim_win_is_valid(w) then pcall(vim.api.nvim_set_current_win, w) end
                  end })
                end
                show(1)
                for round = 2, 4 do
                  vim.wait(600000, function() return (vim.g.rounds_go or 0) >= round end, 5)
                  if round == 3 and LATE then
                    vim.api.nvim_create_autocmd('VimEnter', { once = true, callback = function()
                      local b = vim.api.nvim_create_buf(false, true)
                      vim.bo[b].filetype = 'lazy'
                      vim.api.nvim_open_win(b, true, { relative = 'editor', row = 5, col = 1, width = 20, height = 3 })
                    end })
                  end
                  if round < 4 then show(round) end
                end
                """);
        try (NeovimSession s = NeovimSession.start(NVIM,
                List.of("--clean", "-c", "lua dofile([==[" + config + "]==])"))) {
            assertEquals(0, number(s, "return vim.v.vim_did_enter"), "the session is ready before Neovim's start is over");
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow editor = new EditorWindow();
            s.showWindow(doc, editor, "");
            s.setBufferLines(List.of("abcdef"));
            for (int round = 2; round <= 4; round++) {
                s.inputAsync("x"); // typed while the window is shown, round after round
                // On the input thread after the key, so Neovim has the key in its input by then.
                String go = "vim.g.rounds_go = " + round;
                s.runAsync("the next round", () -> {
                    try {
                        s.lua(go);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            waitFor(() -> number(s, "return vim.v.vim_did_enter") == 1, "Neovim's start over");
            waitFor(() -> List.of("def").equals(s.getBufferLines()), "the three keys deleted three letters of the document");
            assertEquals(editor.winid(), currentWin(s), "Neovim in the editor's window");
            assertEquals(0, number(s, "local n = 0 for _, w in ipairs(vim.api.nvim_list_wins()) do"
                    + " if vim.bo[vim.api.nvim_win_get_buf(w)].filetype == 'lazy' then n = n + 1 end end return n"),
                    "no window of the rounds is left with the keyboard");
            waitFor(() -> Ui2Text.shown(s).contains(startMessage(s)),
                    "the message, shown by Neovim: " + Ui2Text.shown(s));
            // Said once UIEnter is over (still as an error), so that Neovim does not head it with
            // "Error in UIEnter Autocommands for "*":", as if the check had failed.
            assertFalse(Ui2Text.shown(s).contains("Autocommands"), Ui2Text.shown(s));
        }
    }

    /**
     * An operator typed while Neovim starts and its motion typed after: {@code d} waits in Neovim's
     * input until the start is over, then is read, pending; the message about the float closed
     * at the start's end comes meanwhile (said once UIEnter is over, as any event between two
     * keys), and the {@code w} typed then still ends the {@code d}: one word deleted. The config
     * waits until the test says so, never on a clock.
     */
    @Test
    void anOperatorTypedWhileNeovimStartsTakesTheMotionTypedAfter(@TempDir Path dir) throws Exception {
        TestNeovim.require();
        Path config = dir.resolve("waits.lua");
        Files.writeString(config, """
                local b = vim.api.nvim_create_buf(false, true)
                vim.bo[b].filetype = 'lazy'
                vim.api.nvim_open_win(b, true, { relative = 'editor', row = 1, col = 1, width = 20, height = 3 })
                vim.wait(600000, function() return vim.g.waits_go == 1 end, 5)
                """);
        try (NeovimSession s = NeovimSession.start(NVIM,
                List.of("--clean", "-c", "lua dofile([==[" + config + "]==])"))) {
            assertEquals(0, number(s, "return vim.v.vim_did_enter"), "the session is ready before Neovim's start is over");
            DocumentBuffer doc = new DocumentBuffer("");
            s.showWindow(doc, new EditorWindow(), "");
            s.setBufferLines(List.of("one two"));
            s.inputAsync("d");
            s.runAsync("the start's end", () -> { // on the input thread after the d
                try {
                    s.lua("vim.g.waits_go = 1");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            waitFor(() -> Ui2Text.shown(s).contains(startMessage(s)), "the message, shown by Neovim: " + Ui2Text.shown(s));
            assertEquals("no", s.lua("return vim.api.nvim_get_mode().mode"), "the d pending, in operator-pending mode");
            s.inputAsync("w");
            waitFor(() -> List.of("two").equals(s.getBufferLines()), "dw deleted the first word");
            assertEquals(List.of("two"), s.getBufferLines());
        }
    }

    /**
     * An editor opened from another has that editor's file as its alternate file, as
     * {@code :tabedit b.txt} from a.txt gives in Vim: {@code :e #} and {@code Ctrl-^} go back.
     */
    @Test
    void aNewEditorsAlternateFileIsTheFileOfTheEditorBefore(@TempDir Path dir) throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer(dir.resolve("a.txt").toString());
            DocumentBuffer b = new DocumentBuffer(dir.resolve("b.txt").toString());
            s.showWindow(a, new EditorWindow(), dir.toString());
            s.showWindow(b, new EditorWindow(), dir.toString());
            assertEquals(a.bufnr, number(s, "return vim.fn.bufnr('#')"), "the alternate in b.txt's window");
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
     * The travel moves the attached buffer along with its window: should the attach of the editor
     * jumped into fail, going back to the attached buffer puts it in A's new window, not in the one
     * B now holds.
     */
    @Test
    void afterATravelTheAttachedBufferGoesBackToItsEditorsNewWindow() throws Exception {
        try (NeovimSession s = start()) {
            List<Integer> entered = new CopyOnWriteArrayList<>();
            s.setBufferEnterHandler(entered::add);
            DocumentBuffer docA = new DocumentBuffer("");
            DocumentBuffer docB = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(docB, b, "");
            s.showWindow(docA, a, "");
            int aWin = a.winid();
            int bWin = b.winid();
            s.command("buffer " + docB.bufnr());
            waitFor(() -> entered.contains(docB.bufnr()), "the jump to be reported");
            s.travelWindow(a, b, docA);
            assertEquals(bWin, s.getAttachedWinid(), "A's buffer is in A's new window");

            s.returnToAttachedBufferAsync(); // what follows a failed attach of B's editor
            waitFor(() -> currentWin(s) == bWin, "A's new window to be entered");
            assertEquals(docA.bufnr(), number(s, "return vim.api.nvim_get_current_buf()"));
            assertEquals(docB.bufnr(), number(s, "return vim.api.nvim_win_get_buf(" + aWin + ")"),
                    "B's window keeps B's buffer");
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

    /**
     * An untitled editor's window closed in Neovim without the plug-in hearing of it yet (no
     * {@code WinClosed}): its next show finds it gone and opens a new one, which still inherits the
     * folder of the editor attached before, as the first one did.
     */
    @Test
    void anUntitledWindowOpenedAgainStillTakesTheFolderOfTheEditorBefore(@TempDir Path dir) throws Exception {
        try (NeovimSession s = start()) {
            EditorWindow project = new EditorWindow();
            DocumentBuffer projectDoc = new DocumentBuffer("");
            s.showWindow(projectDoc, project, dir.toString());
            EditorWindow untitled = new EditorWindow();
            DocumentBuffer untitledDoc = new DocumentBuffer("");
            s.showWindow(untitledDoc, untitled, null);
            s.showWindow(projectDoc, project, dir.toString());
            int old = untitled.winid();
            s.lua("vim.o.eventignore = 'all'; vim.api.nvim_win_close(" + old + ", true); vim.o.eventignore = ''");

            s.showWindow(untitledDoc, untitled, null);
            assertNotEquals(old, untitled.winid(), "a new window");
            assertEquals(dir.toRealPath(), cwd(s), "the project editor's folder, not the home folder");
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
            waitFor(() -> !doc.isOpenIn(s), "the buffer to be closed");
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

    /**
     * A page's window ({@code NeovimSession.openPageWindow}) on a new scratch buffer holding
     * {@code lines} (a Lua list), as a page editor would open one.
     */
    private static int openPageWindow(NeovimSession s, String lines) throws Exception {
        int buf = number(s, "local b = vim.api.nvim_create_buf(false, true)"
                + " vim.api.nvim_buf_set_lines(b, 0, -1, false, " + lines + ") return b");
        return s.openPageWindow(buf);
    }

    /**
     * A page's window takes the focus while the text editor stays attached ("focused
     * window beside the attached document"): Neovim enters the page's window and is not sent back,
     * the page's cursor is no caret of the document's, the caret's calls still move the attached
     * window's cursor, the focus events reach the target that has the focus, and the focus comes
     * back to the attached window.
     */
    @Test
    void aPageWindowTakesTheFocusAndTheAttachedDocumentStaysAttached() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            s.showWindow(doc, a, "");
            s.lua("vim.api.nvim_buf_set_lines(0, 0, -1, false, {'one', 'two', 'three'})");
            int page = openPageWindow(s, "{'p1', 'p2', 'p3'}");
            List<CursorPos> cursors = new CopyOnWriteArrayList<>();
            s.addCursorListener(cursors::add);
            List<String> modes = new CopyOnWriteArrayList<>();
            s.focus(new FocusTarget() {
                @Override
                public void onMode(String mode) {
                    modes.add(mode);
                }
            });

            s.focusPageAsync(page);
            NeovimBarrier.pass(s);
            assertEquals(page, currentWin(s), "Neovim is in the page's window, and was not sent back");
            assertEquals(page, s.focusedPage());
            assertEquals(a.winid(), s.getAttachedWinid(), "the document is still attached");

            cursors.clear();
            s.inputAsync("jj");
            NeovimBarrier.pass(s);
            assertEquals(3, cursorLine(s), "the page's cursor moved");
            assertTrue(cursors.stream().noneMatch(c -> c.line1Based() == 3), "but it is no caret of the document's: " + cursors);

            s.setCursor(2, 1);
            assertEquals(2, number(s, "return vim.api.nvim_win_get_cursor(" + a.winid() + ")[1]"),
                    "the caret's call moves the attached window's cursor");
            assertEquals(3, cursorLine(s), "not the page's");
            assertEquals(new CursorPos(2, 1), s.getCursor(), "and reads it back from there");

            s.inputAsync("v");
            waitFor(() -> modes.contains("v"), "visual mode reaching the target with the focus");
            s.inputAsync("<Esc>");

            s.focusAttachedAsync();
            NeovimBarrier.pass(s);
            assertEquals(a.winid(), currentWin(s), "back in the attached window");
            assertEquals(EditorWindow.UNKNOWN, s.focusedPage());
        }
    }

    /** A page window closed in Neovim gives up the focus; a later BufEnter elsewhere is judged as before. */
    @Test
    void aClosedPageWindowGivesUpTheFocus() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            s.showWindow(doc, a, "");
            int page = openPageWindow(s, "{'p1'}");
            s.focusPageAsync(page);
            NeovimBarrier.pass(s);
            assertEquals(page, s.focusedPage());
            s.lua("vim.api.nvim_win_close(" + page + ", true)");
            waitFor(() -> s.focusedPage() == EditorWindow.UNKNOWN, "the page's focus to end");
            waitFor(() -> currentWin(s) == a.winid(), "Neovim back in the attached window");
        }
    }

    /**
     * In a page's window, Neovim entering an open document's buffer ({@code :b}, {@code gf}) gives
     * the page its buffer back, and the document's editor is followed from the page (no travel).
     */
    @Test
    void aDocumentEnteredInAPageWindowGoesToItsEditorAndThePageKeepsItsText() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            DocumentBuffer other = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(other, b, "");
            s.showWindow(doc, a, "");
            int page = openPageWindow(s, "{'p1'}");
            int pageBuf = number(s, "return vim.api.nvim_win_get_buf(" + page + ")");
            List<Integer> followed = new CopyOnWriteArrayList<>();
            List<Boolean> fromPage = new CopyOnWriteArrayList<>();
            s.setBufferEnterHandler(bufnr -> {
                followed.add(bufnr);
                fromPage.add(s.wasEnteredFromPage());
            });
            s.focusPageAsync(page);
            NeovimBarrier.pass(s);
            s.inputAsync(":b " + other.bufnr() + "<CR>");
            waitFor(() -> !followed.isEmpty(), "the document followed");
            NeovimBarrier.pass(s);
            assertEquals(List.of(other.bufnr()), followed);
            assertEquals(List.of(true), fromPage, "from a page: the editor takes its own window");
            assertEquals(pageBuf, number(s, "return vim.api.nvim_win_get_buf(" + page + ")"), "the page keeps its text");
        }
    }

    /**
     * A drawn page's grid reaches the page grid listeners once the page has the focus, with the
     * page's text, and a click on that grid moves the page's cursor.
     */
    @Test
    void aFocusedPagesGridIsDrawnAndTakesTheMouse() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            s.showWindow(doc, a, "");
            int page = openPageWindow(s, "{'first page line', 'second page line', 'third'}");
            List<GridSnapshot> drawn = new CopyOnWriteArrayList<>();
            s.addPageGridListener(drawn::add);
            s.drawPageWindow(page);
            s.focusPageAsync(page);
            waitFor(() -> drawn.stream().anyMatch(g -> g.window() == page && g.row(0).startsWith("first page line")),
                    "the page's grid");
            s.pageMouseAsync(page, "left", "press", "", 1, 3);
            s.pageMouseAsync(page, "left", "release", "", 1, 3);
            waitFor(() -> cursorLine(s) == 2, "the click on the page's second row");
            assertEquals(page, currentWin(s));
        }
    }

    /** A window opened as no page's cannot take a page's focus. */
    @Test
    void onlyAPagesWindowTakesAPagesFocus() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            EditorWindow b = new EditorWindow();
            s.showWindow(doc, b, "");
            s.showWindow(doc, a, "");
            s.focusPageAsync(b.winid());
            NeovimBarrier.pass(s);
            assertEquals(EditorWindow.UNKNOWN, s.focusedPage());
            assertEquals(a.winid(), currentWin(s));
        }
    }

    /** A window that no longer exists is not focused, and the focus stays where it was. */
    @Test
    void aGoneWindowIsNotFocused() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            EditorWindow a = new EditorWindow();
            s.showWindow(doc, a, "");
            s.focusPageAsync(99999);
            NeovimBarrier.pass(s);
            assertEquals(EditorWindow.UNKNOWN, s.focusedPage());
            assertEquals(a.winid(), currentWin(s));
        }
    }
}

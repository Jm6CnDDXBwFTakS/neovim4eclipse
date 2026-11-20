// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real {@code nvim}. Skipped automatically when none is on the PATH
 * (override with {@code -Dnvim.path=/path/to/nvim}). Replaces the old {@code docs/SmokeTest.java}.
 *
 * <p>These tests were written without a Neovim available to the author; if one fails, check the
 * assertion against real Neovim behavior before assuming a product bug.
 */
@Tag("integration")
class NeovimSessionIntegrationTest {

    private static final String NVIM = TestNeovim.PATH;

    /**
     * How long a test waits for Neovim to get somewhere. Generous, because a loaded machine (a busy
     * CI runner) can take seconds; a passing test never waits that long. 15 s unless
     * {@code -Dtest.wait.seconds} says otherwise (see {@link TestWait}).
     */
    private static final long WAIT_MS = TestWait.millis();

    private static NeovimSession start() throws Exception {
        return start(List.of("--clean"));
    }

    private static NeovimSession start(List<String> args) throws Exception {
        TestNeovim.require();
        return NeovimSession.start(NVIM, args);
    }

    // ---- errors in the user's configuration ----

    /**
     * A typo in init.lua once made Neovim wait at a "Press ENTER" prompt Eclipse does not show, and
     * the start timed out. With ext_messages there is no prompt: Neovim starts (without that
     * init.lua: a syntax error stops all of it), and the error is reported.
     */
    @Test
    void aSyntaxErrorInTheConfigurationIsReportedAndNeovimStartsAnyway(@TempDir Path dir) throws Exception {
        Path init = dir.resolve("init.lua");
        Files.writeString(init, "vim.g.before = 1\nnnoremap ; :\n");
        try (ExpectedLog log = ExpectedLog.of(NeovimConnection.class);
                NeovimSession s = start(List.of("-u", init.toString()))) {
            assertTrue(s.startupErrors().contains("E5112"), s.startupErrors());
            log.assertLogged(Level.WARNING, "Neovim's configuration reported errors while starting");
            s.setBufferLines(List.of("one", "two"));
            keys(s, "d", "d");
            assertEquals(List.of("two"), s.getBufferLines(), "Neovim works after the error");
            assertEquals(0L, ((Number) s.lua("return vim.g.before or 0")).longValue(), "a syntax error runs nothing");
        }
    }

    /** A runtime error keeps what ran before it, as in a terminal. */
    @Test
    void aRuntimeErrorKeepsWhatRanBeforeIt(@TempDir Path dir) throws Exception {
        Path init = dir.resolve("init.lua");
        Files.writeString(init, "vim.g.before = 1\nerror('boom from init (expected by the test)')\nvim.g.after = 1\n");
        try (ExpectedLog log = ExpectedLog.of(NeovimConnection.class);
                NeovimSession s = start(List.of("-u", init.toString()))) {
            assertTrue(s.startupErrors().contains("boom from init"), s.startupErrors());
            log.assertLogged(Level.WARNING, "Neovim's configuration reported errors while starting");
            assertEquals(1L, ((Number) s.lua("return vim.g.before or 0")).longValue());
            assertEquals(0L, ((Number) s.lua("return vim.g.after or 0")).longValue());
        }
    }

    @Test
    void aCleanStartReportsNoErrors() throws Exception {
        try (NeovimSession s = start()) {
            assertEquals("", s.startupErrors());
        }
    }

    /**
     * Keys reach Neovim one by one through the session, special keys included, and what they do
     * comes back: the cursor and the text. Every key outside insert mode goes to Neovim as it is (no
     * key grammar in Java any more), so this is the one check of that path; what each key does is
     * Neovim's own behavior, not tested here (it used to be, a test per motion, paste and undo).
     */
    @Test
    void keysReachNeovimAndTheCursorAndTextComeBack() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b", "c"));
            keys(s, "g", "g", "j");
            assertEquals(2, s.getCursor().line1Based(), "gg, j");
            keys(s, "y", "y", "2", "p");
            assertEquals(List.of("a", "b", "b", "b", "c"), s.getBufferLines(), "a counted linewise paste");
            keys(s, "d", "d", ".");
            assertEquals(List.of("a", "b", "c"), s.getBufferLines(), "dd and its dot repeat");
            keys(s, "u");
            assertEquals(List.of("a", "b", "b", "c"), s.getBufferLines(), "undo");
            keys(s, "G", "<C-o>"); // a special key: back through the jump list
            assertEquals(3, s.getCursor().line1Based());
        }
    }

    /**
     * Regression: after f/F/t/r or the first g of gg, Neovim waits for another character and
     * defers most API calls. Polling state then used to cost the full 5 s RPC timeout per command.
     *
     * <p>The seven keys are compared with seven keys that leave nothing pending, timed just before,
     * rather than with a fixed limit: on a loaded machine seven plain keys alone can take seconds.
     * One stall would add 5 s; the 4 s margin allows for the load changing between the two.
     *
     * <p>Tagged {@code timeoutReliant}: it measures time, so a busy machine can fail it with nothing
     * wrong, and only {@code -PtimeoutReliant} runs it (CONTRIBUTING.md, "Tests on a loaded machine").
     * The decision it guards is checked without a clock in {@code KeyInputTest}
     * ({@code whileNeovimWaitsForTheRestOfACommandNothingIsAsked}), and that Neovim reports the wait
     * in {@code NeovimApiIntegrationTest} and {@link #waitingForACharacterShowsTheKeyInTheCommandArea}.
     */
    @Test
    @Tag("timeoutReliant")
    void pendingCommandsDoNotStallTheInputThread() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("hello world", "second line", "third line"));
            long plainMs = millisToType(s, "l", "l", "G", "k", "k", "h", "0");
            long pendingMs = millisToType(s, "f", "o", "G", "g", "g", "F", "o"); // fo, G, gg, Fo
            assertTrue(pendingMs < plainMs + 4000,
                    "pending commands took " + pendingMs + " ms, plain keys " + plainMs + " ms");
            assertEquals(1, s.getCursor().line1Based());
        }
    }

    private static long millisToType(NeovimSession s, String... keys) throws Exception {
        long start = System.nanoTime();
        keys(s, keys);
        return (System.nanoTime() - start) / 1_000_000;
    }

    // ---- status bar parts ----

    @Test
    void statusInfoReportsTheStatusline() throws Exception {
        try (NeovimSession s = start()) {
            s.setStatusItems(java.util.EnumSet.allOf(io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem.class));
            s.setStatuslineColumns(60); // as the status bar does once its statusline label is laid out
            s.setBufferLines(List.of("one", "two"));
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);
            keys(s, "j");
            waitFor(() -> seen.stream().anyMatch(i -> !i.statusline().isBlank()), "statusline: " + seen);
        }
    }

    /** The statusline is only evaluated when the status bar shows it. */
    @Test
    void nothingOptionalIsEvaluatedWhenOnlyTheModeIsShown() throws Exception {
        try (NeovimSession s = start()) {
            s.setStatusItems(java.util.EnumSet.of(io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem.MODE));
            s.setBufferLines(List.of("one"));
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);
            keys(s, "2", "d", "d", "/", "n", "o", "<CR>");
            NeovimBarrier.pass(s);
            assertTrue(seen.stream().allMatch(i -> i.statusline().isEmpty()), "unrequested parts must stay empty: " + seen);
        }
    }

    // ---- keys handed to the IDE, command output ----

    /**
     * The safety net of {@link NeovimSession#setCursor}: a line past Neovim's last one puts the
     * cursor at the end of the last line (normal mode: on its last character), and the session
     * remembers that place; any other refusal is still an error.
     */
    @Test
    void aCursorPastTheLastLineGoesToTheEndOfIt() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("alpha", "beté"));
            s.setCursor(5, 0);
            assertEquals(new CursorPos(2, 3), s.getCursor(), "on the é, its last character (bytes 3 and 4)");
            assertThrows(Exception.class, () -> s.setCursor(0, 0), "line 0 is no line past the end");
        }
    }

    /** Neovim's own gd, for files without Open Declaration: the mapping is bypassed. */
    @Test
    void neovimsOwnGdJumpsToTheFirstUseOfTheWord() throws Exception {
        try (NeovimSession s = start()) {
            List<IdeAction> actions = new CopyOnWriteArrayList<>();
            s.addIdeActionListener(actions::add);
            s.setBufferLines(List.of("int x = 1;", "y = x;"));
            s.setCursor(2, 4); // on the x of "y = x;"
            s.normalAsync("gd");
            waitFor(() -> {
                try {
                    return s.getCursor().equals(new CursorPos(1, 4));
                } catch (Exception e) {
                    return false;
                }
            }, "the cursor on the first x");
            assertTrue(actions.isEmpty(), "the plug-in's gd mapping must not run: " + actions);
        }
    }

    /**
     * Neovim's own {@code gd} with nothing under the cursor fails as it does when typed: the error
     * ({@code E349: No identifier under cursor}) is shown by Neovim as an error, and Neovim
     * keeps it in {@code :messages}, because the script echoes it with {@code err = true}.
     */
    @Test
    void anErrorOfNeovimsOwnKeyIsShownAsAnError() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of(""));
            s.normalAsync("gd");
            waitFor(() -> Ui2Text.shown(s).contains("E349: No identifier under cursor"),
                    "E349 shown by Neovim, without the \"Vim:\" prefix pcall adds");
            assertFalse(Ui2Text.shown(s).contains("Vim:E349"));
            String history = (String) s.lua("return vim.api.nvim_exec2('messages', { output = true }).output");
            assertTrue(history.contains("E349: No identifier under cursor"), "kept in :messages: " + history);
        }
    }

    // Commands Eclipse carries out (LuaScripts.HOST_COMMANDS) are tested in Lua, without the session:
    // src/test/lua/host_commands_spec.lua (run by LuaScriptsInNeovimTest).

    // ---- messages: Neovim shows them itself, in ui2's windows (LuaScripts.UI2) ----

    /**
     * A typed command's output and its errors are shown by Neovim; the command is typed as written,
     * so the history has it without {@code :silent}.
     */
    @Test
    void typedCommandsShowWhatTheyPrint() throws Exception {
        try (NeovimSession s = start()) {
            s.commandAsync("echo 'hello from' 'neovim'");
            waitFor(() -> Ui2Text.shown(s).contains("hello from neovim"), "the echo");
            s.commandAsync("nosuchcommand");
            waitFor(() -> Ui2Text.shown(s).contains("E492: Not an editor command: nosuchcommand"), "the error");
            s.commandAsync("let g:x = 1");
            s.commandAsync("echo 'done'");
            waitFor(() -> Ui2Text.shown(s).contains("done"), "the last command's message");
            assertEquals("1", String.valueOf(s.lua("return vim.g.x")));
            assertEquals("echo 'done'", String.valueOf(s.lua("return vim.fn.histget(':', -1)")),
                    "the command is typed, so it is in the history as written");
        }
    }

    /** A listing is typed like every other command, so {@code @:} repeats it; Neovim shows it. */
    @Test
    void aListingIsShownAndIsInTheHistory() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.api.nvim_create_buf(true, true); vim.api.nvim_create_buf(true, true)");
            s.commandAsync("ls!");
            waitFor(() -> Ui2Text.shown(s).contains("line 1"), "the buffer list");
            assertEquals("ls!", String.valueOf(s.lua("return vim.fn.histget(':', -1)")));
        }
    }

    /**
     * A shell command's output is shown by Neovim, one line per line: no {@code \r} left from
     * Windows' {@code \r\n}.
     */
    @Test
    void shellCommandOutputIsShownWithoutCarriageReturns() throws Exception {
        try (NeovimSession s = start()) {
            s.commandAsync(PlatformUtil.isWindows() ? "!echo shellout& echo second" : "!echo shellout; echo second");
            waitFor(() -> Ui2Text.shown(s).contains("second"), "the shell command's output");
            assertFalse(Ui2Text.shown(s).contains("\r"), "no carriage return");
        }
    }

    /**
     * Long output leaves Neovim ready for the next key: ui2 has no "Press ENTER" or "-- More --"
     * prompt to wait at; what does not fit is collapsed, and {@code g<} shows it whole.
     */
    @Test
    void longOutputLeavesNoPrompt() throws Exception {
        try (NeovimSession s = start()) {
            s.commandAsync("echo join(range(300), \"\\n\")");
            waitFor(() -> Ui2Text.shown(s).contains("299"), "the output");
            String mode = String.valueOf(s.lua("return vim.api.nvim_get_mode().mode"));
            assertEquals("n", mode, "no hit-enter (r) or more (rm) prompt");
        }
    }

    /**
     * The plug-in redirects nothing itself, so the user's {@code :redir} simply works.
     * {@code g:mine} is set only by {@code :redir END}, the last queued command, so the test waits
     * for the variable.
     */
    @Test
    void aTypedRedirCollectsWhatIsPrinted() throws Exception {
        try (NeovimSession s = start()) {
            s.commandAsync("redir => g:mine");
            s.commandAsync("echo 'kept'");
            s.commandAsync("redir END");
            waitFor(() -> String.valueOf(uncheckedLua(s, "return vim.g.mine")).contains("kept"),
                    "the user's :redir to collect the echo");
        }
    }

    private static Object uncheckedLua(NeovimSession s, String code) {
        try {
            return s.lua(code);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code :messages} goes to a page editor, its errors marked ({@code LuaScripts.UI2} takes it
     * from ui2), and ui2's pager is not entered.
     */
    @Test
    void theMessageHistoryGoesToTheViewWithItsErrorsMarked() throws Exception {
        try (NeovimSession s = start()) {
            List<ViewText> texts = new CopyOnWriteArrayList<>();
            s.addViewContentListener(c -> {
                if (c instanceof ViewText t) {
                    texts.add(t);
                }
            });
            s.commandAsync("echomsg 'remember me'");
            s.commandAsync("lua vim.api.nvim_echo({ { 'E999: made up' } }, true, { err = true })");
            s.commandAsync("messages");
            waitFor(() -> !texts.isEmpty(), "the history");
            ViewText text = texts.get(0);
            assertEquals(ViewText.MESSAGES, text.title());
            int remembered = text.lines().indexOf("remember me");
            int error = text.lines().indexOf("E999: made up");
            assertTrue(remembered >= 0 && error > remembered, "the history: " + text.lines());
            assertTrue(text.highlights().contains(new ViewText.LineHighlight(error, "ErrorMsg")), text.highlights().toString());
            assertTrue(text.highlights().stream().noneMatch(h -> h.line() == remembered), "a plain message stays plain");
            NeovimBarrier.pass(s);
            assertTrue(Boolean.TRUE.equals(s.lua("return vim.api.nvim_get_current_win() ~= require('vim._core.ui2').wins.pager")),
                    "ui2's pager not entered");
        }
    }

    /** An empty history shows nothing, as in Vim: no empty view that takes the keyboard. */
    @Test
    void anEmptyHistoryOpensNothing() throws Exception {
        try (NeovimSession s = start()) {
            List<ViewText> texts = new CopyOnWriteArrayList<>();
            s.addViewContentListener(c -> {
                if (c instanceof ViewText t) {
                    texts.add(t);
                }
            });
            s.commandAsync("messages clear");
            s.commandAsync("messages");
            NeovimBarrier.pass(s);
            assertEquals(List.of(), texts);
        }
    }

    /**
     * {@code :checkhealth}'s report goes to a page editor too: its buffer, taken from its window by
     * the {@code WinNew} net and kept (unlisted, so {@code :ls} never lists {@code health://}).
     */
    @Test
    void aCheckhealthReportGoesToTheView() throws Exception {
        try (NeovimSession s = start()) {
            List<PageBuffer> pages = pagesOf(s);
            s.commandAsync("checkhealth vim.health");
            waitFor(() -> !pages.isEmpty(), "the report");
            PageBuffer page = pages.get(0);
            assertEquals(PageBuffer.CHECKHEALTH, page.title());
            assertEquals(1, page.line());
            assertTrue(linesOf(s, page).contains("vim.health"), linesOf(s, page));
            assertEquals(false, s.lua("return vim.bo[" + page.buffer() + "].buflisted"), "unlisted");
        }
    }

    /**
     * {@code :help} goes to a page editor: the help buffer, taken from its window by the
     * {@code WinNew} net, and the topic's line.
     */
    @Test
    void aHelpPageGoesToTheView() throws Exception {
        try (NeovimSession s = start()) {
            List<PageBuffer> pages = pagesOf(s);
            s.commandAsync("help help-context");
            waitFor(() -> !pages.isEmpty(), "the page");
            PageBuffer page = pages.get(0);
            assertEquals(PageBuffer.HELP, page.title());
            assertEquals("helphelp.txt", s.lua("return vim.fn.fnamemodify(vim.api.nvim_buf_get_name(" + page.buffer() + "), ':t')"));
            assertTrue(page.line() > 1, "the topic's line: " + page.line());
        }
    }

    /** {@code :checkhealth neovim4eclipse}: the plug-in's own check, registered after the start. */
    @Test
    void thePluginsOwnHealthCheckReports(@TempDir Path runtime) throws Exception {
        try (NeovimSession s = start()) {
            s.installHealthCheckAsync(runtime, new HealthCheck.Info(false, "0.2.27", "4.38.0"));
            List<PageBuffer> pages = pagesOf(s);
            s.commandAsync("checkhealth neovim4eclipse");
            waitFor(() -> !pages.isEmpty(), "the report");
            String report = linesOf(s, pages.get(0));
            assertTrue(report.contains("OK Neovim " + s.neovimVersion()), report);
            assertTrue(report.contains("WARNING A Neovim program of your own"), report);
            assertTrue(report.contains("Neovim for Eclipse 0.2.27"), report);
            assertTrue(report.contains("Eclipse 4.38.0"), report);
            assertTrue(report.contains("OK Modelines are off"), report);
            assertTrue(report.contains("OK The term:// reader is off"), report);
        }
    }

    /** {@code :help neovim4eclipse}: the plug-in's own page, found through the tags written beside it. */
    @Test
    void thePluginsOwnHelpPageIsFound(@TempDir Path runtime) throws Exception {
        try (NeovimSession s = start()) {
            s.installHealthCheckAsync(runtime, new HealthCheck.Info(false, "0.2.28", ""));
            List<PageBuffer> pages = pagesOf(s);
            s.commandAsync("help neovim4eclipse");
            waitFor(() -> !pages.isEmpty(), "the page");
            assertEquals("neovim4eclipse.txt",
                    s.lua("return vim.fn.fnamemodify(vim.api.nvim_buf_get_name(" + pages.get(0).buffer() + "), ':t')"));
        }
    }

    /** The page buffers {@code s} hands to page editors, as they come. */
    private static List<PageBuffer> pagesOf(NeovimSession s) {
        List<PageBuffer> pages = new CopyOnWriteArrayList<>();
        s.addViewContentListener(c -> {
            if (c instanceof PageBuffer page) {
                pages.add(page);
            }
        });
        return pages;
    }

    /** The text of {@code page}'s buffer, its lines joined. */
    private static String linesOf(NeovimSession s, PageBuffer page) throws Exception {
        return String.valueOf(s.lua("return table.concat(vim.api.nvim_buf_get_lines(" + page.buffer() + ", 0, -1, false), '\\n')"));
    }

    /**
     * A message no command waits for, from a timer, is still shown: the case of the diagnostics'
     * jump message, which Neovim echoes later, with vim.schedule.
     */
    @Test
    void aLaterMessageFromATimerIsShown() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.defer_fn(function() vim.api.nvim_echo({ { 'from a timer' } }, false, {}) end, 50)");
            waitFor(() -> Ui2Text.shown(s).contains("from a timer"), "the timer's message");
        }
    }

    // ---- indentation ----

    @Test
    void oAndOIndentInsideBracesWithTabs() throws Exception {
        try (NeovimSession s = start()) {
            s.setIndentation(4, new Indentation(true, 4, true));
            s.setBufferLines(List.of("class A {", "\tvoid f() {", "\t}", "}"));
            s.setCursor(2, 0);
            keys(s, "o", "x", "(", ")", ";", "<Esc>");
            assertEquals(List.of("class A {", "\tvoid f() {", "\t\tx();", "\t}", "}"), s.getBufferLines());
            s.setCursor(4, 0); // the method's closing brace
            keys(s, "O", "y", ";", "<Esc>");
            assertEquals("\t\ty;", s.getBufferLines().get(3));
        }
    }

    @Test
    void oIndentsWithTheDetectedSpaceWidth() throws Exception {
        try (NeovimSession s = start()) {
            s.setIndentation(4, new Indentation(false, 2, true));
            s.setBufferLines(List.of("function f() {", "  if (a) {", "  }", "}"));
            s.setCursor(2, 0);
            keys(s, "o", "g", "(", ")", ";", "<Esc>");
            assertEquals("    g();", s.getBufferLines().get(2));
            keys(s, ">", ">");
            assertEquals("      g();", s.getBufferLines().get(2), ">> shifts by one detected level");
        }
    }

    // ---- the mouse (Neovim's own, see LuaScripts.MOUSE) ----

    /** Presses and releases the left button {@code times} times on line {@code line1}, byte {@code col0}. */
    private static void click(NeovimSession s, int line1, int col0, int times, String modifiers) throws Exception {
        for (int i = 0; i < times; i++) {
            s.mouse(new MouseInput(MouseInput.Action.PRESS, modifiers, line1, col0));
            s.mouse(new MouseInput(MouseInput.Action.RELEASE, modifiers, line1, col0));
        }
    }

    private static VisualSelection lastSelection(List<VisualSelection> seen) {
        return seen.isEmpty() ? VisualSelection.none() : seen.get(seen.size() - 1);
    }

    @Test
    void aClickMovesTheCursorEvenToALineOffNeovimsScreen() throws Exception {
        try (NeovimSession s = start()) {
            List<String> lines = new java.util.ArrayList<>();
            for (int i = 1; i <= 300; i++) {
                lines.add("line " + i);
            }
            s.setBufferLines(lines);
            click(s, 3, 2, 1, "");
            assertEquals(new CursorPos(3, 2), s.getLastCursor());
            click(s, 250, 1, 1, ""); // Neovim's window shows 40 lines: the script scrolls it
            assertEquals(new CursorPos(250, 1), s.getLastCursor());
            assertEquals("n", s.getMode());
        }
    }

    /**
     * Neovim counts a press as a double or triple click when it comes within {@code 'mousetime'}
     * of the one before, at the same place. The default, 500 ms, is too short on a loaded machine,
     * where one press can take a second to arrive, so the test allows 10 s. The triple click is on
     * another line, so it counts from one again without waiting out {@code 'mousetime'}.
     */
    @Test
    void aDoubleClickSelectsAWordAndATripleClickTheLine() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.o.mousetime = 10000");
            s.setBufferLines(List.of("alpha beta gamma", "second"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            click(s, 1, 7, 2, "");
            assertEquals("v", s.getMode());
            assertEquals(6, lastSelection(seen).startCol0Based());
            assertEquals(9, lastSelection(seen).endCol0Based(), "beta");
            keys(s, "<Esc>");
            click(s, 2, 1, 3, "");
            assertTrue(lastSelection(seen).isLineWise(), "mode=" + s.getMode());
        }
    }

    @Test
    void aDragSelectsFromThePressToTheRelease() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "efgh", "ijkl"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            s.mouse(new MouseInput(MouseInput.Action.PRESS, "", 1, 1));
            s.mouse(new MouseInput(MouseInput.Action.DRAG, "", 2, 0));
            s.mouse(new MouseInput(MouseInput.Action.DRAG, "", 3, 2));
            s.mouse(new MouseInput(MouseInput.Action.RELEASE, "", 3, 2));
            VisualSelection last = lastSelection(seen);
            assertEquals("v", last.mode());
            assertEquals(List.of(1, 1, 3, 2), List.of(last.startLine1Based(), last.startCol0Based(),
                    last.endLine1Based(), last.endCol0Based()));
            keys(s, "d");
            assertEquals(List.of("al"), s.getBufferLines(), "d deletes what the drag selected");
        }
    }

    @Test
    void shiftClickExtendsTheSelection() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "efgh", "ijkl"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            click(s, 1, 1, 1, "");
            click(s, 2, 2, 1, "S");
            assertEquals("v", s.getMode());
            assertEquals(List.of(1, 1, 2, 2), List.of(lastSelection(seen).startLine1Based(),
                    lastSelection(seen).startCol0Based(), lastSelection(seen).endLine1Based(),
                    lastSelection(seen).endCol0Based()));
        }
    }

    /** With {@code 'mouse'} off Neovim ignores the mouse; a click then still moves the cursor. */
    @Test
    void withoutMouseAClickOnlyMovesTheCursor() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("alpha beta", "second"));
            s.lua("vim.o.mouse = ''");
            click(s, 1, 7, 2, "");
            assertEquals("n", s.getMode());
            assertEquals(new CursorPos(1, 7), s.getLastCursor());
        }
    }

    // ---- macros ----

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    @Test
    void recordAndPlayBackAMacroWithInsertedText() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b", "c"));
            keys(s, "g", "g", "q", "a");
            assertEquals("a", s.getRecordingRegister());
            // While recording, insert mode is driven by Neovim so that every key is recorded.
            keys(s, "A");
            assertTrue(s.isNeovimDrivenInsert());
            keys(s, "!", "<Esc>", "j", "q");
            assertEquals("", s.getRecordingRegister());
            assertEquals(List.of("a!", "b", "c"), s.getBufferLines());

            keys(s, "@", "a");   // plays on line 2
            assertEquals(List.of("a!", "b!", "c"), s.getBufferLines());
            keys(s, "@", "@");   // repeats the last macro on line 3
            assertEquals(List.of("a!", "b!", "c!"), s.getBufferLines());
        }
    }

    @Test
    void countedMacroPlaybackRunsManyTimes() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("1", "2", "3", "4"));
            keys(s, "g", "g", "q", "a", "A", "x", "<Esc>", "j", "q");
            keys(s, "3", "@", "a");
            assertEquals(List.of("1x", "2x", "3x", "4x"), s.getBufferLines());
        }
    }

    @Test
    void macroPlaybackIsPublishedAsOneSyncNotPerChange() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b", "c", "d"));
            keys(s, "g", "g", "q", "a", "A", "!", "<Esc>", "j", "q");
            List<BufferChange> changes = new CopyOnWriteArrayList<>();
            s.addBufferChangeListener(changes::add);
            keys(s, "3", "@", "a");
            NeovimBarrier.pass(s); // any stray notification has arrived by now
            assertEquals(1, changes.size(), "macro playback must publish a single full-buffer change: " + changes);
            assertEquals(-1, changes.get(0).lastLine());
        }
    }

    /**
     * A macro that ends waiting for a key: register {@code a} holds {@code ddf}. The playback
     * deletes line 1 while buffer events are suppressed, then waits for {@code f}'s character.
     * Once the character is typed, the editor gets the whole buffer, without the deleted line.
     */
    @Test
    void aMacroThatEndsWaitingForAKeyReachesTheEditorAfterThatKey() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b c", "d"));
            List<BufferChange> changes = new CopyOnWriteArrayList<>();
            s.addBufferChangeListener(changes::add);
            keys(s, ":let @a = 'ddf'<CR>", "g", "g");
            keys(s, "@", "a");
            keys(s, "c"); // f's character: the cursor moves to the c on "b c"
            NeovimBarrier.pass(s);
            assertFalse(changes.isEmpty(), "the editor heard the macro's change");
            BufferChange last = changes.get(changes.size() - 1);
            assertEquals(-1, last.lastLine(), "a full sync: " + changes);
            assertEquals(List.of("b c", "d"), last.newLines());
        }
    }

    @Test
    void exCommandsAreRecordedInTheMacroAndRepeatWithAtColon() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("foo", "foo", "foo"));
            keys(s, "g", "g", "q", "a");
            s.commandAsync("s/foo/bar/");
            waitFor(() -> uncheckedLines(s).get(0).equals("bar"), "the command to run");
            keys(s, "j", "q");
            keys(s, "@", "a");
            assertEquals(List.of("bar", "bar", "foo"), s.getBufferLines());

            keys(s, "j", "@", ":"); // repeats the last command line on line 3
            assertEquals(List.of("bar", "bar", "bar"), s.getBufferLines());
        }
    }

    /**
     * Neovim's bundled readers of URLs and archives are not loaded
     * ({@code NvimExecutable.READERS_OFF}): no autocommand reads {@code file://}, {@code http://},
     * {@code scp://} names or zip and tar files, so {@code gf} on a URL in a file's text fetches
     * nothing. The file named here exists and holds a line; with netrw it would be read.
     */
    @Test
    void neovimsUrlAndArchiveReadersAreNotLoaded() throws Exception {
        TestNeovim.require();
        Path dir = Files.createTempDirectory("readers");
        try (NeovimSession s = start(List.of("--clean"))) {
            Files.writeString(dir.resolve("target.c"), "int fetched;\n");
            // The readers' autocommand groups (readers_spec.lua checks that a Neovim started
            // without READERS_OFF has each of them, so a renamed group is noticed there).
            assertEquals(0L, ((Number) s.lua("""
                    local n = 0
                    for _, group in ipairs({ 'Network', 'nvim.net.remotefile', 'zip', 'tar' }) do
                      n = n + vim.fn.exists('#' .. group)
                    end
                    return n + vim.fn.exists('#nvim.terminal#BufReadCmd')""")).longValue(),
                    "no reader of a URL or an archive is defined, and term:// names start nothing");
            assertEquals(0L, ((Number) s.lua("return vim.fn.exists(':Explore')")).longValue(), "netrw's commands are not defined");
            s.lua("vim.fn.chdir('" + dir.toString().replace("\\", "/") + "')");
            s.setBufferLines(List.of("see file://target.c"));
            s.lua("vim.bo.modified = false; vim.api.nvim_win_set_cursor(0, { 1, 6 }); pcall(vim.cmd, 'normal! gf')");
            assertEquals(false, s.lua("""
                    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                      for _, line in ipairs(vim.api.nvim_buf_get_lines(buf, 0, -1, false)) do
                        if line:find('fetched', 1, true) then return true end
                      end
                    end
                    return false"""), "gf on the URL read nothing");
        } finally {
            Files.deleteIfExists(dir.resolve("target.c"));
            Files.deleteIfExists(dir);
        }
    }

    /**
     * The user's config sees {@code vim.g.neovim4eclipse} while it runs, before the plug-in's own
     * setup, and the variable is still set afterwards. The config here only records what it saw.
     */
    @Test
    void theConfigCanTellItRunsInsideEclipse() throws Exception {
        TestNeovim.require();
        Path config = Files.createTempFile("init", ".lua");
        try {
            Files.writeString(config,
                    "_G.saw = { guard = vim.g.neovim4eclipse, channel = vim.g.neovim4eclipse_channel or 'none' }\n");
            try (NeovimSession s = start(List.of("--clean", "-u", config.toString()))) {
                assertEquals("1", String.valueOf(s.lua("return _G.saw.guard")));
                assertEquals("none", s.lua("return _G.saw.channel"), "the plug-in's setup runs after the config");
                assertEquals("1", String.valueOf(s.lua("return vim.g.neovim4eclipse")));
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    /**
     * What Neovim knows about us, pinned before the switch to our own client
     * ({@code docs/design/RPC_CLIENT.md}, step 7): the client info, and a UI of 120 x 40 with 24-bit
     * color and the {@code ext_*} features {@code NeovimConnection.TERMINAL_UI} lists. An empty Lua table has no type, so empty
     * {@code methods} and {@code attributes} may come back as a list or a map; both mean "none".
     */
    @Test
    void neovimKnowsTheClientAndATerminalUi() throws Exception {
        try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean"), ClipboardPort.NONE, "1.2.3.qual")) {
            Map<?, ?> client = (Map<?, ?>) s.lua("return vim.api.nvim_get_chan_info(vim.g.neovim4eclipse_channel).client");
            assertEquals("neovim4eclipse", client.get("name"));
            assertEquals("remote", client.get("type"));
            assertEquals(Map.of("major", 1L, "minor", 2L, "patch", 3L, "prerelease", "qual"),
                    NeovimApiIntegrationTest.numbersAsLong(client.get("version")));
            assertEquals(0, ((java.util.Collection<?>) values(client.get("methods"))).size());
            assertEquals(0, ((java.util.Collection<?>) values(client.get("attributes"))).size());

            List<?> uis = (List<?>) s.lua("return vim.api.nvim_list_uis()");
            assertEquals(1, uis.size());
            Map<?, ?> ui = (Map<?, ?>) NeovimApiIntegrationTest.numbersAsLong(uis.get(0));
            assertEquals(120L, ui.get("width"));
            assertEquals(40L, ui.get("height"));
            for (String option : List.of("rgb", "override", "ext_cmdline", "ext_hlstate", "ext_linegrid",
                    "ext_messages", "ext_multigrid", "ext_popupmenu", "ext_tabline", "ext_termcolors")) {
                boolean on = List.of("rgb", "ext_cmdline", "ext_messages", "ext_linegrid", "ext_multigrid")
                    .contains(option);
                assertEquals(on, ui.get(option), option); // see TERMINAL_UI
            }
        }
    }

    /** Closing on purpose (:qa!, then the end of Neovim's output) is not reported as a crash. */
    @Test
    void aNormalCloseLogsNoWarning() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            try (NeovimSession s = start()) {
                s.command("echo 1");
            }
            log.assertNothingAtOrAbove(Level.WARNING);
        }
    }

    /** The Neovim processes this JVM started that still run. */
    private static List<ProcessHandle> nvimChildren() {
        return ProcessHandle.current().children()
                .filter(p -> p.info().command().map(c -> c.toLowerCase().contains("nvim")).orElse(false))
                .toList();
    }

    /**
     * The other side of {@link #aNormalCloseLogsNoWarning}: a Neovim that is killed (a crash, or
     * ending it in the Task Manager) is still reported as a warning, which reaches the Error Log.
     * The process is found as the child of this JVM that this test started: the Neovim children
     * that were there before it started are left out, as another test's Neovim may still be exiting
     * on a loaded machine (a CI runner), which made "exactly one child" fail with two.
     */
    @Test
    void aKilledNeovimIsLoggedAsAWarning() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            List<Long> before = nvimChildren().stream().map(ProcessHandle::pid).toList();
            try (NeovimSession s = start()) {
                s.command("echo 1");
                List<ProcessHandle> nvims = nvimChildren().stream().filter(p -> !before.contains(p.pid())).toList();
                assertEquals(1, nvims.size(), "this test's Neovim: " + nvims);
                nvims.get(0).destroyForcibly();
                nvims.get(0).onExit().get(WAIT_MS, TimeUnit.MILLISECONDS);
                waitFor(() -> !s.isAlive(), "the session to notice");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS); // the reader thread logs on its own
                while (true) {
                    try {
                        log.assertLogged(Level.WARNING, "Neovim exited unexpectedly");
                        break;
                    } catch (AssertionError notYet) {
                        if (System.nanoTime() > deadline) {
                            throw notYet;
                        }
                        Thread.sleep(20);
                    }
                }
            }
        }
    }

    /**
     * Neovim's stderr is kept apart from the MessagePack on stdout: a line written there is logged,
     * and the session goes on (merged into stdout, it would have broken the stream).
     *
     * <p>On Windows only the second half is checked: there {@code nvim --embed} delivers nothing to
     * the stderr pipe (not its own writes, not even a child's {@code echo x 1>&2}), so there is no
     * line to log. {@code nvim --headless} without {@code --embed} does deliver it.
     */
    @Test
    void neovimsStderrIsLoggedAndDoesNotBreakTheSession() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(NeovimConnection.class);
                NeovimSession s = NeovimSession.start(NVIM,
                        List.of("--clean", "--cmd", "lua io.stderr:write('stray text\\n')"))) {
            s.setBufferLines(List.of("still here"));
            assertEquals(List.of("still here"), s.getBufferLines());
            if (PlatformUtil.isWindows()) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS); // the stderr thread logs on its own
            while (true) {
                try {
                    log.assertLogged(Level.WARNING, "Neovim stderr: stray text");
                    break;
                } catch (AssertionError notYet) {
                    if (System.nanoTime() > deadline) {
                        throw notYet;
                    }
                    Thread.sleep(20);
                }
            }
        }
    }

    /**
     * A setup step that fails once the session exists fails the start, and the half-built session
     * is closed whole (NeovimSession.abandon): its Neovim ends, and its input thread too. The config here breaks SEARCH,
     * a late step, and just before has Neovim report a viewport change: the session puts that work
     * on its input thread, which a start that closed only the connection would leave running.
     */
    @Test
    void aStartThatFailsHalfWayLeavesNoNeovimAndNoInputThread() throws Exception {
        TestNeovim.require();
        String breakSearch = "lua local set = vim.api.nvim_set_decoration_provider; "
                + "vim.api.nvim_set_decoration_provider = function(ns, opts) "
                + "if ns == vim.api.nvim_create_namespace('neovim4eclipse_search') then "
                + "vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_viewport') "
                + "error('broken by the test') end return set(ns, opts) end";
        Set<Long> neovimsBefore = neovims();
        Set<Thread> threadsBefore = inputThreads();

        try (ExpectedLog log = ExpectedLog.of(NeovimSession.class)) {
            Exception failure = assertThrows(Exception.class,
                    () -> NeovimSession.start(NVIM, List.of("--clean", "--cmd", breakSearch)).close());
            assertTrue(String.valueOf(failure.getMessage()).contains("broken by the test")
                    || String.valueOf(failure.getCause()).contains("broken by the test"), failure.toString());
            // The session itself was closed, not only its connection. Checked on the log, since the
            // thread alone cannot tell: the JDK also shuts an executor down once nothing refers to it,
            // at a garbage collection, so a forgotten input thread may be gone by the check below.
            log.assertLogged(Level.INFO, "abandoned");
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (true) {
            Set<Long> neovimsLeft = neovims();
            neovimsLeft.removeAll(neovimsBefore);
            Set<Thread> threadsLeft = inputThreads();
            threadsLeft.removeAll(threadsBefore);
            if (neovimsLeft.isEmpty() && threadsLeft.isEmpty()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("left after the failed start: Neovim " + neovimsLeft + ", input threads " + threadsLeft);
            }
            Thread.sleep(20);
        }
    }

    /** The process ids of this JVM's Neovims still running. */
    private static Set<Long> neovims() {
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().command().map(c -> c.contains("nvim")).orElse(false))
                .map(ProcessHandle::pid).collect(Collectors.toCollection(HashSet::new));
    }

    /** The sessions' input threads still alive ({@code InputThread}'s name). */
    private static Set<Thread> inputThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals("neovim4eclipse-input"))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** A Lua table that may have been decoded as a list or as a map. */
    private static Object values(Object table) {
        return table instanceof Map<?, ?> map ? map.values() : table;
    }

    @Test
    void neovimReportsItIsWaitingForTheArgumentOfAnAtCommand() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a"));
            s.input("@");
            assertTrue(s.isWaitingForArgument(), "the key after @ names the register");
            s.input("<Esc>");
            assertTrue(!s.isWaitingForArgument());
        }
    }

    private static List<String> uncheckedLines(NeovimSession s) {
        try {
            return s.getBufferLines();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- dot-repeat of text typed (by Eclipse) in insert mode ----

    /**
     * Simulates Eclipse ending an insert in which it typed {@code typed} at the cursor of the current
     * line, and waits until Neovim has finished leaving insert mode.
     *
     * <p>Waiting for the buffer to match is not enough: after pushing the document, leaving insert
     * mode still puts Neovim's cursor where Vim leaves it after Esc. On a loaded machine a key sent
     * in between (the {@code j} before a {@code .}) was undone by that cursor move. So the wait is
     * for the input-handled notice, which comes after the whole queued work.
     */
    private static void finishEclipseInsert(NeovimSession s, String before, String after, int base, int caret,
            List<String> documentAfter) throws Exception {
        s.setLeaveInsertPush(() -> {
            try {
                s.setBufferLines(documentAfter); // what BufferSynchronizer.pushFullDocumentToNeovim does
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CountDownLatch handled = new CountDownLatch(1);
        Runnable listener = handled::countDown;
        s.addInputHandledListener(listener);
        try {
            s.leaveInsertModeAsync(InsertReplay.plan(before, after, base, caret));
            assertTrue(handled.await(WAIT_MS, TimeUnit.MILLISECONDS), "Neovim left insert mode");
        } finally {
            s.removeInputHandledListener(listener);
        }
    }

    /**
     * A key typed right after Esc, while Neovim is still leaving insert mode: the session stops
     * Eclipse's typing at the Esc, so the router sends the key to Neovim, queued behind the leave,
     * where it runs in Normal mode. The leave is held in its push until the key is routed, so the
     * mode is certainly still insert then. Example: {@code ihello<Esc>x} typed fast.
     */
    @Test
    void aKeyRightAfterEscGoesToNeovimInNormalMode() throws Exception {
        try (NeovimSession s = start()) {
            s.addBufferChangeListener(change -> {
                if (change.afterApply() != null) {
                    change.afterApply().run(); // as the editor does once it applied the change
                }
            });
            s.setBufferLines(List.of("one"));
            keys(s, "A");
            waitFor(s::isEclipseTypingReady, "Eclipse may type");
            CountDownLatch routed = new CountDownLatch(1);
            s.setLeaveInsertPush(() -> {
                try {
                    assertTrue(routed.await(WAIT_MS, TimeUnit.MILLISECONDS));
                    s.setBufferLines(List.of("one hello")); // what Eclipse typed
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            KeyRouter router = new KeyRouter(s);
            CountDownLatch handled = new CountDownLatch(2); // the leave, then the key
            Runnable listener = handled::countDown;
            s.addInputHandledListener(listener);
            try {
                assertTrue(router.route("<Esc>"));
                assertTrue(s.isTypingMode(), "Neovim has not left insert mode yet");
                assertTrue(router.route("x"), "the key goes to Neovim, not into the document");
                routed.countDown();
                assertTrue(handled.await(WAIT_MS, TimeUnit.MILLISECONDS), "Neovim handled both");
            } finally {
                s.removeInputHandledListener(listener);
            }
            assertEquals("n", s.getMode());
            // Neovim's cursor was after "one" (no replay plan moved it), and Esc put it on the "e":
            // x deleted that in Normal mode, rather than being typed into the text.
            assertEquals(List.of("on hello"), s.getBufferLines());
        }
    }

    /**
     * {@code ihello<Esc>ibye} typed fast: the second {@code i} enters insert mode while {@code b},
     * {@code y} and {@code e} still wait in the queue. Its sync, applied at once here, must not let
     * Eclipse type (their line events come after it): Neovim types them, and the last hands the
     * insert back. Each "Eclipse types" check is taken right after an applied sync.
     */
    @Test
    void anInsertEnteredWhileKeysWaitIsHandedToEclipseOnlyAfterTheLast() throws Exception {
        try (NeovimSession s = start()) {
            List<Boolean> eclipseTypes = new CopyOnWriteArrayList<>();
            s.addBufferChangeListener(change -> {
                if (change.afterApply() != null) {
                    change.afterApply().run(); // as the editor does once it applied the change
                    if (s.isTypingMode()) {
                        eclipseTypes.add(s.isEclipseTypingReady() && !s.isNeovimDrivenInsert());
                    }
                }
            });
            s.setBufferLines(List.of("one"));
            keys(s, "A");
            waitFor(s::isEclipseTypingReady, "Eclipse may type");
            CountDownLatch routed = new CountDownLatch(1);
            s.setLeaveInsertPush(() -> {
                try {
                    assertTrue(routed.await(WAIT_MS, TimeUnit.MILLISECONDS));
                    s.setBufferLines(List.of("one hello")); // what Eclipse typed
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            KeyRouter router = new KeyRouter(s);
            CountDownLatch handled = new CountDownLatch(5); // the leave, then i, b, y, e
            Runnable listener = handled::countDown;
            s.addInputHandledListener(listener);
            eclipseTypes.clear();
            try {
                for (String key : List.of("<Esc>", "i", "b", "y", "e")) {
                    assertTrue(router.route(key), key + " goes to Neovim");
                }
                routed.countDown();
                assertTrue(handled.await(WAIT_MS, TimeUnit.MILLISECONDS), "Neovim handled them all");
            } finally {
                s.removeInputHandledListener(listener);
            }
            waitFor(s::isEclipseTypingReady, "the insert handed back to Eclipse");
            assertFalse(eclipseTypes.isEmpty() || eclipseTypes.get(0), "not at the insert's sync: " + eclipseTypes);
            assertFalse(s.isNeovimDrivenInsert());
            // Esc put the cursor on the "e" of "one"; i inserted bye before it.
            assertEquals(List.of("onbyee hello"), s.getBufferLines());
        }
    }

    @Test
    void dotRepeatsTextTypedInInsertModeByEclipse() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one", "two"));
            keys(s, "g", "g", "A"); // insert mode at the end of line 1; Eclipse now types " add"
            assertTrue(s.isTypingMode());
            finishEclipseInsert(s, "one\ntwo\n", "one add\ntwo\n", 3, 7, List.of("one add", "two"));
            assertEquals(List.of("one add", "two"), s.getBufferLines());
            assertEquals("n", s.getMode());

            keys(s, "j", ".");
            assertEquals(List.of("one add", "two add"), s.getBufferLines(), "dot must repeat the typed text");
        }
    }

    @Test
    void dotAfterChangeWordRepeatsTheChangeWithTheTypedText() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("foo bar", "foo baz"));
            keys(s, "g", "g", "c", "w"); // deletes "foo" and enters insert; Eclipse types "X"
            assertTrue(s.isTypingMode());
            finishEclipseInsert(s, " bar\nfoo baz\n", "X bar\nfoo baz\n", 0, 1, List.of("X bar", "foo baz"));
            keys(s, "j", "0", ".");
            assertEquals(List.of("X bar", "X baz"), s.getBufferLines());
        }
    }

    @Test
    void typedTextWithSpecialCharactersIsRepeatedLiterally() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b"));
            keys(s, "g", "g", "A");
            finishEclipseInsert(s, "a\nb\n", "a<Esc>x\nb\n", 1, 7, List.of("a<Esc>x", "b"));
            keys(s, "j", ".");
            assertEquals(List.of("a<Esc>x", "b<Esc>x"), s.getBufferLines());
        }
    }

    // ---- a push of part of the buffer (INSERT_CTRL_KEYS.md, section 4) ----

    /** Lines 1 .. end-1 replaced: the last line stays, counted from the end; outside the buffer: refused. */
    @Test
    void aPartOfTheBufferIsWrittenCountedFromBothEnds() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b", "c"));
            assertTrue(s.setBufferRegion(1, 1, List.of("B", "x")));
            assertEquals(List.of("a", "B", "x", "c"), s.getBufferLines());
            assertTrue(s.setBufferRegion(0, 0, List.of("a", "B", "x", "c")), "equal: nothing written");
            assertEquals(List.of("a", "B", "x", "c"), s.getBufferLines());
            assertTrue(s.setBufferRegion(2, 1, List.of()), "a line deleted");
            assertEquals(List.of("a", "B", "c"), s.getBufferLines());
            assertFalse(s.setBufferRegion(9, 0, List.of("z")), "not inside the buffer");
            assertEquals(List.of("a", "B", "c"), s.getBufferLines());
        }
    }

    // ---- the user's insert mappings and abbreviations are kept (INSERT_CTRL_KEYS.md, section 3) ----

    /**
     * The re-typing at Esc applies none of the user's insert mappings or abbreviations: Eclipse
     * typed " teh jk", and that is what Neovim gets, although {@code jk} is mapped to Esc and
     * {@code teh} is an abbreviation. They are kept for the keys Neovim reads itself.
     */
    @Test
    void theReTypingAtEscAppliesNoInsertMappingOrAbbreviation() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one", "two"));
            keys(s, ":inoremap jk <lt>Esc><CR>", ":iabbrev teh the<CR>");
            keys(s, "g", "g", "A");
            finishEclipseInsert(s, "one\ntwo\n", "one teh jk\ntwo\n", 3, 10, List.of("one teh jk", "two"));
            assertEquals("n", s.getMode());
            keys(s, "j", ".");
            assertEquals(List.of("one teh jk", "two teh jk"), s.getBufferLines(), "dot repeats the text as typed");

            keys(s, "o", "teh ", "<Esc>"); // keys Neovim reads itself: the abbreviation applies
            assertEquals("the ", s.getBufferLines().get(2));
        }
    }

    /**
     * The other half of the test above: in an insert Neovim
     * drives itself, the user's insert mapping applies, because every key goes to Neovim as typed. A
     * block insert ({@code Ctrl-V j I}) and an insert while recording a macro are such inserts. With
     * {@code inoremap jk <Esc>}, {@code jk} ends both, as in Neovim alone: {@code x} goes on both lines,
     * and the macro holds {@code iyjk}, which replays as an insert of {@code y}.
     */
    @Test
    void inAnInsertNeovimDrivesTheUsersInsertMappingApplies() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("aa", "bb", "cc"));
            keys(s, ":inoremap jk <lt>Esc><CR>");

            keys(s, "g", "g", "<C-v>", "j", "I");
            assertTrue(s.isNeovimDrivenInsert(), "a block insert is Neovim's");
            keys(s, "x", "j", "k");
            assertEquals("n", s.getMode(), "jk left the block insert");
            assertEquals(List.of("xaa", "xbb", "cc"), s.getBufferLines(), "and Neovim repeated it on each line");

            keys(s, "G", "0", "q", "q", "i");
            assertTrue(s.isNeovimDrivenInsert(), "an insert while recording is Neovim's");
            keys(s, "y", "j", "k");
            assertEquals("n", s.getMode(), "jk left the insert while recording");
            keys(s, "q");
            assertEquals(List.of("xaa", "xbb", "ycc"), s.getBufferLines());
            keys(s, "g", "g", "0", "@", "q");
            assertEquals("n", s.getMode(), "the macro's jk ends its insert too");
            assertEquals(List.of("yxaa", "xbb", "ycc"), s.getBufferLines(), "the macro recorded iyjk, as typed");
        }
    }

    /**
     * A sync point with real Neovim: " foo" typed by Eclipse, then Ctrl-U. The re-typed text is
     * this insert's, so Ctrl-U deletes just it; the user's own mapping of Ctrl-U applies to the key.
     */
    @Test
    void aSyncPointKeyGoesThroughTheUsersMappingAfterTheTypedText() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one", "two"));
            keys(s, ":inoremap <lt>C-u> <lt>C-u>U<CR>");
            keys(s, "g", "g", "A");
            List<String> document = List.of("one foo", "two");
            s.setLeaveInsertPush(() -> {
                try {
                    s.setBufferLines(document);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            CountDownLatch handled = new CountDownLatch(1);
            Runnable listener = handled::countDown;
            s.addInputHandledListener(listener);
            try {
                s.syncInsertThenInputAsync(InsertReplay.syncPoint("one\ntwo\n", "one foo\ntwo\n", 3, 7), "<C-u>");
                assertTrue(handled.await(WAIT_MS, TimeUnit.MILLISECONDS), "the sync point was handled");
            } finally {
                s.removeInputHandledListener(listener);
            }
            assertEquals(List.of("oneU", "two"), s.getBufferLines());
            assertTrue(s.isTypingMode(), "the insert goes on");
        }
    }

    // ---- blockwise visual ----

    private static void keys(NeovimSession s, String... keys) throws Exception {
        for (String key : keys) {
            s.input(key);
        }
    }

    @Test
    void blockwiseSelectionIsReportedWithBothCorners() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "efgh", "ijkl"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            keys(s, "g", "g", "<C-v>", "j", "l");
            VisualSelection last = seen.get(seen.size() - 1);
            assertTrue(last.isBlockWise(), "mode=" + last.mode());
            assertEquals(1, last.startLine1Based());
            assertEquals(0, last.startCol0Based());
            assertEquals(2, last.endLine1Based());
            assertEquals(1, last.endCol0Based());
            assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 2), new BlockSelection.ByteRange(2, 1, 2)),
                    last.block(), "Neovim's getregionpos: bytes 1-2 of each line");
        }
    }

    @Test
    void blockDeleteRemovesTheRectangle() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "efgh", "ijkl"));
            keys(s, "g", "g", "<C-v>", "j", "l", "d");
            assertEquals(List.of("cd", "gh", "ijkl"), s.getBufferLines());
        }
    }

    @Test
    void blockInsertIsRepeatedOnEveryLineWhenNeovimSeesTheTyping() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "efgh", "ijkl"));
            keys(s, "g", "g", "<C-v>", "j", "j", "I");
            assertTrue(s.isNeovimDrivenInsert(), "insert started from block visual must be driven by Neovim");
            keys(s, "x", "y", "<Esc>");
            assertEquals(List.of("xyabcd", "xyefgh", "xyijkl"), s.getBufferLines());
            assertTrue(!s.isNeovimDrivenInsert());
        }
    }

    @Test
    void blockAppendAfterDollarAppendsToEachLineEnd() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("ab", "cdef", "g"));
            keys(s, "g", "g", "<C-v>", "j", "j", "$", "A");
            keys(s, "!", "<Esc>");
            assertEquals(List.of("ab!", "cdef!", "g!"), s.getBufferLines());
        }
    }

    @Test
    void ordinaryInsertIsNotNeovimDriven() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abc"));
            s.input("i");
            assertTrue(s.isTypingMode());
            assertTrue(!s.isNeovimDrivenInsert(), "plain insert stays Eclipse-driven");
        }
    }

    @Test
    void modeTracksInsertAndEscape() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abc"));
            s.input("i");
            assertTrue(s.isTypingMode(), "mode=" + s.getMode());
            s.input("<Esc>");
            assertEquals("n", s.getMode());
        }
    }

    @Test
    void bufferChangeListenerSeesDeletion() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b", "c"));
            List<BufferChange> changes = new CopyOnWriteArrayList<>();
            s.addBufferChangeListener(changes::add);
            s.input("d");
            s.input("d");
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (changes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(!changes.isEmpty(), "no buffer change notification received");
        }
    }

    // ---- Neovim's own command line, as the : box shows it (A2) ----

    /** {@link NeovimSession#lua}, or {@code null} if it failed: for {@link #waitFor} conditions. */
    private static Object luaOrNull(NeovimSession s, String code) {
        try {
            return s.lua(code);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The command line Neovim has open, as {@code getcmdtype()} and {@code getcmdline()} together
     * ({@code ":echo"}), with its cursor's byte position after a {@code |}; {@code null} while none
     * is open or the call fails. Neovim draws it itself (ui2).
     */
    private static String cmdline(NeovimSession s) {
        Object line = luaOrNull(s, "return vim.fn.mode() == 'c' and (vim.fn.getcmdtype() .. vim.fn.getcmdline()"
                + " .. '|' .. vim.fn.getcmdpos()) or vim.NIL");
        return line instanceof String text ? text : null;
    }

    @Test
    void typingOnTheCommandLineIsNeovimsWithItsCursor() throws Exception {
        try (NeovimSession s = start()) {
            keys(s, ":", "e", "c", "h", "o", "<Left>");
            waitFor(() -> ":echo|4".equals(cmdline(s)), "the line and the cursor after <Left>: " + cmdline(s));
            keys(s, "<Esc>");
            waitFor(() -> cmdline(s) == null, "the line closed");
        }
    }

    /** Neovim's own history: Up recalls the last command. */
    @Test
    void upRecallsNeovimsHistory() throws Exception {
        try (NeovimSession s = start()) {
            keys(s, ":", "l", "e", "t", " ", "g", ":", "x", "=", "1", "<CR>");
            keys(s, ":", "<Up>");
            waitFor(() -> String.valueOf(cmdline(s)).startsWith(":let g:x=1|"), "the history entry: " + cmdline(s));
        }
    }

    /** Tab is Neovim's completion; the candidates are Neovim's popup menu, drawn as a float. */
    @Test
    void tabCompletesAndNeovimsMenuListsTheCandidates() throws Exception {
        try (NeovimSession s = start()) {
            List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
            s.addFloatingWindowListener(reports::add);
            keys(s, ":", "c", "o", "l", "o", " ", "<Tab>");
            waitFor(() -> !reports.isEmpty() && reports.get(reports.size() - 1).stream()
                    .anyMatch(f -> f.grid().window() == -1 && rows(f.grid()).stream().anyMatch(r -> r.contains("default"))),
                    "colorscheme candidates in the popup menu: " + reports);
            assertTrue(String.valueOf(cmdline(s)).startsWith(":colo "), cmdline(s));
            keys(s, "<Esc>");
        }
    }

    /** A line a mapping opens is Neovim's like any other. */
    @Test
    void aLineOpenedByAMappingIsNeovims() throws Exception {
        try (NeovimSession s = start()) {
            s.command("nnoremap ; :%s/");
            keys(s, ";");
            waitFor(() -> String.valueOf(cmdline(s)).startsWith(":%s/|"), "the mapping's line: " + cmdline(s));
        }
    }

    /** {@code :} in visual mode: Neovim puts the range in itself. */
    @Test
    void colonInVisualModeGetsNeovimsRange() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b"));
            keys(s, "V", "j", ":");
            waitFor(() -> String.valueOf(cmdline(s)).startsWith(":'<,'>|"), "the visual range: " + cmdline(s));
        }
    }

    /** A prompt ({@code input()}) is a command line too: its text is Neovim's line. */
    @Test
    void aPromptIsNeovimsLine() throws Exception {
        try (NeovimSession s = start()) {
            s.inputAsync(":call input('Name: ', 'def')<CR>");
            waitFor(() -> "@def|4".equals(cmdline(s)), "the prompt's line: " + cmdline(s));
            keys(s, "<Esc>");
        }
    }

    /** A paste goes into the line as text: {@code <} stays a character. */
    @Test
    void aPasteGoesIntoTheLine() throws Exception {
        try (NeovimSession s = start()) {
            keys(s, ":");
            s.pasteAsync("echo \"<x>\"");
            waitFor(() -> String.valueOf(cmdline(s)).startsWith(":echo \"<x>\"|"), "the pasted text: " + cmdline(s));
            keys(s, "<Esc>");
        }
    }

    /** While a search is typed, the cursor follows Neovim's preview of the match; Esc puts it back. */
    @Test
    void theCursorFollowsTheMatchWhileASearchIsTyped() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one", "two", "three"));
            keys(s, "/", "t", "h");
            waitFor(() -> s.getLastCursor().line1Based() == 3, "the cursor on the match, before Enter");
            keys(s, "<Esc>");
            waitFor(() -> s.getLastCursor().line1Based() == 1, "back where it was");
            keys(s, "/", "t", "w", "<CR>");
            waitFor(() -> s.getLastCursor().line1Based() == 2, "on the match after Enter");
        }
    }

    /** {@code q:} would open a window Eclipse cannot show: it closes at once, with a message. */
    @Test
    void theCommandLineWindowIsDrawnAndEnterRunsItsLine() throws Exception {
        try (NeovimSession s = start()) {
            List<CommandWindowView> views = new CopyOnWriteArrayList<>();
            s.addCommandWindowListener(views::add);
            s.lua("vim.fn.histadd(':', 'echo \"from the history\"')");
            keys(s, "q", ":");
            waitFor(() -> views.stream().anyMatch(v -> v.open() && v.grid() != null
                    && rows(v.grid()).stream().anyMatch(r -> r.contains("echo \"from the history\""))),
                    "the window drawn with the history: " + views);
            CommandWindowView open = views.get(views.size() - 1);
            assertEquals(":", open.type());
            assertTrue(open.grid().cursorRow() >= 0, "the cursor is in the window");
            assertTrue(s.isCommandWindowOpen());
            assertEquals(":", String.valueOf(s.lua("return vim.fn.getcmdwintype()")));
            keys(s, "k", "<CR>"); // up to the history line, and run it
            waitFor(() -> !views.get(views.size() - 1).open(), "closed after Enter: " + views);
            waitFor(() -> Ui2Text.shown(s).contains("from the history"), "the line ran");
            assertEquals(1L, ((Number) s.lua("return #vim.tbl_filter(function(w) return not vim.w[w].neovim4eclipse_ui2 end, vim.api.nvim_list_wins())")).longValue());
            assertFalse(s.isCommandWindowOpen());
        }
    }

    /**
     * A click on a history line in the panel moves the window's cursor there, through Neovim's own
     * mouse on the window's grid; the wheel scrolls it. Example: three lines in the history, a
     * click on row 0 puts the cursor on the first.
     */
    @Test
    void aClickInTheCommandLineWindowMovesItsCursorAndTheWheelScrolls() throws Exception {
        try (NeovimSession s = start()) {
            List<CommandWindowView> views = new CopyOnWriteArrayList<>();
            s.addCommandWindowListener(views::add);
            for (int i = 1; i <= 40; i++) {
                s.lua("vim.fn.histadd(':', 'echo " + i + "')");
            }
            keys(s, "q", ":");
            waitFor(() -> views.stream().anyMatch(v -> v.open() && v.grid() != null && v.grid().cursorRow() > 0),
                    "the window drawn, the cursor on its last line: " + views);
            long before = number(s, "return vim.fn.line('.')");
            s.commandWindowMouseAsync("left", "press", "", 0, 2);
            s.commandWindowMouseAsync("left", "release", "", 0, 2);
            waitFor(() -> number(s, "return vim.fn.winline()") == 1, "the cursor on the window's first row");
            assertTrue(number(s, "return vim.fn.line('.')") < before, "moved up to the clicked line");
            long top = number(s, "return vim.fn.line('w0')");
            s.commandWindowMouseAsync("wheel", "up", "", 0, 0);
            waitFor(() -> number(s, "return vim.fn.line('w0')") < top, "scrolled up by the wheel");
        }
    }

    // ---- floating windows without the focus ----

    /** Opens a float over the current window without entering it; its ID is {@code vim.g.float}. */
    private static final String OPEN_FLOAT = """
            local buf = vim.api.nvim_create_buf(false, true)
            vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'float text', 'second row' })
            vim.g.float = vim.api.nvim_open_win(buf, false,
              { relative = 'win', bufpos = { 9, 2 }, width = 14, height = 2, style = 'minimal' })
            """;

    private static List<String> sixtyLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            lines.add("line " + i);
        }
        return lines;
    }

    /**
     * {@code floats} without Neovim's own command line and messages (ui2's windows, always drawn;
     * {@code NeovimUi2IntegrationTest}) and its popup menu: the plug-ins' floats alone.
     */
    private static List<FloatWindow> plugInFloats(NeovimSession s, List<FloatWindow> floats) {
        List<Long> ui2 = new ArrayList<>();
        try {
            Object ids = s.lua("local w = require('vim._core.ui2').wins return { w.cmd, w.msg, w.pager, w.dialog }");
            if (ids instanceof List<?> list) {
                list.forEach(id -> ui2.add(((Number) id).longValue()));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return floats.stream().filter(f -> f.grid().window() != -1 && !ui2.contains((long) f.grid().window())).toList();
    }

    /**
     * A floating window a plug-in opens without entering it (key hints, hover text) reaches the
     * listeners with its cells and its place, moves when the text it hangs on scrolls, and is gone
     * when it closes. Example: a float at line 10 of a window showing lines 1 to 23 is on row 10
     * (one below its line); after the text scrolled a line, on row 9.
     */
    @Test
    void aFloatingWindowWithoutTheFocusIsReportedMovesWithItsTextAndGoesWhenClosed() throws Exception {
        try (NeovimSession s = start()) {
            assertTrue(s.floatingWindowsDrawn());
            s.setBufferLines(sixtyLines());
            List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
            s.addFloatingWindowListener(reports::add);
            s.lua(OPEN_FLOAT);
            waitFor(() -> !reports.isEmpty() && plugInFloats(s, reports.get(reports.size() - 1)).size() == 1
                    && rows(plugInFloats(s, reports.get(reports.size() - 1)).get(0).grid()).get(0).startsWith("float text"),
                    "the float drawn");
            FloatWindow drawn = plugInFloats(s, reports.get(reports.size() - 1)).get(0);
            assertEquals(number(s, "return vim.g.float"), drawn.grid().window());
            assertEquals(14, drawn.grid().width());
            assertEquals(2, drawn.grid().height());
            assertEquals(-1, drawn.grid().cursorRow(), "no cursor: Neovim's is in the editor's window");
            assertEquals(2, drawn.col());
            assertEquals(1L, number(s, "return vim.fn.line('.')"), "the editor's window kept the focus");
            assertEquals(reports.get(reports.size() - 1), s.floatingWindows());
            int row = drawn.row();
            assertEquals(number(s, "return vim.api.nvim_win_get_height(0)"), drawn.hostRows(),
                    "the editor's window, whose place Neovim said once, right after the UI attached");
            keys(s, "<C-e>");
            waitFor(() -> plugInFloats(s, reports.get(reports.size() - 1)).size() == 1
                    && plugInFloats(s, reports.get(reports.size() - 1)).get(0).row() == row - 1,
                    "the float one row up after the text scrolled a line");
            s.lua("vim.api.nvim_win_close(vim.g.float, true)");
            waitFor(() -> plugInFloats(s, reports.get(reports.size() - 1)).isEmpty(), "gone when closed");
            assertEquals(List.of(), plugInFloats(s, s.floatingWindows()));
        }
    }

    /**
     * {@code vim.g.neovim4eclipse_floating_windows = 0} turns the drawing off, and so does the
     * viewport being off: a float's place is a cell of Neovim's screen, which is then not the
     * editor's size. The float still opens in Neovim; no listener hears of it. (Neovim's own
     * command line and messages, ui2's windows, are drawn all the same: they are left out here.)
     */
    @Test
    void floatingWindowsAreNotReportedWhenTurnedOffOrWithoutTheViewport() throws Exception {
        for (String off : List.of("let g:neovim4eclipse_floating_windows = 0", "let g:neovim4eclipse_viewport = 0")) {
            try (NeovimSession s = start(List.of("--clean", "--cmd", off))) {
                assertFalse(s.floatingWindowsDrawn(), off);
                s.setBufferLines(sixtyLines());
                List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
                s.addFloatingWindowListener(reports::add);
                s.lua(OPEN_FLOAT);
                keys(s, "j"); // a key after it: Neovim has redrawn, float and all, before it answers
                waitFor(() -> number(s, "return vim.fn.line('.')") == 2, "the key handled");
                NeovimBarrier.pass(s);
                assertEquals(1L, number(s, "return vim.api.nvim_win_is_valid(vim.g.float) and 1 or 0"), "the float is there");
                assertTrue(reports.stream().allMatch(r -> plugInFloats(s, r).isEmpty()), off + ": " + reports);
                assertEquals(List.of(), plugInFloats(s, s.floatingWindows()), off);
            }
        }
    }

    /**
     * A click that reaches Neovim after its command-line window closed does nothing. The panel's
     * click and an Enter typed just before it queue in Neovim's input in order, so the Enter can
     * close the window before the plug-in hears of it, and the click then names the closed
     * window's grid. Neovim finds no window for that grid and drops the click, and it gives a new
     * window a new grid number, never the closed one's: the click cannot land in the editor's
     * window, or in a command-line window opened again since. Example: {@code q:}, Ctrl-C, a click
     * on row 0 of the old grid; the file's cursor stays on line 20.
     */
    @Test
    void aClickOnTheGridOfAClosedCommandLineWindowDoesNothing() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.api.nvim_buf_set_lines(0, 0, -1, false, vim.fn.map(vim.fn.range(1, 40), 'string(v:val)'))"
                    + " vim.api.nvim_win_set_cursor(0, {20, 0})");
            for (int i = 1; i <= 40; i++) {
                s.lua("vim.fn.histadd(':', 'echo " + i + "')");
            }
            keys(s, "q", ":");
            waitFor(() -> s.commandWindowGrid() != -1, "the window drawn");
            int gone = s.commandWindowGrid();
            keys(s, "<C-c>", "<C-c>"); // out of the window, nothing run
            waitFor(() -> !s.isCommandWindowOpen() && s.commandWindowGrid() == -1, "closed");
            String cursors = "return vim.inspect(vim.tbl_map(vim.api.nvim_win_get_cursor, vim.tbl_filter(function(w) return not vim.w[w].neovim4eclipse_ui2 end, vim.api.nvim_list_wins())))"
                    + " .. vim.fn.mode() .. vim.fn.getcmdwintype()";

            clickRows(s, gone); // with only the editor's window open
            assertEquals("{ { 20, 0 } }n", s.lua(cursors), "the file's cursor, the mode: unchanged");

            keys(s, "q", ":");
            waitFor(() -> s.commandWindowGrid() != -1, "the window drawn again");
            assertNotEquals(gone, s.commandWindowGrid(), "a new grid for the new window");
            Object open = s.lua(cursors);
            clickRows(s, gone); // with the new command-line window open
            assertEquals(open, s.lua(cursors), "neither window's cursor moved");
        }
    }

    /** Left clicks on rows 0 to 2 of {@code grid}, sent as the panel sends them, then waits until Neovim handled them. */
    private static void clickRows(NeovimSession s, int grid) throws Exception {
        for (int row = 0; row < 3; row++) {
            s.lua("vim.api.nvim_input_mouse('left', 'press', '', " + grid + ", " + row + ", 2)"
                    + " vim.api.nvim_input_mouse('left', 'release', '', " + grid + ", " + row + ", 2)");
        }
        NeovimBarrier.pass(s);
    }

    /** A number Neovim answers ({@code return vim.fn.line('.')}), for a wait's condition; -1 if the call fails. */
    private static long number(NeovimSession s, String lua) {
        try {
            return ((Number) s.lua(lua)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Closed in Normal mode: no :quit reaches the editor's window, which would close its editor. */
    @Test
    void closingTheCommandLineWindowInNormalModeQuitsNothingElse() throws Exception {
        try (NeovimSession s = start()) {
            s.lua("vim.api.nvim_create_autocmd('QuitPre', { callback = function() vim.g.quit_seen = vim.fn.getcmdwintype() end })");
            keys(s, "q", ":");
            waitFor(s::isCommandWindowOpen, "open");
            s.closeCommandWindowAsync();
            waitFor(() -> !s.isCommandWindowOpen(), "closed");
            s.lua("return 1"); // the keys typed by the close have been handled
            assertEquals(Boolean.TRUE, s.lua("return vim.g.quit_seen == nil"), "no :quit ran outside the window");
            assertEquals(1L, ((Number) s.lua("return #vim.tbl_filter(function(w) return not vim.w[w].neovim4eclipse_ui2 end, vim.api.nvim_list_wins())")).longValue());
        }
    }

    /** Closed with a command half typed ({@code r}, {@code "}, {@code g}): the history line under the cursor does not run. */
    @Test
    void closingTheCommandLineWindowMidCommandRunsNothing() throws Exception {
        for (String pending : List.of("r", "\"", "g", "d")) {
            try (NeovimSession s = start()) {
                s.lua("vim.fn.histadd(':', 'let g:ran = 1')");
                keys(s, "q", ":");
                waitFor(s::isCommandWindowOpen, "open");
                keys(s, "k", pending); // on the history line, a command waiting for its next key
                s.closeCommandWindowAsync();
                waitFor(() -> !s.isCommandWindowOpen(), "closed after " + pending);
                s.lua("return 1");
                assertEquals(Boolean.TRUE, s.lua("return vim.g.ran == nil"), "the line did not run after " + pending);
                assertEquals(Boolean.TRUE, s.lua("return vim.fn.histget(':', -1) == 'let g:ran = 1'"),
                        "and was not changed in the history (r replaced no character) after " + pending);
            }
        }
    }

    @Test
    void closingTheCommandLineWindowRunsNothingEvenInInsertMode() throws Exception {
        try (NeovimSession s = start()) {
            List<CommandWindowView> views = new CopyOnWriteArrayList<>();
            s.addCommandWindowListener(views::add);
            keys(s, "q", ":");
            waitFor(s::isCommandWindowOpen, "open");
            keys(s, "i", "l", "e", "t", " ", "g", ":", "x", "=", "1"); // a line typed, not run
            s.closeCommandWindowAsync();
            waitFor(() -> !s.isCommandWindowOpen(), "closed: " + views);
            assertEquals("", String.valueOf(s.lua("return vim.fn.getcmdwintype()")));
            assertEquals(Boolean.TRUE, s.lua("return vim.g.x == nil"), "the line did not run");
            assertEquals("n", String.valueOf(s.lua("return vim.api.nvim_get_mode().mode")));
        }
    }

    @Test
    void ctrlFOnTheCommandLineOpensTheWindowWithTheLine() throws Exception {
        try (NeovimSession s = start()) {
            List<CommandWindowView> views = new CopyOnWriteArrayList<>();
            s.addCommandWindowListener(views::add);
            keys(s, ":", "e", "c", "h", "o", " ", "4", "2", "<C-f>");
            waitFor(() -> views.stream().anyMatch(v -> v.open() && v.grid() != null
                    && rows(v.grid()).stream().anyMatch(r -> r.contains("echo 42"))), "the window with the line: " + views);
            // Ctrl-C in the window: back to the command line, with the line under the cursor.
            keys(s, "<C-c>");
            waitFor(() -> !s.isCommandWindowOpen() && String.valueOf(cmdline(s)).startsWith(":echo 42|"),
                    "back on the line: " + cmdline(s));
            keys(s, "<Esc>");
        }
    }

    /**
     * The window's highlights are known though Neovim defined some before the session listened:
     * {@code :echo} is a Vim command, bold in Neovim's default colorscheme ({@code Statement}),
     * light or dark.
     */
    @Test
    void theCommandLineWindowsHighlightsAreKnownFromTheStart() throws Exception {
        try (NeovimSession s = start()) {
            List<CommandWindowView> views = new CopyOnWriteArrayList<>();
            s.addCommandWindowListener(views::add);
            s.lua("vim.fn.histadd(':', 'echo 1')");
            keys(s, "q", ":");
            waitFor(() -> views.stream().anyMatch(v -> v.grid() != null && rows(v.grid()).stream().anyMatch(r -> r.contains("echo 1"))),
                    "the window drawn: " + views);
            GridSnapshot grid = views.get(views.size() - 1).grid();
            int row = rows(grid).indexOf(rows(grid).stream().filter(r -> r.contains("echo 1")).findFirst().orElseThrow());
            int col = grid.row(row).indexOf("echo");
            assertTrue(grid.attr(grid.hlAt(row, col)).bold(), "echo in Statement's bold: " + grid.attr(grid.hlAt(row, col)));
        }
    }

    private static List<String> rows(GridSnapshot grid) {
        List<String> rows = new ArrayList<>();
        for (int r = 0; r < grid.height(); r++) {
            rows.add(grid.row(r));
        }
        return rows;
    }

    @Test
    void closeIsIdempotentAndStopsTheProcess() throws Exception {
        NeovimSession s = start();
        s.close();
        s.close();
        assertTrue(!s.isAlive());
    }
}

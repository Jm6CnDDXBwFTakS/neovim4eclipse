// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

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

    private static NeovimSession start() throws Exception {
        return start(List.of("--clean"));
    }

    private static NeovimSession start(List<String> args) throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        return NeovimSession.start(NVIM, args);
    }

    // ---- errors in the user's configuration ----

    /**
     * A typo in init.lua made Neovim wait at a "Press ENTER" prompt Eclipse does not show, and the
     * start timed out. Now the prompt is dismissed, Neovim starts (without that init.lua: a syntax
     * error stops all of it), and the error is reported.
     */
    @Test
    void aSyntaxErrorInTheConfigurationIsReportedAndNeovimStartsAnyway(@TempDir Path dir) throws Exception {
        Path init = dir.resolve("init.lua");
        Files.writeString(init, "vim.g.before = 1\nnnoremap ; :\n");
        try (NeovimSession s = start(List.of("-u", init.toString()))) {
            assertTrue(s.startupErrors().contains("E5112"), s.startupErrors());
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
        Files.writeString(init, "vim.g.before = 1\nerror('boom from init')\nvim.g.after = 1\n");
        try (NeovimSession s = start(List.of("-u", init.toString()))) {
            assertTrue(s.startupErrors().contains("boom from init"), s.startupErrors());
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

    @Test
    void motionsAndLineDeletionWork() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("hello", "world", "from", "neovim4eclipse"));
            s.input("g");
            s.input("g");
            s.input("j");
            CursorPos pos = s.getCursor();
            assertEquals(2, pos.line1Based());

            s.input("d");
            s.input("d");
            assertEquals(List.of("hello", "from", "neovim4eclipse"), s.getBufferLines());
        }
    }

    @Test
    void linewisePasteIsDoneByNeovim() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b"));
            s.input("y");
            s.input("y");
            s.input("p");
            assertEquals(List.of("a", "a", "b"), s.getBufferLines());
            s.input("P");
            assertEquals(List.of("a", "a", "a", "b"), s.getBufferLines());
        }
    }

    @Test
    void countedPasteAndDotRepeatAreDoneByNeovim() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b"));
            s.input("y");
            s.input("y");
            s.input("2");
            s.input("p");
            assertEquals(List.of("a", "a", "a", "b"), s.getBufferLines());
            s.input(".");
            assertEquals(List.of("a", "a", "a", "a", "a", "b"), s.getBufferLines());
        }
    }

    @Test
    void charwisePasteIsDoneByNeovim() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abc"));
            s.input("y");
            s.input("l"); // yl: yank one character
            s.input("$");
            s.input("p");
            assertEquals(List.of("abca"), s.getBufferLines());
        }
    }

    @Test
    void ctrlOAndCtrlIMoveThroughTheJumpList() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("1", "2", "3", "4", "5"));
            s.input("G"); // a jump: line 1 goes on the jump list
            s.input("<C-o>");
            assertEquals(1, s.getCursor().line1Based());
            s.input("<C-i>");
            assertEquals(5, s.getCursor().line1Based());
        }
    }

    @Test
    void undoRestoresADeletedLine() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b", "c"));
            s.input("j");
            s.input("d");
            s.input("d");
            assertEquals(List.of("a", "c"), s.getBufferLines());
            s.input("u");
            assertEquals(List.of("a", "b", "c"), s.getBufferLines());
        }
    }

    /**
     * Regression: after f/F/t/r or the first g of gg, Neovim waits for another character and
     * defers most API calls. Polling state then used to cost the full 5 s RPC timeout per command.
     */
    @Test
    void pendingCommandsDoNotStallTheInputThread() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("hello world", "second line", "third line"));
            long start = System.nanoTime();
            s.input("f");
            s.input("o"); // fo: cursor to the first 'o'
            s.input("G");
            s.input("g");
            s.input("g");
            s.input("F");
            s.input("o");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 2000, "pending commands took " + elapsedMs + " ms");
            assertEquals(1, s.getCursor().line1Based());
        }
    }

    // ---- status bar parts ----

    @Test
    void statusInfoReportsPendingCommandMessageAndStatusline() throws Exception {
        try (NeovimSession s = start()) {
            s.setStatusItems(java.util.EnumSet.allOf(io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem.class));
            s.setBufferLines(List.of("one", "two"));
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);

            keys(s, "2", "d");
            assertTrue(seen.stream().anyMatch(i -> i.cmd().contains("2d")), "pending command: " + seen);
            keys(s, "d");

            keys(s, "/", "n", "o", "p", "e", "<CR>"); // a search that fails: E486
            assertTrue(seen.stream().anyMatch(i -> i.message().contains("E486")), "error message: " + seen);
            assertTrue(seen.stream().anyMatch(i -> !i.statusline().isBlank()), "statusline: " + seen);
        }
    }

    @Test
    void waitingForACharacterShowsTheKeyInTheCommandArea() throws Exception {
        try (NeovimSession s = start()) {
            s.setStatusItems(java.util.EnumSet.of(io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem.CMD));
            s.setBufferLines(List.of("abc"));
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);
            keys(s, "2", "f");
            assertTrue(seen.stream().anyMatch(i -> i.cmd().equals("2f")), "pending 2f: " + seen);
            keys(s, "c");
            assertEquals("", seen.get(seen.size() - 1).cmd(), "the command completed");
        }
    }

    /** The status bar parts are only read when shown; messages always are, for the message line. */
    @Test
    void nothingOptionalIsEvaluatedWhenOnlyTheModeIsShown() throws Exception {
        try (NeovimSession s = start()) {
            s.setStatusItems(java.util.EnumSet.of(io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem.MODE));
            s.setBufferLines(List.of("one"));
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);
            keys(s, "2", "d", "d", "/", "n", "o", "<CR>");
            assertTrue(seen.stream().allMatch(i -> i.statusline().isEmpty() && i.cmd().isEmpty()),
                    "unrequested parts must stay empty: " + seen);
            assertTrue(seen.stream().anyMatch(i -> i.error() && i.message().contains("E486")), "the error: " + seen);
        }
    }

    // ---- keys handed to the IDE, command output ----

    @Test
    void ideKeysAreHandedToTheIdeWithTheirCount() throws Exception {
        try (NeovimSession s = start()) {
            List<IdeAction> actions = new CopyOnWriteArrayList<>();
            s.addIdeActionListener(actions::add);
            s.setBufferLines(List.of("a", "b"));
            keys(s, "g", "t");
            keys(s, "3", "g", "T");
            keys(s, "g", "d");
            keys(s, "K");
            waitFor(() -> actions.size() == 4, "four IDE actions");
            assertEquals(List.of(new IdeAction(IdeAction.NEXT_TAB, 0), new IdeAction(IdeAction.PREVIOUS_TAB, 3),
                    new IdeAction(IdeAction.DEFINITION, 0), new IdeAction(IdeAction.HOVER, 0)), actions);
            keys(s, "g", "g", "j", "d", "d"); // the g prefix still works for Neovim's own commands
            assertEquals(List.of("a"), s.getBufferLines());
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

    @Test
    void ideKeysCanBeLeftToNeovim() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        for (String value : List.of("0", "v:false")) {
            try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean", "--cmd", "let g:neovim4eclipse_ide_keys = " + value))) {
                List<IdeAction> actions = new CopyOnWriteArrayList<>();
                s.addIdeActionListener(actions::add);
                keys(s, "g", "t"); // Neovim's own gt: there is only one tab page, so nothing happens
                s.getBufferLines(); // a round trip: any notification sent before it has arrived
                assertTrue(actions.isEmpty(), "no IDE action expected with " + value + ": " + actions);
            }
        }
    }

    @Test
    void typedCommandsReportWhatTheyPrint() throws Exception {
        try (NeovimSession s = start()) {
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.commandAsync("echo 'hello from' 'neovim'", outputs::add);
            s.commandAsync("set tabstop?", outputs::add);
            s.commandAsync("nosuchcommand", outputs::add); // an error: shown in the status bar only
            s.commandAsync("let g:x = 1", outputs::add); // prints nothing
            s.commandAsync("echo 'done'", outputs::add);
            waitFor(() -> outputs.contains("done"), "the last command's output");
            assertEquals(List.of("hello from neovim", "  tabstop=8", "done"), outputs);
            assertEquals("1", String.valueOf(s.lua("return vim.g.x")));
            assertEquals("silent echo 'done'", String.valueOf(s.lua("return vim.fn.histget(':', -1)")),
                    "the command is typed, so it is in the history");
        }
    }

    /**
     * {@code :%s} prints "N substitutions on M lines", which the message line shows ({@code v:statusmsg}):
     * no popup repeats it. The {@code :echo} after it is real output and is passed on, so the
     * substitution's turn has passed when the test looks.
     */
    @Test
    void aSubstitutionsMessageIsNotOutput() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("alpha", "data"));
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.commandAsync("%s/a/b/g", outputs::add);
            s.commandAsync("echo 'after'", outputs::add);
            waitFor(() -> !outputs.isEmpty(), "the :echo output");
            assertEquals(List.of("after"), outputs);
            assertEquals(List.of("blphb", "dbtb"), s.getBufferLines());
        }
    }

    @Test
    void shellCommandOutputIsShownWithoutTheEchoedCommand() throws Exception {
        try (NeovimSession s = start()) {
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.commandAsync("!echo shellout", outputs::add);
            waitFor(() -> !outputs.isEmpty(), "the shell command's output");
            assertEquals(List.of("shellout"), outputs);
        }
    }

    /**
     * A shell command that prints several lines ({@code :!dir} on Windows, {@code :!ls} elsewhere)
     * shows one line per entry: no {@code \r} left from Windows' {@code \r\n}, which the popup
     * would show as stray {@code ^M} characters or double spacing.
     */
    @Test
    void multiLineShellCommandOutputHasNoCarriageReturns() throws Exception {
        try (NeovimSession s = start()) {
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.commandAsync(PlatformUtil.isWindows() ? "!dir" : "!ls -a", outputs::add);
            waitFor(() -> !outputs.isEmpty(), "the shell command's output");
            String output = outputs.get(0);
            assertTrue(output.contains("\n"), "several lines: " + output);
            assertFalse(output.contains("\r"), "no carriage return: " + output.replace("\r", "\\r"));
        }
    }

    @Test
    void aTypedRedirKeepsRedirecting() throws Exception {
        try (NeovimSession s = start()) {
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.commandAsync("redir => g:mine", outputs::add);
            s.commandAsync("echo 'kept'", outputs::add); // not captured: it would take the redirection over
            s.commandAsync("redir END", outputs::add);
            s.commandAsync("echo 'after'", outputs::add); // captured again
            waitFor(() -> outputs.contains("after"), "the output after redir END");
            assertEquals(List.of("after"), outputs);
            assertTrue(String.valueOf(s.lua("return vim.g.mine")).contains("kept"),
                    "the user's :redir must have collected the echo");
        }
    }

    /** As above, for the display-only commands the command line runs without typing ({@code :echo}, {@code :ls}). */
    @Test
    void aTypedRedirAlsoCollectsDisplayOnlyCommands() throws Exception {
        try (NeovimSession s = start()) {
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.commandAsync("redir => g:mine", outputs::add);
            s.captureOutputAsync("echo 'kept'", outputs::add); // no popup: the user's :redir gets it
            s.commandAsync("redir END", outputs::add);
            s.captureOutputAsync("echo 'after'", outputs::add); // shown again
            waitFor(() -> outputs.contains("after"), "the output after redir END");
            assertEquals(List.of("after"), outputs);
            assertTrue(String.valueOf(s.lua("return vim.g.mine")).contains("kept"),
                    "the user's :redir must have collected the echo");
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

    // ---- macros ----

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
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
            Thread.sleep(200); // let any stray notifications arrive
            assertEquals(1, changes.size(), "macro playback must publish a single full-buffer change: " + changes);
            assertEquals(-1, changes.get(0).lastLine());
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
     * The user's config sees {@code vim.g.neovim4eclipse} while it runs, before the plug-in's own
     * setup, and the variable is still set afterwards. The config here only records what it saw.
     */
    @Test
    void theConfigCanTellItRunsInsideEclipse() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
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
     * ({@code docs/design/RPC_CLIENT.md}, step 7): the client info, and a terminal-like UI of 120 x 40 with
     * no 24-bit color and no {@code ext_*} features. An empty Lua table has no type, so empty
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
                assertEquals("ext_cmdline".equals(option), ui.get(option), option); // see TERMINAL_UI
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

    /**
     * The other side of {@link #aNormalCloseLogsNoWarning}: a Neovim that is killed (a crash, or
     * ending it in the Task Manager) is still reported as a warning, which reaches the Error Log.
     * The process is found as a child of this JVM, the only Neovim this test started.
     */
    @Test
    void aKilledNeovimIsLoggedAsAWarning() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            try (NeovimSession s = start()) {
                s.command("echo 1");
                List<ProcessHandle> nvims = ProcessHandle.current().children()
                        .filter(p -> p.info().command().map(c -> c.toLowerCase().contains("nvim")).orElse(false))
                        .toList();
                assertEquals(1, nvims.size(), "this test's Neovim: " + nvims);
                nvims.get(0).destroyForcibly();
                nvims.get(0).onExit().get(5, TimeUnit.SECONDS);
                waitFor(() -> !s.isAlive(), "the session to notice");
                long deadline = System.nanoTime() + 2_000_000_000L; // the reader thread logs on its own
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
            long deadline = System.nanoTime() + 2_000_000_000L; // the stderr thread logs on its own
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

    /** Simulates Eclipse ending an insert in which it typed {@code typed} at the cursor of the current line. */
    private static void finishEclipseInsert(NeovimSession s, String before, String after, int base, int caret,
            List<String> documentAfter) throws Exception {
        s.setLeaveInsertPush(() -> {
            try {
                s.setBufferLines(documentAfter); // what BufferSynchronizer.pushFullDocumentToNeovim does
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        s.leaveInsertModeAsync(InsertReplay.plan(before, after, base, caret));
        long deadline = System.currentTimeMillis() + 3000;
        while ((s.isTypingMode() || !s.getBufferLines().equals(documentAfter))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
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
            assertTrue(!last.toEndOfLine());
        }
    }

    @Test
    void dollarMakesTheBlockExtendToEndOfLine() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "ef", "ijklmn"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            keys(s, "g", "g", "<C-v>", "j", "$");
            assertTrue(seen.get(seen.size() - 1).toEndOfLine());
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
            long deadline = System.currentTimeMillis() + 2000;
            while (changes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(!changes.isEmpty(), "no buffer change notification received");
        }
    }

    @Test
    void searchMovesTheCursor() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one", "two", "three"));
            s.search("three", false);
            long deadline = System.currentTimeMillis() + 2000;
            while (s.getLastCursor().line1Based() != 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(3, s.getLastCursor().line1Based());
        }
    }

    @Test
    void closeIsIdempotentAndStopsTheProcess() throws Exception {
        NeovimSession s = start();
        s.close();
        s.close();
        assertTrue(!s.isAlive());
    }
}

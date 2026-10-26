// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

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
     * A typo in init.lua once made Neovim wait at a "Press ENTER" prompt Eclipse does not show, and
     * the start timed out. With ext_messages there is no prompt: Neovim starts (without that
     * init.lua: a syntax error stops all of it), and the error is reported.
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

    /** The status bar parts are only shown when chosen; messages always reach the message line. */
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

    /**
     * Neovim's own {@code gd} with nothing under the cursor fails as it does when typed: the error
     * ({@code E349: No identifier under cursor}) reaches the message line as an error, and Neovim
     * keeps it in {@code :messages}, because the script echoes it with {@code err = true}.
     */
    @Test
    void anErrorOfNeovimsOwnKeyIsShownAsAnError() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of(""));
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);
            s.normalAsync("gd");
            waitFor(() -> seen.stream().anyMatch(i -> i.error() && i.message().equals("E349: No identifier under cursor")),
                    "E349 as an error in the message line, without the \"Vim:\" prefix pcall adds");
            String history = (String) s.lua("return vim.api.nvim_exec2('messages', { output = true }).output");
            assertTrue(history.contains("E349: No identifier under cursor"), "kept in :messages: " + history);
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

    /**
     * {@code ZZ} and {@code ZQ} are Eclipse's save-and-close and close, mapped in Neovim, so Neovim
     * reads them: {@code fZ} takes the {@code Z} as its argument, and only a whole {@code ZZ} or
     * {@code ZQ} reaches the IDE. They stay mapped with {@code neovim4eclipse_ide_keys = 0}: Neovim's
     * own {@code ZZ} would quit the embedded Neovim.
     */
    @Test
    void zzAndZqAreHandedToTheIdeEvenWithIdeKeysOff() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean", "--cmd", "let g:neovim4eclipse_ide_keys = 0"))) {
            List<IdeAction> actions = new CopyOnWriteArrayList<>();
            s.addIdeActionListener(actions::add);
            s.setBufferLines(List.of("aZb"));
            keys(s, "f", "Z", "x");
            assertEquals(List.of("ab"), s.getBufferLines(), "fZ found the Z, x deleted it");
            assertTrue(actions.isEmpty(), "fZ is no file command: " + actions);
            keys(s, "Z", "Z");
            keys(s, "Z", "Q");
            waitFor(() -> actions.size() == 2, "two IDE actions");
            assertEquals(List.of(new IdeAction(IdeAction.SAVE_AND_CLOSE, 0), new IdeAction(IdeAction.CLOSE, 0)), actions);
            assertTrue(s.isAlive(), "Neovim did not quit");
        }
    }

    // ---- commands Eclipse carries out (LuaScripts.HOST_COMMANDS) ----

    /** {@code expression}'s value, asked through the session's queue (so after the keys before it). */
    private static String eval(NeovimSession s, String expression) throws Exception {
        java.util.concurrent.CompletableFuture<String> value = new java.util.concurrent.CompletableFuture<>();
        s.evalAsync(expression, value::complete);
        return value.get(10, TimeUnit.SECONDS);
    }

    /**
     * A command Eclipse carries out reaches it parsed by Neovim, whoever ran the line: typed, a
     * mapping, a macro, {@code @:}. Neovim expands abbreviations ({@code :wa} is {@code wall}), and
     * the history keeps the line as typed.
     */
    @Test
    void hostCommandsReachEclipseFromTypedLinesMappingsMacrosAndAtColon() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean", "--cmd", "nnoremap <Space>q :q<CR>"))) {
            List<HostCommand> commands = new CopyOnWriteArrayList<>();
            s.addHostCommandListener(commands::add);
            // Set here, not with --cmd "let @a = ...": Windows drops the double quotes of a process
            // argument, and register a then held no command (the macro step timed out there only).
            s.lua("vim.fn.setreg('a', ':tabn 3' .. string.char(13))");

            keys(s, ":", "wa", "<CR>");
            waitFor(() -> commands.size() == 1, ":wa");
            assertEquals(HostCommand.Kind.WRITE_ALL, commands.get(0).kind());
            assertEquals("wa", eval(s, "histget(':', -1)"), "the history has the line as typed");

            keys(s, "<Space>", "q");
            waitFor(() -> commands.size() == 2, "the mapping's :q");
            assertEquals(HostCommand.Kind.QUIT, commands.get(1).kind());

            keys(s, "@", "a");
            waitFor(() -> commands.size() == 3, "the macro's :tabn 3");
            assertEquals(new HostCommand(HostCommand.Kind.TAB_NEXT, false, List.of("3"), false), commands.get(2));

            // @: repeats the last line with a typed character in it (:help quote_:), here :wa.
            keys(s, "@", ":");
            waitFor(() -> commands.size() == 4, "@: repeating :wa");
            assertEquals(commands.get(0), commands.get(3));

            keys(s, ":", "q!", "<CR>");
            waitFor(() -> commands.size() == 5, ":q!");
            assertTrue(commands.get(4).force());
            assertTrue(s.isAlive(), "Neovim did not quit");
        }
    }

    /**
     * Arguments as Neovim splits them ({@code :e my\ file.txt}), a count in front of a tab command,
     * what follows {@code |} still runs, and a command with a range or arguments it does not take
     * stays Neovim's.
     */
    @Test
    void hostCommandsKeepTheirArgumentsAndLeaveTheRestToNeovim() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("1", "2", "3"));
            List<HostCommand> commands = new CopyOnWriteArrayList<>();
            s.addHostCommandListener(commands::add);

            keys(s, ":", "e my\\ file.txt", "<CR>");
            keys(s, ":", "2tabn", "<CR>");
            keys(s, ":", "Eclipse! quit | let g:after = 1", "<CR>");
            waitFor(() -> commands.size() == 3, "three commands");
            assertEquals(List.of("my file.txt"), commands.get(0).args());
            assertEquals(List.of("2"), commands.get(1).args(), ":2tabn is :tabn 2");
            assertTrue(commands.get(2).force());
            assertEquals("1", eval(s, "get(g:, 'after', 0)"), "the command after | ran");

            keys(s, ":", "q foo", "<CR>");
            keys(s, ":", "1,2tabn", "<CR>");
            keys(s, ":", "Eclipse nope", "<CR>");
            assertEquals("ok", eval(s, "'ok'")); // the keys before it are done
            assertEquals(3, commands.size(), "Neovim's, not Eclipse's: " + commands);
            assertTrue(s.isAlive());
        }
    }

    // ---- messages (ext_messages): the message line and the output popup ----

    /** What a session shows: the message line's messages and the popup's output, in order. */
    private static final class Shown {
        final List<StatusInfo> lines = new CopyOnWriteArrayList<>();
        final List<CommandOutput> outputs = new CopyOnWriteArrayList<>();

        Shown(NeovimSession s) {
            s.addStatusListener(info -> {
                if (!info.message().isEmpty()) {
                    lines.add(info);
                }
            });
            s.addOutputListener(outputs::add);
        }

        boolean line(String message, boolean error) {
            return lines.stream().anyMatch(i -> i.message().equals(message) && i.error() == error);
        }
    }

    /**
     * A typed command's one-line output and its errors go to the message line, a listing to the
     * popup with its command line as title; a command that prints nothing shows nothing. The
     * command is typed as written, so the history has it without {@code :silent}.
     */
    @Test
    void typedCommandsShowWhatTheyPrint() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("echo 'hello from' 'neovim'");
            s.commandAsync("set tabstop?");
            s.commandAsync("nosuchcommand");
            s.commandAsync("let g:x = 1");
            s.commandAsync("echo 'done'");
            waitFor(() -> shown.line("done", false), "the last command's message");
            assertTrue(shown.line("hello from neovim", false), "one-line echo: " + shown.lines);
            assertTrue(shown.line("E492: Not an editor command: nosuchcommand", true), "the error: " + shown.lines);
            assertEquals(List.of(new CommandOutput(":set tabstop?", "  tabstop=8")), shown.outputs);
            assertEquals("1", String.valueOf(s.lua("return vim.g.x")));
            assertEquals("echo 'done'", String.valueOf(s.lua("return vim.fn.histget(':', -1)")),
                    "the command is typed, so it is in the history as written");
        }
    }

    /**
     * Listings are typed like every other command, so {@code @:} repeats them. ({@code :ls!}: the
     * session's scratch buffer is unlisted, so a plain {@code :ls} would print nothing.)
     */
    @Test
    void aListingOpensThePopupAndIsInTheHistory() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("ls!");
            waitFor(() -> !shown.outputs.isEmpty(), "the listing");
            assertEquals(":ls!", shown.outputs.get(0).title());
            assertTrue(shown.outputs.get(0).text().contains("line 1"), "a buffer list: " + shown.outputs);
            assertEquals("ls!", String.valueOf(s.lua("return vim.fn.histget(':', -1)")));
        }
    }

    @Test
    void aSubstitutionsCountIsShownOnTheMessageLine() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("alpha", "data"));
            Shown shown = new Shown(s);
            s.commandAsync("%s/a/b/g");
            waitFor(() -> shown.line("4 substitutions on 2 lines", false), "the count");
            assertEquals(List.of("blphb", "dbtb"), s.getBufferLines());
            assertTrue(shown.outputs.isEmpty(), "no popup: " + shown.outputs);
        }
    }

    /** A message of two lines is shown as Vim shows it before a "Press ENTER": in the popup. */
    @Test
    void aMessageOfSeveralLinesOpensThePopup() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("echo \"one\\ntwo\"");
            waitFor(() -> !shown.outputs.isEmpty(), "the popup");
            assertEquals(new CommandOutput(":echo \"one\\ntwo\"", "one\ntwo"), shown.outputs.get(0));
        }
    }

    @Test
    void shellCommandOutputIsShownWithoutTheEchoedCommand() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("!echo shellout");
            waitFor(() -> !shown.outputs.isEmpty(), "the shell command's output");
            assertEquals(new CommandOutput(":!echo shellout", "shellout"), shown.outputs.get(shown.outputs.size() - 1));
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
            Shown shown = new Shown(s);
            s.commandAsync(PlatformUtil.isWindows() ? "!dir" : "!ls -a");
            waitFor(() -> !shown.outputs.isEmpty(), "the shell command's output");
            String output = shown.outputs.get(shown.outputs.size() - 1).text();
            assertTrue(output.contains("\n"), "several lines: " + output);
            assertFalse(output.contains("\r"), "no carriage return: " + output.replace("\r", "\\r"));
        }
    }

    /**
     * Long output leaves Neovim ready for the next key: with {@code ext_messages} there is no
     * "Press ENTER" or "-- More --" prompt to wait at (experiment X2), so nothing dismisses one.
     */
    @Test
    void longOutputLeavesNoPrompt() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("echo join(range(300), \"\\n\")");
            waitFor(() -> !shown.outputs.isEmpty(), "the output");
            assertEquals(300, shown.outputs.get(0).text().split("\n").length);
            String mode = String.valueOf(s.lua("return vim.api.nvim_get_mode().mode"));
            assertEquals("n", mode, "no hit-enter (r) or more (rm) prompt");
        }
    }

    /** The plug-in no longer redirects anything itself, so the user's {@code :redir} simply works. */
    @Test
    void aTypedRedirCollectsWhatIsPrinted() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("redir => g:mine");
            s.commandAsync("echo 'kept'");
            s.commandAsync("redir END");
            waitFor(() -> shown.line("kept", false), "the echo, shown as well, as in Vim");
            assertTrue(String.valueOf(s.lua("return vim.g.mine")).contains("kept"),
                    "the user's :redir must have collected the echo");
        }
    }

    /** {@code :messages} arrives as its own event (msg_history_show) and opens the popup. */
    @Test
    void theMessageHistoryOpensThePopup() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.commandAsync("echomsg 'remember me'");
            s.commandAsync("messages");
            waitFor(() -> !shown.outputs.isEmpty(), "the history");
            assertEquals(":messages", shown.outputs.get(0).title());
            assertTrue(shown.outputs.get(0).text().contains("remember me"), "the history: " + shown.outputs);
        }
    }

    /**
     * A message no command waits for, from a timer, still reaches the message line: the case of the
     * diagnostics plan's jump message, which Neovim echoes later, with vim.schedule.
     */
    @Test
    void aLaterMessageFromATimerIsShown() throws Exception {
        try (NeovimSession s = start()) {
            Shown shown = new Shown(s);
            s.lua("vim.defer_fn(function() vim.api.nvim_echo({ { 'from a timer' } }, false, {}) end, 50)");
            waitFor(() -> shown.line("from a timer", false), "the timer's message");
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

    @Test
    void aDoubleClickSelectsAWordAndATripleClickTheLine() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("alpha beta gamma", "second"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            click(s, 1, 7, 2, "");
            assertEquals("v", s.getMode());
            assertEquals(6, lastSelection(seen).startCol0Based());
            assertEquals(9, lastSelection(seen).endCol0Based(), "beta");
            keys(s, "<Esc>");
            Thread.sleep(600); // past 'mousetime', so the next click counts from one again
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
                boolean on = List.of("ext_cmdline", "ext_messages", "ext_linegrid", "ext_popupmenu").contains(option);
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
            assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 2), new BlockSelection.ByteRange(2, 1, 2)),
                    last.block(), "Neovim's getregionpos: bytes 1-2 of each line");
        }
    }

    @Test
    void dollarMakesTheBlockExtendToEndOfLine() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abcd", "ef", "ijklmn"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            keys(s, "g", "g", "<C-v>", "j", "j", "$");
            assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 4), new BlockSelection.ByteRange(2, 1, 2),
                    new BlockSelection.ByteRange(3, 1, 6)), seen.get(seen.size() - 1).block(), "each line to its end");
        }
    }

    /** Tabs and wide characters: the cell arithmetic is Neovim's (experiment X5). */
    @Test
    void aBlockOverTabsAndWideCharactersComesFromNeovim() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a\tbcdef", "abcdefghij", "aあいうb"));
            List<VisualSelection> seen = new CopyOnWriteArrayList<>();
            s.addSelectionListener(seen::add);
            keys(s, "g", "g", "<C-v>", "j", "j", "l"); // l onto あ (cells 1-2): the block is cells 0-2
            // Line 1: a and the tab (cells 1-7, only partly inside); line 2: abc; line 3: a and あ.
            assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 2), new BlockSelection.ByteRange(2, 1, 3),
                    new BlockSelection.ByteRange(3, 1, 4)), seen.get(seen.size() - 1).block());
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

    // ---- Neovim's own command line, as the : box shows it (A2) ----

    /** {@link NeovimSession#lua}, or {@code null} if it failed: for {@link #waitFor} conditions. */
    private static Object luaOrNull(NeovimSession s, String code) {
        try {
            return s.lua(code);
        } catch (Exception e) {
            return null;
        }
    }

    /** The views the command-line listener heard, in order. */
    private static List<CmdlineState.View> cmdlineViews(NeovimSession s) {
        List<CmdlineState.View> views = new CopyOnWriteArrayList<>();
        s.addCmdlineListener(views::add);
        return views;
    }

    private static CmdlineState.View last(List<CmdlineState.View> views) {
        return views.isEmpty() ? CmdlineState.View.CLOSED : views.get(views.size() - 1);
    }

    @Test
    void typingOnTheCommandLineIsReportedWithItsCursor() throws Exception {
        try (NeovimSession s = start()) {
            List<CmdlineState.View> views = cmdlineViews(s);
            keys(s, ":", "e", "c", "h", "o", "<Left>");
            waitFor(() -> last(views).caret() == 4, "the caret after <Left>: " + views);
            assertEquals(":echo", last(views).text());
            keys(s, "<Esc>");
            waitFor(() -> !last(views).open(), "the line closed: " + views);
        }
    }

    /** Neovim's own history: Up recalls the last command, typed through the box or anywhere. */
    @Test
    void upRecallsNeovimsHistory() throws Exception {
        try (NeovimSession s = start()) {
            List<CmdlineState.View> views = cmdlineViews(s);
            keys(s, ":", "l", "e", "t", " ", "g", ":", "x", "=", "1", "<CR>");
            keys(s, ":", "<Up>");
            waitFor(() -> last(views).text().equals(":let g:x=1"), "the history entry: " + views);
        }
    }

    /** Tab is Neovim's completion; the candidates come as the menu of the command line. */
    @Test
    void tabCompletesAndListsTheCandidates() throws Exception {
        try (NeovimSession s = start()) {
            List<CmdlineState.View> views = cmdlineViews(s);
            keys(s, ":", "c", "o", "l", "o", " ", "<Tab>");
            waitFor(() -> last(views).candidates().size() > 1, "colorscheme candidates: " + views);
            assertTrue(last(views).candidates().contains("default"), last(views).candidates().toString());
            assertTrue(last(views).text().startsWith(":colo "), last(views).text());
        }
    }

    /** A line a mapping opens is shown like any other: it is Neovim's line. */
    @Test
    void aLineOpenedByAMappingIsShown() throws Exception {
        try (NeovimSession s = start()) {
            s.command("nnoremap ; :%s/");
            List<CmdlineState.View> views = cmdlineViews(s);
            keys(s, ";");
            waitFor(() -> last(views).text().equals(":%s/"), "the mapping's line: " + views);
        }
    }

    /** {@code :} in visual mode: Neovim puts the range in itself. */
    @Test
    void colonInVisualModeGetsNeovimsRange() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b"));
            List<CmdlineState.View> views = cmdlineViews(s);
            keys(s, "V", "j", ":");
            waitFor(() -> last(views).text().equals(":'<,'>"), "the visual range: " + views);
        }
    }

    @Test
    void aPromptIsShownWithItsText() throws Exception {
        try (NeovimSession s = start()) {
            List<CmdlineState.View> views = cmdlineViews(s);
            s.inputAsync(":call input('Name: ', 'def')<CR>");
            waitFor(() -> last(views).text().equals("Name: def"), "the prompt: " + views);
            assertEquals("", last(views).firstc());
            keys(s, "<Esc>");
        }
    }

    /** A paste goes into the line as text: {@code <} stays a character. */
    @Test
    void aPasteGoesIntoTheLine() throws Exception {
        try (NeovimSession s = start()) {
            List<CmdlineState.View> views = cmdlineViews(s);
            keys(s, ":");
            s.pasteAsync("echo \"<x>\"");
            waitFor(() -> last(views).text().equals(":echo \"<x>\""), "the pasted text: " + views);
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

    /** The plug-in's own typed lines ({@code commandAsync}) do not open the box. */
    @Test
    void thePluginsOwnLinesAreNotReported() throws Exception {
        try (NeovimSession s = start()) {
            List<CmdlineState.View> views = cmdlineViews(s);
            s.commandAsync("let g:own = 1");
            waitFor(() -> Long.valueOf(1).equals(luaOrNull(s, "return vim.g.own")), "the command ran");
            keys(s, ":");
            waitFor(() -> last(views).open(), "the user's line");
            assertEquals(":", views.get(0).text(), "the first view is the user's line, not :let: " + views);
        }
    }

    /** {@code q:} would open a window Eclipse cannot show: it closes at once, with a message. */
    @Test
    void theCommandLineWindowClosesWithAMessage() throws Exception {
        try (NeovimSession s = start()) {
            List<StatusInfo> seen = new CopyOnWriteArrayList<>();
            s.addStatusListener(seen::add);
            keys(s, "q", ":");
            waitFor(() -> seen.stream().anyMatch(i -> i.message().startsWith("The command-line window is not available")),
                    "the message: " + seen);
            assertEquals("", String.valueOf(s.lua("return vim.fn.getcmdwintype()")));
            assertEquals(1L, ((Number) s.lua("return #vim.api.nvim_list_wins()")).longValue());
        }
    }

    /** {@code :e} completion and {@code :!} start in the editor's project (decision D5). */
    @Test
    void theWindowFolderIsSet(@TempDir Path dir) throws Exception {
        try (NeovimSession s = start()) {
            s.setWindowFolderAsync(dir.toString());
            Path real = dir.toRealPath(); // macOS: /var is a link to /private/var, and getcwd() resolves it
            waitFor(() -> List.of(dir.toString(), real.toString()).contains(String.valueOf(luaOrNull(s, "return vim.fn.getcwd(0)"))),
                    "the window's folder");
            assertEquals(1L, ((Number) s.lua("return vim.fn.haslocaldir(0)")).longValue(), "window-local (:lcd)");
        }
    }

    /**
     * Before Eclipse deletes a project, Neovim leaves its folder: every working folder inside it (the
     * global one, a tab page's, a window's) moves to the folder's parent, and a window working
     * elsewhere keeps its folder. Windows refuses to delete a folder Neovim's process works in.
     */
    @Test
    void leavingAFolderMovesEveryWorkingFolderInsideItToItsParent(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath(); // macOS: /var is a link to /private/var; getcwd() resolves it
        Path project = Files.createDirectories(root.resolve("app/src"));
        Path other = Files.createDirectories(root.resolve("other"));
        try (NeovimSession s = start()) {
            s.lua("vim.cmd.cd(" + luaString(project) + ")");
            s.lua("vim.cmd.vsplit(); vim.cmd.lcd(" + luaString(other) + "); vim.cmd.wincmd('w'); vim.cmd.lcd("
                    + luaString(project) + ")");
            s.lua("vim.cmd.tabnew(); vim.cmd.tcd(" + luaString(project) + "); vim.cmd.tabprevious()");

            assertEquals(3, s.leaveFolder(root.resolve("app").toString(), Duration.ofSeconds(5)), "global, tab page 2, window 2");

            String parent = root.toString();
            assertEquals(parent, s.lua("return vim.fn.getcwd(-1, -1)"), "the global folder");
            assertEquals(parent, s.lua("return vim.fn.getcwd(-1, 2)"), "tab page 2's folder");
            assertEquals(parent, s.lua("return vim.fn.getcwd()"), "the current window's folder");
            assertEquals(other.toString(), s.lua("return vim.fn.getcwd(1, 1)"), "a window elsewhere keeps its folder");
            assertEquals(1L, ((Number) s.lua("return vim.fn.haslocaldir(1, 1)")).longValue(), "still its own (:lcd)");
            assertEquals(parent, s.lua("return vim.uv.cwd()"), "the process's folder, the one Windows checks");
            assertEquals(0, s.leaveFolder(root.resolve("app").toString(), Duration.ofSeconds(5)), "nothing left inside");
        }
    }

    /**
     * A window whose folder is outside, and gone already (a project deleted before), does not stop
     * the others from moving: setting that folder again would fail with E344.
     */
    @Test
    void aWindowInAFolderThatIsGoneDoesNotStopLeaving(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Path project = Files.createDirectories(root.resolve("app"));
        Path gone = Files.createDirectories(root.resolve("gone"));
        try (NeovimSession s = start()) {
            s.lua("vim.cmd.vsplit(); vim.cmd.lcd(" + luaString(gone) + "); vim.cmd.wincmd('w'); vim.cmd.cd("
                    + luaString(project) + ")");
            Files.delete(gone);

            assertEquals(1, s.leaveFolder(project.toString(), Duration.ofSeconds(5)), "the global folder");
            assertEquals(root.toString(), s.lua("return vim.uv.cwd()"));
        }
    }

    /** {@code path} as a Lua string literal ({@code C:\\x} on Windows keeps its backslashes). */
    private static String luaString(Path path) {
        return "'" + path.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @Test
    void closeIsIdempotentAndStopsTheProcess() throws Exception {
        NeovimSession s = start();
        s.close();
        s.close();
        assertTrue(!s.isAlive());
    }
}

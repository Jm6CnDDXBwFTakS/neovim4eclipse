// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;

/**
 * vim-fugitive in a real editor, at the commit pinned and vetted in
 * {@code releng/test-plugins.properties}, on a git repository the test makes in its project
 * (test.txt committed as "first"). {@code :Gwrite} saves the editor through Eclipse and stages the
 * file; {@code :Gread} puts the staged text in the editor, unsaved; {@code :Git commit -m} commits;
 * {@code :Git blame} and {@code :Git log} open their output as an editor beside this one.
 * {@code :Git commit} without {@code -m} opens git's message file ({@code .git/COMMIT_EDITMSG}) as
 * an editor beside this one, and git commits the message once that editor closes: saved with
 * {@code :wq}, the commit is made; closed unsaved with {@code :q!}, git finds no message and makes
 * none, as in Vim.
 *
 * <p>Why the message editor works. fugitive opens the file in a split of its own with
 * {@code 'bufhidden'} wipe, and waits for that buffer to be deleted. The plug-in's safety net hands
 * the split to Eclipse as an editor and closes Neovim's window, keeping the buffer, which the new
 * editor's attach takes over by its name (core/lua/windows/net.lua, {@code take_file_window}). The
 * buffer ends only when the editor closes.
 *
 * <p>git runs, from the test and from fugitive in the test's Neovim, with an environment of the
 * test's own ({@link #isolation}): no {@code GIT_} variable of the developer's (a {@code GIT_DIR}
 * that a hook exported would point git at the developer's own repository), no system or global
 * config (theirs could change what is committed, or run their programs), and no repository looked
 * for above the test's workspace. So the repository's own config is the whole config: a name and
 * address, no signing, no hooks, no line-ending conversion. Before anything is committed the test
 * checks that git's repository is the project's own. git itself must be on the PATH:
 * without it, as without the plug-in ({@link PinnedPlugins#HOW}), the tests are skipped. fugitive's
 * commands and autocommands are the whole Neovim's, which the UI tests share, so each test ends with
 * <em>Restart Neovim</em> ({@code restartNeovimAfterThisTest}), after closing the editors it opened.
 * The project, its repository with it, goes after each test anyway ({@code EndToEndTestBase}).
 */
class FugitiveEndToEndTest extends EndToEndTestBase {

    /** test.txt as the base test makes it, committed as "first" by {@link #makeRepository}. */
    private static final String COMMITTED = "alpha\nbeta\ngamma\n";

    /** test.txt changed by a test, through Eclipse's document. */
    private static final String CHANGED = "alpha\nBETA\ngamma\n";

    /** The message the message editor gets, typed in Neovim's insert mode. */
    private static final String MESSAGE = "a message typed in Eclipse";

    /**
     * Lua that answers whether a git commit that fugitive started still runs: a job channel of
     * Neovim's whose program's arguments hold {@code commit}. Decided from Neovim's own list of
     * channels, read when asked, not from what git printed.
     */
    private static final String COMMIT_RUNNING = """
            for _, c in ipairs(vim.api.nvim_list_chans()) do
              if c.stream == 'job' and type(c.argv) == 'table' and vim.tbl_contains(c.argv, 'commit') then
                return true
              end
            end
            return false""";

    /**
     * The environment variables git runs with, from the test ({@link #git}) and in the test's
     * Neovim ({@link #isolateNeovimsGit}), on top of the inherited ones minus every {@code GIT_}
     * one: no system config, an empty global config, and no repository looked for above the
     * workspace's folder. Filled by {@link #makeRepository}.
     */
    private final Map<String, String> isolation = new LinkedHashMap<>();

    /** The empty global config git reads ({@link #isolate}), in the workspace's folder. */
    private static final String GLOBAL_CONFIG = "fugitive-test.gitconfig";

    /** The file git's output goes to ({@link #run}), in the workspace's folder. */
    private static final String GIT_OUTPUT = "fugitive-test-git.out";

    /** Whether this test loaded the plug-in, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @BeforeEach
    void loadFugitive() throws Exception {
        Optional<Path> fugitive = PinnedPlugins.find("vim-fugitive");
        assumeTrue(fugitive.isPresent(), "vim-fugitive is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        isolate();
        assumeTrue(gitRuns(), "git is not on the PATH");
        makeRepository();
        loaded = true;
        isolateNeovimsGit();
        // A "Window closed" message an earlier test class left in the shared Neovim would read as
        // this test's (outputEditor checks there is none).
        SessionProbe.lua(session, "vim.cmd('messages clear') return 0");
        SessionProbe.lua(session, PinnedPlugins.loadFugitive(fugitive.get()) + " return 0");
    }

    @AfterEach
    void restartNeovim() {
        try {
            if (!loaded) {
                return; // skipped, or the editor never opened: nothing was loaded
            }
            closeEditorsButTestTxt(); // a message editor left unsaved by a failed test asks nothing
            Path workspace = folder().getParent();
            try {
                Files.deleteIfExists(workspace.resolve(GLOBAL_CONFIG));
                Files.deleteIfExists(workspace.resolve(GIT_OUTPUT));
            } catch (IOException e) {
                throw new AssertionError("the test's own files in " + workspace, e);
            }
            NeovimSession fresh = restartNeovimAfterThisTest();
            assertEquals(true, SessionProbe.lua(fresh, "return vim.g.loaded_fugitive == nil"),
                    "the next test's Neovim never loaded fugitive");
        } finally {
            // Last, once the Neovim that ran fugitive's git is gone; also when the restart failed,
            // or makeRepository left a repository half made (loaded is then still false).
            deleteRepository();
        }
    }

    /**
     * Deletes the project's {@code .git}, deepest first, before the base class deletes the project.
     * Git writes its objects read-only ({@code .git/objects/44/3cb2475d6f...}), and on Windows a
     * read-only file cannot be deleted: Eclipse's project delete then fails on each one, leaves the
     * folder behind, and every later test's project delete fails on it too. So each file is made
     * writable first; on Linux and macOS, where a folder's permissions decide instead, that changes
     * nothing.
     */
    private void deleteRepository() {
        if (project == null) {
            return;
        }
        Path git = folder().resolve(".git");
        if (!Files.exists(git, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(git)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new AssertionError("the test's repository " + git, e);
        }
    }

    /**
     * test.txt changed in Eclipse, then {@code :Gwrite}: the editor is saved (through Eclipse:
     * Neovim writes no file of a document itself) and the file staged; {@code :Git commit -m}
     * commits it.
     */
    @Test
    void gwriteSavesThroughEclipseAndStagesAndGitCommitMCommits() throws Exception {
        changeInEclipse();
        runCommandLine(widget, "Gwrite");
        waitUntil("test.txt saved and staged", TIMEOUT_MS, () -> {
            settle();
            return !editor.isDirty() && CHANGED.equals(onDisk()) && "test.txt".equals(git("diff", "--cached", "--name-only"));
        }, () -> "; dirty " + editor.isDirty() + ", staged " + git("diff", "--cached", "--name-only"));
        runCommandLine(widget, "Git commit -m second");
        waitUntil("the commit made", TIMEOUT_MS, () -> "second".equals(git("log", "-1", "--format=%s")),
                () -> "; log " + git("log", "--oneline"));
        assertEquals("", git("status", "--porcelain", "--", "test.txt"), "test.txt is committed as saved");
    }

    /**
     * test.txt changed in Eclipse, unsaved, then {@code :Gread}: the editor gets the staged text
     * (the committed one: nothing is staged), still unsaved, and the file on disk is as it was.
     */
    @Test
    void greadPutsTheStagedTextInTheEditor() throws Exception {
        changeInEclipse();
        runCommandLine(widget, "Gread");
        waitUntil("the staged text in the editor", TIMEOUT_MS, () -> {
            settle();
            return COMMITTED.equals(document.get());
        }, () -> "; text " + document.get());
        assertEquals(COMMITTED, onDisk(), "the file on disk as it was");
        assertEquals("first", git("log", "-1", "--format=%s"), "nothing committed");
    }

    /**
     * {@code :Git blame}: fugitive's split on its blame output (a temporary file of its own, outside
     * the project) opens as an editor beside this one, with a line for each of test.txt's, each
     * naming the commit's author. Not scroll-bound to test.txt's editor: Eclipse scrolls each
     * editor on its own.
     */
    @Test
    void gitBlameOpensItsOutputAsAnEditor() {
        ITextEditor blame = outputEditor("Git blame", "(Test");
        assertEquals(3, documentOf(blame).get().strip().split("\n").length, "a line for each of test.txt's: "
                + documentOf(blame).get());
        assertEquals(COMMITTED, document.get(), "test.txt's editor as it was");
    }

    /** {@code :Git log}: its output, the one commit, opens as an editor beside this one, the same way. */
    @Test
    void gitLogOpensItsOutputAsAnEditor() {
        ITextEditor log = outputEditor("Git log --oneline", "first");
        assertEquals(git("log", "--oneline"), documentOf(log).get().strip(), "git's own log");
    }

    /**
     * Runs {@code command} and answers the editor it opens in front, once that editor's text holds
     * {@code expected}.
     */
    private ITextEditor outputEditor(String command, String expected) {
        runCommandLine(widget, command);
        waitUntil(":" + command + "'s output in an editor in front", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() instanceof ITextEditor active && active != editor
                    && documentOf(active).get().contains(expected);
        }, () -> "; active " + page().getActiveEditor());
        assertFalse(String.valueOf(SessionProbe.lua(session, "return vim.fn.execute('messages')")).contains("Window closed"),
                "no window of fugitive's was closed instead");
        return (ITextEditor) page().getActiveEditor();
    }

    /**
     * A change staged with {@code :Gwrite}, then {@code :Git commit}: git's message file opens as an
     * editor in front; a message typed there and {@code :wq} save it and close the editor, and git
     * commits with that message.
     */
    @Test
    void theMessageEditorCommitsWhatWqSaved() throws Exception {
        ITextEditor message = openTheMessageEditor();
        typeTheMessage(message);
        runCommandLine(widgetOf(message), "wq");
        waitUntil("the commit made with the message typed", TIMEOUT_MS,
                () -> MESSAGE.equals(git("log", "-1", "--format=%s")), () -> "; log " + git("log", "--oneline"));
        waitUntil("the message editor closed", TIMEOUT_MS, () -> {
            settle();
            return editorNamed("COMMIT_EDITMSG") == null;
        });
        assertEquals("", git("status", "--porcelain", "--", "test.txt"), "test.txt is committed");
    }

    /**
     * The same, but {@code :q!}: the editor closes without saving the message, so git reads the
     * file as it wrote it, finds no message, and commits nothing, as Vim's {@code :q!} aborts a
     * commit. The change stays staged.
     */
    @Test
    void theMessageEditorClosedUnsavedCommitsNothing() throws Exception {
        ITextEditor message = openTheMessageEditor();
        typeTheMessage(message);
        runCommandLine(widgetOf(message), "q!");
        waitUntil("the message editor closed, git's commit ended", TIMEOUT_MS, () -> {
            settle();
            return editorNamed("COMMIT_EDITMSG") == null && Boolean.FALSE.equals(SessionProbe.lua(session, COMMIT_RUNNING));
        });
        assertEquals("first", git("log", "-1", "--format=%s"), "nothing committed");
        assertEquals("test.txt", git("diff", "--cached", "--name-only"), "the change is still staged");
    }

    /**
     * Stages test.txt's change with {@code :Gwrite}, runs {@code :Git commit} and answers the
     * message editor it opens, in front and attached, with git's own text in it (its comment lines,
     * which name the file to be committed).
     */
    private ITextEditor openTheMessageEditor() {
        changeInEclipse();
        runCommandLine(widget, "Gwrite");
        waitUntil("test.txt staged", TIMEOUT_MS, () -> "test.txt".equals(git("diff", "--cached", "--name-only")));
        runCommandLine(widget, "Git commit");
        waitUntil("the message editor in front", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() instanceof ITextEditor active
                    && "COMMIT_EDITMSG".equals(active.getEditorInput().getName());
        }, () -> "; active " + page().getActiveEditor());
        ITextEditor message = (ITextEditor) page().getActiveEditor();
        waitForAttach(message, "COMMIT_EDITMSG");
        waitUntil("git's text in the message editor", TIMEOUT_MS, () -> {
            settle();
            return documentOf(message).get().contains("test.txt");
        }, () -> "; text " + documentOf(message).get());
        assertEquals(true, SessionProbe.lua(session, COMMIT_RUNNING), "git waits for the message");
        return message;
    }

    /** Types {@link #MESSAGE} on the message editor's first line, in insert mode, then Esc. */
    private void typeTheMessage(ITextEditor message) {
        StyledText text = widgetOf(message);
        type(text, "i");
        type(text, MESSAGE);
        escape(text);
        waitUntil("the message typed, insert mode left", TIMEOUT_MS, () -> {
            settle();
            return documentOf(message).get().startsWith(MESSAGE + "\n") && !session.isTypingMode();
        }, () -> "; text " + documentOf(message).get());
        assertFalse(git("log", "-1", "--format=%s").equals(MESSAGE), "no commit before the editor closes");
    }

    /** Changes test.txt's second line in Eclipse's document, unsaved, and waits until Neovim has it. */
    private void changeInEclipse() {
        document.set(CHANGED);
        waitUntil("Neovim has the change", TIMEOUT_MS, () -> {
            settle();
            return "BETA".equals(SessionProbe.lua(session, "return vim.fn.getline(2)"));
        });
    }

    /**
     * Fills {@link #isolation}, with an empty file as the global config, in the workspace's folder
     * (the project's parent, inside the build's {@code target/}), which git then does not look
     * above for a repository.
     */
    private void isolate() throws IOException {
        Path workspace = folder().getParent();
        Path emptyConfig = workspace.resolve(GLOBAL_CONFIG);
        Files.write(emptyConfig, new byte[0]);
        isolation.put("GIT_CONFIG_NOSYSTEM", "1");
        isolation.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
        isolation.put("GIT_CEILING_DIRECTORIES", workspace.toString());
        isolation.put("GIT_TERMINAL_PROMPT", "0");
    }

    /**
     * Gives the test's Neovim, and so fugitive's git, the same environment as {@link #git}: every
     * {@code GIT_} variable removed, then {@link #isolation}. This Neovim is restarted after the
     * test, so the next one starts from the inherited environment again.
     */
    private void isolateNeovimsGit() {
        StringBuilder lua = new StringBuilder("""
                for name in pairs(vim.fn.environ()) do
                  if name:upper():find('^GIT_') then
                    vim.env[name] = nil
                  end
                end
                """);
        isolation.forEach((name, value) -> {
            if (value.contains("]==]")) {
                throw new IllegalArgumentException("cannot be passed to Lua: " + value);
            }
            // A long string takes the value as it is: a Windows path's backslashes stay.
            lua.append("vim.env.").append(name).append(" = [==[").append(value).append("]==]\n");
        });
        assertEquals(isolation.get("GIT_CONFIG_GLOBAL"), SessionProbe.lua(session, lua + "return vim.env.GIT_CONFIG_GLOBAL"),
                "Neovim's git reads the empty global config");
    }

    /** Whether {@code git --version} runs. */
    private boolean gitRuns() {
        try {
            return run("--version").exit() == 0;
        } catch (AssertionError e) {
            return false; // did not start: no git on the PATH
        }
    }

    /**
     * Makes the project's folder a git repository with test.txt committed as "first", once git is
     * known to use the repository in that folder. Its config is the whole config git reads
     * ({@link #isolation}): a name and address for the commits, no signing (a key would be asked
     * for), a hooks folder that does not exist, and no line-ending conversion (Git for Windows
     * would otherwise turn test.txt's line ends, and say so).
     */
    private void makeRepository() {
        gitOrFail("init", "-q");
        String gitDir = gitOrFail("rev-parse", "--absolute-git-dir");
        assertTrue(FilePaths.same(Path.of(gitDir), folder().resolve(".git")),
                "git uses the project's own repository, not " + gitDir);
        gitOrFail("config", "user.name", "Test");
        gitOrFail("config", "user.email", "test@example.org");
        gitOrFail("config", "commit.gpgsign", "false");
        gitOrFail("config", "core.hooksPath", ".git/no-hooks");
        gitOrFail("config", "core.autocrlf", "false");
        gitOrFail("add", "test.txt");
        gitOrFail("commit", "-q", "-m", "first");
    }

    /** Runs git as {@link #git} does, and fails the test unless git succeeded; answers its output. */
    private String gitOrFail(String... args) {
        GitResult result = run(args);
        if (result.exit() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed (" + result.exit() + "): " + result.out());
        }
        return result.out();
    }

    /** Runs git as {@link #run} does and answers its output, whether it succeeded or not. */
    private String git(String... args) {
        return run(args).out();
    }

    /** What git printed (standard output and error, stripped) and how it ended. */
    private record GitResult(int exit, String out) {
    }

    /**
     * Runs git in the project's folder with the test's environment ({@link #isolation}, every
     * inherited {@code GIT_} variable removed), its output into a file in the workspace's folder.
     * Waits at most the test's time: a git that waits for input (it should never) is ended and
     * fails the test instead of holding the run. Into a file, not a pipe, so that the wait itself
     * has the limit: reading a pipe to its end would wait as long as git does.
     */
    private GitResult run(String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Path output = folder().getParent().resolve(GIT_OUTPUT);
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(folder().toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE);
            builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
            builder.environment().putAll(isolation);
            Process process = builder.start();
            process.getOutputStream().close(); // no input: a question git asks ends at once
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("git " + String.join(" ", args) + " did not end");
            }
            return new GitResult(process.exitValue(), Files.readString(output).strip());
        } catch (IOException e) {
            throw new AssertionError("git " + String.join(" ", args) + " did not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** test.txt's text on disk. */
    private String onDisk() {
        try {
            return Files.readString(folder().resolve("test.txt"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Path folder() {
        return project.getLocation().toFile().toPath();
    }
}

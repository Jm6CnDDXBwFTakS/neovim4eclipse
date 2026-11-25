// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds a pinned Neovim plug-in for a test (docs/UPGRADING.md, "The plug-ins the tests run"): a
 * UI test, or an integration test of {@code tests/unit}, which share this class (shared/):
 * the folder {@code java releng/tools/plugins/TestPlugins.java fetch} unpacked it into, under the
 * repository's {@code .tool-cache/test-plugins/}.
 *
 * <p>The repository holds no plug-in, so a test must cope with one that is not there (the tool
 * did not run, or had no network) or that is of another commit than the pin (the pin moved since
 * the last fetch): {@link #find} is empty then, and the test skips or leaves its picture out,
 * saying {@link #HOW}. Running an unpinned copy would test something nobody looked at.
 */
public final class PinnedPlugins {

    /** What to run to get the plug-ins. */
    public static final String HOW = "java releng/tools/plugins/TestPlugins.java fetch";

    private static final String PINS = "releng/test-plugins.properties";

    /**
     * Where the tool unpacks the plug-ins, from the repository's root: a copy of
     * {@code common.ToolCache.TEST_PLUGINS} (this module cannot see the tools' sources;
     * {@code ToolCacheTest} checks the copy). Not under {@code target/}, so {@code ./mvnw clean}
     * leaves the plug-ins in place.
     */
    static final String FOLDER = ".tool-cache/test-plugins";

    private PinnedPlugins() {
    }

    /**
     * The folder of the plug-in pinned as {@code name} (to put on Neovim's {@code 'runtimepath'}),
     * if it is fetched at the pinned commit and archive hash (the marker's whole line, as the
     * tool's own check). The repository's root is the first folder above the
     * working directory that holds the pins.
     */
    public static Optional<Path> find(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(PINS))) {
            root = root.getParent();
        }
        if (root == null) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(root.resolve(PINS));
            Optional<String> commit = value(lines, name + ".commit=");
            Optional<String> sha = value(lines, name + ".sha256=");
            Path folder = root.resolve(FOLDER).resolve(name);
            Path marker = root.resolve(FOLDER).resolve(name + ".pin");
            boolean there = commit.isPresent() && sha.isPresent() && Files.isDirectory(folder) && Files.isRegularFile(marker)
                    && Files.readString(marker).strip().equals(commit.get() + " " + sha.get());
            return there ? Optional.of(folder) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The value of the line that starts with {@code key} (the pins file allows no space around the =). */
    private static Optional<String> value(List<String> lines, String key) {
        return lines.stream().filter(line -> line.startsWith(key)).map(line -> line.substring(key.length()).strip()).findFirst();
    }

    /**
     * Lua that loads flash from {@code folder} with only its jump: its other modes map
     * {@code f}, {@code t}, {@code ;} and {@code ,} and change {@code /}, which would stay for
     * every later test in the same Neovim.
     */
    public static String loadFlash(Path folder) {
        return onRuntimePath(folder)
                + " require('flash').setup({ modes = { char = { enabled = false }, search = { enabled = false } } })";
    }

    /**
     * Lua that loads Telescope and its library plenary from their folders and maps {@code gp} to
     * its {@code current_buffer_fuzzy_find} (pure Lua: no {@code rg} or {@code fd}), as a user's
     * {@code <leader>/} would. Its history is off, so that one test's prompt cannot come back in
     * the next. Undo the mapping with {@code vim.keymap.del('n', 'gp')}.
     */
    public static String loadTelescope(Path plenary, Path telescope) {
        return onRuntimePath(plenary) + " " + onRuntimePath(telescope)
                + " require('telescope').setup({ defaults = { history = false } })"
                + " vim.keymap.set('n', 'gp', function() require('telescope.builtin').current_buffer_fuzzy_find() end)";
    }

    /**
     * Lua (a chunk taking {@code ...}) that answers how many of Telescope's windows are open: its
     * prompt and results, known by their buffers' filetypes ({@code TelescopePrompt},
     * {@code TelescopeResults}). With a word as its one argument, only the results windows that
     * list a line with it, and the prompt windows whose line has it.
     */
    public static final String TELESCOPE_WINDOWS = """
            local text = ...
            local n = 0
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              local b = vim.api.nvim_win_get_buf(w)
              local ft = vim.bo[b].filetype
              if ft:match('^Telescope') and (text == nil
                  or vim.iter(vim.api.nvim_buf_get_lines(b, 0, -1, false)):any(function(l) return l:find(text, 1, true) end)) then
                n = n + 1
              end
            end
            return n
            """;

    /** Lua that closes every window of Telescope's, its insert ended: a test's clean-up. */
    public static final String CLOSE_TELESCOPE = """
            vim.cmd.stopinsert()
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              if vim.api.nvim_win_is_valid(w) and vim.bo[vim.api.nvim_win_get_buf(w)].filetype:match('^Telescope') then
                pcall(vim.api.nvim_win_close, w, true)
              end
            end
            """;

    /** Why an fzf-lua test skipped: fzf is not pinned, so each machine brings its own. */
    public static final String FZF_HOW = "fzf is not on PATH: install it (apt install fzf, brew install fzf,"
            + " winget install fzf), or put the folder of one first on PATH for the run";

    /** Why an fzf-lua test skipped though an fzf is on PATH: the pinned fzf-lua found it too old. */
    public static final String FZF_TOO_OLD = "the fzf on PATH is older than the pinned fzf-lua accepts"
            + " (its message in Neovim names the minimum): install a newer one, or put its folder first on PATH";

    /**
     * The fzf program fzf-lua runs, found in the absolute folders of {@code PATH}, in their order
     * ({@code fzf}, on Windows {@code fzf.exe}), or empty. fzf is not pinned as the plug-ins are:
     * it is a Go program built per system and processor, not a plug-in's source, so the fzf-lua
     * tests use the one the machine has and skip without one, saying {@link #FZF_HOW}. A relative
     * entry ({@code .}, {@code bin}) is passed over, though a shell would take it: it would be
     * read from the test's working folder, inside the repository, where a cloned file named
     * {@code fzf} could stand in. {@code System.getenv("PATH")}, not the map of
     * {@code System.getenv()}: Windows usually spells the variable {@code Path}, which only the
     * former finds (as {@code common/Shell.env} says for the tools). Example:
     * {@code /usr/bin/fzf} after {@code apt install fzf}; for a run with another,
     * {@code PATH=/opt/fzf/bin:$PATH}. fzf-lua puts the path into a shell command unquoted, so a
     * folder with a space or a {@code $} in it spoils the run (fzf-lua then says so).
     */
    public static Optional<Path> fzf() {
        return onPath("fzf");
    }

    /** Why a ripgrep test skipped: rg is a program of the system's, as fzf is. */
    public static final String RG_HOW = "rg (ripgrep) is not on PATH: install it (apt install ripgrep,"
            + " brew install ripgrep, winget install BurntSushi.ripgrep.MSVC), or put the folder of one first on PATH";

    /**
     * The ripgrep program, found as {@link #fzf} finds fzf, or empty: what Telescope's
     * {@code live_grep} runs (given this path). Not pinned either
     * (a Rust program built per system), so a test that needs it skips without one, saying
     * {@link #RG_HOW}. Example: {@code /usr/bin/rg} after {@code apt install ripgrep}.
     */
    public static Optional<Path> rg() {
        return onPath("rg");
    }

    /**
     * The program {@code base} ({@code base.exe} on Windows) in the absolute folders of
     * {@code PATH}, in their order, or empty: the rule {@link #fzf} explains (a relative entry is
     * passed over; {@code System.getenv("PATH")} also finds Windows' {@code Path}).
     */
    private static Optional<Path> onPath(String base) {
        String name = System.getProperty("os.name", "").startsWith("Windows") ? base + ".exe" : base;
        String path = System.getenv("PATH");
        for (String folder : (path == null ? "" : path).split(java.io.File.pathSeparator)) {
            try {
                Path dir = Path.of(folder);
                Path program = dir.resolve(name);
                if (dir.isAbsolute() && Files.isRegularFile(program) && Files.isExecutable(program)) {
                    return Optional.of(program);
                }
            } catch (java.nio.file.InvalidPathException e) {
                // An entry that is not a path on this system (a quoted Windows folder) names no program.
            }
        }
        return Optional.empty();
    }

    /**
     * Lua that answers whether the pinned fzf-lua, once {@link #loadFzfLua} has run, accepts the
     * fzf it was given, by the check fzf-lua makes itself before each list (its
     * {@code config.normalize_opts}, which runs {@code fzf --version}, keeps the version it read
     * in {@code vim.g.fzf_lua_fzf_version} and refuses one older than its minimum, saying so).
     * The minimum stays fzf-lua's: no copy of it here to keep current. The answer is
     * {@value #FZF_IS_ACCEPTED}; {@value #FZF_IS_TOO_OLD} when fzf-lua read a version and refused
     * it, on which a test skips, saying {@link #FZF_TOO_OLD}; else what went wrong (no version
     * read: {@code fzf --version} failed; or fzf-lua's own error, after a pin move), on which a
     * test fails, since that fzf or that fzf-lua is broken rather than missing.
     */
    public static final String FZF_CHECK = """
            vim.g.fzf_lua_fzf_version = nil
            local ok, opts = pcall(require('fzf-lua.config').normalize_opts, {}, 'blines')
            if not ok then return 'fzf-lua failed: ' .. tostring(opts) end
            if opts ~= nil then return 'accepted' end
            if vim.g.fzf_lua_fzf_version ~= nil then return 'too old' end
            return 'fzf-lua could not read the version of the fzf on PATH (fzf --version failed): '
              .. vim.trim(vim.fn.execute('messages'))
            """;

    /** {@link #FZF_CHECK}'s answer when fzf-lua takes the fzf. */
    public static final String FZF_IS_ACCEPTED = "accepted";

    /** {@link #FZF_CHECK}'s answer when fzf-lua read the fzf's version and refused it. */
    public static final String FZF_IS_TOO_OLD = "too old";

    /**
     * Lua that loads fzf-lua from {@code folder}, set to run {@code fzf}, and maps {@code gz} to
     * its {@code blines} (the current buffer's lines: no {@code fd}, {@code rg} or {@code find}),
     * as a user's {@code <leader>/} would. fzf reads its options from the environment
     * ({@code FZF_DEFAULT_OPTS}, {@code FZF_DEFAULT_OPTS_FILE}, {@code FZF_DEFAULT_COMMAND}) and
     * fzf-lua starts it with Neovim's, so they are taken out of Neovim's first, with fzf-lua's
     * own ({@code FZF_LUA_NVIM_BIN}, {@code FZF_LUA_NVIM_RUNTIME}: another Neovim for its helpers;
     * {@code FZF_LUA_SERVER}): the developer's own fzf config must not change what a test sees (CONTRIBUTING.md, "Your Neovim config and
     * the UI tests"). Checked on Linux, where fzf-lua's {@code os.getenv} sees the change; not on
     * Windows, whose C runtime may keep a copy of the environment of its own. Undo the
     * mapping with {@code vim.keymap.del('n', 'gz')}.
     */
    public static String loadFzfLua(Path folder, Path fzf) {
        return onRuntimePath(folder)
                + " vim.env.FZF_DEFAULT_OPTS = nil vim.env.FZF_DEFAULT_OPTS_FILE = nil vim.env.FZF_DEFAULT_COMMAND = nil"
                + " vim.env.FZF_LUA_NVIM_BIN = nil vim.env.FZF_LUA_NVIM_RUNTIME = nil vim.env.FZF_LUA_SERVER = nil"
                + " require('fzf-lua').setup({ fzf_bin = " + longString(fzf) + " })"
                + " vim.keymap.set('n', 'gz', function() require('fzf-lua').blines() end)";
    }

    /**
     * Lua (a chunk taking {@code ...}) that answers how many of fzf-lua's lists are open: its
     * terminal windows, known by their buffers' filetype {@code fzf}. With a word as its one
     * argument, only those whose lines (fzf's prompt and its list, as fzf drew them) have it.
     */
    public static final String FZF_LUA_WINDOWS = """
            local text = ...
            local n = 0
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              local b = vim.api.nvim_win_get_buf(w)
              if vim.bo[b].filetype == 'fzf' and (text == nil
                  or vim.iter(vim.api.nvim_buf_get_lines(b, 0, -1, false)):any(function(l) return l:find(text, 1, true) end)) then
                n = n + 1
              end
            end
            return n
            """;

    /**
     * Lua that closes fzf-lua's windows and ends every fzf it started, the insert over: a test's
     * clean-up. fzf-lua's default profile includes "hide": Enter or Esc closes the list's window
     * but keeps fzf running in a hidden terminal buffer, for {@code :FzfLua resume}, as in a
     * terminal. In the UI tests' one Neovim those would add up, so each buffer of filetype
     * {@code fzf}, shown or hidden, has its job stopped and is wiped ({@link #FZF_BUFFERS} checks
     * that none is left). Checked on Linux, where the job is the shell running fzf; on Windows
     * fzf-lua can start fzf through a Neovim of its own ({@code win_spawn.lua}), and whether
     * stopping that ends fzf too is not checked.
     */
    public static final String CLOSE_FZF_LUA = """
            vim.cmd.stopinsert()
            local ok, win = pcall(function() return require('fzf-lua.win').__SELF() end)
            if ok and win then pcall(win.close, win) end
            for _, b in ipairs(vim.api.nvim_list_bufs()) do
              if vim.api.nvim_buf_is_valid(b) and vim.bo[b].filetype == 'fzf' then
                if vim.b[b].terminal_job_id then pcall(vim.fn.jobstop, vim.b[b].terminal_job_id) end
                pcall(vim.api.nvim_buf_delete, b, { force = true })
              end
            end
            """;

    /** Lua that answers how many buffers of fzf-lua's lists are left, shown or hidden: 0 after {@link #CLOSE_FZF_LUA}. */
    public static final String FZF_BUFFERS = """
            return #vim.tbl_filter(function(b) return vim.bo[b].filetype == 'fzf' end, vim.api.nvim_list_bufs())
            """;

    /**
     * Lua that prepares Neovim for a picker that runs a program it picks by name (Telescope's
     * {@code find_files}: {@code rg}, {@code fd}, {@code find} or {@code where}): {@code PATH} keeps only its absolute folders, the rule {@link #fzf} keeps for
     * the tests' own lookup, so that a relative entry ({@code .}) cannot make Neovim run a file of
     * the test project's folder, where the picker starts it (on Windows the program's own folder may
     * still be searched first, as Windows does; that folder holds only the files the test makes);
     * and ripgrep's config file ({@code RIPGREP_CONFIG_PATH}) is taken out, so that a developer's
     * own cannot change what a list holds (fd reads its ignore file from {@code XDG_CONFIG_HOME},
     * which the UI tests' pom.xml points into {@code target/}).
     */
    public static final String FOR_PROGRAMS = """
            vim.env.RIPGREP_CONFIG_PATH = nil
            local sep = vim.fn.has('win32') == 1 and ';' or ':'
            local kept = {}
            for _, dir in ipairs(vim.split(vim.env.PATH or '', sep, { plain = true })) do
              if dir ~= '' and vim.fn.isabsolutepath(dir) == 1 then table.insert(kept, dir) end
            end
            vim.env.PATH = table.concat(kept, sep)
            """;

    /**
     * Lua that puts {@code folder} first on Neovim's {@code 'runtimepath'}, so that
     * {@code require} finds the plug-in there. Example: {@code vim.opt.rtp:prepend([==[/home/you/
     * src/neovim4eclipse/.tool-cache/test-plugins/telescope]==])}.
     */
    public static String onRuntimePath(Path folder) {
        return "vim.opt.rtp:prepend(" + longString(folder) + ")";
    }

    /**
     * A path as a Lua long string, taken as it is (no escapes: a Windows path's backslashes stay).
     * Example: {@code [==[/usr/bin/fzf]==]}.
     */
    public static String longString(Path file) {
        String path = file.toAbsolutePath().toString();
        if (path.contains("]==]")) {
            throw new IllegalArgumentException("a path with ]==] in it cannot be passed to Lua: " + path);
        }
        return "[==[" + path + "]==]";
    }
}

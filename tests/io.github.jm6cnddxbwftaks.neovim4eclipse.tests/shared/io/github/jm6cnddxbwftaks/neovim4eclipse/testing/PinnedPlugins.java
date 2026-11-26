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
     * {@code live_grep} runs (given this path) and snacks.nvim's {@code files} with {@code cmd = 'rg'}
     * (by name, from a {@code PATH} of absolute folders: {@link #FOR_PROGRAMS}). Not pinned either
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
     * Lua that loads snacks.nvim from {@code folder} and maps {@code gs} to its picker's
     * {@code lines} (the current buffer's lines, pure Lua: no program), as a user's
     * {@code <leader>/} would. snacks' {@code setup()} is not called: it would turn on the
     * library's other parts (dashboard, scrolling, notifications...) in the Neovim the UI tests
     * share, and the picker works without it. Undo the mapping with {@code vim.keymap.del('n', 'gs')}.
     */
    public static String loadSnacks(Path folder) {
        return onRuntimePath(folder)
                + " vim.keymap.set('n', 'gs', function() require('snacks').picker.lines() end)";
    }

    /**
     * Lua (a chunk taking {@code ...}) that answers how many of snacks' picker windows are open:
     * its input, list and preview, known by their buffers' filetypes
     * ({@code snacks_picker_input}, {@code snacks_picker_list}, {@code snacks_picker_preview}).
     * With a word as its one argument, only those whose lines have it.
     */
    public static final String SNACKS_WINDOWS = """
            local text = ...
            local n = 0
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              local b = vim.api.nvim_win_get_buf(w)
              if vim.bo[b].filetype:match('^snacks_picker') and (text == nil
                  or vim.iter(vim.api.nvim_buf_get_lines(b, 0, -1, false)):any(function(l) return l:find(text, 1, true) end)) then
                n = n + 1
              end
            end
            return n
            """;

    /**
     * Lua that closes every open snacks picker, its insert ended: a test's clean-up. The picker's
     * own {@code close} (it also closes its layout's box and backdrop), then any window of a
     * {@code snacks_} filetype left over.
     */
    public static final String CLOSE_SNACKS = """
            vim.cmd.stopinsert()
            local ok, pickers = pcall(function() return require('snacks').picker.get() end)
            for _, p in ipairs(ok and pickers or {}) do pcall(p.close, p) end
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              if vim.api.nvim_win_is_valid(w) and vim.bo[vim.api.nvim_win_get_buf(w)].filetype:match('^snacks_') then
                pcall(vim.api.nvim_win_close, w, true)
              end
            end
            """;

    /**
     * Lua that prepares Neovim for a picker that runs a program it picks by name (Telescope's
     * {@code find_files}: {@code rg}, {@code fd}, {@code find} or {@code where}; snacks.nvim's
     * {@code files}): {@code PATH} keeps only its absolute folders, the rule {@link #fzf} keeps for
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
     * Lua (a chunk taking {@code ...}, a plain word of the test's own) that answers how many lines
     * of snacks' picker list have it: its list only, not its preview, which shows the whole file.
     */
    public static final String SNACKS_LIST_HAS = """
            local word = ...
            local n = 0
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              local b = vim.api.nvim_win_get_buf(w)
              if vim.bo[b].filetype == 'snacks_picker_list' then
                for _, l in ipairs(vim.api.nvim_buf_get_lines(b, 0, -1, false)) do
                  if l:find(word, 1, true) then n = n + 1 end
                end
              end
            end
            return n
            """;

    /**
     * Lua that loads blink.cmp from {@code folder} and calls its {@code setup()} as a user's config
     * would, with its {@code default} key preset ({@code Ctrl-N}, {@code Ctrl-P}, {@code Ctrl-Y},
     * {@code Ctrl-E} mapped in Insert mode) and the words of the open buffers as its one source.
     *
     * <p>Its fuzzy matcher is the pure-Lua one ({@code fuzzy.implementation = 'lua'}): by default
     * blink.cmp downloads a prebuilt Rust program for it, which is not pinned or vetted. With
     * {@code 'lua'} its downloader returns before it asks git or curl anything
     * ({@code blink/cmp/fuzzy/download/init.lua}, {@code ensure_downloaded}); the download is off as
     * well, should that change. And from before the setup, {@code vim.system} and
     * {@code vim.fn.jobstart} (the only ways blink.cmp starts a program at this pin: git, curl, a
     * build) refuse to start anything ({@link #REFUSE_PROGRAMS}), so even a changed pin cannot
     * download behind the test's back. {@link #BLINK_READY} names any command refused,
     * and tells when the setup, which finishes in a scheduled callback, is done. Its
     * {@code plugin/} file (the {@code :BlinkCmp} command, which can build the matcher) is not run:
     * the setup needs none of it. {@code setup()} acts on the whole Neovim (autocommands, mappings
     * when an insert starts) and cannot be undone: a test that shares its Neovim restarts it
     * afterwards.
     *
     * <p>One setting differs from blink.cmp's defaults:
     * {@code completion.list.selection.auto_insert} is off, so its {@code Ctrl-N} selects the next
     * entry without putting it in the line. With it on, blink.cmp at times closes its own menu
     * right after putting a match in (its {@code BlinkCmpMenuClose} as Neovim leaves the
     * {@code Ctrl-X} mode blink.cmp enters to record the edit for {@code .}), the match left in the
     * line and no menu: seen on a Windows runner on one core, about 5 tries in 100, with the keys
     * sent to Neovim directly, no Eclipse in between. That is blink.cmp's own timing, which a test
     * cannot wait out; with nothing put in, the menu stays open.
     */
    public static String loadBlinkCmp(Path folder) {
        return onRuntimePath(folder) + "\n" + REFUSE_PROGRAMS + """
                require('blink.cmp').setup({
                  fuzzy = { implementation = 'lua', prebuilt_binaries = { download = false } },
                  sources = { default = { 'buffer' } },
                  completion = { list = { selection = { auto_insert = false } } },
                  keymap = { preset = 'default' },
                  signature = { enabled = false },
                })
                """;
    }

    /**
     * Lua, run before a plug-in's setup, after which {@code vim.system}, {@code vim.fn.jobstart} and
     * {@code vim.fn.termopen} refuse to start any program in that Neovim: they note the command in
     * {@code _G.n4e_refused} ({@link #PROGRAMS_REFUSED} answers it) and raise an error. For a plug-in that could start one
     * (blink.cmp's downloader, lualine's git), so that a test proves none ran, and a pin moved to a
     * commit that does start one fails instead of running it. Other ways (Vim's {@code system()},
     * {@code io.popen}) are not watched: each plug-in's pin says which it uses.
     */
    public static final String REFUSE_PROGRAMS = """
            _G.n4e_refused = {}
            local function refuse(cmd)
              table.insert(_G.n4e_refused, vim.inspect(cmd))
              error('a test refused to start ' .. vim.inspect(cmd))
            end
            vim.system = refuse
            vim.fn.jobstart = refuse
            vim.fn.termopen = refuse
            """;

    /** Lua that answers the commands {@link #REFUSE_PROGRAMS} refused so far: "" for none. */
    public static final String PROGRAMS_REFUSED = "return table.concat(_G.n4e_refused or {}, ', ')";

    /**
     * Lua that loads lualine from {@code folder} and calls its {@code setup()} as a user's config
     * would, with two changes: no icons (they need a font and nvim-web-devicons), and its second
     * section without {@code branch} and {@code diff} ({@code diagnostics} alone). {@code diff}
     * runs git, a program of the system's ({@code lualine/components/diff/git_diff.lua}), and both
     * would show this repository's own state; programs are refused anyway
     * ({@link #REFUSE_PROGRAMS}). lualine has no {@code plugin/} file. It sets
     * {@code 'statusline'} for the whole Neovim and refreshes it on a timer: a test that shares its
     * Neovim restarts it afterwards.
     */
    public static String loadLualine(Path folder) {
        return onRuntimePath(folder) + "\n" + REFUSE_PROGRAMS
                + "require('lualine').setup({ options = { icons_enabled = false },"
                + " sections = { lualine_b = { 'diagnostics' } } })";
    }

    /**
     * Lua that answers whether blink.cmp's setup ({@link #loadBlinkCmp}) is done with the Lua
     * matcher, no program asked for since it began: {@code "lua"} then, else what it found (the
     * commands refused, or that the setup is not done yet: the matcher's module is first loaded in
     * the setup's scheduled callback, together with the mappings).
     */
    public static final String BLINK_READY = """
            if _G.n4e_refused and #_G.n4e_refused > 0 then
              return 'refused: ' .. table.concat(_G.n4e_refused, ', ')
            end
            local fuzzy = package.loaded['blink.cmp.fuzzy']
            if not fuzzy then return 'not set up yet' end
            return fuzzy.implementation_type
            """;

    /**
     * Lua that loads nvim-cmp from {@code cmp} and its source cmp-buffer from {@code buffer}, and
     * calls {@code setup()} as a user's config would: the words of the buffer as its source, its
     * {@code preset.insert} keys ({@code Ctrl-N}, {@code Ctrl-P}, {@code Ctrl-E}) and
     * {@code Ctrl-Y} to confirm. Both plug-ins' {@code plugin/} files run by hand
     * ({@code :runtime}), since Neovim sourced the runtimepath's long before. As for
     * {@link #loadBlinkCmp}, nothing of it can be undone.
     */
    public static String loadNvimCmp(Path cmp, Path buffer) {
        return onRuntimePath(cmp) + " " + onRuntimePath(buffer) + """

                vim.cmd('runtime plugin/cmp.lua')
                vim.cmd('runtime after/plugin/cmp_buffer.lua')
                local cmp = require('cmp')
                cmp.setup({
                  sources = cmp.config.sources({ { name = 'buffer' } }),
                  mapping = cmp.mapping.preset.insert({ ['<C-y>'] = cmp.mapping.confirm({ select = true }) }),
                })
                """;
    }

    /**
     * Lua (a chunk taking {@code ...}) that answers how many completion menus are shown: windows
     * whose buffers have blink.cmp's ({@code blink-cmp-menu}) or nvim-cmp's ({@code cmp_menu})
     * filetype and are not hidden. With a word as its one argument, only those whose lines have it.
     */
    public static final String COMPLETION_MENUS = """
            local text = ...
            local n = 0
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              local b = vim.api.nvim_win_get_buf(w)
              local ft = vim.bo[b].filetype
              if (ft == 'blink-cmp-menu' or ft == 'cmp_menu') and not vim.api.nvim_win_get_config(w).hide
                  and (text == nil or vim.iter(vim.api.nvim_buf_get_lines(b, 0, -1, false))
                    :any(function(l) return l:find(text, 1, true) end)) then
                n = n + 1
              end
            end
            return n
            """;

    /**
     * Lua that loads vim-surround from {@code surround} and vim-repeat from {@code repeat}, as a
     * plug-in manager would: both on the {@code 'runtimepath'}, and vim-surround's
     * {@code plugin/surround.vim} run by hand ({@code :runtime}, which takes the first on the
     * runtimepath: the pinned one), since Neovim sourced the runtimepath's long before. vim-repeat
     * has no {@code plugin/} file: vim-surround calls its {@code repeat#set}, which the runtimepath
     * finds in its {@code autoload/}. The mappings they make (vim-surround's {@code ys},
     * {@code cs}, {@code ds}, {@code S} in Visual mode, {@code Ctrl-G s} in Insert mode;
     * vim-repeat's {@code .}, {@code u}, {@code U} and {@code Ctrl-R}, at its first use) are the
     * whole Neovim's: a test that shares its Neovim restarts it afterwards.
     */
    public static String loadSurround(Path surround, Path repeat) {
        return onRuntimePath(repeat) + " " + onRuntimePath(surround) + " vim.cmd('runtime plugin/surround.vim')";
    }

    /**
     * Lua that loads mini.nvim from {@code folder} and sets up two of its modules with their
     * defaults, as a user's config would: mini.pairs (Insert-mode mappings that put in the closing
     * bracket or quote and take a pair out with Backspace) and mini.ai (text objects such as
     * {@code a(}, {@code i"}, {@code af} that reach the next object past the cursor and across
     * lines). mini.nvim has no {@code plugin/} file: a module acts only once its {@code setup()}
     * ran. Their mappings are the whole Neovim's: a test that shares its Neovim restarts it
     * afterwards.
     */
    public static String loadMini(Path folder) {
        return onRuntimePath(folder) + " require('mini.pairs').setup() require('mini.ai').setup()";
    }

    /**
     * An {@code init.lua} that loads noice.nvim from {@code noice} and its library nui.nvim from
     * {@code nui} and calls noice's {@code setup()} with its defaults, as a user's config (LazyVim's
     * among them) does while Neovim starts. noice first checks the UI ({@code noice/health.lua},
     * {@code check}): for each of the command line, the popup menu and the messages that it draws
     * itself, as Eclipse's does the command line and the messages (ui2), it reports a line. Then it
     * loads at {@code VimEnter} anyway, without its views of those ({@code noice/init.lua},
     * {@code setup}; {@code noice/ui/init.lua}): its notifications and its popup menu run. Neither
     * plug-in has a {@code plugin/} file that would run by itself.
     */
    public static String noiceInit(Path noice, Path nui) {
        return onRuntimePath(nui) + "\n" + onRuntimePath(noice) + "\nrequire('noice').setup()\n";
    }

    /**
     * Lua that loads oil.nvim from {@code folder} and calls its {@code setup()} with its defaults,
     * as a user's config would, after {@link #REFUSE_PROGRAMS}. oil has no {@code plugin/} file: it
     * acts once its setup ran, which makes it the reader of every {@code oil://} buffer and of a
     * folder opened with {@code :edit}, and defines {@code :Oil}. Its float lists a folder, one line
     * a file ({@code require('oil').open_float(folder)}); a line renamed there moves the file once
     * the buffer is written and its confirmation answered. That is the whole Neovim's: a test that
     * shares its Neovim restarts it afterwards.
     */
    public static String loadOil(Path folder) {
        return onRuntimePath(folder) + "\n" + REFUSE_PROGRAMS + "require('oil').setup({})";
    }

    /**
     * Lua that opens oil's float on {@code folder}: {@code require('oil').open_float(folder)}.
     * Example: {@code require('oil').open_float([==[/home/you/project]==])}.
     */
    public static String openOilFloat(Path folder) {
        return "require('oil').open_float(" + longString(folder) + ")";
    }

    /**
     * Lua that answers the actions oil's confirmation window lists, one a line, trimmed, while
     * that window has the keyboard (its buffer's filetype {@code oil_preview}); "" otherwise.
     * Example: {@code "MOVE open-me.txt -> moved.txt"}. A test answers {@code y} only to the list it
     * expects: {@code y} carries out every action listed, a {@code DELETE} too.
     */
    public static final String OIL_CONFIRMATION = """
            if vim.bo.filetype ~= 'oil_preview' then
              return ''
            end
            local actions = {}
            for _, line in ipairs(vim.api.nvim_buf_get_lines(0, 0, -1, false)) do
              local action = vim.trim(line)
              if action:match('^%u+ ') then
                table.insert(actions, action)
              end
            end
            return table.concat(actions, '\\n')""";

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

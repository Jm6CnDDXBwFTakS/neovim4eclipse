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
              if vim.bo[vim.api.nvim_win_get_buf(w)].filetype:match('^Telescope') then
                pcall(vim.api.nvim_win_close, w, true)
              end
            end
            """;

    /**
     * Lua that puts {@code folder} first on Neovim's {@code 'runtimepath'}, so that
     * {@code require} finds the plug-in there. Example: {@code vim.opt.rtp:prepend([==[/home/you/
     * src/neovim4eclipse/.tool-cache/test-plugins/telescope]==])}.
     */
    public static String onRuntimePath(Path folder) {
        String path = folder.toAbsolutePath().toString();
        if (path.contains("]==]")) {
            throw new IllegalArgumentException("a path with ]==] in it cannot be passed to Lua: " + path);
        }
        return "vim.opt.rtp:prepend([==[" + path + "]==])";
    }
}

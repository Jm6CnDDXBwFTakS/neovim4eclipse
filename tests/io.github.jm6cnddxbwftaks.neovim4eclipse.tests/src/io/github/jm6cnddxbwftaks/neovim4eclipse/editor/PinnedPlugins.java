// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds a pinned Neovim plug-in for a UI test (docs/UPGRADING.md, "The plug-ins the tests run"):
 * the folder {@code java releng/tools/plugins/TestPlugins.java fetch} unpacked it into, under the
 * repository's {@code target/test-plugins/}.
 *
 * <p>The repository holds no plug-in, so a test must cope with one that is not there (the tool
 * did not run, or had no network) or that is of another commit than the pin (the pin moved since
 * the last fetch): {@link #find} is empty then, and the test skips or leaves its picture out,
 * saying {@link #HOW}. Running an unpinned copy would test something nobody looked at.
 */
final class PinnedPlugins {

    /** What to run to get the plug-ins. */
    static final String HOW = "java releng/tools/plugins/TestPlugins.java fetch";

    private static final String PINS = "releng/test-plugins.properties";

    private PinnedPlugins() {
    }

    /**
     * The folder of the plug-in pinned as {@code name} (to put on Neovim's {@code 'runtimepath'}),
     * if it is fetched at the pinned commit. The repository's root is the first folder above the
     * working directory that holds the pins.
     */
    static Optional<Path> find(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(PINS))) {
            root = root.getParent();
        }
        if (root == null) {
            return Optional.empty();
        }
        try {
            Optional<String> commit = Files.readAllLines(root.resolve(PINS)).stream()
                    .filter(line -> line.startsWith(name + ".commit="))
                    .map(line -> line.substring(line.indexOf('=') + 1).strip())
                    .findFirst();
            Path folder = root.resolve("target/test-plugins").resolve(name);
            Path marker = root.resolve("target/test-plugins").resolve(name + ".pin");
            boolean there = commit.isPresent() && Files.isDirectory(folder) && Files.isRegularFile(marker)
                    && Files.readString(marker).startsWith(commit.get() + " ");
            return there ? Optional.of(folder) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Lua that loads flash from {@code folder} with only its jump: its other modes map
     * {@code f}, {@code t}, {@code ;} and {@code ,} and change {@code /}, which would stay for
     * every later test in the same Neovim.
     */
    static String loadFlash(Path folder) {
        String path = folder.toAbsolutePath().toString();
        if (path.contains("]==]")) {
            throw new IllegalArgumentException("a path with ]==] in it cannot be passed to Lua: " + path);
        }
        return "vim.opt.rtp:prepend([==[" + path + "]==])"
                + " require('flash').setup({ modes = { char = { enabled = false }, search = { enabled = false } } })";
    }
}

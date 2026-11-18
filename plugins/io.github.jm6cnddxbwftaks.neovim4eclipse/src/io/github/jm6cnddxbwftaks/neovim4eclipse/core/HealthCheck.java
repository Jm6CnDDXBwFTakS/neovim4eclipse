// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;

/**
 * The plug-in's own {@code :checkhealth neovim4eclipse}: which Neovim runs (its version against
 * the minimum, the dedicated one or a program of the user's), the plug-in's and Eclipse's versions,
 * and whether what {@code SECURITY.md} turns off for the files you open is still off.
 *
 * <h2>How Neovim finds it</h2>
 * {@code :checkhealth} looks for checks only among runtime files: a {@code lua/<name>/health.lua}
 * somewhere on {@code 'runtimepath'} ({@code :help health-dev}). So the plug-in keeps one such file
 * in a folder of its own, {@code <plug-in folder>/runtime} ({@link #runtimeFolder}), written again
 * at every start ({@link #writeStub}), and appends that folder to the editing Neovim's
 * {@code 'runtimepath'}. The file holds no check: the check itself is Lua the plug-in sends at
 * start ({@link LuaScripts#HEALTH_CHECK}), which puts the module in {@code package.loaded}, where
 * {@code require} finds it before it would read the file. The file answers only if that did not
 * happen, with an error saying so. The only other files in the folder are the plug-in's help page
 * and its tags ({@link PluginHelp}, in {@code doc/}), so appending it to the runtimepath adds this
 * check and that page, and nothing that runs on its own ({@code plugin/}, {@code ftplugin/}).
 */
public final class HealthCheck {

    /** The stub's place below {@link #runtimeFolder}, as {@code :checkhealth} looks for it. */
    static final String STUB_PATH = "lua/neovim4eclipse/health.lua";

    /** The stub's text: the check registered at start, or an error saying it was not. */
    static final String STUB = """
            -- Written by Neovim for Eclipse at each start; do not edit. The check itself is the
            -- plug-in's own, sent at start (core/HealthCheck): require finds it in package.loaded
            -- before this file is read. This file answers only if that did not happen.
            return {
              check = function()
                vim.health.error('Neovim for Eclipse did not register its check: restart Neovim (Neovim > Restart Neovim...)')
              end,
            }
            """;

    /**
     * What the check reports that only Eclipse knows.
     *
     * @param dedicated whether the program is the dedicated Neovim, checked before it started
     *                  ({@code NvimExecutable.Kind.DEDICATED}), rather than one the user named
     * @param plugin    this plug-in's version, as {@code 0.2.27}
     * @param eclipse   Eclipse's version, as {@code 4.38.0}, or {@code ""} if unknown
     */
    public record Info(boolean dedicated, String plugin, String eclipse) {

        /** The table {@link LuaScripts#HEALTH_CHECK} reads. */
        Map<String, Object> toLua() {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("minimum", NeovimVersion.MINIMUM.toString());
            table.put("program", dedicated ? "dedicated" : "own");
            table.put("plugin", plugin);
            table.put("eclipse", eclipse);
            table.put("readers", NvimExecutable.READERS_OFF);
            return table;
        }
    }

    private HealthCheck() {
    }

    /** {@code <plug-in folder>/runtime}: the folder appended to the runtimepath; see the class comment. */
    public static Path runtimeFolder(Path pluginFolder) {
        return pluginFolder.resolve("runtime");
    }

    /**
     * Whether {@code folder} can go on {@code 'runtimepath'} as one entry: a name with a comma in it
     * would be split there into two, the second a relative folder, read from wherever Neovim's
     * current folder is (a project). Refused rather than escaped: one check, nothing to get right.
     * Example: {@code /data,home/neovim4eclipse-plugin/runtime} is refused.
     */
    static boolean fitsRuntimepath(Path folder) {
        return !folder.toString().contains(",");
    }

    /** Writes {@link #STUB} to {@code <folder>/}{@value #STUB_PATH}, replacing what was there. */
    static void writeStub(Path folder) throws IOException {
        Path stub = folder.resolve(STUB_PATH);
        Files.createDirectories(stub.getParent());
        Files.writeString(stub, STUB, StandardCharsets.UTF_8);
    }
}

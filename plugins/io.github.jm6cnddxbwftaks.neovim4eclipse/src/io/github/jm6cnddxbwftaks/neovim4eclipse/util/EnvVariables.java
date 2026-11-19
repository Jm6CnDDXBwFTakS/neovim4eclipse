// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Map;

/**
 * An environment variable in a map of them ({@code System.getenv()}, or a
 * {@link ProcessBuilder#environment()}), found by the operating system's rule for names.
 *
 * <p>Linux and macOS tell {@code HOME} from {@code Home}: two variables. Windows ignores the case
 * of a name: {@code set Xdg_Data_Home=C:\data} sets the variable a program reads as
 * {@code XDG_DATA_HOME}, and Neovim reads it so (checked on Neovim 0.12.6:
 * {@code Xdg_Data_Home=C:\xdg-test} gives {@code stdpath('data')} = {@code C:\xdg-test\nvim-data},
 * and {@code Shell=/usr/bin/bash} sets {@code 'shell'}). Java's maps keep each name as it was
 * spelled and compare the keys exactly, so {@code map.get("XDG_DATA_HOME")} misses that variable
 * and the plug-in would decide differently from the Neovim it starts. These methods look the way
 * the OS does.
 *
 * <p>({@code System.getenv(name)} ignores the case on Windows by itself; a map is used where the
 * rule must be testable with any environment, as in {@link PluginFolders}, or where a child's
 * environment is changed, as in {@link NvimExecutable#prepareEnvironment}.)
 */
public final class EnvVariables {

    private EnvVariables() {
    }

    /**
     * The value of the variable {@code name} in {@code env}, or {@code null} when it is not set.
     * The exact spelling first; on Windows then any other case.
     *
     * @param windows whether Windows' rule for names applies
     */
    public static String get(Map<String, String> env, String name, boolean windows) {
        String exact = env.get(name);
        if (exact != null || !windows) {
            return exact;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Removes the variable {@code name} from {@code env}: on Windows under every spelling
     * ({@code SHELL}, {@code Shell}), elsewhere only this one.
     *
     * @param windows whether Windows' rule for names applies
     */
    public static void remove(Map<String, String> env, String name, boolean windows) {
        if (windows) {
            env.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        } else {
            env.remove(name);
        }
    }
}

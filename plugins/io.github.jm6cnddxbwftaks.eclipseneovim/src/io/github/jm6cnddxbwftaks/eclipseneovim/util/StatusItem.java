// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.EnumSet;
import java.util.Set;

/**
 * The parts of Vim's status information that can be shown in the Eclipse status bar (preference
 * <em>Window → Preferences → Neovim</em>), named as in the Visual Studio Code Neovim extension.
 * The declaration order is the display order.
 */
public enum StatusItem {

    /** The mode, e.g. {@code -- INSERT --}, with {@code recording @q} while a macro is recorded. */
    MODE("mode"),
    /** The pending command as Vim's {@code showcmd} shows it, e.g. {@code 2d} or {@code "a}. */
    CMD("cmd"),
    /** The latest Neovim message or error, e.g. {@code E486: Pattern not found: foo}. */
    MSG("msg"),
    /** Neovim's own {@code 'statusline'} (file, cursor position, ...), evaluated by Neovim. */
    STATUSLINE("statusline");

    private final String key;

    StatusItem(String key) {
        this.key = key;
    }

    /** The name used in documentation and preference labels. */
    public String key() {
        return key;
    }

    /** What is shown out of the box: everything except the (long) statusline. */
    public static Set<StatusItem> defaults() {
        return EnumSet.of(MODE, CMD, MSG);
    }
}

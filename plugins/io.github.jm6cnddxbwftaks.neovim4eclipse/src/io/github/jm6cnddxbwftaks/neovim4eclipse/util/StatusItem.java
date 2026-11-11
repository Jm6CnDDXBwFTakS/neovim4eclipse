// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.EnumSet;
import java.util.Set;

/**
 * The parts of Vim's status information that can be shown in the Eclipse status bar (preference
 * <em>Window → Preferences → Neovim</em>), named as in the Visual Studio Code Neovim extension.
 * The declaration order is the display order.
 * Messages and errors are not among them: they appear in the editor ({@code editor/MessageLine}).
 */
public enum StatusItem {

    /** The mode, e.g. {@code INSERT}, with {@code rec @q} while a macro is recorded. */
    MODE("mode"),
    /** The pending command as Vim's {@code showcmd} shows it, e.g. {@code 2d} or {@code "a}. */
    CMD("cmd"),
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
        return EnumSet.of(MODE, CMD);
    }

    /**
     * The parts the status bar shows: the ones the user chose ({@code chosen}, from the Status Bar
     * preference page), or, while Eclipse handles the keys ({@code eclipseMode}), the mode alone,
     * whatever was chosen. In Eclipse mode Neovim's command and statusline say nothing about the
     * editor, and the mode part, which then reads {@code ECLIPSE}, is how one sees where keys go.
     * Example: with only the statusline chosen, Neovim mode shows the statusline, Eclipse mode
     * {@code ECLIPSE}.
     *
     * @return a new set the caller may change
     */
    public static Set<StatusItem> shown(Set<StatusItem> chosen, boolean eclipseMode) {
        if (eclipseMode) {
            return EnumSet.of(MODE);
        }
        Set<StatusItem> parts = EnumSet.noneOf(StatusItem.class);
        parts.addAll(chosen);
        return parts;
    }
}

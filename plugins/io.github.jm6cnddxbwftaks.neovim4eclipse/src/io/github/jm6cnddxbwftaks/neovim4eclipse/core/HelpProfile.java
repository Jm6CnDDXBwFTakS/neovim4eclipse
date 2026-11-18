// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HelpPage;

/**
 * The Neovim view's help profile: what a {@link ScreenSession} for {@code :help} is started with,
 * and how a page is opened in it ({@link HelpPage}).
 *
 * <p><b>Why a profile of its own.</b> The output profile ({@link OutputProfile}) loads none of the
 * user's plug-ins, so text from {@code :messages} meets nothing that could act on it. Help is
 * different: its point is the user's own pages, which live on the user's {@code 'runtimepath'}, and
 * Neovim runs the help scripts it finds there on each page (a plug-in's
 * {@code ftplugin/help.lua}, {@code after/ftplugin/help.lua}, a parser for a code block's
 * language), as it does in the user's editor. So help gets a second Neovim of its own, given that
 * path once started ({@link LuaScripts#SCREEN_SHOW_HELP}); the text profile's never is. The view
 * starts the other one when the kind of content changes ({@code editor/NeovimView}).
 *
 * <p>What still holds in both: the rule every second Neovim keeps ({@link ScreenSession#FIXED_ARGS}:
 * {@code --clean}, no swap file, {@code 'write'} and {@code 'modeline'} off) and {@code -M}, so no
 * buffer is changed or written. The readers are checked at the start, as for every second Neovim;
 * a help script that adds one later is the user's own plug-in, which their editor runs too.
 *
 * <p>Example:
 * <pre>{@code
 * ScreenSession s = ScreenSession.start(nvim, HelpProfile.ARGS, version, folder, 80, 24, listener);
 * HelpProfile.show(s, new HelpPage("help-context", "helphelp.txt", 27, rtp), true);
 * }</pre>
 */
public final class HelpProfile {

    /** The profile's own start arguments, after {@link ScreenSession#FIXED_ARGS}: no changes. */
    public static final List<String> ARGS = List.of("-M");

    private HelpProfile() {
    }

    /**
     * Opens {@code page} in the second Neovim. Returns at once; the future completes with
     * {@code ""}, or Neovim's error if the tag was not found.
     *
     * @param dark whether the editor's background is dark, for Neovim's {@code 'background'}
     */
    public static CompletableFuture<Object> show(ScreenSession session, HelpPage page, boolean dark) {
        return session.execLuaAsync(LuaScripts.SCREEN_SHOW_HELP,
                List.of(page.tag(), page.file(), page.line(), page.runtimepath(), dark ? "dark" : "light",
                        page.helpgrep(), page.index()));
    }
}

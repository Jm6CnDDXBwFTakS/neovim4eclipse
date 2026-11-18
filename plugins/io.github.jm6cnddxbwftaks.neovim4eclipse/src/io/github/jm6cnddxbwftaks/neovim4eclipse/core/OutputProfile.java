// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

/**
 * A page editor's output profile: what a {@link ScreenSession} is started with, and how text is
 * put into it, for {@code :messages} and {@code :checkhealth} ({@link ViewText}). The text only
 * shows: the profile starts Neovim with {@code -M} (no changes to the text, no writes), on top of
 * the rule every second Neovim keeps ({@link ScreenSession#FIXED_ARGS}).
 *
 * <p>Example:
 * <pre>{@code
 * ScreenSession s = ScreenSession.start(nvim, OutputProfile.ARGS, version, folder, 80, 24, listener);
 * OutputProfile.show(s, ViewText.checkhealth(lines), true);   // the report, on a dark editor
 * }</pre>
 */
public final class OutputProfile {

    /** The profile's own start arguments, after {@link ScreenSession#FIXED_ARGS}. */
    public static final List<String> ARGS = List.of("-M");

    private OutputProfile() {
    }

    /**
     * Replaces what the second Neovim shows with {@code text} ({@link LuaScripts#SCREEN_SHOW_TEXT}).
     * {@code :messages} puts the cursor on the newest message, the last line; anything else on the
     * first. Returns at once; the future completes with the line count.
     *
     * @param dark whether the editor's background is dark, for Neovim's {@code 'background'}
     */
    public static CompletableFuture<Object> show(ScreenSession session, ViewText text, boolean dark) {
        List<List<Object>> highlights = new ArrayList<>();
        for (ViewText.LineHighlight h : text.highlights()) {
            highlights.add(List.of(h.line(), h.group()));
        }
        String cursor = ViewText.MESSAGES.equals(text.title()) ? "end" : "top";
        return session.execLuaAsync(LuaScripts.SCREEN_SHOW_TEXT,
                List.of(text.lines(), text.filetype(), highlights, dark ? "dark" : "light", cursor, text.title()));
    }
}

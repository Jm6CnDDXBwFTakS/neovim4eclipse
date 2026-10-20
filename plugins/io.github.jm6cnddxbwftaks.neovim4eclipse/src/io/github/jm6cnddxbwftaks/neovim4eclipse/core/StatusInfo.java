// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * What Neovim currently reports besides the mode: the status bar parts, and the message that the
 * editor's message line shows.
 *
 * @param statusline Neovim's evaluated {@code 'statusline'}, or "" if not requested
 * @param cmd        the pending command ({@code showcmd}), e.g. {@code 2d}
 * @param message    the latest message or error, kept until the next key
 * @param error      whether {@code message} is an error ({@code v:errmsg}, e.g.
 *                   {@code E486: Pattern not found: x}) rather than a plain message
 *                   ({@code v:statusmsg}, e.g. {@code 3 lines yanked})
 */
public record StatusInfo(String statusline, String cmd, String message, boolean error) {

    public static final StatusInfo EMPTY = new StatusInfo("", "", "", false);

    /** A status with a plain message (not an error), or none. */
    public StatusInfo(String statusline, String cmd, String message) {
        this(statusline, cmd, message, false);
    }
}

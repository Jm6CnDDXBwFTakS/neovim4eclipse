// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the user's own {@code :redir} commands, which decide whether the plug-in may collect a
 * typed command's output with a {@code :redir} of its own.
 *
 * <p>Vim has one redirection at a time: a new {@code :redir} replaces the active one, and
 * {@code :redir END} ends whichever is active. So from {@code :redir @a} until {@code :redir END}
 * the plug-in must not capture anything, or it would take over (and then end) the user's
 * redirection. Example: {@code :redir => g:x}, {@code :echo 1}, {@code :redir END} leaves
 * {@code "\n1"} in {@code g:x} only if the {@code :echo} is typed without a capture.
 *
 * <p>Not detected: a {@code :redir} hidden behind {@code |}, {@code :execute} or a user command, or
 * typed on Neovim's own command line.
 */
public final class OutputCapture {

    /** What a command does to redirection. */
    public enum Redir {
        /** Not a {@code :redir} command. */
        NONE,
        /** {@code :redir @a}, {@code :redir => var}, {@code :redir > file}: a redirection starts. */
        START,
        /** {@code :redir END}. */
        END
    }

    /** Optional {@code :sil[ent][!]} prefixes, then {@code :redi[r][!]}, then the rest. */
    private static final Pattern REDIR = Pattern.compile("^(?:sil(?:e(?:n(?:t)?)?)?!?\\s+)*redir?!?(?=$|[\\s>@=])(.*)$");

    private OutputCapture() {
    }

    /** @param body the command line without the leading {@code :}; may be {@code null} */
    public static Redir redirOf(String body) {
        if (body == null) {
            return Redir.NONE;
        }
        String cmd = body.strip();
        if (cmd.startsWith(":")) {
            cmd = cmd.substring(1).strip();
        }
        Matcher m = REDIR.matcher(cmd.toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return Redir.NONE;
        }
        return m.group(1).strip().equals("end") ? Redir.END : Redir.START;
    }
}

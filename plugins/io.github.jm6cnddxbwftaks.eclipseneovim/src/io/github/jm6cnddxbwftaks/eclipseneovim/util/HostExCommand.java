// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies an ex command (text typed after {@code :}) that Eclipse, not Neovim, must execute.
 *
 * <p>The embedded buffer is a scratch buffer with no file, so Neovim's own {@code :w} fails
 * (E382). File operations are therefore mapped onto Eclipse save/close. Everything else
 * (e.g. {@code :%s/a/b/g}) is left to Neovim.
 *
 * <p>Not handled (goes to Neovim, and will fail or do nothing useful): {@code :wa}, {@code :wqa},
 * {@code :xa}, {@code :e}, {@code :w otherfile} (the path is ignored and the current editor is saved).
 */
public record HostExCommand(Kind kind, boolean force) {

    public enum Kind {
        /** Not a host command: forward to Neovim. */
        NONE,
        /** {@code :w}, {@code :write}, {@code :update}. */
        WRITE,
        /** {@code :q}, {@code :quit}, {@code :qa}. */
        QUIT,
        /** {@code :wq}, {@code :x}, {@code :exit}. */
        WRITE_QUIT
    }

    private static final HostExCommand NOT_HOST = new HostExCommand(Kind.NONE, false);

    private static final Set<String> WRITE = Set.of("w", "w!", "write", "write!", "update", "update!", "up", "up!");
    private static final Set<String> QUIT = Set.of("q", "q!", "quit", "quit!", "qa", "qa!", "quita", "quita!");
    private static final Set<String> WRITE_QUIT = Set.of("wq", "wq!", "x", "x!", "exit", "exit!", "xi", "xit", "xit!");

    /** @return {@code true} if Eclipse should handle the command instead of Neovim */
    public boolean isHostCommand() {
        return kind != Kind.NONE;
    }

    /**
     * @param raw command text, with or without a leading {@code :}; may be {@code null}
     * @return the classification; never {@code null}
     */
    public static HostExCommand parse(String raw) {
        if (raw == null) {
            return NOT_HOST;
        }
        String cmd = normalize(raw);
        if (cmd.isEmpty()) {
            return NOT_HOST;
        }
        // "w path" / "wq path" / "x path": the argument is ignored (see class comment).
        int space = cmd.indexOf(' ');
        boolean hasArgument = space >= 0;
        String head = hasArgument ? cmd.substring(0, space) : cmd;
        boolean force = head.endsWith("!");

        if (WRITE.contains(head)) {
            return new HostExCommand(Kind.WRITE, force);
        }
        if (WRITE_QUIT.contains(head)) {
            return new HostExCommand(Kind.WRITE_QUIT, force);
        }
        if (QUIT.contains(head) && !hasArgument) {
            return new HostExCommand(Kind.QUIT, force);
        }
        return NOT_HOST;
    }

    private static String normalize(String raw) {
        String s = raw.strip();
        if (s.startsWith(":")) {
            s = s.substring(1).strip();
        }
        // ":%w" is the same as ":w"; ranges are otherwise not supported for host commands.
        if (s.startsWith("%")) {
            s = s.substring(1).strip();
        }
        return s.toLowerCase(Locale.ROOT);
    }
}

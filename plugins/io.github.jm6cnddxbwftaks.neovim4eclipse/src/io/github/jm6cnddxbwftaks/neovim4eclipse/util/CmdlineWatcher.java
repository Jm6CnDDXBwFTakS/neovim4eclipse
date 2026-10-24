// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tells, from Neovim's command-line events ({@link CmdlineEvent}), when the <em>user</em> opened a
 * command line in Neovim that the editor's box did not: a mapping ({@code nnoremap ; :}), a count
 * ({@code 2:}), {@code !} with a motion. The session then lets the editor take it over.
 *
 * <h2>The plug-in's own command lines</h2>
 * The plug-in types command lines itself: every {@code :} command from the box is typed as
 * {@code :silent cmd<CR>}, every search as {@code /pattern<CR>}, so that macros record them. Neovim
 * reports those as well, as a {@code cmdline_show} and a {@code cmdline_hide}. Before typing one,
 * the session calls {@link #expectOwn}; the next line of that type is then the plug-in's, and its
 * events are ignored until it closes. An expectation that no line fulfils (the keys did not open
 * one) is dropped after {@link #OWN_LINE_TIMEOUT_MS}, so it cannot hide a user's line for long.
 *
 * <pre>
 *   expectOwn(":")                    the box runs :ls
 *   show ":" "silent ls"     ignored  the plug-in's line
 *   hide                              ... closed
 *   show ":" ""              USER     a mapping opened one: the editor takes it over
 *   show ":" "w"             ignored  the same line, more text: taken over already
 *   hide                              ... closed
 * </pre>
 *
 * <p>Only top-level lines of type {@code :}, {@code /} and {@code ?} count. A hide without a show
 * (Tab completion types into a line inside one Lua call and Neovim reports only its end) and lines
 * inside lines ({@code <C-r>=}) are ignored.
 *
 * <p>Thread-safe: the session calls it from the RPC reader thread (events) and from the input
 * thread ({@link #expectOwn}); its methods are {@code synchronized}.
 */
public final class CmdlineWatcher {

    /** How long an expected own line may take to show before the expectation is dropped. */
    public static final long OWN_LINE_TIMEOUT_MS = 2000;

    /** What to do with a {@code cmdline_show}. */
    public enum Decision {
        /** Nothing: the plug-in's own line, a line already taken over, or one of another type. */
        IGNORE,
        /** A command line the user opened in Neovim: the editor should take it over. */
        USER_LINE
    }

    private record Expected(String firstc, long atMillis) {
    }

    private final Deque<Expected> expected = new ArrayDeque<>();
    /** The line now shown is the plug-in's own. */
    private boolean ownLineShown;
    /** The line now shown is the user's and was reported once already. */
    private boolean userLineShown;

    /** The plug-in is about to type a command line of type {@code firstc} ({@code ":"}, {@code "/"}, {@code "?"}). */
    public synchronized void expectOwn(String firstc, long nowMillis) {
        expected.addLast(new Expected(firstc, nowMillis));
    }

    /** Decides about one event; for a hide the answer is always {@link Decision#IGNORE}. */
    public synchronized Decision onEvent(CmdlineEvent event, long nowMillis) {
        if (event.level() != 1) {
            return Decision.IGNORE;
        }
        if (!event.shown()) {
            ownLineShown = false;
            userLineShown = false;
            return Decision.IGNORE;
        }
        if (ownLineShown || userLineShown) {
            return Decision.IGNORE; // the same line again, with more text
        }
        while (!expected.isEmpty() && nowMillis - expected.peekFirst().atMillis() > OWN_LINE_TIMEOUT_MS) {
            expected.removeFirst();
        }
        if (!expected.isEmpty() && expected.peekFirst().firstc().equals(event.firstc())) {
            expected.removeFirst();
            ownLineShown = true;
            return Decision.IGNORE;
        }
        if (":".equals(event.firstc()) || "/".equals(event.firstc()) || "?".equals(event.firstc())) {
            userLineShown = true;
            return Decision.USER_LINE;
        }
        return Decision.IGNORE; // an input() prompt, an expression line ...
    }
}

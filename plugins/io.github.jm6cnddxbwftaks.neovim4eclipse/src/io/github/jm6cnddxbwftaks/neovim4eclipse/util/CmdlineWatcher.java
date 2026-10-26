// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tells the plug-in's own command lines from the user's, so that the {@code :} box does not flash
 * open for a line the plug-in types itself.
 *
 * <p>Every command line Neovim opens is shown in the box ({@link CmdlineState}): one typed with
 * {@code :}, one a mapping opened ({@code nnoremap ; :}), a prompt ({@code input()}). The plug-in
 * still types a few lines of its own in one go ({@code NeovimSession.commandAsync} types
 * {@code :cmd<CR>}, so that macros and {@code @:} see it), and Neovim reports those too, as a
 * {@code cmdline_show} and a {@code cmdline_hide}. Before typing one, the session calls
 * {@link #expectOwn}; the next top-level line of that type is then the plug-in's, and every event
 * of it, up to and including its hide, is not shown. An expectation that no line fulfils (the keys
 * did not open one) is dropped after {@link #OWN_LINE_TIMEOUT_MS}, so it cannot hide a user's line
 * for long.
 *
 * <pre>
 *   expectOwn(":")                     the session types :ls&lt;CR&gt;
 *   show ":" "ls"          own         not shown
 *   hide                   own         ... closed
 *   show ":" ""            user's      the box opens
 * </pre>
 *
 * <p>Thread-safe: the session calls it from the RPC reader thread (events) and from the input
 * thread ({@link #expectOwn}); its methods are {@code synchronized}.
 */
public final class CmdlineWatcher {

    /** How long an expected own line may take to show before the expectation is dropped. */
    public static final long OWN_LINE_TIMEOUT_MS = 2000;

    private record Expected(String firstc, long atMillis) {
    }

    private final Deque<Expected> expected = new ArrayDeque<>();
    /** The top-level line now open is the plug-in's own. */
    private boolean ownLineShown;

    /** The plug-in is about to type a command line of type {@code firstc} ({@code ":"}, {@code "/"}, {@code "?"}). */
    public synchronized void expectOwn(String firstc, long nowMillis) {
        expected.addLast(new Expected(firstc, nowMillis));
    }

    /** Whether {@code event} belongs to the plug-in's own line, and so is not shown. */
    public synchronized boolean isOwn(CmdlineEvent event, long nowMillis) {
        if (event instanceof CmdlineEvent.Show show && show.level() == 1 && !ownLineShown) {
            while (!expected.isEmpty() && nowMillis - expected.peekFirst().atMillis() > OWN_LINE_TIMEOUT_MS) {
                expected.removeFirst();
            }
            if (!expected.isEmpty() && expected.peekFirst().firstc().equals(show.firstc())) {
                expected.removeFirst();
                ownLineShown = true;
            }
            return ownLineShown;
        }
        if (event instanceof CmdlineEvent.Hide hide && hide.level() == 1 && ownLineShown) {
            ownLineShown = false;
            return true;
        }
        return ownLineShown;
    }
}

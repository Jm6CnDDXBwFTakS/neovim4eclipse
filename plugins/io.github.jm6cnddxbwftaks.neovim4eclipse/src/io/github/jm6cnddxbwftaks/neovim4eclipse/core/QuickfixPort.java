// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Current;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Entry;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Mode;

/**
 * What the quickfix lists of Eclipse's problems and tasks ({@code :Eproblems}, {@code :Etasks};
 * docs/design/DIAGNOSTICS.md, section 5) need from the session: hear the commands, send the lists,
 * and ask which list is current. {@code editor/QuickfixLists} reads the workspace; Neovim keeps and
 * walks the list ({@link LuaScripts#QUICKFIX}). {@link NeovimSession} implements it.
 */
public interface QuickfixPort {

    /** What Neovim asks of Eclipse; called on an RPC thread. */
    interface Handler {

        /**
         * {@code :Eproblems[!] [severity]} or {@code :Etasks[!]} ran.
         *
         * @param arg  the severity word ({@code "warning"}, or "") or the task tags ({@code "JM6,FIXME"})
         * @param bang whether the command had {@code !} (fill the list without jumping)
         */
        void request(Kind kind, String arg, boolean bang);

        /** {@code :copen} or {@code :cwindow} with one of Eclipse's lists current: show its view. */
        void showView(Kind kind);
    }

    /** Who hears the commands; {@code null} for nobody. */
    void setQuickfixHandler(Handler handler);

    /**
     * Fills or updates a list ({@link LuaScripts#SET_QUICKFIX}; see {@link QuickfixEntries#toLuaArgs}),
     * on the input thread, after every key queued before. Returns at once.
     */
    void setQuickfixAsync(Kind kind, String arg, Mode mode, boolean jump, List<Entry> entries);

    /**
     * The current list when it is one of Eclipse's, else {@code null}, answered on the input thread
     * after every key queued before. The future fails when Neovim fails or is closed. Neovim has no
     * event for a change of the current list ({@code :colder}), so an update asks this first.
     */
    CompletableFuture<Current> currentQuickfixAsync();
}

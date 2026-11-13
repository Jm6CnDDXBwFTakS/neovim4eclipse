// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.function.Consumer;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimHighlight;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;

/**
 * What search highlighting needs from the session (docs/design/SEARCH_HIGHLIGHTING.md): whether
 * it is on, and the matches of Neovim's search in the lines its window shows. An interface, rather
 * than the whole {@link NeovimSession}, so that the editor's side is tested with a small fake.
 * {@link NeovimSession} implements it.
 *
 * <p>The flow, for {@code /foo} typed in normal mode: Neovim redraws its window with the matches
 * highlighted; {@link LuaScripts#SEARCH} hears the redraw, finds the matches in the visible lines
 * and sends them; the session passes them to the listener of the attached editor, which paints
 * them behind its text. {@code :noh}, a scroll and each character of an incremental search are
 * reported the same way; {@code n} is not, as it changes no match, only the cursor.
 */
public interface SearchPort {

    /**
     * Whether search matches are reported: {@code true} unless the user's config sets
     * {@code vim.g.neovim4eclipse_search_highlight = 0} (or {@code false}). Read once when the session starts.
     */
    boolean searchEnabled();

    /**
     * Hears the visible matches of the attached editor's window, on a session thread (hop to the UI
     * thread yourself). Only for the attached window; an empty list means none (no search, or
     * {@code :noh}).
     */
    void addSearchListener(Consumer<Matches> listener);

    void removeSearchListener(Consumer<Matches> listener);

    /**
     * Whether Neovim's own highlights are reported (the yank flash, window matches; see
     * {@link LuaScripts#HIGHLIGHTS}): {@code true} unless the user's config sets
     * {@code vim.g.neovim4eclipse_highlights = 0} (or {@code false}). Read once when the session
     * starts. {@link #resendSearchAsync} and {@link #setSearchViewAsync} serve them too.
     */
    boolean highlightsEnabled();

    /**
     * Hears the attached editor's window's highlights, on a session thread (hop to the UI thread
     * yourself), lowest priority first. An empty list means none.
     */
    void addHighlightsListener(Consumer<Highlights> listener);

    void removeHighlightsListener(Consumer<Highlights> listener);

    /**
     * Has Neovim send the matches of {@code buffer}'s window again, after every key queued before:
     * the editor is back in Neovim mode and has none painted. Matches are otherwise sent only when
     * they change, or when the window or buffer is entered (an attach). Nothing when
     * {@code buffer} is no longer the one shown.
     */
    void resendSearchAsync(DocumentBuffer buffer);

    /**
     * The editor of {@code buffer} shows lines {@code top1} to {@code bottom1} (1-based): Neovim
     * searches them along with its window's own visible lines, which differ from the editor's when
     * the viewport feature is off, when Eclipse scrolled alone in insert mode, when Neovim does not
     * know Eclipse's folds, and by a partly shown last line. After every key queued before; views
     * that arrive faster than Neovim answers replace each other. Nothing when {@code buffer} is no
     * longer the one shown.
     */
    void setSearchViewAsync(DocumentBuffer buffer, int top1, int bottom1);

    /**
     * The matches Neovim's window shows for buffer {@code bufnr}.
     *
     * @param bufnr   Neovim's buffer number
     * @param matches in the order Neovim found them, top to bottom
     */
    record Matches(int bufnr, List<SearchMatch> matches) {

        public Matches {
            matches = List.copyOf(matches);
        }
    }

    /**
     * The highlights Neovim's window shows for buffer {@code bufnr}.
     *
     * @param bufnr      Neovim's buffer number
     * @param highlights lowest priority first: the last one lies on top
     */
    record Highlights(int bufnr, List<NeovimHighlight> highlights) {
        public Highlights {
            highlights = List.copyOf(highlights);
        }
    }

    /** The lines an editor shows, for {@code setSearchViewAsync}. */
    record View(DocumentBuffer buffer, int top1, int bottom1) {
    }
}

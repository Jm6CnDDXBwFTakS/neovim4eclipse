// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.ui.IEditorSite;


/**
 * The one Neovim item in Eclipse's status bar for the page editors shown from the editing Neovim
 * (the messages pages): the mode, with the register being recorded, and the pending command. Those
 * belong to Neovim, not to a window (there is one mode, whichever page has the focus), so one item
 * serves every messages editor in a status bar: the first one there makes it, each feeds it while
 * it has the focus, the last one to close removes and disposes it. No item is ever "the wrong
 * page's", and two halves of a split never put two in one bar. The status line itself is drawn in
 * each page, below its text ({@code WindowPane}).
 *
 * <p>Eclipse gives all editors of one kind one status line manager in a window, so the item is kept
 * by that manager. UI thread.
 */
final class PageStatus {

    /** One item and how many messages editors use it. */
    private static final class Shared {
        final ModeStatusContribution item;
        int users;

        Shared(ModeStatusContribution item) {
            this.item = item;
        }
    }

    private static final Map<IStatusLineManager, Shared> SHARED = new HashMap<>();

    private final IEditorSite site;
    private final Shared shared;
    private boolean released;

    /** Takes the shared item of {@code site}'s status bar, making it if this is the first page there. */
    PageStatus(IEditorSite site) {
        this.site = site;
        IStatusLineManager statusLine = site.getActionBars().getStatusLineManager();
        Shared found = SHARED.get(statusLine);
        if (found == null) {
            found = new Shared(new ModeStatusContribution(ModeStatusContribution.PAGE_ID));
            SHARED.put(statusLine, found);
            found.item.addTo(site);
        }
        found.users++;
        this.shared = found;
    }

    void setMode(String mode) {
        shared.item.setMode(mode);
    }

    void setRecording(String register) {
        shared.item.setRecording(register);
    }

    /** This page is done with the item; the last one removes it from the bar and frees its fonts. */
    void release() {
        if (released) {
            return;
        }
        released = true;
        if (--shared.users == 0) {
            SHARED.values().remove(shared);
            shared.item.removeFrom(site);
            shared.item.dispose();
        }
    }
}

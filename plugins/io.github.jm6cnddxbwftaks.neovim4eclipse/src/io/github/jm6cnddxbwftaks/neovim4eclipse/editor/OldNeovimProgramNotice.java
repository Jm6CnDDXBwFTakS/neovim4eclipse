// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.HeadedMessageDialog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OldNeovimProgram;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * Shows, once, the note for a "Neovim program" preference still stored in this workspace
 * ({@link OldNeovimProgram}: the words, and when there is something to show), then removes the value
 * so the note never comes back. Called by {@code EarlyStartup} at Eclipse's start, before Get Neovim
 * may open by itself: the old value no longer counts, so without the note someone who named a Neovim
 * of their own (FreeBSD, NixOS) would see Get Neovim and not know why.
 *
 * <p>The value is removed when the note is shown, not when it is closed: an Eclipse that is shut
 * down with the note open does not show it again at every start. What comes next at the start
 * (Get Neovim's offer) waits until the note is closed ({@code then}): queued at once, it would open
 * inside the note's own event loop, one dialog over the other.
 */
public final class OldNeovimProgramNotice {

    private static final Logger LOG = Logger.getLogger(OldNeovimProgramNotice.class.getName());

    private OldNeovimProgramNotice() {
    }

    /**
     * Shows the note if a value is stored, on the UI thread, and removes the value; then runs
     * {@code then}, after the note is closed, or at once (on this thread) when there is no value.
     * Any thread. {@code then} never runs on the UI thread: Get Neovim's start offer may hash the
     * dedicated Neovim's files first (0.1 to 0.2 s), which EarlyStartup keeps off the UI thread.
     */
    public static void showOnce(Runnable then) {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String stored = store.getString(PreferenceConstants.REMOVED_NVIM_PROGRAM);
        Optional<HeadedMessage> message = OldNeovimProgram.message(stored, NeovimOption.value(),
                PlatformUtil.isWindows());
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (!OldNeovimProgram.isStored(stored) || display == null || display.isDisposed()) {
            then.run();
            return;
        }
        display.asyncExec(() -> {
            remove(store); // also when no note shows: Eclipse already runs with the option
            if (message.isEmpty()) {
                CompletableFuture.runAsync(then);
                return;
            }
            Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null ? null
                    : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
            new HeadedMessageDialog(shell, OldNeovimProgram.TITLE, MessageDialog.INFORMATION, message.get(), 0,
                    OldNeovimProgram.BUTTONS.toArray(String[]::new)).open();
            CompletableFuture.runAsync(then); // off the UI thread, as EarlyStartup runs it
        });
    }

    /** Removes the stored value, and saves at once, not at Eclipse's exit, so a crash does not bring it back. */
    private static void remove(IPreferenceStore store) {
        store.setToDefault(PreferenceConstants.REMOVED_NVIM_PROGRAM);
        if (store instanceof IPersistentPreferenceStore persistent) {
            try {
                persistent.save();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not save the removal of the old Neovim program preference", e);
            }
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Supplier;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.link.ILinkedModeListener;
import org.eclipse.jface.text.link.LinkedModeModel;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LinkedEditInsert;

/**
 * Ends Neovim's insert mode when the rename it was begun for is accepted with Enter (see
 * {@link LinkedEditInsert} for when, and why). Example: Alt+Shift+R on {@code count} in normal mode,
 * {@code eaTotal}, Enter: the name is {@code countTotal} everywhere and Neovim is in normal mode.
 *
 * <p><b>How it learns that the rename ended.</b> Eclipse takes that Enter before the plug-in's key
 * listener sees it, and leaves linked mode only a moment later, from the UI queue. The Java rename
 * then runs its refactoring right there, inside a nested event loop. So no check made at the key
 * press is right: too early, linked mode is still on; posted to the UI queue, it runs in the middle
 * of the refactoring, and an Esc sent then made the refactoring stop with an error dialog.
 * Instead, when insert mode begins inside a linked edit, this class registers an
 * {@link ILinkedModeListener} on it. Eclipse calls the listeners in the order they were added, so
 * the rename's own listener (which performs the refactoring) has finished when this one hears
 * {@code left}. Its flags tell how linked mode ended: {@link ILinkedModeListener#UPDATE_CARET}
 * means the caret goes to the edit's exit, which is what Enter does; Esc and a click leave
 * without it.
 *
 * <p>UI thread only: the editor calls {@link #insertBegan} and {@link #insertEnded} there, and
 * Eclipse calls {@code left} there.
 */
final class LinkedEditWatch {

    private final Supplier<IDocument> document;
    /** Ends insert mode as an Esc would ({@code KeyInterceptor.leaveInsertMode}). */
    private final Runnable leaveInsertMode;
    private final LinkedEditInsert decision = new LinkedEditInsert();
    /** The linked edit this insert began in, listened to until it or the insert ends; or {@code null}. */
    private LinkedModeModel watched;

    private final ILinkedModeListener listener = new ILinkedModeListener() {
        @Override
        public void left(LinkedModeModel model, int flags) {
            if (model != watched) {
                return;
            }
            watched = null; // Eclipse drops its listeners as it calls them
            if (decision.linkedEditEnded((flags & ILinkedModeListener.UPDATE_CARET) != 0)) {
                leaveInsertMode.run();
            }
        }

        @Override
        public void suspend(LinkedModeModel model) {
            // A nested linked edit (a template inside the rename) took over: nothing ends.
        }

        @Override
        public void resume(LinkedModeModel model, int flags) {
            // Back from the nested edit.
        }
    };

    LinkedEditWatch(Supplier<IDocument> document, Runnable leaveInsertMode) {
        this.document = document;
        this.leaveInsertMode = leaveInsertMode;
    }

    /** Neovim entered insert mode: if a linked edit is on, listen for its end. UI thread. */
    void insertBegan() {
        stopWatching();
        IDocument doc = document.get();
        // The offset is not used to choose: the model on top of the document's stack is returned.
        LinkedModeModel model = doc == null ? null : LinkedModeModel.getModel(doc, 0);
        decision.insertBegan(model != null);
        if (model != null) {
            watched = model;
            model.addLinkingListener(listener);
        }
    }

    /** Neovim left insert mode: the end of the linked edit no longer matters. UI thread. */
    void insertEnded() {
        decision.insertEnded();
        stopWatching();
    }

    private void stopWatching() {
        if (watched != null) {
            watched.removeLinkingListener(listener);
            watched = null;
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;

/**
 * What a {@link NeovimPageEditor} is opened on: only its kind ({@link PageKind}), no file. Two
 * inputs of the same kind are equal, so Eclipse's {@code openEditor} and {@code findEditor} find the
 * editor of that kind already open instead of opening a second one. Example:
 * {@code new NeovimPageInput(PageKind.HELP)} is named "Neovim: help".
 *
 * <p>Not saved with the workbench ({@link #getPersistable} is {@code null}): its second Neovim
 * ended with Eclipse, and there is nothing to show at the next start. Eclipse still keeps a tab for
 * it in its saved layout, which {@link NeovimPageEditor#closeLeftovers} closes at the start.
 */
final class NeovimPageInput implements IEditorInput {

    private final PageKind kind;

    NeovimPageInput(PageKind kind) {
        this.kind = kind;
    }

    PageKind kind() {
        return kind;
    }

    /** No file behind it: Eclipse lists it in no "recent files" and never asks to reopen it. */
    @Override
    public boolean exists() {
        return false;
    }

    /** None: the tab shows the editor's own icon, from {@code plugin.xml}. */
    @Override
    public ImageDescriptor getImageDescriptor() {
        return null;
    }

    @Override
    public String getName() {
        return ScreenInput.title(kind.title());
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return getName();
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NeovimPageInput input && input.kind == kind;
    }

    @Override
    public int hashCode() {
        return kind.hashCode();
    }

    @Override
    public String toString() {
        return getName();
    }
}

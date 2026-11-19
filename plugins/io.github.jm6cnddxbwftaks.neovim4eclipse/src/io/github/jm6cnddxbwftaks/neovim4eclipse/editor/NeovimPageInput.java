// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;

/**
 * What a {@link NeovimPageEditor} is opened on: its kind ({@link PageKind}) and which editor of it
 * ({@link #serial}), no file. The first inputs of a kind are equal, so Eclipse's {@code openEditor}
 * and {@code findEditor} find the editor of that kind already open instead of opening a second one;
 * a split's second half has an input of its own ({@link #another}). Example:
 * {@code new NeovimPageInput(PageKind.HELP)} is named "Neovim: help".
 *
 * <p>Not saved with the workbench ({@link #getPersistable} is {@code null}): its window was in the
 * Neovim that ended with Eclipse, and there is nothing to show at the next start. Eclipse still keeps a tab for
 * it in its saved layout, which {@link NeovimPageEditor#closeLeftovers} closes at the start.
 */
final class NeovimPageInput implements IEditorInput {

    private final PageKind kind;
    /**
     * Which editor of its kind this is: 0 for the first, the only one help and checkhealth have.
     * The messages page can be split ({@code :split} in it: a second editor, each half its own
     * Neovim window on the one messages buffer), and each half needs an input of its own, or
     * Eclipse's {@code findEditor} would take the second for the first. One buffer per kind, any
     * number of editors.
     */
    private final int serial;
    /** The last serial given out ({@link #another}). UI thread. */
    private static int lastSerial;

    NeovimPageInput(PageKind kind) {
        this(kind, 0);
    }

    private NeovimPageInput(PageKind kind, int serial) {
        this.kind = kind;
        this.serial = serial;
    }

    /** An input for another editor of {@code kind} (a split's second half), unequal to every other. UI thread. */
    static NeovimPageInput another(PageKind kind) {
        return new NeovimPageInput(kind, ++lastSerial);
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
        return other instanceof NeovimPageInput input && input.kind == kind && input.serial == serial;
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + serial;
    }

    @Override
    public String toString() {
        return getName();
    }
}

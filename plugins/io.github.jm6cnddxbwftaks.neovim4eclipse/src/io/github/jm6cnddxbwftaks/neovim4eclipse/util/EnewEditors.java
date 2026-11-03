// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The untitled editors {@code :enew} opened, known by their editor input. Their Neovim window gets
 * no folder of its own: it keeps the one it copies from the window it opens from, which is the
 * window of the editor {@code :enew} was typed in, {@code :lcd} included. That is what a Vim window
 * does when {@code :enew} empties it. Example: in {@code app/src/Main.java}, {@code :lcd src} then
 * {@code :enew} then {@code :w notes.txt} writes {@code app/src/notes.txt}. An untitled editor
 * opened from Eclipse's menu is not listed and takes the selected project's folder instead
 * ({@code editor/SelectedProject}).
 *
 * <p>Why a "first one asking" rule: the wizard {@code :enew} runs opens the editor, and Neovim may
 * attach to it (asking {@link #isOpenedByEnew}) before the wizard returns, while {@code :enew}
 * cannot know the editor's input in advance. So between {@link #opening} and {@link #opened}, the
 * first input asked about is taken as the one {@code :enew} opened, and only that one. When the
 * editor attaches later instead, {@code :enew} adds it ({@link #add}) once the wizard returns.
 *
 * <p>The inputs are held weakly: an entry goes with its editor. Keys are plain objects (Eclipse's
 * {@code IEditorInput} in the plug-in) so that this class needs no Eclipse; one thread (the UI
 * thread).
 */
public final class EnewEditors {

    private final Set<Object> inputs = Collections.newSetFromMap(new WeakHashMap<>());
    /** Between {@link #opening} and the first input asked about, or {@link #opened}. */
    private boolean waiting;

    /** {@code :enew} is about to run the wizard that opens its editor. */
    public void opening() {
        waiting = true;
    }

    /** The wizard returned: an input asked about from now on was not opened by {@code :enew}, unless {@link #add}ed. */
    public void opened() {
        waiting = false;
    }

    /** Lists {@code input} as opened by {@code :enew} (it had not asked while the wizard ran). */
    public void add(Object input) {
        if (input != null) {
            inputs.add(input);
        }
    }

    /**
     * Whether {@code :enew} opened the editor of {@code input}. While its wizard runs, the first
     * input asked about that is not listed yet is listed now (see the class comment), so ask only
     * for an editor that has no folder of its own: an untitled one.
     */
    public boolean isOpenedByEnew(Object input) {
        if (input == null) {
            return false;
        }
        if (waiting && inputs.add(input)) {
            waiting = false;
        }
        return inputs.contains(input);
    }
}

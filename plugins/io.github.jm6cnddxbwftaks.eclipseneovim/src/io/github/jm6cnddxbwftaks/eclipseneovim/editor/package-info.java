// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * Eclipse editor integration: attach lifecycle, key interception, status line, caret style, visual
 * selection mirroring, and the {@code :} command line.
 *
 * <h2>Who does what</h2>
 * <ul>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.editor.EditorAttachManager}: one per IDE. Watches
 *       the workbench (part listeners) and attaches Neovim to the active text editor.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.editor.NeovimEditorController}: one per attached
 *       editor. Wires the editor's viewer and widget to the shared
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession}, through one helper per
 *       concern: {@code CaretSync} (caret and cursor, seeing the caret through
 *       {@code EditorCaret} so it is tested without a widget), {@code EditorKeyContext} (when the Vim
 *       Ctrl-key bindings are active), {@code VisualSelectionView} (shows the blockwise rectangle
 *       through {@code BlockHighlight}, painted by {@code BlockOverlay}; so it too is tested without
 *       a widget), {@code InsertTracker}.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.editor.KeyInterceptor}: the key listener; it
 *       asks {@link io.github.jm6cnddxbwftaks.eclipseneovim.core.KeyRouter} whether Neovim or Eclipse
 *       handles each key.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.editor.SendKeyHandler},
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.editor.VimKeyContexts}: Ctrl keys that Eclipse
 *       would otherwise take first (key bindings and contexts).</li>
 *   <li>{@code CommandLineBox}, {@code OutputPopup}, {@code OverlayFrame}: the widgets drawn over the
 *       editor; {@link io.github.jm6cnddxbwftaks.eclipseneovim.editor.ModeStatusContribution} and
 *       {@code CursorStyleController}: status bar and caret.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * Everything here runs on the SWT UI thread unless a comment says otherwise. Session callbacks
 * arrive on other threads and must hop over with {@code Display.asyncExec} before touching a widget
 * or a document. See {@code docs/LEARNING.md}, part 1.5.
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Whether the end of one of Eclipse's linked edits also ends Neovim's insert mode.
 *
 * <p><b>Linked edits.</b> Eclipse's in-editor rename (Alt+Shift+R) and the fields of a template
 * (content assist's {@code fore}, {@code sysout}) are <em>linked mode</em>: the occurrences of a
 * name change together, Tab moves between fields, and Enter or Esc leaves. Eclipse takes those keys
 * before Neovim gets them.
 *
 * <p><b>Which end ends the insert.</b> It depends on where the insert began and how the linked edit
 * ended:
 * <ul>
 *   <li><b>Insert begun inside the linked edit, which Enter accepts</b>: a rename started in normal
 *       mode, then {@code ea} and a suffix typed, then Enter. The insert existed only to type the new
 *       name, as Vim's {@code ciw} ends with the change, so it ends too. Without this, Neovim stayed
 *       in insert mode, and so did the next editor the user switched to (Neovim has one mode for all
 *       its windows).</li>
 *   <li><b>Insert begun before the linked edit</b>: a template inserted while typing. Its Enter
 *       leaves the fields and puts the caret where typing goes on (the body of a {@code for} loop):
 *       insert mode stays.</li>
 *   <li><b>Any other end</b> (a click elsewhere, an edit outside the name): insert mode stays, as a
 *       click does in Vim. Esc is handled where keys are ({@code editor/KeyInterceptor}).</li>
 * </ul>
 *
 * <p>Pure, so {@code LinkedEditInsertTest} checks it without Eclipse; {@code editor/LinkedEditWatch}
 * tells it when an insert begins and ends and when the linked edit ends. One instance per editor;
 * one thread (the SWT UI thread).
 */
public final class LinkedEditInsert {

    /** Whether the insert going on began while a linked edit was active; false outside insert mode. */
    private boolean insertBeganInLinkedEdit;

    /**
     * Insert mode began.
     *
     * @param linkedEditActive whether one of Eclipse's linked edits was active then
     */
    public void insertBegan(boolean linkedEditActive) {
        insertBeganInLinkedEdit = linkedEditActive;
    }

    /** Insert mode ended (Esc, or any other way). */
    public void insertEnded() {
        insertBeganInLinkedEdit = false;
    }

    /**
     * The linked edit that was active when the insert began has ended. Answers once: the insert's
     * linked edit is over either way.
     *
     * @param acceptedWithEnter whether Enter ended it, putting the caret at its exit
     * @return whether insert mode should end too
     */
    public boolean linkedEditEnded(boolean acceptedWithEnter) {
        boolean ends = insertBeganInLinkedEdit && acceptedWithEnter;
        insertBeganInLinkedEdit = false;
        return ends;
    }
}

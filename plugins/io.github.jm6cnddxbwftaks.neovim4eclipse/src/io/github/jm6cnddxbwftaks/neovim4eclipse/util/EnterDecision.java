// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What Eclipse does when Neovim enters a buffer in a window ({@code BufEnter}, reported by
 * {@code LuaScripts.SETUP}): the one decision behind {@code core/DocumentBuffers.onEntered}, kept
 * pure so that each case is a unit test.
 *
 * <p>Eclipse shows a Neovim window only as an editor: a text editor's window shows that editor's
 * document, and a page's window (a page editor shown from this Neovim) shows its page. So when
 * Neovim puts something else in a window by itself, Eclipse either follows (opens or activates the
 * editor of that file) or puts the window back as it was.
 *
 * <table>
 *   <caption>The decisions</caption>
 *   <tr><th>Window</th><th>Buffer entered</th><th>{@link Action}</th></tr>
 *   <tr><td>a page's, with the focus</td><td>its page</td><td>{@link Action#ACCEPT}: the focus moved there</td></tr>
 *   <tr><td>a page's, without the focus</td><td>its page</td><td>{@link Action#RETURN_BACK}: Neovim went there by itself</td></tr>
 *   <tr><td>a page's</td><td>an open document ({@code gf}, {@code :b 3})</td><td>{@link Action#PAGE_BACK_THEN_FOLLOW}</td></tr>
 *   <tr><td>a page's</td><td>a file without an editor</td><td>{@link Action#PAGE_BACK_THEN_OPEN_FILE}</td></tr>
 *   <tr><td>a page's</td><td>anything else (a scratch buffer)</td><td>{@link Action#PAGE_BACK}</td></tr>
 *   <tr><td>the attached editor's</td><td>its document</td><td>{@link Action#ACCEPT}: Eclipse's own switch</td></tr>
 *   <tr><td>the attached editor's</td><td>another open document</td><td>{@link Action#FOLLOW}</td></tr>
 *   <tr><td>the attached editor's</td><td>a file without an editor</td><td>{@link Action#OPEN_FILE}</td></tr>
 *   <tr><td>the attached editor's</td><td>anything else (help, a scratch buffer)</td><td>{@link Action#RETURN_BACK}</td></tr>
 *   <tr><td>a plug-in's float Neovim stays in, a picker's prompt or a hover float entered ({@code LuaScripts.WINDOWS}' {@code entered_float})</td><td>no
 *       document, no project file</td><td>{@link Action#ACCEPT}: the prompt is Neovim's while it is
 *       open; when it closes, Neovim enters a window again, and that enter decides</td></tr>
 *   <tr><td>any other</td><td>anything</td><td>{@link Action#RETURN_BACK}</td></tr>
 * </table>
 *
 * <p>The rule a page adds, easy to check in one place: a page's window never shows a file's
 * buffer. Whatever put one there, the window gets its page back, and the file goes to an editor of
 * its own, without the page's window travelling (a page window never swaps with a text editor's).
 */
public final class EnterDecision {

    /** A window or buffer number that is none: no page, an old sender that named no window. */
    public static final int NONE = -1;

    /** What Eclipse does. */
    public enum Action {
        /** Nothing: the switch is Eclipse's own, or the focus moving to a page. */
        ACCEPT,
        /** The editor of the document entered takes over (it may travel into the window, {@code Ctrl-O}). */
        FOLLOW,
        /** The file entered gets an editor; the attached window travels into it. */
        OPEN_FILE,
        /**
         * Neovim goes back to the attached editor's window and document; from a window Eclipse
         * does not show, not while Neovim is by then in another plug-in's float the editor draws (a
         * picker's preview float entered only for the moment of a call: {@code core/DocumentBuffers.returnFromEnter}).
         */
        RETURN_BACK,
        /** The page's window gets its page back; nothing opens. */
        PAGE_BACK,
        /** The page's window gets its page back, and the document's editor comes to the front, in its own window. */
        PAGE_BACK_THEN_FOLLOW,
        /** The page's window gets its page back, and the file gets an editor, in its own window. */
        PAGE_BACK_THEN_OPEN_FILE
    }

    /**
     * What Neovim did and what Eclipse knows.
     *
     * @param window       the window entered; {@link #NONE} when the sender named none
     * @param buffer       the buffer entered
     * @param attachedWin  the attached text editor's window
     * @param attachedBuf  the attached document's buffer
     * @param focusedPage  the window of the page with the focus, or {@link #NONE}
     * @param pageBuffer   the page's buffer when {@code window} is a page's window, else {@link #NONE}
     * @param document     whether {@code buffer} is an open document's
     * @param projectFile  whether {@code buffer} is a file Eclipse can open that has no editor yet
     * @param floatEntered whether {@code window} is a plug-in's float Neovim stays in: a picker's
     *                     prompt, {@code :Lazy}, a hover float entered by a second {@code K}
     *                     ({@code LuaScripts.WINDOWS}' {@code entered_float})
     */
    public record Enter(int window, int buffer, int attachedWin, int attachedBuf, int focusedPage, int pageBuffer,
            boolean document, boolean projectFile, boolean floatEntered) {
    }

    private EnterDecision() {
    }

    public static Action decide(Enter e) {
        if (e.floatEntered() && !e.document() && !e.projectFile()) {
            // A plug-in's float with the cursor (a picker's prompt): Neovim stays. Were it sent back,
            // the insert its plug-in starts would begin in the editor's window, where Eclipse types
            // (KeyInput.onModeReport). One the editor does not draw is closed (WINDOWS' close_undrawn). Leaving the editor moves Neovim out of it (WINDOWS' move_out_of_float). Telescope's picks come
            // from the window it returns to (:edit there), which this decides as any other enter.
            // A document or a file shown in it goes the usual way below.
            return Action.ACCEPT;
        }
        if (e.window() != NONE && e.pageBuffer() != NONE) {
            if (e.buffer() == e.pageBuffer()) {
                return e.window() == e.focusedPage() ? Action.ACCEPT : Action.RETURN_BACK;
            }
            if (e.document()) {
                return Action.PAGE_BACK_THEN_FOLLOW;
            }
            return e.projectFile() ? Action.PAGE_BACK_THEN_OPEN_FILE : Action.PAGE_BACK;
        }
        boolean attachedWindow = e.window() == NONE || e.window() == e.attachedWin();
        if (!attachedWindow) {
            return Action.RETURN_BACK; // a :tabnext the user typed, the window Neovim entered after a :tabclose
        }
        if (e.buffer() == e.attachedBuf()) {
            return Action.ACCEPT;
        }
        if (e.document()) {
            return Action.FOLLOW;
        }
        return e.projectFile() ? Action.OPEN_FILE : Action.RETURN_BACK;
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What a page editor does with a command Neovim hands to Eclipse ({@link HostCommand}) when the
 * command ran in the page's own window: the table for pages shown from a window of the editing
 * Neovim (the messages page first). A pure decision, so each row is a unit test; the page editor
 * carries it out.
 *
 * <p>The rule behind it: a page is a Neovim window that shows text no file has. So what Vim does
 * to <em>the current window</em> happens to the page ({@code :q} closes it, {@code :split} gives
 * a second view of it, {@code Ctrl-W l} moves on from it), what Vim does to <em>the window's
 * file</em> goes to the code instead ({@code :e file} opens the file in an editor in the tab group
 * the page came from, since the page cannot show a file), and what is about all editors
 * ({@code :wa}, {@code :qa}) is done as from the code. Writing the page is refused, with Vim's own
 * error for a buffer that is no file's.
 *
 * <table>
 *   <caption>Commands typed in a page</caption>
 *   <tr><th>Command</th><th>{@link Action}</th></tr>
 *   <tr><td>{@code :q}, {@code :close}, {@code :bd} (no buffer named), {@code :x}</td><td>{@link Action#CLOSE_PAGE}</td></tr>
 *   <tr><td>{@code :split}, {@code :vsplit}, {@code Ctrl-W s}/{@code v}, {@code :tab split}</td><td>{@link Action#SECOND_PAGE}</td></tr>
 *   <tr><td>{@code Ctrl-W h j k l w p}, {@code :only}, sizes, {@code :new}, {@code :vsplit b.txt},
 *       {@code :tabnew}, {@code gt}</td><td>{@link Action#FROM_PAGE}</td></tr>
 *   <tr><td>{@code :e b.txt}, {@code :enew}</td><td>{@link Action#IN_CODE_GROUP}</td></tr>
 *   <tr><td>{@code :wa}, {@code :qa}, {@code :wqa}, {@code :bd 3}, {@code :terminal}</td><td>{@link Action#AS_FROM_CODE}</td></tr>
 *   <tr><td>{@code :wq}, {@code :e}, {@code :e!}</td><td>{@link Action#REFUSE}, with Vim's error</td></tr>
 * </table>
 */
public final class PageCommands {

    /** What the page does. */
    public enum Action {
        /** Close this page editor and its window. */
        CLOSE_PAGE,
        /**
         * A second page editor of the same kind, its own window on the same buffer, placed as the
         * {@link WindowCommand} says (a new tab group beside, or a tab of this group).
         */
        SECOND_PAGE,
        /**
         * The command as from a code editor, with the page as the current editor: the focus moves
         * on from the page, the page's group is sized, a new untitled editor or a file opens beside
         * the page, the page's group switches tabs.
         */
        FROM_PAGE,
        /**
         * The file (or a new untitled editor) opens in the tab group of the code the page came
         * from; the page keeps its text.
         */
        IN_CODE_GROUP,
        /** The command as the attached code editor would run it: it is about every editor, not the page. */
        AS_FROM_CODE,
        /** Nothing happens; {@link Decision#error()} says why, as Vim says it. */
        REFUSE
    }

    /**
     * @param action what to do
     * @param error  for {@link Action#REFUSE}, Vim's message; "" otherwise
     */
    public record Decision(Action action, String error) {

        static Decision of(Action action) {
            return new Decision(action, "");
        }
    }

    /** Vim's error for writing a buffer whose {@code 'buftype'} is set ({@code :h E382}). */
    static final String CANNOT_WRITE = "E382: Cannot write, 'buftype' option is set";
    /** Vim's error for {@code :e} with no file in a buffer that has none ({@code :h E32}). */
    static final String NO_FILE_NAME = "E32: No file name";
    /** Why {@code :file name} does nothing in a page: a page is no file to rename. */
    static final String NO_FILE_TO_RENAME = "file: a page has no file to rename";

    private PageCommands() {
    }

    /** What a page does with {@code command}, typed in its own window. */
    public static Decision decide(HostCommand command) {
        return switch (command.kind()) {
            case QUIT -> Decision.of(Action.CLOSE_PAGE);
            // :wq always writes (E382 in a page); :x and :exit write only a changed buffer, and a
            // page never changes, so they just close it, as Vim's :x does in such a buffer.
            case WRITE_QUIT -> command.writesUnchanged() ? new Decision(Action.REFUSE, CANNOT_WRITE)
                    : Decision.of(Action.CLOSE_PAGE);
            case EDIT -> command.args().isEmpty() ? new Decision(Action.REFUSE, NO_FILE_NAME)
                    : Decision.of(Action.IN_CODE_GROUP);
            case NEW_UNTITLED -> Decision.of(Action.IN_CODE_GROUP);
            case RENAME -> new Decision(Action.REFUSE, NO_FILE_TO_RENAME);
            case WRITE_ALL, QUIT_ALL, WRITE_QUIT_ALL, BUFFER_DELETE, TERMINAL -> Decision.of(Action.AS_FROM_CODE);
            case TAB_NEXT, TAB_PREVIOUS, TAB_FIRST, TAB_LAST -> Decision.of(Action.FROM_PAGE);
            case WINDOW -> window(command.window());
        };
    }

    private static Decision window(WindowCommand window) {
        if (window == null) {
            return Decision.of(Action.FROM_PAGE); // HostCommand.of already refused unknown words
        }
        boolean samePage = window.target() == WindowCommand.Target.SAME
                && (window.action() == WindowCommand.Action.SPLIT || window.action() == WindowCommand.Action.TAB);
        return Decision.of(samePage ? Action.SECOND_PAGE : Action.FROM_PAGE);
    }
}

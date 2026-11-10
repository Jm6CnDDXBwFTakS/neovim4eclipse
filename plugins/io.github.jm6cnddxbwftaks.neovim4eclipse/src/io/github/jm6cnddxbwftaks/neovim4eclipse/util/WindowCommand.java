// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;

/**
 * One of Vim's window commands, as Neovim hands it to Eclipse ({@code :Eclipse window ...}), where
 * each Vim window is an Eclipse editor and each split an editor stack (docs/design/SPLITS.md).
 *
 * <p>Neovim works out everything Vim would before it sends one: where a split goes (from
 * {@code 'splitbelow'}, {@code 'splitright'} and {@code :aboveleft} and the like), which file it
 * shows ({@code :sfind} searches {@code 'path'}, {@code :sbuffer 3} names buffer 3's file,
 * {@code Ctrl-W ^} the alternate file), and the count. So Eclipse gets plain words, and this
 * record only reads them; {@code LuaScripts.WINDOW_COMMANDS} writes them.
 *
 * <table>
 *   <caption>The words after {@code window}, and what sends them</caption>
 *   <tr><th>Words</th><th>From</th></tr>
 *   <tr><td>{@code split below same}</td><td>{@code :split}, {@code Ctrl-W s}, Toggle Split Editor</td></tr>
 *   <tr><td>{@code split right file /ws/app/b.txt}</td><td>{@code :vsplit b.txt}, {@code :sfind b.txt}, {@code Ctrl-W f}</td></tr>
 *   <tr><td>{@code split above new}</td><td>{@code :new}, {@code Ctrl-W n}</td></tr>
 *   <tr><td>{@code focus l 2}</td><td>{@code 2 Ctrl-W l}, {@code :2wincmd l}; keys {@code h j k l w W t b p}</td></tr>
 *   <tr><td>{@code only}</td><td>{@code :only}, {@code Ctrl-W o}</td></tr>
 *   <tr><td>{@code size + 5}</td><td>{@code 5 Ctrl-W +}, {@code :resize +5}; keys {@code = + - < > _ |}</td></tr>
 *   <tr><td>{@code exchange 0}</td><td>{@code Ctrl-W x}</td></tr>
 *   <tr><td>{@code rotate down 1}</td><td>{@code Ctrl-W r}; {@code up} for {@code Ctrl-W R}</td></tr>
 * </table>
 *
 * <p>A count of 0 means none was given, as Vim's {@code v:count}: {@code Ctrl-W w} goes to the next
 * window, {@code 3 Ctrl-W w} to window 3.
 *
 * @param action what to do
 * @param where  for {@link Action#SPLIT}: the side of the current editor the new one goes to
 * @param target for {@link Action#SPLIT}: what the new editor shows
 * @param file   for {@link Target#FILE}: the file's full path (Neovim made it absolute); "" otherwise
 * @param key    for {@link Action#FOCUS} and {@link Action#SIZE}: Vim's key after {@code Ctrl-W}
 *               ({@code 'l'}, {@code '+'}); for {@link Action#ROTATE} {@code 'r'} (down or right) or
 *               {@code 'R'} (up or left); 0 otherwise
 * @param count  Vim's count, 0 when none was given
 */
public record WindowCommand(Action action, Where where, Target target, String file, char key, int count) {

    /** What the command does. */
    public enum Action {
        /** A new editor in a new stack beside the current one. */
        SPLIT,
        /** Go to another editor on screen. */
        FOCUS,
        /** Every other stack's editors join the current stack, as tabs; nothing is closed. */
        ONLY,
        /** Change the current editor's height or width, or make them all equal. */
        SIZE,
        /** Swap the current editor with the next one in its row or column. */
        EXCHANGE,
        /** Move every editor in the row or column one place on. */
        ROTATE
    }

    /** The side of the current editor a split goes to. */
    public enum Where {
        ABOVE, BELOW, LEFT, RIGHT;

        /** Whether the two editors end up side by side ({@code :vsplit}), not one above the other. */
        public boolean sideBySide() {
            return this == LEFT || this == RIGHT;
        }
    }

    /** What a split's new editor shows. */
    public enum Target {
        /** The current editor's file, in a second editor (Window &gt; Editor &gt; Clone). */
        SAME,
        /** Another file, by {@link WindowCommand#file()}. */
        FILE,
        /** A new untitled editor ({@code :new}). */
        NEW
    }

    /** The keys {@link Action#FOCUS} takes. */
    static final String FOCUS_KEYS = "hjklwWtbp";
    /** The keys {@link Action#SIZE} takes. */
    static final String SIZE_KEYS = "=+-<>_|";

    public WindowCommand {
        file = file == null ? "" : file;
    }

    /**
     * Reads the words Neovim sent after {@code window}, or returns {@code null} when they are not a
     * window command (a hand-typed {@code :Eclipse window} with a typo). The caller shows an error.
     *
     * <p>Examples: {@code [split, below, same]}, {@code [split, right, file, /ws/a.txt]},
     * {@code [focus, l, 2]}, {@code [size, =, 0]}, {@code [only]}.
     */
    public static WindowCommand parse(List<String> words) {
        if (words == null || words.isEmpty()) {
            return null;
        }
        try {
            return switch (words.get(0)) {
                case "split" -> split(words);
                case "focus" -> keyed(Action.FOCUS, FOCUS_KEYS, words);
                case "size" -> keyed(Action.SIZE, SIZE_KEYS, words);
                case "only" -> words.size() == 1 ? new WindowCommand(Action.ONLY, null, null, "", (char) 0, 0) : null;
                case "exchange" -> words.size() == 2
                        ? new WindowCommand(Action.EXCHANGE, null, null, "", (char) 0, count(words.get(1))) : null;
                case "rotate" -> rotate(words);
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null; // an unknown side or a count that is not a number
        }
    }

    private static WindowCommand split(List<String> words) {
        if (words.size() < 3) {
            return null;
        }
        Where where = Where.valueOf(words.get(1).toUpperCase(Locale.ROOT));
        return switch (words.get(2)) {
            case "same" -> words.size() == 3 ? new WindowCommand(Action.SPLIT, where, Target.SAME, "", (char) 0, 0) : null;
            case "new" -> words.size() == 3 ? new WindowCommand(Action.SPLIT, where, Target.NEW, "", (char) 0, 0) : null;
            // The path is one word: Neovim's :Eclipse line backslashes its spaces (fargs).
            case "file" -> words.size() == 4 && !words.get(3).isEmpty()
                    ? new WindowCommand(Action.SPLIT, where, Target.FILE, words.get(3), (char) 0, 0) : null;
            default -> null;
        };
    }

    private static WindowCommand keyed(Action action, String keys, List<String> words) {
        if (words.size() != 3 || words.get(1).length() != 1 || keys.indexOf(words.get(1).charAt(0)) < 0) {
            return null;
        }
        return new WindowCommand(action, null, null, "", words.get(1).charAt(0), count(words.get(2)));
    }

    private static WindowCommand rotate(List<String> words) {
        if (words.size() != 3) {
            return null;
        }
        char key = switch (words.get(1)) {
            case "down" -> 'r';
            case "up" -> 'R';
            default -> 0;
        };
        return key == 0 ? null : new WindowCommand(Action.ROTATE, null, null, "", key, count(words.get(2)));
    }

    /** A count word: digits only, at most 6 of them (Vim's counts are small). */
    private static int count(String word) {
        if (!word.matches("\\d{1,6}")) {
            throw new IllegalArgumentException("not a count: " + word);
        }
        return Integer.parseInt(word);
    }
}

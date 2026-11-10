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
 *   <tr><td>{@code tab file /ws/app/b.txt}, {@code tab new}, {@code tab same}</td><td>{@code :tabedit b.txt},
 *       {@code :tabnew}, {@code :tab split}: a new editor tab in this stack (Vim's tab pages are
 *       Eclipse's editor tabs here)</td></tr>
 *   <tr><td>{@code totab}</td><td>{@code Ctrl-W T}: this editor out of its split, as a tab of the first other stack</td></tr>
 *   <tr><td>{@code tabclose 2 0}, {@code tabclose $ 0}, {@code tabclose +1 1}</td><td>{@code :tabclose 2},
 *       {@code :tabclose $}, {@code :tabclose! +1}: another editor tab of this stack; the last word
 *       is the {@code !}</td></tr>
 * </table>
 *
 * <p>A count of 0 means none was given, as Vim's {@code v:count}: {@code Ctrl-W w} goes to the next
 * window, {@code 3 Ctrl-W w} to window 3.
 *
 * @param action what to do
 * @param where  for {@link Action#SPLIT}: the side of the current editor the new one goes to
 * @param target for {@link Action#SPLIT} and {@link Action#TAB}: what the new editor shows
 * @param file   for {@link Target#FILE}: the file's full path (Neovim made it absolute); "" otherwise
 * @param key    for {@link Action#FOCUS} and {@link Action#SIZE}: Vim's key after {@code Ctrl-W}
 *               ({@code 'l'}, {@code '+'}); for {@link Action#ROTATE} {@code 'r'} (down or right) or
 *               {@code 'R'} (up or left); for {@link Action#TAB_CLOSE} {@code '$'} (the last tab),
 *               {@code '+'} or {@code '-'} (that many tabs on or back) or 0 (tab number
 *               {@code count}); 0 otherwise
 * @param count  Vim's count, 0 when none was given
 * @param force  for {@link Action#TAB_CLOSE}: the {@code !}, which discards the tab's changes
 */
public record WindowCommand(Action action, Where where, Target target, String file, char key, int count,
        boolean force) {

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
        ROTATE,
        /** A new editor tab in this editor's stack ({@code :tabedit}, {@code :tabnew}, {@code :tab split}). */
        TAB,
        /** This editor out of its split, as a tab of another stack ({@code Ctrl-W T}). */
        TO_TAB,
        /** Close another editor tab of this stack ({@code :tabclose 2}). */
        TAB_CLOSE
    }

    /** The side of the current editor a split goes to. */
    public enum Where {
        ABOVE, BELOW, LEFT, RIGHT;

        /** Whether the two editors end up side by side ({@code :vsplit}), not one above the other. */
        public boolean sideBySide() {
            return this == LEFT || this == RIGHT;
        }
    }

    /** A command of {@code action} with only a key and a count. */
    private static WindowCommand of(Action action, char key, int count) {
        return new WindowCommand(action, null, null, "", key, count, false);
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
                case "only" -> words.size() == 1 ? of(Action.ONLY, (char) 0, 0) : null;
                case "exchange" -> words.size() == 2 ? of(Action.EXCHANGE, (char) 0, count(words.get(1))) : null;
                case "rotate" -> rotate(words);
                case "tab" -> opening(Action.TAB, null, words, 1);
                case "totab" -> words.size() == 1 ? of(Action.TO_TAB, (char) 0, 0) : null;
                case "tabclose" -> tabClose(words);
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null; // an unknown side or a count that is not a number
        }
    }

    private static WindowCommand split(List<String> words) {
        if (words.size() < 2) {
            return null;
        }
        return opening(Action.SPLIT, Where.valueOf(words.get(1).toUpperCase(Locale.ROOT)), words, 2);
    }

    /** A split or a tab: what it shows is at {@code at} ({@code same}, {@code new}, {@code file path}). */
    private static WindowCommand opening(Action action, Where where, List<String> words, int at) {
        if (words.size() <= at) {
            return null;
        }
        int rest = words.size() - at;
        return switch (words.get(at)) {
            case "same" -> rest == 1 ? new WindowCommand(action, where, Target.SAME, "", (char) 0, 0, false) : null;
            case "new" -> rest == 1 ? new WindowCommand(action, where, Target.NEW, "", (char) 0, 0, false) : null;
            // The path is one word: Neovim's :Eclipse line backslashes its spaces (fargs).
            case "file" -> rest == 2 && !words.get(at + 1).isEmpty()
                    ? new WindowCommand(action, where, Target.FILE, words.get(at + 1), (char) 0, 0, false) : null;
            default -> null;
        };
    }

    /** {@code tabclose 2 0}: the tab ({@code N}, {@code $}, {@code +N}, {@code -N}, {@code +}, {@code -}) and the {@code !}. */
    private static WindowCommand tabClose(List<String> words) {
        if (words.size() != 3 || !(words.get(2).equals("0") || words.get(2).equals("1"))) {
            return null;
        }
        String tab = words.get(1);
        boolean force = words.get(2).equals("1");
        if (tab.equals("$")) {
            return new WindowCommand(Action.TAB_CLOSE, null, null, "", '$', 0, force);
        }
        char key = tab.startsWith("+") || tab.startsWith("-") ? tab.charAt(0) : 0;
        String number = key == 0 ? tab : tab.substring(1);
        int count = number.isEmpty() && key != 0 ? 0 : count(number);
        return new WindowCommand(Action.TAB_CLOSE, null, null, "", key, count, force);
    }

    private static WindowCommand keyed(Action action, String keys, List<String> words) {
        if (words.size() != 3 || words.get(1).length() != 1 || keys.indexOf(words.get(1).charAt(0)) < 0) {
            return null;
        }
        return of(action, words.get(1).charAt(0), count(words.get(2)));
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
        return key == 0 ? null : of(Action.ROTATE, key, count(words.get(2)));
    }

    /** A count word: digits only, at most 6 of them (Vim's counts are small). */
    private static int count(String word) {
        if (!word.matches("\\d{1,6}")) {
            throw new IllegalArgumentException("not a count: " + word);
        }
        return Integer.parseInt(word);
    }
}

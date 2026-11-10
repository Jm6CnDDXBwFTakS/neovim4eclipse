// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Which editor tab Vim's tab commands go to, as plain index arithmetic (0-based indexes, tabs in
 * the order shown).
 *
 * <table>
 *   <caption>Vim's rules</caption>
 *   <tr><th>Command</th><th>Without count</th><th>With count N</th></tr>
 *   <tr><td>{@code gt}, {@code :tabn}</td><td>next tab, wrapping</td><td>tab N (1-based)</td></tr>
 *   <tr><td>{@code gT}, {@code :tabp}</td><td>previous tab, wrapping</td><td>N tabs back, wrapping</td></tr>
 *   <tr><td>{@code :tabfirst}</td><td>first tab</td><td>-</td></tr>
 *   <tr><td>{@code :tablast}</td><td>last tab</td><td>-</td></tr>
 * </table>
 *
 * <p>Example: with 4 tabs and tab 1 (index 0) active, {@code gT} goes to index 3 and {@code 3gt}
 * to index 2.
 */
public final class TabNavigation {

    public enum Action {
        NEXT, PREVIOUS, FIRST, LAST
    }

    private TabNavigation() {
    }

    /**
     * @param current index of the active tab, or -1 if unknown (then NEXT/PREVIOUS start from the ends)
     * @param size    number of tabs
     * @param count   Vim's count; 0 when none was given
     * @return the index to activate, or {@code current} when there is nowhere to go ({@code 9gt} with
     *         4 tabs does nothing, as in Vim)
     */
    public static int target(Action action, int current, int size, int count) {
        if (size <= 0) {
            return current;
        }
        int from = current < 0 || current >= size ? -1 : current;
        return switch (action) {
            case FIRST -> 0;
            case LAST -> size - 1;
            case NEXT -> {
                if (count > 0) {
                    yield count <= size ? count - 1 : current;
                }
                yield (from + 1) % size;
            }
            case PREVIOUS -> {
                int steps = Math.max(count, 1) % size;
                int start = from < 0 ? 0 : from;
                yield Math.floorMod(start - steps, size);
            }
        };
    }

    /**
     * The tab {@code :tabclose} with an argument closes, as Vim counts: {@code :tabclose 2} the
     * second tab, {@code :tabclose $} the last, {@code :tabclose +} the next, {@code :tabclose -2}
     * two back. No wrapping: {@code :tabclose +} on the last tab names none, as in Vim (E16).
     *
     * @param key     {@code '$'}, {@code '+'}, {@code '-'}, or 0 for tab number {@code count}
     * @param count   the number after the key (0 for none: one tab on or back)
     * @param current index of the active tab, or -1 if unknown (then {@code +} and {@code -} name none)
     * @param size    number of tabs
     * @return the index to close, or -1 when there is no such tab
     */
    public static int closeTarget(char key, int count, int current, int size) {
        if (current < 0 && (key == '+' || key == '-')) {
            return -1; // no active tab to count from
        }
        int target = switch (key) {
            case '$' -> size - 1;
            case '+' -> current + Math.max(count, 1);
            case '-' -> current - Math.max(count, 1);
            default -> count - 1;
        };
        return target >= 0 && target < size ? target : -1;
    }
}

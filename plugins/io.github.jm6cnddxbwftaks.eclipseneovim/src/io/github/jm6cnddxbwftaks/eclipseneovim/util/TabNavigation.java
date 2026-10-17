// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

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
}

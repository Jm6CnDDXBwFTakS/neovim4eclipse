// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Where {@code Ctrl-O} and {@code Ctrl-I} go in Eclipse's navigation history once a Neovim
 * window's own jump history has run out (docs/design/WINDOWS.md, E2, "Back and Forward that
 * retrace"). Plain list arithmetic; the Eclipse calls are in {@code editor/NavigationHistorySteps}.
 *
 * <p><b>Two histories.</b> Each editor is a Neovim window with its own jump history, and Eclipse
 * keeps one history of editor switches (Back, {@code Alt+Left}). When a Neovim jump goes into a
 * file another editor shows, the plug-in activates that editor, and Eclipse records the switch as
 * if the user had made it. That has two effects, which this class undoes:
 * <ol>
 *   <li><b>Repeats.</b> The switch is in both histories. {@code Ctrl-O} first walks back through
 *       the Neovim jumps, then, as Back, through the same switches again. So Back steps over the
 *       entries the plug-in made ({@link #markOwn}): Neovim's history already has them.</li>
 *   <li><b>A lost way forward.</b> A new entry deletes every entry ahead of it, as a new page does
 *       in a browser. When {@code Ctrl-I} undoes a Neovim jump across files, or {@code Ctrl-O} makes
 *       one after a Back, the plug-in's switch adds such an entry, and Eclipse's Forward has
 *       nothing left. So every Back is remembered here ({@link #wentBack}), with what is needed to
 *       come back to where it started, and Forward undoes the Backs in reverse order
 *       ({@link #forward}): through Eclipse's Forward while the place is still in its history,
 *       else by showing the editor again.</li>
 * </ol>
 * When {@code Ctrl-I} undoes a Back rather than a Neovim jump is Neovim's to decide: it keeps a
 * trail of the {@code Ctrl-O} steps ({@code LuaScripts.SETUP}).
 *
 * <p>Example: two files, {@code :e other.txt}, {@code :e #}, {@code :e #}. Neovim keeps two jumps
 * (it drops repeats of a place), Eclipse seven entries, five of them the plug-in's. Before:
 * {@code Ctrl-O} moved 8 times (one jump, then Back through every entry), {@code Ctrl-I} 2 times.
 * Now: one jump, then Back past the plug-in's entries to the editor switches the user made, and
 * {@code Ctrl-I} retraces the same places in reverse.
 *
 * <p>The remembered Backs are forgotten when Eclipse's history gets an entry the plug-in did not
 * make (a tab click, Open Declaration, opening a file): that is a new way to go, as in Eclipse.
 * Neovim jumps do not forget them, since Vim never drops a jump either.
 *
 * <p>Locations are compared by identity: Eclipse updates an entry's location in place, and two
 * entries can describe the same place. Not thread-safe (the UI thread uses it).
 *
 * @param <L> a history entry's location (Eclipse's {@code INavigationLocation})
 * @param <S> what is needed to come back without Eclipse's Forward (the editor and its caret)
 */
public final class HistorySteps<L, S> {

    /** Where Forward goes: Eclipse's Forward {@code steps} times, or back to a remembered place. */
    public sealed interface Forward<S> {
    }

    /** Run Eclipse's Forward this many times (0: nowhere to go). */
    public record Steps<S>(int steps) implements Forward<S> {
    }

    /** The place is no longer in Eclipse's history: show it again from {@code state}. */
    public record Restore<S>(S state) implements Forward<S> {
    }

    /** One Back: where it started, and how many presses it counts for ({@code 3 Ctrl-O} is one). */
    private record Departure<L, S>(L location, S state, int count) {
    }

    private final Set<L> own = identitySet();
    private final Deque<Departure<L, S>> departures = new ArrayDeque<>();
    /** The history when the last Back was remembered: an entry not in it, nor own, is the user's. */
    private Set<L> seen = identitySet();

    /** An entry the plug-in's own editor switch added ({@code null} is ignored). */
    public void markOwn(L location) {
        if (location != null) {
            own.add(location);
        }
    }

    /** Whether {@code location} is an entry the plug-in added. */
    public boolean isOwn(L location) {
        return location != null && own.contains(location);
    }

    /**
     * How many of Eclipse's Back steps {@code count} presses of {@code Ctrl-O} take: each press goes
     * to the nearest older entry the plug-in did not add. Example: {@code [a, b*, c*, d]} with
     * {@code d} current and {@code *} the plug-in's: one press is 3 steps, to {@code a}. When the
     * oldest entries are all the plug-in's, the last press stops on the oldest entry.
     *
     * @param history Eclipse's entries, oldest first
     * @param current the current entry
     * @param count   Vim's count (0 for none)
     * @return the steps; 0 at the start of the history. With the current entry unknown (not in
     *         {@code history}), the presses as steps, as Eclipse's own Back would take
     */
    public int backSteps(List<L> history, L current, int count) {
        int from = indexOf(history, current);
        if (from < 0) {
            return Math.max(1, count);
        }
        int to = from;
        for (int press = 0; press < Math.max(1, count) && to > 0; press++) {
            to--;
            while (to > 0 && isOwn(history.get(to))) {
                to--;
            }
        }
        return from - to;
    }

    /**
     * Remembers a Back that is about to start from {@code current}, so that {@link #forward} can
     * come back. Forgets the earlier ones first if the user made a new entry since.
     *
     * @param state the editor and caret at {@code current}, for when Eclipse's Forward cannot reach it
     * @param count the presses the Back counts for (Vim's count, at least 1)
     */
    public void wentBack(List<L> history, L current, S state, int count) {
        forgetIfTheUserMovedOn(history);
        departures.push(new Departure<>(current, state, Math.max(1, count)));
        seen = identitySet();
        seen.addAll(history);
    }

    /**
     * Where {@code count} presses of {@code Ctrl-I} go: back to where the last Backs started,
     * newest first; with none remembered, Eclipse's Forward, stepping over the plug-in's entries
     * to the last of them (where a Back started from, before it skipped them).
     *
     * @param history Eclipse's entries, oldest first
     * @param current the current entry
     * @param count   Vim's count (0 for none)
     */
    public Forward<S> forward(List<L> history, L current, int count) {
        forgetIfTheUserMovedOn(history);
        int from = indexOf(history, current);
        if (departures.isEmpty()) {
            return new Steps<>(from < 0 ? Math.max(1, count) : plainForwardSteps(history, from, count));
        }
        Departure<L, S> target = departures.pop();
        int presses = target.count();
        while (presses < Math.max(1, count) && !departures.isEmpty()) {
            target = departures.pop();
            presses += target.count();
        }
        int to = indexOf(history, target.location());
        if (from >= 0 && to > from) {
            return new Steps<>(to - from);
        }
        return to >= 0 && to == from ? new Steps<>(0) : new Restore<>(target.state());
    }

    /** Forgets the remembered Backs (an editor in them closed, say). */
    public void forget() {
        departures.clear();
    }

    /** Whether a Back is remembered (for tests). */
    public boolean remembersBacks() {
        return !departures.isEmpty();
    }

    /**
     * Drops what refers to entries Eclipse no longer has (its history holds 50), and the
     * remembered Backs if an entry appeared that is neither the plug-in's nor seen at the last Back.
     */
    private void forgetIfTheUserMovedOn(List<L> history) {
        Set<L> now = identitySet();
        now.addAll(history);
        own.retainAll(now);
        if (departures.isEmpty()) {
            return;
        }
        for (L location : history) {
            if (location != null && !seen.contains(location) && !own.contains(location)) {
                departures.clear();
                return;
            }
        }
    }

    /** Eclipse's Forward without remembered Backs: over a run of the plug-in's entries to its last. */
    private int plainForwardSteps(List<L> history, int from, int count) {
        int to = from;
        for (int press = 0; press < Math.max(1, count) && to + 1 < history.size(); press++) {
            to++;
            while (to + 1 < history.size() && isOwn(history.get(to)) && isOwn(history.get(to + 1))) {
                to++;
            }
        }
        return to - from;
    }

    private static <L> int indexOf(List<L> history, L location) {
        if (location == null) {
            return -1;
        }
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) == location) {
                return i;
            }
        }
        return -1;
    }

    private static <L> Set<L> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}

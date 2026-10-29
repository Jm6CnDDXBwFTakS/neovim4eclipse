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
 *       entries the plug-in made for Neovim's jumps ({@link #markOwn}): Neovim's history has them.</li>
 *   <li><b>A lost way forward.</b> A new entry deletes every entry ahead of it, as a new page does
 *       in a browser. When {@code Ctrl-I} undoes a Neovim jump across files, or {@code Ctrl-O} makes
 *       one after a Back, the plug-in's switch adds such an entry, and Eclipse's Forward has
 *       nothing left. So every Back step is remembered here under its id ({@link #wentBack}), with
 *       what is needed to come back to where it started, and {@link #forward} goes back to it:
 *       through Eclipse's Forward while the place is still in its history, else by showing the
 *       editor again.</li>
 * </ol>
 *
 * <p><b>Step ids.</b> Neovim keeps the order of the {@code Ctrl-O} steps (the trail in
 * {@code LuaScripts.SETUP}: jumps and Back steps). Each Back step has an id that Neovim chooses
 * and sends along; {@code Ctrl-I} sends back the id of the step to undo. So the two sides never
 * match steps by counting: a step that went nowhere, or that Neovim gave up, is simply an id with
 * no entry here, and nothing moves.
 *
 * <p>Example: two files, {@code :e other.txt}, {@code :e #}, {@code :e #}. Neovim keeps two jumps
 * (it drops repeats of a place), Eclipse seven entries, five of them the plug-in's. Before:
 * {@code Ctrl-O} moved 8 times (one jump, then Back through every entry), {@code Ctrl-I} 2 times.
 * Now: one jump, then Back past the plug-in's entries to the editor switches the user made, and
 * {@code Ctrl-I} retraces the same places in reverse.
 *
 * <p>The remembered Back steps are forgotten when Eclipse's history gets an entry the plug-in did
 * not make (a tab click, Open Declaration, opening a file): that is a new way to go, as in Eclipse
 * ({@link #userMovedOn}).
 *
 * <p>Locations are compared by identity: Eclipse updates an entry's location in place, and two
 * entries can describe the same place. Not thread-safe (the UI thread uses it).
 *
 * @param <L> a history entry's location (Eclipse's {@code INavigationLocation})
 * @param <S> what is needed to come back without Eclipse's Forward (the editor and its caret)
 */
public final class HistorySteps<L, S> {

    /**
     * At most this many Back steps are remembered; the oldest go first. Eclipse's own history holds
     * 50 entries, and every Back step goes back at least one of them.
     */
    static final int MAX_STEPS = 100;

    /** Where Forward goes: Eclipse's Forward {@code steps} times, or back to a remembered place. */
    public sealed interface Forward<S> {
    }

    /** Run Eclipse's Forward this many times (0: nowhere to go). */
    public record Steps<S>(int steps) implements Forward<S> {
    }

    /** The place is no longer in Eclipse's history: show it again from {@code state}. */
    public record Restore<S>(S state) implements Forward<S> {
    }

    /** One Back step: its id (Neovim's), and where it started. */
    private record Departure<L, S>(long id, L location, S state) {
    }

    private final Set<L> own = identitySet();
    /** Newest first. */
    private final Deque<Departure<L, S>> departures = new ArrayDeque<>();
    /** The history when the last Back was remembered: an entry not in it, nor own, is the user's. */
    private Set<L> seen = identitySet();

    /** An entry the plug-in's own editor switch added ({@code null} is ignored). */
    public void markOwn(L location) {
        if (location != null) {
            own.add(location);
        }
    }

    /**
     * An entry the plug-in added that Back does not step over: a switch for a Neovim move that is
     * not in its jump history ({@code :keepjumps}), or a place shown again by {@link Restore}. It is
     * no user's entry, so it does not forget the remembered Back steps ({@code null} is ignored).
     */
    public void markKnown(L location) {
        if (location != null) {
            seen.add(location);
        }
    }

    /** Whether {@code location} is an entry the plug-in added. */
    public boolean isOwn(L location) {
        return location != null && own.contains(location);
    }

    /**
     * How many of Eclipse's Back steps one press of {@code Ctrl-O} takes: to the nearest older entry
     * the plug-in did not add. Example: {@code [a, b*, c*, d]} with {@code d} current and {@code *}
     * the plug-in's: 3 steps, to {@code a}. When the oldest entries are all the plug-in's, it stops
     * on the oldest entry.
     *
     * @param history Eclipse's entries, oldest first
     * @param current the current entry
     * @return the steps; 0 at the start of the history. With the current entry unknown (not in
     *         {@code history}), 1, as Eclipse's own Back would take
     */
    public int backSteps(List<L> history, L current) {
        int from = indexOf(history, current);
        if (from < 0) {
            return 1;
        }
        int to = from - 1;
        while (to > 0 && isOwn(history.get(to))) {
            to--;
        }
        return to < 0 ? 0 : from - to;
    }

    /**
     * Remembers Back step {@code id}, about to start from {@code current}. Forgets the earlier ones
     * first if the user made a new entry since.
     *
     * @param state the editor and caret at {@code current}, for when Eclipse's Forward cannot reach it
     */
    public void wentBack(long id, List<L> history, L current, S state) {
        userMovedOn(history);
        departures.push(new Departure<>(id, current, state));
        while (departures.size() > MAX_STEPS) {
            departures.removeLast();
        }
        seen = identitySet();
        seen.addAll(history);
    }

    /** Forgets Back step {@code id}: it moved nothing the user could see. */
    public void wentNowhere(long id) {
        departures.removeIf(d -> d.id() == id);
    }

    /**
     * Where {@code Ctrl-I} goes to undo the Back steps from the newest down to {@code id}: back to
     * where step {@code id} started. Those steps are forgotten.
     *
     * @param history Eclipse's entries, oldest first
     * @param current the current entry
     * @return {@code Steps(0)} when no such step is remembered (it went nowhere, or was forgotten)
     */
    public Forward<S> forward(long id, List<L> history, L current) {
        userMovedOn(history);
        Departure<L, S> target = null;
        while (!departures.isEmpty() && departures.peek().id() >= id) {
            target = departures.pop();
        }
        if (target == null || target.id() != id) {
            return new Steps<>(0);
        }
        int from = indexOf(history, current);
        int to = indexOf(history, target.location());
        if (from >= 0 && to > from) {
            return new Steps<>(to - from);
        }
        return to >= 0 && to == from ? new Steps<>(0) : new Restore<>(target.state());
    }

    /**
     * {@code Ctrl-I} with no Back step to undo (Neovim's trail was given up: a key typed between):
     * Eclipse's Forward, stepping over a run of the plug-in's entries to its last (where a Back
     * started from, before it skipped them). The remembered steps are forgotten.
     */
    public int plainForwardSteps(List<L> history, L current, int count) {
        departures.clear();
        int from = indexOf(history, current);
        if (from < 0) {
            return Math.max(1, count);
        }
        int to = from;
        for (int press = 0; press < Math.max(1, count) && to + 1 < history.size(); press++) {
            to++;
            while (to + 1 < history.size() && isOwn(history.get(to)) && isOwn(history.get(to + 1))) {
                to++;
            }
        }
        return to - from;
    }

    /** Forgets the remembered Back steps (an editor in them closed, say). */
    public void forget() {
        departures.clear();
    }

    /**
     * Drops what refers to entries Eclipse no longer has (its history holds 50), and forgets the
     * remembered Back steps if an entry appeared that is neither the plug-in's nor seen at the last
     * Back: the user went somewhere new.
     *
     * @return whether remembered steps were forgotten (Neovim's trail should forget its own then)
     */
    public boolean userMovedOn(List<L> history) {
        Set<L> now = identitySet();
        now.addAll(history);
        own.retainAll(now);
        if (departures.isEmpty()) {
            return false;
        }
        for (L location : history) {
            if (location != null && !seen.contains(location) && !own.contains(location)) {
                departures.clear();
                return true;
            }
        }
        return false;
    }

    /** How many Back steps are remembered (for tests). */
    int remembered() {
        return departures.size();
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

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The decisions behind mirroring Eclipse's folds into Neovim (docs/design/FOLDING.md): which of
 * Eclipse's regions become Neovim folds, in what order, where each collapsed one is closed, and how
 * the closed folds Neovim reports turn into "collapse these, expand those" for Eclipse. (Where
 * {@code SET_FOLDS} closes each collapsed fold, its "handle line", is worked out in Lua, because in
 * one case only Neovim knows the folds' states; see {@code LuaScripts.FOLDS}.) Pure: no
 * Eclipse, no Neovim, unit-tested in {@code FoldRegionsTest}.
 *
 * <p><b>The concept.</b> Eclipse folds code with projection annotations: each covers a range of
 * lines and is collapsed or expanded. Neovim's {@code 'foldmethod'=manual} folds are the same idea,
 * made with {@code :{first},{last}fold}. The plug-in copies Eclipse's ranges into Neovim (one way)
 * and keeps the open/closed state the same on both sides (both ways), so that Neovim's own
 * {@code zo}, {@code zc}, {@code zM}, {@code j} over a closed fold and so on act on Eclipse's folds.
 *
 * <p>Lines are 1-based everywhere in this class, as Neovim's {@code :fold} ranges are. Example: a
 * Java class on lines 1-20 with a method on 3-8 and a collapsed method on 10-15 gives
 * {@code [Fold(1, 20, open), Fold(3, 8, open), Fold(10, 15, collapsed)]}.
 */
public final class FoldRegions {

    /** How many of {@link #toLuaArgs}' arguments each fold takes. */
    public static final int LUA_ARGS_PER_FOLD = 3;

    private FoldRegions() {
    }

    /** One of Eclipse's fold regions: its first and last line (1-based), and whether it is collapsed. */
    public record Region(int firstLine, int lastLine, boolean collapsed) {
    }

    /**
     * A fold as sent to Neovim. Its index in the list from {@link #forNeovim} is its id on both
     * sides: Neovim reports closed folds by that index, and Eclipse maps it back to its annotation.
     */
    public record Fold(int firstLine, int lastLine, boolean collapsed) {

        /** Whether {@code other} lies within this fold and is not this fold's own range. */
        boolean contains(Fold other) {
            return firstLine <= other.firstLine && other.lastLine <= lastLine
                    && (firstLine != other.firstLine || lastLine != other.lastLine);
        }
    }

    /**
     * Eclipse's regions as the folds Neovim gets, in the order {@code SET_FOLDS} creates them.
     *
     * <ul>
     *   <li><b>Dropped:</b> regions of one line (a one-line fold would hide nothing; Eclipse does not
     *       fold them either), and impossible ones (last before first).</li>
     *   <li><b>Merged:</b> two regions with the same lines become one fold, collapsed if either is:
     *       Neovim cannot have two folds over the same lines that differ, and a collapsed one hides
     *       the lines in Eclipse.</li>
     *   <li><b>Sorted:</b> by first line, and for the same first line the longer first. Outer folds
     *       are then made before the folds inside them, which {@code :fold} needs: made the other way
     *       round, a range over an existing closed fold grows to cover it (experiment X3).</li>
     *   <li><b>Dropped, overlapping:</b> a region that crosses the border of one kept before it (lines
     *       2-6 and 4-9). Manual folds must nest: Neovim widens the second to 2-9 (experiment X14),
     *       which would hide lines Eclipse shows. Eclipse's Java and Generic editors do not make such
     *       regions; if one does, that fold works in Eclipse only.</li>
     * </ul>
     */
    public static List<Fold> forNeovim(Collection<Region> regions) {
        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(Region::firstLine)
                .thenComparing(Comparator.comparingInt(Region::lastLine).reversed()));

        List<Region> kept = new ArrayList<>();
        for (Region region : sorted) {
            if (region.lastLine() <= region.firstLine() || region.firstLine() < 1) {
                continue;
            }
            Region last = kept.isEmpty() ? null : kept.get(kept.size() - 1);
            if (last != null && last.firstLine() == region.firstLine() && last.lastLine() == region.lastLine()) {
                kept.set(kept.size() - 1, new Region(last.firstLine(), last.lastLine(),
                        last.collapsed() || region.collapsed()));
                continue;
            }
            if (!crossesAny(region, kept)) {
                kept.add(region);
            }
        }

        List<Fold> folds = new ArrayList<>(kept.size());
        for (Region region : kept) {
            folds.add(new Fold(region.firstLine(), region.lastLine(), region.collapsed()));
        }
        return List.copyOf(folds);
    }

    /** Whether {@code region} starts inside a kept region and ends after it (kept ones start no later). */
    private static boolean crossesAny(Region region, List<Region> kept) {
        for (Region other : kept) {
            if (other.firstLine() <= region.firstLine() && region.firstLine() <= other.lastLine()
                    && region.lastLine() > other.lastLine()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code SET_FOLDS}' arguments: the buffer number, then three per fold: first line, last line,
     * {@code "1"} if collapsed (else {@code "0"}). Example: buffer 3 with one collapsed fold over
     * lines 2-5 gives {@code ["3", "2", "5", "1"]}. An empty list gives the number alone, which tells
     * Neovim the editor cannot fold.
     */
    public static List<String> toLuaArgs(int bufnr, List<Fold> folds) {
        List<String> args = new ArrayList<>(1 + LUA_ARGS_PER_FOLD * folds.size());
        args.add(String.valueOf(bufnr));
        for (Fold fold : folds) {
            args.add(String.valueOf(fold.firstLine()));
            args.add(String.valueOf(fold.lastLine()));
            args.add(fold.collapsed() ? "1" : "0");
        }
        return args;
    }

    /** What Eclipse must change so that it shows the folds Neovim has: indexes into the sent list. */
    public record StateChange(List<Integer> collapse, List<Integer> expand) {

        public boolean isEmpty() {
            return collapse.isEmpty() && expand.isEmpty();
        }
    }

    /**
     * From the closed folds Neovim reports to the folds Eclipse must collapse or expand.
     *
     * <p>Neovim reports only the <b>outermost</b> closed folds: while a fold is closed, Neovim cannot
     * tell whether the folds inside it are open or closed ({@code foldclosed()} gives the outer
     * fold's first line for every line inside it, and nothing else reads a hidden fold's state). So
     * a fold inside a reported one is "unknown" and keeps Eclipse's state. Every other fold is
     * closed if reported and open if not. Example: a class 0 with methods 1 and 2, all collapsed in
     * Eclipse; {@code zo} on the class makes Neovim report {@code {1, 2}}, and Eclipse expands the
     * class only.
     *
     * @param sent               the list last sent ({@link #forNeovim}), whose indexes Neovim reports
     * @param collapsedInEclipse each sent fold's state in Eclipse now, by index
     * @param closedInNeovim     the indexes Neovim reports closed; unknown indexes are ignored
     */
    public static StateChange stateChange(List<Fold> sent, List<Boolean> collapsedInEclipse,
            Set<Integer> closedInNeovim) {
        List<Integer> collapse = new ArrayList<>();
        List<Integer> expand = new ArrayList<>();
        int count = Math.min(sent.size(), collapsedInEclipse.size());
        for (int i = 0; i < count; i++) {
            if (insideReported(i, sent, closedInNeovim)) {
                continue;
            }
            boolean closed = closedInNeovim.contains(i);
            boolean collapsed = Boolean.TRUE.equals(collapsedInEclipse.get(i));
            if (closed && !collapsed) {
                collapse.add(i);
            } else if (!closed && collapsed) {
                expand.add(i);
            }
        }
        return new StateChange(List.copyOf(collapse), List.copyOf(expand));
    }

    private static boolean insideReported(int index, List<Fold> sent, Set<Integer> closedInNeovim) {
        Fold fold = sent.get(index);
        for (int reported : closedInNeovim) {
            if (reported != index && reported >= 0 && reported < sent.size() && sent.get(reported).contains(fold)) {
                return true;
            }
        }
        return false;
    }
}

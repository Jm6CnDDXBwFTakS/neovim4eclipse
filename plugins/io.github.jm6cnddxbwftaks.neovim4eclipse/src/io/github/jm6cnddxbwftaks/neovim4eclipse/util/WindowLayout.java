// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vim's window commands worked out on a picture of Eclipse's editor area: which editor
 * {@code Ctrl-W l} goes to, how {@code 5 Ctrl-W +} shares the rows out, what {@code Ctrl-W r}
 * moves where. No Eclipse code: {@code editor/EditorSplits} draws the picture from Eclipse's model
 * and applies the answer, and {@code WindowLayoutTest} tests the rules.
 *
 * <h2>The picture</h2>
 * Vim keeps its windows in a tree of frames: a frame is a window, a row of frames side by side, or
 * a column of frames one above the other. Eclipse's editor area is the same kind of tree: sash
 * containers (rows or columns) holding editor stacks, each showing one editor. So a {@link Node} is
 * a {@link Window} (an editor on screen, with its bounds in display pixels) or a {@link Group} (a
 * row or column of nodes). Every node has an id, which the caller maps back to its model element.
 *
 * <pre>
 *   +-----+-----+       Group(row)
 *   |     |  B  |       ├── Window A
 *   |  A  +-----+       └── Group(column)
 *   |     |  C  |           ├── Window B
 *   +-----+-----+           └── Window C
 * </pre>
 *
 * <p>The order of the windows is Vim's: depth first, so A, B, C above. {@code Ctrl-W w} follows
 * it, and {@code 2 Ctrl-W w} goes to B.
 */
public final class WindowLayout {

    /** A rectangle in display pixels. */
    public record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        static Rect union(Rect a, Rect b) {
            int x = Math.min(a.x, b.x);
            int y = Math.min(a.y, b.y);
            return new Rect(x, y, Math.max(a.right(), b.right()) - x, Math.max(a.bottom(), b.bottom()) - y);
        }
    }

    /** A frame of the layout: a window, or a row or column of frames. */
    public sealed interface Node permits Window, Group {
        int id();

        Rect bounds();
    }

    /** An editor on screen. */
    public record Window(int id, Rect bounds) implements Node {
    }

    /**
     * A row ({@code sideBySide}) or column of frames, in the order shown (left to right, top to
     * bottom). Its bounds are those of its children together.
     */
    public record Group(int id, boolean sideBySide, List<Node> children) implements Node {
        public Group {
            if (children.isEmpty()) {
                throw new IllegalArgumentException("a group with no frames");
            }
            children = List.copyOf(children);
        }

        @Override
        public Rect bounds() {
            Rect all = children.get(0).bounds();
            for (Node child : children) {
                all = Rect.union(all, child.bounds());
            }
            return all;
        }
    }

    /**
     * New sizes for a group's children, along its own direction (widths in a row, heights in a
     * column), in the order of {@link Group#children()}. Eclipse takes them as the children's
     * weights.
     */
    public record Sizes(int group, int[] sizes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Sizes s && s.group == group && Arrays.equals(s.sizes, sizes);
        }

        @Override
        public int hashCode() {
            return 31 * group + Arrays.hashCode(sizes);
        }

        @Override
        public String toString() {
            return "Sizes[group=" + group + ", sizes=" + Arrays.toString(sizes) + "]";
        }
    }

    /**
     * What the size commands need to know about the current editor, in pixels: one text line's
     * height and one character's width (Vim counts in those), and the height and width of the text
     * itself (the editor without its tab row and rulers), for {@code 10 Ctrl-W _}, which means ten
     * lines of text.
     */
    public record Metrics(int lineHeight, int charWidth, int textHeight, int textWidth) {
    }

    private WindowLayout() {
    }

    /** The windows in Vim's order: depth first, left to right, top to bottom. */
    public static List<Window> windows(Node root) {
        List<Window> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Node node, List<Window> found) {
        if (node instanceof Window window) {
            found.add(window);
        } else if (node instanceof Group group) {
            for (Node child : group.children()) {
                collect(child, found);
            }
        }
    }

    /**
     * The window that {@code Ctrl-W} with {@code key} goes to, or {@code current} when there is
     * none (Vim then does nothing either).
     *
     * <ul>
     *   <li>{@code h j k l}: the next window to the left, below, above, right, among those beside
     *       the current one (overlapping it across); of several, the one level with the caret
     *       ({@code caretX}, {@code caretY}), as Vim takes the one at the cursor's row or column.
     *       A count goes that many windows on, the caret's row kept.</li>
     *   <li>{@code w}, {@code W}: the next or previous window in Vim's order, wrapping; with a
     *       count, window N (the last when there are fewer).</li>
     *   <li>{@code t}, {@code b}: the first and the last window.</li>
     *   <li>{@code p}: {@code previous}, the window active before, if it is still on screen.</li>
     * </ul>
     *
     * @param previous the window active before this one, or -1
     * @param count    Vim's count, 0 when none
     */
    public static int focus(Node root, int current, int previous, char key, int count, int caretX, int caretY) {
        List<Window> order = windows(root);
        int index = indexOf(order, current);
        if (index < 0) {
            return current;
        }
        return switch (key) {
            case 'h', 'j', 'k', 'l' -> {
                Window at = order.get(index);
                for (int step = 0; step < Math.max(count, 1); step++) {
                    Window next = beside(order, at, key, caretX, caretY);
                    if (next == null) {
                        break;
                    }
                    at = next;
                }
                yield at.id();
            }
            case 'w', 'W' -> {
                if (count > 0) {
                    yield order.get(Math.min(count, order.size()) - 1).id();
                }
                int step = key == 'w' ? 1 : -1;
                yield order.get(Math.floorMod(index + step, order.size())).id();
            }
            case 't' -> order.get(0).id();
            case 'b' -> order.get(order.size() - 1).id();
            case 'p' -> indexOf(order, previous) >= 0 ? previous : current;
            default -> current;
        };
    }

    /** The window beside {@code from} in the direction of {@code key}, or {@code null}. */
    private static Window beside(List<Window> windows, Window from, char key, int caretX, int caretY) {
        Rect f = from.bounds();
        boolean across = key == 'h' || key == 'l'; // moving across: the windows must overlap in height
        List<Window> candidates = new ArrayList<>();
        int nearest = Integer.MAX_VALUE;
        for (Window w : windows) {
            Rect r = w.bounds();
            if (w == from || !(across ? r.y < f.bottom() && r.bottom() > f.y : r.x < f.right() && r.right() > f.x)) {
                continue;
            }
            int gap = switch (key) {
                case 'h' -> f.x - r.right();
                case 'l' -> r.x - f.right();
                case 'k' -> f.y - r.bottom();
                default -> r.y - f.bottom();
            };
            if (gap < 0) {
                continue; // not on that side
            }
            if (gap < nearest) {
                nearest = gap;
                candidates.clear();
            }
            if (gap == nearest) {
                candidates.add(w);
            }
        }
        Window best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Window w : candidates) {
            Rect r = w.bounds();
            int distance = across ? distance(caretY, r.y, r.bottom()) : distance(caretX, r.x, r.right());
            if (distance < bestDistance) {
                best = w;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** How far {@code at} is from the range {@code [start, end)}: 0 inside it. */
    private static int distance(int at, int start, int end) {
        return at < start ? start - at : at >= end ? at - end + 1 : 0;
    }

    /**
     * The new sizes for a size command, or none when there is nothing to change (a single window,
     * or {@code Ctrl-W +} with no window above or below).
     *
     * <ul>
     *   <li>{@code =}: every row and column shares its space out equally, as Vim makes all windows
     *       (almost) the same size.</li>
     *   <li>{@code + -}: N lines taller or shorter (1 without a count); {@code _}: N lines of text,
     *       or as tall as it can be without a count. In the nearest column that holds the current
     *       window: the space comes from the windows after it, then before it, as in Vim.</li>
     *   <li>{@code < > |}: the same for the width, in characters, in the nearest row.</li>
     * </ul>
     *
     * <p>Each window keeps at least one line (or character) of text and its tab row: {@code minimum}
     * is that size in pixels.
     */
    public static List<Sizes> resize(Node root, int current, char key, int count, Metrics metrics, int minimum) {
        if (key == '=') {
            List<Sizes> all = new ArrayList<>();
            equalize(root, all);
            return all;
        }
        boolean heights = key == '+' || key == '-' || key == '_';
        List<Group> path = pathTo(root, current);
        if (path == null) {
            return List.of();
        }
        // The nearest group of the right direction, and its child that holds the current window.
        for (int i = path.size() - 1; i >= 0; i--) {
            Group group = path.get(i);
            if (group.sideBySide() == heights || group.children().size() < 2) {
                continue;
            }
            Node child = i + 1 < path.size() ? path.get(i + 1) : windowNode(group, current);
            int index = group.children().indexOf(child);
            int[] sizes = group.children().stream()
                    .mapToInt(n -> heights ? n.bounds().height() : n.bounds().width()).toArray();
            int unit = heights ? metrics.lineHeight() : metrics.charWidth();
            int text = heights ? metrics.textHeight() : metrics.textWidth();
            int now = sizes[index];
            int wanted = switch (key) {
                case '+', '>' -> now + Math.max(count, 1) * unit;
                case '-', '<' -> now - Math.max(count, 1) * unit;
                default -> count > 0 ? now + count * unit - text : Integer.MAX_VALUE; // _ |
            };
            return List.of(new Sizes(group.id(), resized(sizes, index, wanted, minimum)));
        }
        return List.of();
    }

    private static void equalize(Node node, List<Sizes> all) {
        if (node instanceof Group group) {
            if (group.children().size() > 1) {
                int total = group.children().stream()
                        .mapToInt(n -> group.sideBySide() ? n.bounds().width() : n.bounds().height()).sum();
                int[] sizes = new int[group.children().size()];
                Arrays.fill(sizes, Math.max(total / sizes.length, 1));
                all.add(new Sizes(group.id(), sizes));
            }
            for (Node child : group.children()) {
                equalize(child, all);
            }
        }
    }

    /**
     * {@code sizes} with the one at {@code index} made {@code wanted}, the rest giving or taking
     * the difference as Vim's windows do: a window that grows takes the space of the windows after
     * it first, then of those before it, each down to {@code minimum}; one that shrinks gives the
     * space to the next window (the previous one when it is the last). The total stays the same.
     *
     * <p>Example: {@code [100, 100, 100]}, the first made 180 with a minimum of 30: the second gives
     * 70, the third 10, so {@code [180, 30, 90]}.
     */
    static int[] resized(int[] sizes, int index, int wanted, int minimum) {
        int[] out = sizes.clone();
        int total = Arrays.stream(sizes).sum();
        int most = total - minimum * (sizes.length - 1);
        int target = (int) Math.max(Math.min((long) wanted, most), Math.min(minimum, most));
        int delta = target - out[index];
        if (delta > 0) {
            int needed = delta;
            for (int i = index + 1; i < out.length && needed > 0; i++) {
                needed -= give(out, i, needed, minimum);
            }
            for (int i = index - 1; i >= 0 && needed > 0; i--) {
                needed -= give(out, i, needed, minimum);
            }
            out[index] += delta - needed;
        } else if (delta < 0) {
            int receiver = index + 1 < out.length ? index + 1 : index - 1;
            out[receiver] -= delta;
            out[index] += delta;
        }
        return out;
    }

    /** Takes up to {@code needed} from {@code sizes[i]}, down to {@code minimum}; returns what it took. */
    private static int give(int[] sizes, int i, int needed, int minimum) {
        int taken = Math.max(0, Math.min(needed, sizes[i] - minimum));
        sizes[i] -= taken;
        return taken;
    }

    /**
     * The window {@code Ctrl-W x} swaps the current one with: the next window in its row or column,
     * or the previous one when it is the last; with a count, the Nth (1-based). -1 when there is
     * none, or when that frame is a row or column itself (Vim swaps only two windows).
     */
    public static int exchangeWith(Node root, int current, int count) {
        Group parent = parentOf(root, current);
        if (parent == null) {
            return -1;
        }
        List<Node> siblings = parent.children();
        int index = siblings.indexOf(windowNode(parent, current));
        int other;
        if (count > 0) {
            other = count - 1;
        } else {
            other = index + 1 < siblings.size() ? index + 1 : index - 1;
        }
        if (other < 0 || other >= siblings.size() || other == index || !(siblings.get(other) instanceof Window w)) {
            return -1;
        }
        return w.id();
    }

    /**
     * The windows of the current one's row or column after {@code Ctrl-W r} ({@code down}) or
     * {@code Ctrl-W R}, done {@code count} times (once without a count): the ids in their new
     * places, first to last. {@code Ctrl-W r} moves each window one place down or right and the
     * last to the front. Empty when there is nothing to rotate (a window alone).
     *
     * @throws IllegalStateException when a frame of that row or column is split itself: Vim's
     *                               {@code E443: Cannot rotate when another window is split}
     */
    public static List<Integer> rotated(Node root, int current, boolean down, int count) {
        Group parent = parentOf(root, current);
        if (parent == null || parent.children().size() < 2) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Node child : parent.children()) {
            if (!(child instanceof Window w)) {
                throw new IllegalStateException("E443: Cannot rotate when another window is split");
            }
            ids.add(w.id());
        }
        int steps = Math.max(count, 1) % ids.size();
        java.util.Collections.rotate(ids, down ? steps : -steps);
        return ids;
    }

    /** The group that holds the window {@code id} directly, or {@code null} (the window is the root). */
    static Group parentOf(Node root, int id) {
        List<Group> path = pathTo(root, id);
        return path == null || path.isEmpty() ? null : path.get(path.size() - 1);
    }

    /** The groups from the root down to the one holding window {@code id}, or {@code null} if absent. */
    private static List<Group> pathTo(Node node, int id) {
        if (node instanceof Window w) {
            return w.id() == id ? new ArrayList<>() : null;
        }
        Group group = (Group) node;
        for (Node child : group.children()) {
            List<Group> below = pathTo(child, id);
            if (below != null) {
                below.add(0, group);
                return below;
            }
        }
        return null;
    }

    /** The child of {@code group} that is the window {@code id}. */
    private static Node windowNode(Group group, int id) {
        for (Node child : group.children()) {
            if (child instanceof Window w && w.id() == id) {
                return w;
            }
        }
        throw new IllegalArgumentException("window " + id + " is not in group " + group.id());
    }

    private static int indexOf(List<Window> windows, int id) {
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }
}

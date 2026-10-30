// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import org.eclipse.jface.action.StatusLineLayoutData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusBarWidths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * The widgets of the plug-in's part of the Eclipse status bar: one label per part the user chose
 * (mode, cmd, statusline; {@link StatusItem}), each after a separator, left-aligned, sized in
 * characters of the bar's font and fitted under the cap ({@link StatusBarWidths}). Only SWT and JFace,
 * no workbench, so {@code tests/swt} can test it; {@code ModeStatusContribution} decides what the
 * labels say.
 *
 * <h2>Why widths in characters</h2>
 * A width in pixels means fewer characters with a larger font or on a high-resolution screen. As
 * Eclipse's own status items do ({@code StatusLineContributionItem}), the width is the font's average
 * character width times a number of characters. Example: 60 characters of a 7 px font is 420 px.
 *
 * <p>The bar's width changes with the window, and with it the cap, so the labels are fitted again on
 * every resize of the bar. The statusline label's width in characters goes to {@code columns}, the
 * {@code maxwidth} Neovim fits its {@code 'statusline'} into.
 *
 * <p>UI thread only.
 */
final class StatusBarLabels {

    /** The least the statusline label is shrunk to under the cap, in characters. */
    static final int STATUSLINE_MIN_CHARS = 10;

    /**
     * What the labels are sized for.
     *
     * @param modeChars       the mode label, in characters (the longest text of the chosen style)
     * @param cmdChars        the pending-command label
     * @param statuslineChars the statusline label, before the cap
     * @param maxShare        the most all labels may take of the bar, in percent
     */
    record Widths(int modeChars, int cmdChars, int statuslineChars, int maxShare) {
    }

    /** The gap JFace's status bar layout leaves after every item, in pixels. */
    private static final int GAP = 3;

    private final Composite bar;
    private final Map<StatusItem, CLabel> labels = new EnumMap<>(StatusItem.class);
    private final IntConsumer columns;
    private final Listener onResize = event -> fit();
    private Widths widths;
    /** The statusline's columns last passed to {@link #columns}; -1 before the first. */
    private int columnsSent = -1;

    /**
     * Creates the labels of {@code parts} at the end of {@code bar}, the status bar's composite.
     *
     * @param font    the labels' font (bold, as before); {@code null} keeps the bar's
     * @param columns hears the statusline label's width in characters whenever it changes
     */
    StatusBarLabels(Composite bar, Set<StatusItem> parts, Font font, Widths widths, IntConsumer columns) {
        this.bar = bar;
        this.widths = widths;
        this.columns = columns;
        for (StatusItem part : StatusItem.values()) { // the declaration order is the display order
            if (!parts.contains(part)) {
                continue;
            }
            Label separator = new Label(bar, SWT.SEPARATOR);
            StatusLineLayoutData separatorData = new StatusLineLayoutData();
            separatorData.heightHint = bar.getSize().y;
            separator.setLayoutData(separatorData);
            CLabel label = new CLabel(bar, SWT.LEFT);
            if (font != null) {
                label.setFont(font);
            }
            label.setLayoutData(new StatusLineLayoutData());
            labels.put(part, label);
        }
        // The bar outlives these labels (Eclipse disposes and refills them on every rebuild of the bar),
        // so the listener goes with them.
        bar.addListener(SWT.Resize, onResize);
        labels.values().stream().findFirst().ifPresent(first -> first.addDisposeListener(event -> {
            if (!bar.isDisposed()) {
                bar.removeListener(SWT.Resize, onResize);
            }
        }));
        fit();
    }

    /** The parts that have a label. */
    Set<StatusItem> parts() {
        return labels.keySet();
    }

    /** Whether the labels are gone (the bar was rebuilt or closed). */
    boolean isDisposed() {
        return labels.values().stream().anyMatch(CLabel::isDisposed);
    }

    /** The label of {@code part}, or {@code null} when it has none; for tests. */
    CLabel label(StatusItem part) {
        return labels.get(part);
    }

    /**
     * Shows {@code text} in {@code part}'s label, with {@code tooltip} (or the text itself when
     * {@code null}: a text cut with "..." is then readable whole on hover) and {@code foreground}
     * ({@code null}: the bar's own color). Does nothing for a part without a label.
     */
    void show(StatusItem part, String text, String tooltip, Color foreground) {
        CLabel label = labels.get(part);
        if (label == null || label.isDisposed()) {
            return;
        }
        String shown = text == null ? "" : text;
        if (!shown.equals(label.getText())) {
            label.setText(shown);
        }
        label.setToolTipText(shown.isEmpty() ? null : tooltip != null ? tooltip : shown);
        label.setForeground(foreground);
    }

    /**
     * New sizes (a preference changed, or recording started): fits the labels again. The same sizes
     * do nothing, so this can be called with every status update without measuring the font each key.
     */
    void setWidths(Widths newWidths) {
        if (newWidths.equals(widths)) {
            return;
        }
        widths = newWidths;
        fit();
    }

    /**
     * Sets each label's width from {@link #widths} under the cap, lays the bar out, and passes the
     * statusline's columns on when they changed.
     */
    void fit() {
        if (labels.isEmpty() || isDisposed()) {
            return;
        }
        CLabel any = labels.values().iterator().next();
        double charWidth = averageCharWidth(any);
        int margins = any.getLeftMargin() + any.getRightMargin();
        StatusItem[] order = labels.keySet().toArray(StatusItem[]::new);
        int[] wanted = new int[order.length];
        int[] minimum = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            int chars = switch (order[i]) {
                case MODE -> widths.modeChars();
                case CMD -> widths.cmdChars();
                case STATUSLINE -> widths.statuslineChars();
            };
            wanted[i] = StatusBarWidths.pixels(chars, charWidth) + margins;
            minimum[i] = order[i] == StatusItem.STATUSLINE
                    ? StatusBarWidths.pixels(Math.min(chars, STATUSLINE_MIN_CHARS), charWidth) + margins
                    : wanted[i]; // the mode and the pending command keep their size: they are short
        }
        int barWidth = bar.getClientArea().width;
        int cap = Math.min(StatusBarWidths.cap(barWidth, widths.maxShare()), StatusBarWidths.free(barWidth, othersWidth()));
        int[] fitted = StatusBarWidths.fit(wanted, minimum, cap);
        boolean changed = false;
        for (int i = 0; i < order.length; i++) {
            CLabel label = labels.get(order[i]);
            StatusLineLayoutData data = (StatusLineLayoutData) label.getLayoutData();
            if (data.widthHint != fitted[i]) {
                data.widthHint = fitted[i];
                changed = true;
            }
            if (order[i] == StatusItem.STATUSLINE) {
                int cols = StatusBarWidths.columns(fitted[i] - margins, charWidth);
                if (cols != columnsSent) {
                    columnsSent = cols;
                    columns.accept(cols);
                }
            }
        }
        if (changed) {
            bar.layout(true);
        }
    }

    /**
     * What everything in the bar but the labels takes, as JFace's layout counts it: Eclipse's items
     * (Writable, Insert, the cursor position) and every separator with its gap, this class's
     * included; not the message area (the first child), nor the progress bar and its cancel button
     * (a {@code Composite} and a {@code ToolBar}, hidden but for a running job).
     */
    private int othersWidth() {
        Control[] children = bar.getChildren();
        int width = 0;
        for (int i = 1; i < children.length; i++) {
            Control child = children[i];
            if (labels.containsValue(child) || child instanceof ToolBar
                    || (child instanceof Composite && !(child instanceof CLabel))) {
                continue;
            }
            int hint = child.getLayoutData() instanceof StatusLineLayoutData data ? data.widthHint : SWT.DEFAULT;
            width += child.computeSize(hint, SWT.DEFAULT).x + GAP;
        }
        return width + labels.size() * GAP;
    }

    /** The average character width of {@code label}'s font, in pixels. */
    private static double averageCharWidth(CLabel label) {
        GC gc = new GC(label);
        try {
            gc.setFont(label.getFont());
            return gc.getFontMetrics().getAverageCharacterWidth();
        } finally {
            gc.dispose();
        }
    }
}

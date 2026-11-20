// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.Set;

import org.eclipse.jface.action.StatusLineLayoutData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LabelText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * The widgets of the plug-in's part of the Eclipse status bar: the mode's label ({@link StatusItem}),
 * after a separator, left-aligned. Only SWT and JFace, no workbench, so {@code tests/swt} can test
 * it; {@code ModeStatusContribution} decides what the label says.
 *
 * <h2>How the label is sized</h2>
 * As wide as the widest of the mode's texts as the font draws them ({@code GC.textExtent}), so that
 * the label keeps its size from mode to mode and the items right of it do not move. Mode texts are
 * nearly all capitals, wider than the font's average character, so counting characters would cut
 * the longest ones ("(S) V-BLO..."). Example: {@code NORMAL} and {@code (S) V-BLOCK} share the width
 * of the second.
 *
 * <p>Neovim's status line is not here: each editor draws its own window's under its text
 * ({@link StatusLineRow}), as Neovim lays out its screen.
 *
 * <p>UI thread only.
 */
final class StatusBarLabels {

    private final CLabel label;
    private final Set<StatusItem> parts;
    private List<String> modeTexts;

    /**
     * Creates the mode's label at the end of {@code bar}, the status bar's composite, if
     * {@code parts} holds it.
     *
     * @param font      the label's font (bold); {@code null} keeps the bar's
     * @param modeTexts every text the label may show; it is as wide as the widest
     */
    StatusBarLabels(Composite bar, Set<StatusItem> parts, Font font, List<String> modeTexts) {
        this.parts = Set.copyOf(parts);
        this.modeTexts = List.copyOf(modeTexts);
        if (!parts.contains(StatusItem.MODE)) {
            label = null;
            return;
        }
        Label separator = new Label(bar, SWT.SEPARATOR);
        StatusLineLayoutData separatorData = new StatusLineLayoutData();
        separatorData.heightHint = bar.getSize().y;
        separator.setLayoutData(separatorData);
        label = new CLabel(bar, SWT.LEFT);
        if (font != null) {
            label.setFont(font);
        }
        label.setLayoutData(new StatusLineLayoutData());
        fit();
    }

    /** The parts that have a label. */
    Set<StatusItem> parts() {
        return parts;
    }

    /** Whether the label is gone (the bar was rebuilt or closed). */
    boolean isDisposed() {
        return label != null && label.isDisposed();
    }

    /** The label of {@code part}, or {@code null} when it has none; for tests. */
    CLabel label(StatusItem part) {
        return part == StatusItem.MODE ? label : null;
    }

    /**
     * Shows {@code text} in {@code part}'s label, with {@code tooltip} (or the text itself when
     * {@code null}) and {@code foreground} ({@code null}: the bar's own color). Does nothing for a
     * part without a label.
     */
    void show(StatusItem part, String text, String tooltip, Color foreground) {
        CLabel shownIn = label(part);
        if (shownIn == null || shownIn.isDisposed()) {
            return;
        }
        String shown = text == null ? "" : text;
        String literal = LabelText.literal(shown); // an & stays an & (no mnemonic)
        if (!literal.equals(shownIn.getText())) {
            shownIn.setText(literal);
        }
        shownIn.setToolTipText(shown.isEmpty() ? null : tooltip != null ? tooltip : shown);
        shownIn.setForeground(foreground);
    }

    /**
     * New texts to size for (recording started or stopped: {@code rec @a} widens the label). The
     * same texts do nothing, so this can be called with every status update.
     */
    void setModeTexts(List<String> texts) {
        if (texts.equals(modeTexts)) {
            return;
        }
        modeTexts = List.copyOf(texts);
        fit();
    }

    /** Sets the label's width from the widest text and lays the bar out when it changed. */
    void fit() {
        if (label == null || label.isDisposed()) {
            return;
        }
        int width = widestText(label, modeTexts) + label.getLeftMargin() + label.getRightMargin();
        StatusLineLayoutData data = (StatusLineLayoutData) label.getLayoutData();
        if (data.widthHint != width) {
            data.widthHint = width;
            label.getParent().layout(true);
        }
    }

    /** The widest of {@code texts} in {@code label}'s font, in pixels. */
    private static int widestText(CLabel label, List<String> texts) {
        GC gc = new GC(label);
        try {
            gc.setFont(label.getFont());
            int widest = 0;
            for (String text : texts) {
                widest = Math.max(widest, gc.textExtent(text).x);
            }
            return widest;
        } finally {
            gc.dispose();
        }
    }
}

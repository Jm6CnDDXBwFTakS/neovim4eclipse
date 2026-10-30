// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.action.StatusLineLayoutData;
import org.eclipse.jface.action.StatusLineManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusBarWidths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * {@link StatusBarLabels} in a real JFace status bar ({@code StatusLineManager.createControl}, the
 * composite Eclipse puts at the bottom of a window, with its own layout): one label per chosen part,
 * widths in characters, the cap, and the statusline's columns passed on.
 */
class StatusBarLabelsTest {

    private Shell shell;
    private Composite bar;
    private final List<Integer> columns = new ArrayList<>();
    private StatusBarLabels labels;

    @BeforeEach
    void createBar() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        bar = (Composite) new StatusLineManager().createControl(shell);
        shell.setSize(2000, 60);
        shell.open();
        shell.layout(true, true);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    private StatusBarLabels create(Set<StatusItem> parts, StatusBarLabels.Widths widths) {
        labels = new StatusBarLabels(bar, parts, null, widths, columns::add);
        return labels;
    }

    /**
     * Resizes the bar itself, as the window's layout would: synchronous, unlike a shell resize, which
     * the window manager may apply later.
     */
    private void resizeBar(int width) {
        bar.setSize(width, bar.getSize().y);
    }

    private int width(StatusItem part) {
        return ((StatusLineLayoutData) labels.label(part).getLayoutData()).widthHint;
    }

    private double charWidth() {
        GC gc = new GC(labels.label(StatusItem.MODE));
        try {
            gc.setFont(labels.label(StatusItem.MODE).getFont());
            return gc.getFontMetrics().getAverageCharacterWidth();
        } finally {
            gc.dispose();
        }
    }

    private int margins() {
        CLabel label = labels.label(StatusItem.MODE);
        return label.getLeftMargin() + label.getRightMargin();
    }

    @Test
    void oneLeftAlignedLabelPerChosenPartInDisplayOrder() {
        create(EnumSet.of(StatusItem.STATUSLINE, StatusItem.MODE), new StatusBarLabels.Widths(14, 10, 60, 90));
        assertEquals(EnumSet.of(StatusItem.MODE, StatusItem.STATUSLINE), labels.parts());
        assertNull(labels.label(StatusItem.CMD), "cmd not chosen: no label");
        List<Control> children = List.of(bar.getChildren());
        assertTrue(children.indexOf(labels.label(StatusItem.MODE)) < children.indexOf(labels.label(StatusItem.STATUSLINE)),
                "mode before statusline: " + children);
        assertEquals(SWT.LEFT, labels.label(StatusItem.MODE).getAlignment());
    }

    /** As Eclipse's own status items: the font's average character width times the characters. */
    @Test
    void widthsAreInCharactersOfTheBarsFont() {
        create(EnumSet.allOf(StatusItem.class), new StatusBarLabels.Widths(14, 10, 60, 90));
        double cw = charWidth();
        assertEquals(StatusBarWidths.pixels(14, cw) + margins(), width(StatusItem.MODE));
        assertEquals(StatusBarWidths.pixels(10, cw) + margins(), width(StatusItem.CMD));
        assertEquals(StatusBarWidths.pixels(60, cw) + margins(), width(StatusItem.STATUSLINE));
        assertEquals(List.of(60), columns, "the statusline's columns, once");
    }

    /** A narrow window: the statusline gives up its width first, and Neovim hears the new columns. */
    @Test
    void theCapShrinksTheStatuslineAndTellsNeovim() {
        create(EnumSet.allOf(StatusItem.class), new StatusBarLabels.Widths(14, 10, 60, 40));
        double cw = charWidth();
        int fixed = width(StatusItem.MODE) + width(StatusItem.CMD);
        resizeBar((int) ((fixed + StatusBarWidths.pixels(30, cw) + margins()) / 0.40) + 1);
        int statusline = width(StatusItem.STATUSLINE);
        assertTrue(statusline < StatusBarWidths.pixels(60, cw) + margins(), "shrunk: " + statusline);
        assertEquals(StatusBarWidths.columns(statusline - margins(), cw), columns.get(columns.size() - 1),
                "Neovim fits the statusline to what is left: " + columns);
        assertTrue(columns.get(columns.size() - 1) < 60);

        resizeBar(4000);
        assertEquals(60, columns.get(columns.size() - 1), "a wider window gives the full width back");
    }

    @Test
    void textsAndTooltips() {
        create(EnumSet.allOf(StatusItem.class), new StatusBarLabels.Widths(14, 10, 60, 90));
        labels.show(StatusItem.MODE, "-VR-", "-VR- = VREPLACE", null);
        assertEquals("-VR-", labels.label(StatusItem.MODE).getText());
        assertEquals("-VR- = VREPLACE", labels.label(StatusItem.MODE).getToolTipText());
        labels.show(StatusItem.STATUSLINE, "a.txt   1,1", null, null);
        assertEquals("a.txt   1,1", labels.label(StatusItem.STATUSLINE).getToolTipText(), "the whole text on hover");
        labels.show(StatusItem.CMD, "", "unused", null);
        assertNull(labels.label(StatusItem.CMD).getToolTipText(), "no tooltip for an empty label");
    }

    /** Eclipse disposes the labels when it rebuilds the bar; the bar's resize must not reach them. */
    @Test
    void disposedLabelsStopListeningToTheBar() {
        create(EnumSet.allOf(StatusItem.class), new StatusBarLabels.Widths(14, 10, 60, 40));
        for (StatusItem part : StatusItem.values()) {
            labels.label(part).dispose();
        }
        assertTrue(labels.isDisposed());
        int heard = columns.size();
        resizeBar(900);
        assertEquals(heard, columns.size(), "nothing fitted after disposal");
    }
}

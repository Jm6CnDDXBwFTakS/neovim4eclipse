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
import org.eclipse.jface.resource.JFaceResources;
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
 * each sized to its content and font, the cap, and the statusline's columns passed on.
 */
class StatusBarLabelsTest {

    /** Mode texts as the Full style has them, the widest last (capitals, wider than average). */
    private static final List<String> MODE_TEXTS = List.of("-- NORMAL --", "-- INSERT --", "-- (select) VISUAL BLOCK --");

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

    private static StatusBarLabels.Widths widths(int statuslineChars, int maxShare) {
        return new StatusBarLabels.Widths(MODE_TEXTS, 10, statuslineChars, maxShare);
    }

    private StatusBarLabels create(Set<StatusItem> parts, StatusBarLabels.Widths widths) {
        labels = new StatusBarLabels(bar, parts, null, JFaceResources.getTextFont(), widths, columns::add);
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

    private double charWidth(StatusItem part) {
        GC gc = new GC(labels.label(part));
        try {
            gc.setFont(labels.label(part).getFont());
            return gc.getFontMetrics().getAverageCharacterWidth();
        } finally {
            gc.dispose();
        }
    }

    private int textWidth(StatusItem part, String text) {
        GC gc = new GC(labels.label(part));
        try {
            gc.setFont(labels.label(part).getFont());
            return gc.textExtent(text).x;
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
        create(EnumSet.of(StatusItem.STATUSLINE, StatusItem.MODE), widths(60, 90));
        assertEquals(EnumSet.of(StatusItem.MODE, StatusItem.STATUSLINE), labels.parts());
        assertNull(labels.label(StatusItem.CMD), "cmd not chosen: no label");
        List<Control> children = List.of(bar.getChildren());
        assertTrue(children.indexOf(labels.label(StatusItem.MODE)) < children.indexOf(labels.label(StatusItem.STATUSLINE)),
                "mode before statusline: " + children);
        assertEquals(SWT.LEFT, labels.label(StatusItem.MODE).getAlignment());
        assertEquals(JFaceResources.getTextFont(), labels.label(StatusItem.STATUSLINE).getFont(),
                "the statusline in the editor's fixed-width font, so its columns are exact");
    }

    /**
     * The mode as wide as its widest text as drawn (capitals overflow an average-width count), cmd
     * and statusline in characters of their fonts.
     */
    @Test
    void eachLabelIsSizedToItsContentAndFont() {
        create(EnumSet.allOf(StatusItem.class), widths(60, 90));
        int widest = MODE_TEXTS.stream().mapToInt(text -> textWidth(StatusItem.MODE, text)).max().orElseThrow();
        assertEquals(widest + margins(), width(StatusItem.MODE));
        assertEquals(StatusBarWidths.pixels(10, charWidth(StatusItem.CMD)) + margins(), width(StatusItem.CMD));
        assertEquals(StatusBarWidths.pixels(60, charWidth(StatusItem.STATUSLINE)) + margins(), width(StatusItem.STATUSLINE));
        assertEquals(List.of(60), columns, "the statusline's columns, once");
    }

    /** A narrow window: the statusline gives up its width first, and Neovim hears the new columns. */
    @Test
    void theCapShrinksTheStatuslineAndTellsNeovim() {
        create(EnumSet.allOf(StatusItem.class), widths(60, 40));
        double cw = charWidth(StatusItem.STATUSLINE);
        int fixed = width(StatusItem.MODE) + width(StatusItem.CMD);
        resizeBar((int) ((fixed + StatusBarWidths.pixels(30, cw) + margins()) / 0.40) + 1);
        int statusline = width(StatusItem.STATUSLINE);
        assertTrue(statusline < StatusBarWidths.pixels(60, cw) + margins(), "shrunk: " + statusline);
        assertEquals(StatusBarWidths.columns(statusline - margins(), cw), columns.get(columns.size() - 1),
                "Neovim fits the statusline to what is left: " + columns);
        assertTrue(columns.get(columns.size() - 1) < 60);
        assertEquals(columns.get(columns.size() - 1), labels.statuslineColumns(), "kept for the window's next editor");

        resizeBar(4000);
        assertEquals(60, columns.get(columns.size() - 1), "a wider window gives the full width back");
    }

    /**
     * Another item in the bar (Eclipse's heap status is a {@code Composite}) takes space too: what is
     * free shrinks by its width, so JFace does not have to cut the plug-in's labels.
     */
    @Test
    void otherItemsInTheBarAreCounted() {
        resizeBar(1500);
        create(EnumSet.allOf(StatusItem.class), widths(60, 90));
        int alone = width(StatusItem.STATUSLINE);
        Control[] children = bar.getChildren();
        for (int i = 3; i < children.length; i++) { // the labels and separators; JFace's own three stay
            children[i].dispose();
        }
        Composite heapStatus = new Composite(bar, SWT.NONE);
        StatusLineLayoutData data = new StatusLineLayoutData();
        data.widthHint = 400;
        heapStatus.setLayoutData(data);
        create(EnumSet.allOf(StatusItem.class), widths(60, 90));
        assertTrue(width(StatusItem.STATUSLINE) < alone,
                "the 400 px item left the statusline less room: " + width(StatusItem.STATUSLINE) + " < " + alone);
    }

    @Test
    void textsAndTooltips() {
        create(EnumSet.allOf(StatusItem.class), widths(60, 90));
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
        int before = bar.getListeners(SWT.Resize).length;
        create(EnumSet.allOf(StatusItem.class), widths(60, 40));
        for (StatusItem part : StatusItem.values()) {
            labels.label(part).dispose();
        }
        assertTrue(labels.isDisposed());
        assertEquals(before, bar.getListeners(SWT.Resize).length, "the listener left with the labels");
        int heard = columns.size();
        resizeBar(900);
        assertEquals(heard, columns.size(), "nothing fitted after disposal");
    }

    /** With every part turned off there is no label to take a listener away again: none is added. */
    @Test
    void noPartsAddNoListener() {
        int before = bar.getListeners(SWT.Resize).length;
        create(EnumSet.noneOf(StatusItem.class), widths(60, 40));
        assertEquals(before, bar.getListeners(SWT.Resize).length);
    }
}

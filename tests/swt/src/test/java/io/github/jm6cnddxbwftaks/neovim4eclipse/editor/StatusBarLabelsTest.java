// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * {@link StatusBarLabels} in a real JFace status bar ({@code StatusLineManager.createControl}, the
 * composite Eclipse puts at the bottom of a window, with its own layout): the mode label, as wide
 * as its widest text. Neovim's status line is not here: each editor draws it under its text
 * ({@code StatusLineRowTest}).
 */
class StatusBarLabelsTest {

    /** Mode texts, the widest last (capitals, wider than average). */
    private static final List<String> MODE_TEXTS = List.of("NORMAL", "INSERT", "(S) V-BLOCK");

    private Shell shell;
    private Composite bar;
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

    private StatusBarLabels create(Set<StatusItem> parts) {
        labels = new StatusBarLabels(bar, parts, JFaceResources.getTextFont(), MODE_TEXTS);
        return labels;
    }

    private int width() {
        return ((StatusLineLayoutData) labels.label(StatusItem.MODE).getLayoutData()).widthHint;
    }

    private int widest(List<String> texts) {
        CLabel label = labels.label(StatusItem.MODE);
        GC gc = new GC(label);
        try {
            gc.setFont(label.getFont());
            return texts.stream().mapToInt(text -> gc.textExtent(text).x).max().orElseThrow()
                    + label.getLeftMargin() + label.getRightMargin();
        } finally {
            gc.dispose();
        }
    }

    @Test
    void theModeIsALeftAlignedLabelInTheFontGiven() {
        create(EnumSet.of(StatusItem.MODE));
        assertEquals(EnumSet.of(StatusItem.MODE), labels.parts());
        assertEquals(SWT.LEFT, labels.label(StatusItem.MODE).getAlignment());
        assertEquals(JFaceResources.getTextFont(), labels.label(StatusItem.MODE).getFont());
    }

    @Test
    void noModeNoLabel() {
        int children = bar.getChildren().length;
        create(EnumSet.noneOf(StatusItem.class));
        assertNull(labels.label(StatusItem.MODE));
        assertEquals(children, bar.getChildren().length, "nothing added to the bar");
        assertFalse(labels.isDisposed());
    }

    /** As wide as its widest text as drawn (capitals overflow an average-width count). */
    @Test
    void theLabelIsAsWideAsItsWidestText() {
        create(EnumSet.of(StatusItem.MODE));
        assertEquals(widest(MODE_TEXTS), width());
    }

    /** New mode texts (another set of names) refit the label; the same texts change nothing. */
    @Test
    void newModeTextsRefitTheLabel() {
        create(EnumSet.of(StatusItem.MODE));
        List<String> longer = List.of("NORMAL", "VISUAL BLOCK REPLACE PENDING");
        labels.setModeTexts(longer);
        assertEquals(widest(longer), width());
        assertTrue(width() > widest(MODE_TEXTS));
    }

    @Test
    void textsAndTooltips() {
        create(EnumSet.of(StatusItem.MODE));
        labels.show(StatusItem.MODE, "-VR-", "-VR- = VREPLACE", null);
        assertEquals("-VR-", labels.label(StatusItem.MODE).getText());
        assertEquals("-VR- = VREPLACE", labels.label(StatusItem.MODE).getToolTipText());
        labels.show(StatusItem.MODE, "a & b", null, null);
        assertEquals("a & b", labels.label(StatusItem.MODE).getToolTipText(), "the text itself when no tooltip is given");
        labels.show(StatusItem.MODE, "", "unused", null);
        assertNull(labels.label(StatusItem.MODE).getToolTipText(), "no tooltip for an empty label");
    }

    /** Eclipse disposes the labels when it rebuilds the bar; showing then does nothing. */
    @Test
    void disposedLabelsAreLeftAlone() {
        create(EnumSet.of(StatusItem.MODE));
        labels.label(StatusItem.MODE).dispose();
        assertTrue(labels.isDisposed());
        labels.show(StatusItem.MODE, "INSERT", null, null);
        labels.setModeTexts(List.of("X"));
    }
}

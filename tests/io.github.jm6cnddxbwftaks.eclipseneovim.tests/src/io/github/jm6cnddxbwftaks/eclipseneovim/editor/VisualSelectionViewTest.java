// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession.VisualSelection;

/** Neovim's visual selections shown in a real StyledText. */
class VisualSelectionViewTest {

    private static final String TEXT = "alpha\nbeta\ngamma";

    private Shell shell;
    private StyledText widget;
    private IDocument doc;
    private VisualSelectionView view;
    private final List<String> caretChanges = new ArrayList<>();

    @BeforeEach
    void create() {
        shell = new Shell(Display.getCurrent());
        widget = new StyledText(shell, SWT.MULTI);
        widget.setText(TEXT);
        doc = new Document(TEXT);
        view = new VisualSelectionView(widget, change -> {
            caretChanges.add("from Neovim");
            change.run();
        });
    }

    @AfterEach
    void dispose() {
        view.dispose();
        shell.dispose();
    }

    @Test
    void characterwiseSelectionIncludesTheCharacterUnderTheCursor() throws Exception {
        // v from "lpha" (line 1, col 1) to "be" (line 2, col 1)
        view.show(doc, new VisualSelection(true, "v", 1, 1, 2, 1));
        assertEquals(new Point(1, 8), widget.getSelection());
    }

    @Test
    void cornersInEitherOrderGiveTheSameSelection() throws Exception {
        view.show(doc, new VisualSelection(true, "v", 2, 1, 1, 1));
        assertEquals(new Point(1, 8), widget.getSelection());
    }

    @Test
    void linewiseSelectionCoversWholeLines() throws Exception {
        view.show(doc, new VisualSelection(true, "V", 2, 2, 3, 0));
        assertEquals(new Point(6, 16), widget.getSelection());
    }

    @Test
    void inactiveSelectionClearsTheWidgetSelection() throws Exception {
        widget.setSelection(0, 5);
        view.show(doc, VisualSelection.none());
        assertEquals(0, widget.getSelectionCount());
    }

    @Test
    void blockwiseSelectionMovesOnlyTheCaretAndMarksItAsNeovims() throws Exception {
        widget.setSelection(0, 5);
        view.show(doc, new VisualSelection(true, "\u0016", 1, 1, 3, 2));
        assertEquals(0, widget.getSelectionCount());
        assertEquals(13, widget.getCaretOffset()); // line 3, col 2
        assertEquals(List.of("from Neovim"), caretChanges);
    }
}

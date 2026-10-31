// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;

/**
 * A {@link MessageDialog} whose message is laid out, not one block of text: the opening paragraph,
 * then each point as a row with its heading in bold on the left and its text wrapping beside it (a
 * hanging indent), then the closing line, with some space between the three groups
 * ({@link HeadedMessage} has the parts and an example). The welcome and the Restart Neovim question
 * use it, so the plug-in's longer dialogs look alike.
 *
 * <p>How: a {@code MessageDialog} lays out two columns, the icon and its message label. Here the
 * message is {@code null}, so it draws the icon only ({@code createMessageArea}), and this class
 * puts its own composite in the second column, where the label would be. The bold font comes from
 * JFace's registry (shared, never disposed here), and {@code Dialog.applyDialogFont} leaves a font
 * that was set alone. A wrapping label wraps at its width hint, so each gets one: the paragraphs the
 * whole width, the points' texts what the widest heading leaves of it.
 */
class HeadedMessageDialog extends MessageDialog {

    /** The text's width in dialog units: a little wider than a plain message's (300), for the heading column. */
    private static final int WIDTH_DLUS = 340;
    /** The space between a point's rows, in dialog units. */
    private static final int ROW_DLUS = 3;
    /** The extra space between the groups (intro, points, footer), in dialog units. */
    private static final int GROUP_DLUS = 6;
    /** The space between a heading and its text, in dialog units. */
    private static final int GAP_DLUS = 14;

    private final HeadedMessage message;

    /**
     * @param kind         the icon: {@link MessageDialog#INFORMATION}, {@link MessageDialog#QUESTION}, ...
     * @param defaultIndex the button Enter presses
     * @param buttons      the buttons' labels, in order; a press closes the dialog and {@code open()}
     *                     returns its index
     */
    HeadedMessageDialog(Shell shell, String title, int kind, HeadedMessage message, int defaultIndex,
            String... buttons) {
        super(shell, title, null, null, kind, defaultIndex, buttons);
        this.message = message;
    }

    @Override
    protected Control createMessageArea(Composite composite) {
        super.createMessageArea(composite); // the icon; no label, as the message is null
        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
        int width = convertHorizontalDLUsToPixels(WIDTH_DLUS);
        int gap = convertHorizontalDLUsToPixels(GAP_DLUS);
        int groupGap = convertVerticalDLUsToPixels(GROUP_DLUS);
        Composite body = new Composite(composite, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(gap, convertVerticalDLUsToPixels(ROW_DLUS))
                .applyTo(body);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(body);
        int textWidth = width - gap - widestHeading(body, bold);

        paragraph(body, message.intro(), 0, width);
        boolean first = true;
        for (HeadedMessage.Point point : message.points()) {
            int above = first ? groupGap : 0;
            Label heading = new Label(body, SWT.NONE);
            heading.setText(point.heading());
            heading.setFont(bold);
            GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.BEGINNING).indent(0, above).applyTo(heading);
            Label text = new Label(body, SWT.WRAP);
            text.setText(point.text());
            GridDataFactory.fillDefaults().grab(true, false).hint(textWidth, SWT.DEFAULT).indent(0, above)
                    .applyTo(text);
            first = false;
        }
        if (message.hasFooter()) {
            paragraph(body, message.footer(), groupGap, width);
        }
        return composite;
    }

    /** The width of the widest heading in {@code bold}, in pixels. */
    private int widestHeading(Composite body, Font bold) {
        GC gc = new GC(body);
        try {
            gc.setFont(bold);
            int widest = 0;
            for (HeadedMessage.Point point : message.points()) {
                widest = Math.max(widest, gc.textExtent(point.heading()).x);
            }
            return widest;
        } finally {
            gc.dispose();
        }
    }

    /** A paragraph across both columns, wrapped at {@code width}, {@code above} pixels below the row before it. */
    private static void paragraph(Composite body, String text, int above, int width) {
        Label label = new Label(body, SWT.WRAP);
        label.setText(text);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(width, SWT.DEFAULT).indent(0, above)
                .applyTo(label);
    }
}

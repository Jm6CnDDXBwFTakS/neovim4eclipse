// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;

/**
 * A {@link MessageDialog} whose message is laid out, not one block of text: the opening paragraph,
 * then each point as a row with its heading in bold on the left and its text wrapping beside it (a
 * hanging indent), then the closing line, with some space between the three groups
 * ({@link HeadedMessage} has the parts and an example). The welcome, the Restart Neovim question,
 * the "Neovim not started" notice and Get Neovim's dialogs use it, so the plug-in's longer dialogs
 * look alike.
 *
 * <p>A point's cell (the second column) holds, from the top: its text; its value to copy, in a
 * read-only field in the text font (fixed width, so a hash or a path reads character by character)
 * with a <em>Copy</em> button; its links, on one line. Example:
 *
 * <pre>
 *   (i)  Neovim 0.12.6 is installed.
 *
 *        Program   [/home/you/.local/share/.../bin/nvim] (Copy)
 *        SHA-256   The archive matched the one pinned in the plug-in.
 *                  [2e58d259ffcc...] (Copy)
 *                  VirusTotal report
 * </pre>
 *
 * <p>How: a {@code MessageDialog} lays out two columns, the icon and its message label. Here the
 * message is {@code null}, so it draws the icon only ({@code createMessageArea}), and this class
 * puts its own composite in the second column, where the label would be. The bold and the text font
 * come from JFace's registry (shared, never disposed here), and {@code Dialog.applyDialogFont} leaves
 * a font that was set alone. A wrapping label wraps at its width hint, so each gets one: the
 * paragraphs the whole width, the points' texts what the widest heading leaves of it. A value wider
 * than that (a 64-digit SHA-256) widens the whole text, rather than scroll inside its field.
 *
 * <p>The dialog uses SWT and JFace only, no workbench, so {@code tests/swt} can open it. Links open
 * with SWT's {@link Program#launch}, the system's browser; a caller in the workbench may pass
 * Eclipse's own browser support instead ({@link #openLinksWith}).
 *
 * <p>{@link #open()} returns the index of the button pressed, or {@link #CLOSED} for Esc or the
 * window's close box.
 */
public class HeadedMessageDialog extends MessageDialog {

    /** {@link #open()}'s answer when no button was pressed: what {@code MessageDialog} returns then. */
    public static final int CLOSED = SWT.DEFAULT;

    /** The text's width in dialog units: a little wider than a plain message's (300), for the heading column. */
    private static final int WIDTH_DLUS = 340;
    /** The space between a point's rows, and between the parts of a point's cell, in dialog units. */
    private static final int ROW_DLUS = 3;
    /**
     * The extra space above each point after the first, in dialog units, so that the points read as
     * paragraphs (2026-11-05; with {@link #ROW_DLUS} alone the rows ran together). A row with no
     * heading continues the point above it (the Unused list's paths, a line after a Copy field) and
     * gets none.
     */
    private static final int POINT_DLUS = 3;

    /**
     * Room left after the text of a Copy field, in horizontal dialog units. A field is sized to its
     * text, and without this a long value (a folder, a quoted -vmargs line) touched the field's right
     * border on Windows (seen 2026-11-05 in the checks gallery). 4 DLUs is about one character.
     */
    private static final int VALUE_ROOM_DLUS = 4;
    /** The extra space between the groups (intro, points, footer), in dialog units. */
    private static final int GROUP_DLUS = 6;
    /** The space between a heading and its text, in dialog units. */
    private static final int GAP_DLUS = 14;

    /** Between two links on their line: a middle dot, which reads as "or", with room around it. */
    static final String LINK_SEPARATOR = "   ·   ";

    private final HeadedMessage message;
    private Consumer<URI> linkOpener = url -> Program.launch(url.toString());

    /** The width hints that follow the text's width, if a value widens it: the paragraphs and the points' cells. */
    private final List<GridData> fullWidth = new ArrayList<>();
    private final List<GridData> cellWidth = new ArrayList<>();

    /**
     * @param kind         the icon: {@link MessageDialog#INFORMATION}, {@link MessageDialog#QUESTION},
     *                     {@link MessageDialog#ERROR}, ...
     * @param defaultIndex the button Enter presses
     * @param buttons      the buttons' labels, in order; a press closes the dialog and {@code open()}
     *                     returns its index
     */
    public HeadedMessageDialog(Shell shell, String title, int kind, HeadedMessage message, int defaultIndex,
            String... buttons) {
        super(shell, title, null, null, kind, defaultIndex, buttons);
        this.message = message;
    }

    /**
     * Opens the points' links with {@code opener} instead of the system's browser. Example: Eclipse's
     * external browser, which follows the user's choice in General &gt; Web Browser.
     */
    public HeadedMessageDialog openLinksWith(Consumer<URI> opener) {
        this.linkOpener = Objects.requireNonNull(opener);
        return this;
    }

    @Override
    protected Control createMessageArea(Composite composite) {
        super.createMessageArea(composite); // the icon; no label, as the message is null
        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
        int width = convertHorizontalDLUsToPixels(WIDTH_DLUS);
        int gap = convertHorizontalDLUsToPixels(GAP_DLUS);
        int row = convertVerticalDLUsToPixels(ROW_DLUS);
        int groupGap = convertVerticalDLUsToPixels(GROUP_DLUS);
        int pointGap = convertVerticalDLUsToPixels(POINT_DLUS);
        Composite body = new Composite(composite, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(gap, row).applyTo(body);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(body);
        int headings = widestHeading(body, bold);
        int textWidth = width - gap - headings;

        paragraph(body, message.intro(), 0, width);
        int widestValue = 0;
        boolean first = true;
        for (HeadedMessage.Point point : message.points()) {
            int above = first ? groupGap : point.heading().isEmpty() ? 0 : pointGap;
            Label heading = new Label(body, SWT.NONE);
            heading.setText(literal(point.heading()));
            heading.setFont(bold);
            GridData headingData = GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.BEGINNING)
                    .indent(0, above).create();
            heading.setLayoutData(headingData);
            Composite cell = new Composite(body, SWT.NONE);
            GridLayoutFactory.fillDefaults().spacing(0, row).applyTo(cell);
            GridData cellData = GridDataFactory.fillDefaults().grab(true, false).hint(textWidth, SWT.DEFAULT)
                    .indent(0, above).create();
            cell.setLayoutData(cellData);
            cellWidth.add(cellData);
            if (!point.text().isEmpty()) {
                Label text = new Label(cell, SWT.WRAP);
                text.setText(literal(point.text()));
                GridData textData = GridDataFactory.fillDefaults().grab(true, false).hint(textWidth, SWT.DEFAULT)
                        .create();
                text.setLayoutData(textData);
                cellWidth.add(textData);
            }
            if (point.hasCopy()) {
                Composite value = valueRow(cell, point.copy());
                widestValue = Math.max(widestValue, value.computeSize(SWT.DEFAULT, SWT.DEFAULT).x
                        + convertHorizontalDLUsToPixels(VALUE_ROOM_DLUS));
                if (point.text().isEmpty()) { // the heading beside the field's middle, not above it
                    int lift = value.computeSize(SWT.DEFAULT, SWT.DEFAULT).y - heading.computeSize(SWT.DEFAULT,
                            SWT.DEFAULT).y;
                    headingData.verticalIndent += Math.max(0, lift / 2);
                }
            }
            if (!point.links().isEmpty()) {
                links(cell, point.links());
            }
            first = false;
        }
        if (message.hasFooter()) {
            paragraph(body, message.footer(), groupGap, width);
        }
        if (widestValue > textWidth) { // widen everything to the value, so it shows whole
            int extra = widestValue - textWidth;
            cellWidth.forEach(d -> d.widthHint += extra);
            fullWidth.forEach(d -> d.widthHint += extra);
        }
        return composite;
    }

    /** A read-only field in the text font with {@code value}, and a Copy button ({@link CopyField}). */
    private Composite valueRow(Composite cell, String value) {
        Composite row = CopyField.create(cell, value);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(row);
        return row;
    }

    /** The links on one line, apart by {@link #LINK_SEPARATOR}; each opens its URL with {@link #openLinksWith the opener}. */
    private void links(Composite cell, List<HeadedMessage.Link> links) {
        Link widget = new Link(cell, SWT.NONE);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < links.size(); i++) {
            text.append(i == 0 ? "" : LINK_SEPARATOR).append("<a href=\"").append(i).append("\">")
                    .append(literal(links.get(i).label())).append("</a>");
        }
        widget.setText(text.toString());
        // A Link has one tooltip, not one per link: it lists where each goes.
        widget.setToolTipText(links.stream().map(l -> l.url().toString()).collect(Collectors.joining("\n")));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(widget);
        widget.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            int index = Integer.parseInt(e.text); // the href of the link clicked
            linkOpener.accept(links.get(index).url());
        }));
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

    /**
     * {@code text} as a Label or Link shows it literally: SWT takes a single {@code &} as the
     * mnemonic marker (it underlines the next letter and hides itself), so each one is doubled.
     * Example: {@code "Tom & Jerry"} is set as {@code "Tom && Jerry"}, and shows as written.
     */
    static String literal(String text) {
        return text.replace("&", "&&");
    }

    /** A paragraph across both columns, wrapped at {@code width}, {@code above} pixels below the row before it. */
    private void paragraph(Composite body, String text, int above, int width) {
        Label label = new Label(body, SWT.WRAP);
        label.setText(literal(text));
        GridData data = GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(width, SWT.DEFAULT)
                .indent(0, above).create();
        label.setLayoutData(data);
        fullWidth.add(data);
    }
}

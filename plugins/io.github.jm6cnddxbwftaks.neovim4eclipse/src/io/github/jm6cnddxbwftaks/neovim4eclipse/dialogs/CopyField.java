// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.PixelConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;

/**
 * A value to copy: a read-only field in the text font, and a <em>Copy</em> button that puts the
 * value on the clipboard and then says <em>Copied</em>. Example, a row of the Get Neovim dialog or
 * of the preference page's help:
 *
 * <pre>
 *   [-vmargs -Dneovim4eclipse.nvim=/path/to/nvim]   (Copy)
 * </pre>
 *
 * One class for every such row ({@link HeadedMessageDialog}'s Copy fields, the preference page's
 * command line), so they look and behave alike. A field, not a label: the text can be selected in
 * part, and it shows that it is a value rather than prose.
 */
public final class CopyField {

    /** The button's text, and what it says once pressed. */
    public static final String COPY = "Copy";
    public static final String COPIED = "Copied";

    private CopyField() {
    }

    /**
     * Adds the row to {@code parent}: a composite of two columns, the field filling the width it is
     * given and the button at its end. The caller sets the row's layout data.
     */
    public static Composite create(Composite parent, String value) {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(row);
        Text field = new Text(row, SWT.READ_ONLY | SWT.SINGLE | SWT.BORDER);
        field.setText(value);
        field.setFont(JFaceResources.getTextFont());
        // No selection when the field gains focus. A page gives its first control focus when it
        // opens, and on macOS (where links and buttons take no focus by default) that is this field,
        // whose text the system then selects whole: it looked selected by accident. The select-all
        // comes after the focus event, so the selection is cleared once the event loop has run it.
        // Copy is the button's job; a mouse can still select part of the text.
        field.addListener(SWT.FocusIn, e -> field.getDisplay().asyncExec(() -> {
            if (!field.isDisposed()) {
                field.setSelection(0);
            }
        }));
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(field);
        Button copy = new Button(row, SWT.PUSH);
        copy.setText(COPY);
        PixelConverter dlus = new PixelConverter(row);
        // As wide as "Copied", which it says after a press, so the row does not shift.
        int buttonWidth = Math.max(dlus.convertHorizontalDLUsToPixels(40), Math.max(copy.computeSize(SWT.DEFAULT,
                SWT.DEFAULT).x, textWidthOf(copy, COPIED) + dlus.convertHorizontalDLUsToPixels(8)));
        GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).hint(buttonWidth, SWT.DEFAULT).applyTo(copy);
        copy.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            copyToClipboard(copy, value);
            copy.setText(COPIED);
        }));
        return row;
    }

    private static void copyToClipboard(Control control, String text) {
        Clipboard clipboard = new Clipboard(control.getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    /** The width of {@code text} in {@code control}'s font, in pixels. */
    private static int textWidthOf(Control control, String text) {
        GC gc = new GC(control);
        try {
            return gc.textExtent(text).x;
        } finally {
            gc.dispose();
        }
    }
}

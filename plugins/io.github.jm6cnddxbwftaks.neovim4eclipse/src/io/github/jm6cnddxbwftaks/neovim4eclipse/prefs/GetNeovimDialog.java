// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * The dialogs of <i>Get Neovim</i>: the confirmation, the done note and the failure. Each is a text,
 * optionally the archive's SHA-256 (selectable, with <em>Copy</em>), links that open in the external
 * browser, and its buttons; {@link #open()} returns the index of the button pressed, or
 * {@link #CLOSED} if the dialog was closed otherwise (Esc, the window's close box).
 *
 * <p>Why the SHA-256 and the links: the plug-in checks the archive against the hash pinned in it, and
 * a user who wants to check that hash themselves can compare it with GitHub's release page or look
 * it up on VirusTotal. The plug-in never contacts VirusTotal: only the user's click on the link sends
 * the hash, from their browser.
 */
final class GetNeovimDialog extends Dialog {

    /** {@link #open()}'s answer when no button was pressed. */
    static final int CLOSED = -1;

    private static final Logger LOG = Logger.getLogger(GetNeovimDialog.class.getName());

    /** Width at which the text wraps. */
    private static final int WRAP_WIDTH = 480;

    /** A link under the text. */
    record DialogLink(String label, URI url) {
    }

    private final String title;
    private final String message;
    private final String sha256;
    private final List<DialogLink> links;
    private final List<String> buttons;
    private final int defaultButton;
    private int pressed = CLOSED;

    /**
     * @param sha256        shown with Copy, or {@code null}
     * @param buttons       labels, left to right
     * @param defaultButton the index of the button Enter presses
     */
    GetNeovimDialog(Shell parent, String title, String message, String sha256, List<DialogLink> links,
            List<String> buttons, int defaultButton) {
        super(parent);
        this.title = title;
        this.message = message;
        this.sha256 = sha256;
        this.links = links;
        this.buttons = buttons;
        this.defaultButton = defaultButton;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(title);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        ((GridLayout) area.getLayout()).numColumns = 2;

        Label text = new Label(area, SWT.WRAP);
        text.setText(message);
        text.setLayoutData(wrapping(2));

        if (sha256 != null) {
            Label label = new Label(area, SWT.NONE);
            label.setText("SHA-256:");
            label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 2, 1));
            Text hash = new Text(area, SWT.READ_ONLY | SWT.SINGLE | SWT.BORDER);
            hash.setText(sha256);
            hash.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
            hash.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Button copy = new Button(area, SWT.PUSH);
            copy.setText("Copy");
            copy.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> copy(hash.getText())));
        }
        for (DialogLink link : links) {
            Link widget = new Link(area, SWT.NONE);
            widget.setText("<a>" + link.label() + "</a>");
            widget.setToolTipText(link.url().toString());
            widget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            widget.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> openInBrowser(link.url())));
        }
        return area;
    }

    private static GridData wrapping(int span) {
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, span, 1);
        data.widthHint = WRAP_WIDTH;
        return data;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        for (int i = 0; i < buttons.size(); i++) {
            createButton(parent, IDialogConstants.CLIENT_ID + i, buttons.get(i), i == defaultButton);
        }
    }

    @Override
    protected void buttonPressed(int buttonId) {
        pressed = buttonId - IDialogConstants.CLIENT_ID;
        close();
    }

    @Override
    public int open() {
        pressed = CLOSED;
        super.open();
        return pressed;
    }

    private void copy(String text) {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    static void openInBrowser(URI url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(url.toURL());
        } catch (Exception e) { // PartInitException (no browser), or a malformed URL (a bug)
            LOG.log(Level.WARNING, "Could not open " + url, e);
        }
    }
}

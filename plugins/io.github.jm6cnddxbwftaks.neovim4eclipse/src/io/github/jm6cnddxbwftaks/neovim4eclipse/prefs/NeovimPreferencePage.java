// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Window → Preferences → Neovim.
 *
 * <p>A {@link FieldEditorPreferencePage} builds the page from <em>field editors</em>: each one
 * ({@link StringFieldEditor}, {@link BooleanFieldEditor}, ...) shows one preference, loads it from the
 * store, writes it back on Apply, and restores its default on Restore Defaults, with no further
 * code. {@code plugin.xml} registers the page under {@code org.eclipse.ui.preferencePages}; the
 * Status Bar ({@link StatusBarPreferencePage}) and Ctrl Keys ({@link CtrlKeysPreferencePage}) pages
 * are nested under it with a {@code category}.
 */
public class NeovimPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    /** The README section on where the plug-in looks for Neovim, opened by the link under the description. */
    public static final String FINDING_NEOVIM_URL = "https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#finding-neovim";

    /** The link's text; {@code <a>} marks the clickable part. */
    public static final String FINDING_NEOVIM_LINK = "<a>How Neovim for Eclipse finds Neovim</a>";

    /** Wrap the description at this width rather than widening the dialog to fit it on one line. */
    private static final int DESCRIPTION_WIDTH = 400;

    private static final Logger LOG = Logger.getLogger(NeovimPreferencePage.class.getName());

    private Label description;

    public NeovimPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure the Neovim for Eclipse integration. Leave the Neovim folder blank to search the "
                + "default folders, or enter a folder that holds Neovim, or enter a direct path to a nvim executable.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // no-op
    }

    /**
     * The description, followed by a link to the README section that lists the default folders. A
     * {@link Label} cannot hold a link, so the {@link Link} is a widget of its own right below it.
     */
    @Override
    protected Label createDescriptionLabel(Composite parent) {
        description = super.createDescriptionLabel(parent);
        Link link = new Link(parent, SWT.NONE);
        link.setText(FINDING_NEOVIM_LINK);
        link.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        link.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> openInBrowser(FINDING_NEOVIM_URL)));
        return description;
    }

    private static void openInBrowser(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
        } catch (Exception e) { // PartInitException (no browser), or a malformed URL (a bug)
            LOG.log(Level.WARNING, "Could not open " + url, e);
        }
    }

    @Override
    protected void createFieldEditors() {
        // PreferencePage gives the description its layout data after createDescriptionLabel; here,
        // later, a width hint makes the long description wrap instead of widening the dialog.
        if (description != null && description.getLayoutData() instanceof GridData data) {
            data.widthHint = DESCRIPTION_WIDTH;
        }
        // A folder (Neovim's install folder), a program, a command name, or blank to search; the note
        // under it says which Neovim that finds (NeovimLocationFieldEditor, util/NvimExecutable).
        addField(new NeovimLocationFieldEditor(
                PreferenceConstants.NVIM_EXECUTABLE,
                "Neovim folder or program:",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_TOOLBAR,
                "Show the Neovim button on the main toolbar",
                getFieldEditorParent()));

        // Hidden, its two entries stay reachable: Window > Preferences > Neovim, and the toggle's
        // key binding, toolbar button and Quick Access (Ctrl+3).
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_MENU,
                "Show Neovim menu in the menu bar",
                getFieldEditorParent()));

        // Messages and errors always appear in a line at the bottom of the editor; this adds a flash.
        addField(new BooleanFieldEditor(
                PreferenceConstants.FLASH_ON_ERROR,
                "Flash the editor on errors",
                getFieldEditorParent()));

        // The welcome after installing, and the short note after an update to a new minor version.
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_WELCOME,
                "Show a welcome after installing, and a note after updates to a new minor version",
                getFieldEditorParent()));
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        if (ok) {
            // Re-evaluate menu/toolbar visibility expressions
            ContributionVisibility.refresh();
        }
        return ok;
    }

    @Override
    protected void performApply() {
        super.performApply();
        ContributionVisibility.refresh();
    }
}

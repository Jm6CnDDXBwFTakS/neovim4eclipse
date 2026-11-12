// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.layout.GridLayout;

/**
 * The body of Window > Preferences > Neovim > Status Bar, in JFace alone: which parts of Vim's
 * status information the Eclipse status bar shows, and how wide the parts may be. Changes apply at
 * once (the {@code Activator} listens to the preference store). {@link StatusBarPreferencePage} is
 * the page Eclipse opens (why two classes: {@code package-info}).
 *
 * <p>Like {@link NeovimPage}, a {@link FieldEditorPreferencePage}: each control is a field editor
 * that loads, stores and restores its preference by itself. An {@link IntegerFieldEditor} refuses a
 * value outside its range and disables OK until it is fixed.
 */
class StatusBarPage extends FieldEditorPreferencePage {

    /** A page on {@code store}: the plug-in's in Eclipse, an in-memory one in the SWT tests. */
    StatusBarPage(IPreferenceStore store) {
        super(GRID);
        setPreferenceStore(store);
        setDescription("Parts of Vim's status information shown in the Eclipse status bar. Messages and errors appear at the bottom of the editor.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_MODE,
                "mode  (INSERT, V-BLOCK, rec @q)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_SHOWCMD,
                "cmd  (pending command, e.g. 2d)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_STATUSLINE,
                "statusline  (Neovim's 'statusline')", getFieldEditorParent()));
        IntegerFieldEditor width = new IntegerFieldEditor(PreferenceConstants.STATUS_STATUSLINE_WIDTH,
                "Statusline width (characters):", getFieldEditorParent());
        width.setValidRange(PreferenceConstants.STATUS_STATUSLINE_WIDTH_MIN, PreferenceConstants.STATUS_STATUSLINE_WIDTH_MAX);
        addField(width);
        IntegerFieldEditor share = new IntegerFieldEditor(PreferenceConstants.STATUS_MAX_WIDTH_PERCENT,
                "At most this share of the status bar (%):", getFieldEditorParent());
        share.setValidRange(PreferenceConstants.STATUS_MAX_WIDTH_PERCENT_MIN, PreferenceConstants.STATUS_MAX_WIDTH_PERCENT_MAX);
        addField(share);
    }

    /**
     * {@link FieldEditorPreferencePage} lays its editors out in a {@link GridLayout} on
     * {@link #getFieldEditorParent()} and sets its columns here, after every editor was added; the
     * row gap ({@link PageLayout#ROW_SPACING_DLUS}) is set at the same place, on the same layout.
     */
    @Override
    protected void adjustGridLayout() {
        super.adjustGridLayout();
        if (getFieldEditorParent().getLayout() instanceof GridLayout layout) {
            layout.verticalSpacing = convertVerticalDLUsToPixels(PageLayout.ROW_SPACING_DLUS);
        }
    }
}

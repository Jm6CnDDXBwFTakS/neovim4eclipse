// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeLabels;

/**
 * Window > Preferences > Neovim > Status Bar: which parts of Vim's status information the Eclipse
 * status bar shows, how the mode reads, and how wide the parts may be. Changes apply at once (the
 * {@link Activator} listens to the preference store).
 *
 * <p>Like {@link NeovimPreferencePage}, a {@link FieldEditorPreferencePage}: each control is a field
 * editor that loads, stores and restores its preference by itself. An {@link IntegerFieldEditor}
 * refuses a value outside its range and disables OK until it is fixed.
 */
public class StatusBarPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public StatusBarPreferencePage() {
        super(GRID);
        setDescription("Parts of Vim's status information shown in the Eclipse status bar. Messages and errors appear at the bottom of the editor.");
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_ITEM_MODE,
                "mode  (INSERT, recording @q)", getFieldEditorParent()));
        addField(new RadioGroupFieldEditor(PreferenceConstants.STATUS_MODE_STYLE, "Mode style", 3,
                new String[][] {
                        { "Full  (-- INSERT --)", ModeLabels.Style.FULL.key() },
                        { "Short  (INSERT)", ModeLabels.Style.SHORT.key() },
                        { "Letters  (-I-)", ModeLabels.Style.LETTERS.key() } },
                getFieldEditorParent(), true));
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_ITEM_CMD,
                "cmd  (pending command, e.g. 2d)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_ITEM_STATUSLINE,
                "statusline  (Neovim's 'statusline')", getFieldEditorParent()));
        IntegerFieldEditor width = new IntegerFieldEditor(PreferenceConstants.STATUS_STATUSLINE_WIDTH,
                "Statusline width (characters):", getFieldEditorParent());
        width.setValidRange(PreferenceConstants.STATUS_STATUSLINE_WIDTH_MIN, PreferenceConstants.STATUS_STATUSLINE_WIDTH_MAX);
        addField(width);
        IntegerFieldEditor share = new IntegerFieldEditor(PreferenceConstants.STATUS_MAX_SHARE,
                "At most this share of the status bar (%):", getFieldEditorParent());
        share.setValidRange(PreferenceConstants.STATUS_MAX_SHARE_MIN, PreferenceConstants.STATUS_MAX_SHARE_MAX);
        addField(share);
    }

    /**
     * Space between the rows, in dialog units (DLUs): a DLU follows the dialog font, so the gap grows
     * with a larger font or a high-resolution screen, as the rest of the dialog does. A vertical DLU
     * is an eighth of the font's height: 4 DLUs came out as 10 pixels with Linux's default dialog
     * font (screenshots, 2026-10-30). SWT's own default is a fixed 5 pixels, which left the check
     * boxes, the radio group and the number fields crowded together.
     */
    private static final int ROW_SPACING_DLUS = 4;

    /**
     * {@link FieldEditorPreferencePage} lays its editors out in a {@link GridLayout} on
     * {@link #getFieldEditorParent()} and sets its columns here, after every editor was added; the
     * row gap is set at the same place, on the same layout.
     */
    @Override
    protected void adjustGridLayout() {
        super.adjustGridLayout();
        if (getFieldEditorParent().getLayout() instanceof GridLayout layout) {
            layout.verticalSpacing = convertVerticalDLUsToPixels(ROW_SPACING_DLUS);
        }
    }
}

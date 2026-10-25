// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.fieldassist.ControlDecoration;
import org.eclipse.jface.fieldassist.FieldDecoration;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.preference.StringButtonFieldEditor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * The "Neovim folder or program" field: a text field, a <em>Browse...</em> button that picks a
 * folder, a note underneath saying which Neovim the current text finds, for example
 * {@code Found: Neovim 0.12.6, /home/you/.local/share/neovim4eclipse-plugin/neovim/bin/nvim}, and
 * the help: what the field takes, with a link to the README section on where Neovim is searched.
 *
 * <p>The note comes first because it answers what was typed and changes with it; the help, which
 * does not change, follows. A note that reports a problem (Neovim would not start:
 * {@link NvimProbe.Note#problem}) is shown in the theme's error color
 * ({@link JFacePreferences#ERROR_COLOR}, which dark themes brighten), and the field gets Eclipse's
 * error mark at its corner (a {@link ControlDecoration}); hovering the mark shows the note again.
 * The text says the same, so neither the color nor the mark is needed to tell what is wrong.
 *
 * <h2>How a field editor is extended</h2>
 * {@link StringButtonFieldEditor} already lays out label, text and button in the page's grid and
 * calls {@link #changePressed} for the button. This class adds three controls, each in a row of its
 * own: the note, the help, and an empty spacer that separates the field from the check boxes.
 * {@link #doFillIntoGrid} creates them after the others, spanning every column, and
 * {@link #adjustForNumColumns} keeps that span when the page adds wider fields.
 *
 * <h2>Why the note is computed off the UI thread</h2>
 * The note runs {@code nvim --version} ({@link NvimProbe#describe}), which can take a moment and
 * could hang on a broken program. Typing restarts a short delay; when it ends, the probe runs on a
 * background thread and its answer is shown only if no newer text was typed meanwhile
 * ({@link #generation}), so a slow answer never overwrites a newer one.
 */
final class NeovimLocationFieldEditor extends StringButtonFieldEditor {

    /** Typing pause before the probe runs, so it does not start a process for every key. */
    private static final int DELAY_MS = 400;

    /** Height of the empty row below the help, in dialog units (about half a line of text). */
    private static final int SPACE_BELOW_DLUS = 6;

    /** Width at which the note and the help wrap, rather than widening the dialog. */
    private static final int WRAP_WIDTH = 400;

    private static final Logger LOG = Logger.getLogger(NeovimLocationFieldEditor.class.getName());

    private Label note;
    private Link help;
    private Label spacer;
    private ControlDecoration errorMark;
    /** Bumped on every change; a probe's answer is used only if it still matches. */
    private final AtomicInteger generation = new AtomicInteger();

    NeovimLocationFieldEditor(String name, String labelText, Composite parent) {
        super(name, labelText, parent);
        setChangeButtonText("Browse...");
        setEmptyStringAllowed(true);
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        super.doFillIntoGrid(parent, numColumns);
        // No tooltip: the help below says what the field takes, the note what it finds.
        getTextControl().setMessage("Leave blank to search for Neovim"); // shown while the field is empty
        addErrorMark();

        note = new Label(parent, SWT.WRAP);
        note.setLayoutData(fullRow(numColumns, WRAP_WIDTH));

        help = new Link(parent, SWT.NONE);
        help.setText(NeovimPreferencePage.NEOVIM_FIELD_HELP);
        help.setLayoutData(fullRow(numColumns, WRAP_WIDTH));
        help.addSelectionListener(SelectionListener.widgetSelectedAdapter(
                e -> openInBrowser(NeovimPreferencePage.FINDING_NEOVIM_URL)));

        // An empty row under the help, so note and help read as part of this field rather than of
        // the check boxes below. Dialog units (DLUs) scale with the font, as the rest of the page does.
        spacer = new Label(parent, SWT.NONE);
        GridData gap = fullRow(numColumns, SWT.DEFAULT);
        gap.heightHint = convertVerticalDLUsToPixels(spacer, SPACE_BELOW_DLUS);
        spacer.setLayoutData(gap);
        getTextControl().addModifyListener(e -> scheduleProbe());
    }

    /**
     * Eclipse's error mark at the field's top left corner, hidden until a note reports a problem. The
     * mark is drawn outside the text field, so the field moves right by the width of the widest
     * decoration, as Eclipse's own dialogs do, to leave it room.
     */
    private void addErrorMark() {
        FieldDecorationRegistry decorations = FieldDecorationRegistry.getDefault();
        FieldDecoration error = decorations.getFieldDecoration(FieldDecorationRegistry.DEC_ERROR);
        errorMark = new ControlDecoration(getTextControl(), SWT.LEFT | SWT.TOP);
        errorMark.setImage(error.getImage());
        errorMark.hide();
        if (getTextControl().getLayoutData() instanceof GridData text) {
            text.horizontalIndent = decorations.getMaximumDecorationWidth();
        }
    }

    /** Layout data for a control on a row of its own; {@code widthHint} lets wrapping text wrap. */
    private static GridData fullRow(int numColumns, int widthHint) {
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, numColumns, 1);
        data.widthHint = widthHint;
        return data;
    }

    private static void openInBrowser(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
        } catch (Exception e) { // PartInitException (no browser), or a malformed URL (a bug)
            LOG.log(Level.WARNING, "Could not open " + url, e);
        }
    }

    @Override
    protected void adjustForNumColumns(int numColumns) {
        super.adjustForNumColumns(numColumns);
        if (note != null) {
            ((GridData) note.getLayoutData()).horizontalSpan = numColumns;
            ((GridData) help.getLayoutData()).horizontalSpan = numColumns;
            ((GridData) spacer.getLayoutData()).horizontalSpan = numColumns;
        }
    }

    @Override
    public int getNumberOfControls() {
        return 3; // label, text, button; the note has a row of its own
    }

    /** Browse: pick Neovim's folder, starting at the current one or the plug-in's own folder. */
    @Override
    protected String changePressed() {
        DirectoryDialog dialog = new DirectoryDialog(getShell(), SWT.NONE);
        dialog.setText("Neovim folder");
        dialog.setMessage("Choose Neovim's folder (the one with bin/nvim, or a folder with extracted releases"
                + " up to " + NvimExecutable.maxSearchDepth() + " levels down).");
        Path start = startFolder(getStringValue());
        if (start != null) {
            dialog.setFilterPath(start.toString());
        }
        return dialog.open(); // null when cancelled: the text stays as it is
    }

    /** The folder to open the dialog in: the field's folder (or the program's), else the plug-in's own. */
    private static Path startFolder(String text) {
        try {
            if (!text.isBlank()) {
                Path typed = Path.of(NvimExecutable.expandHome(text.strip()));
                if (Files.isDirectory(typed)) {
                    return typed;
                }
                if (typed.getParent() != null && Files.isDirectory(typed.getParent())) {
                    return typed.getParent();
                }
            }
        } catch (RuntimeException invalidPath) {
            // fall through to the default
        }
        Path dedicated = NvimExecutable.dedicatedFolder();
        return Files.isDirectory(dedicated) ? dedicated : null;
    }

    /** Shows a note: its text, in the error color and with the error mark if it reports a problem. */
    private void show(NvimProbe.Note shown) {
        note.setText(shown.text());
        note.setForeground(shown.problem() ? errorColor() : null); // null: the theme's normal color
        if (shown.problem()) {
            errorMark.setDescriptionText(shown.text()); // shown when hovering the mark
            errorMark.show();
        } else {
            errorMark.hide();
        }
        note.getParent().layout(true, true);
    }

    /** The theme's error color: red in the light theme, a brighter red in the dark one. */
    private Color errorColor() {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.ERROR_COLOR);
        return color != null ? color : note.getDisplay().getSystemColor(SWT.COLOR_RED);
    }

    private void scheduleProbe() {
        int mine = generation.incrementAndGet();
        show(new NvimProbe.Note("Looking for Neovim...", false));
        String text = getTextControl().getText();
        note.getDisplay().timerExec(DELAY_MS, () -> {
            if (mine != generation.get() || note.isDisposed()) {
                return; // typed again meanwhile
            }
            CompletableFuture.supplyAsync(() -> NvimProbe.describe(text)).thenAccept(result -> {
                if (note.isDisposed()) {
                    return;
                }
                note.getDisplay().asyncExec(() -> {
                    if (!note.isDisposed() && mine == generation.get()) {
                        show(result);
                    }
                });
            });
        });
    }
}

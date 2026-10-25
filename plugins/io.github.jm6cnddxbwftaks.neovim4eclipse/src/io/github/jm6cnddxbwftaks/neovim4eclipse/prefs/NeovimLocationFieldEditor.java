// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.StringButtonFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * The "Neovim folder or program" field: a text field, a <em>Browse...</em> button that picks a
 * folder, and a note underneath saying which Neovim the current text finds, for example
 * {@code Found: Neovim 0.12.6, /home/you/.local/share/neovim4eclipse-plugin/neovim/bin/nvim}
 *
 * <h2>How a field editor is extended</h2>
 * {@link StringButtonFieldEditor} already lays out label, text and button in the page's grid and
 * calls {@link #changePressed} for the button. This class adds two controls, each in a row of its
 * own: the note, and an empty spacer below it that separates the field from the check boxes.
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

    /** Height of the empty row below the note, in dialog units (about half a line of text). */
    private static final int SPACE_BELOW_DLUS = 6;

    private Label note;
    private Label spacer;
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
        // No tooltip: the page description says what the field takes, the note below what it finds.
        getTextControl().setMessage("Leave blank to search for Neovim"); // shown while the field is empty
        note = new Label(parent, SWT.WRAP);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, numColumns, 1);
        data.widthHint = 400; // lets the label wrap instead of widening the page
        note.setLayoutData(data);
        // An empty row under the note, so it reads as part of this field rather than of the
        // check boxes below. Dialog units (DLUs) scale with the font, as the rest of the page does.
        spacer = new Label(parent, SWT.NONE);
        GridData gap = new GridData(SWT.FILL, SWT.TOP, true, false, numColumns, 1);
        gap.heightHint = convertVerticalDLUsToPixels(spacer, SPACE_BELOW_DLUS);
        spacer.setLayoutData(gap);
        getTextControl().addModifyListener(e -> scheduleProbe());
    }

    @Override
    protected void adjustForNumColumns(int numColumns) {
        super.adjustForNumColumns(numColumns);
        if (note != null) {
            ((GridData) note.getLayoutData()).horizontalSpan = numColumns;
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

    private void scheduleProbe() {
        int mine = generation.incrementAndGet();
        note.setText("Looking for Neovim...");
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
                        note.setText(result);
                        note.getParent().layout(true, true);
                    }
                });
            });
        });
    }
}

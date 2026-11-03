// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * The "Neovim program" field: a text field (blank for the dedicated Neovim, or the absolute path of
 * one program), a <em>Browse...</em> button that picks a program file, a note underneath saying
 * which Neovim the current text names, for example
 * {@code Found: dedicated Neovim 0.12.6, checked, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim},
 * the <em>Get Neovim 0.12.6...</em> button ({@link GetNeovim}), and the help: what the field takes,
 * with a link to the README section on it.
 *
 * <p>When the note finds the dedicated Neovim, current ({@link NvimProbe.Note#current}), there is
 * nothing to get: the button is hidden, and a small <em>Reinstall...</em> link under the note
 * opens the same Get Neovim dialog, for a copy damaged in a way {@code --version} does not show.
 * Example:
 *
 * <pre>
 *   Found: dedicated Neovim 0.12.6, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
 *   Reinstall...
 * </pre>
 *
 * The note is a {@link Label} and the link a {@link Link} of its own, not one {@code Link} for both:
 * a {@code Link} reads {@code <a>...</a>} in its text as a link, and the note shows paths that
 * anyone who can name a folder chooses ({@link #labelText}).
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
 * calls {@link #changePressed} for the button. This class adds two controls, each in a row of its
 * own: the note, the Get Neovim button and the help. (The page's group box, {@link NeovimPreferencePage}, sets them apart
 * from the check boxes.) {@link #doFillIntoGrid} creates them after the others, spanning every column, and
 * {@link #adjustForNumColumns} keeps that span when the page adds wider fields.
 *
 * <h2>Why the note is computed off the UI thread</h2>
 * The note may run {@code nvim --version} ({@link NvimProbe#describe}), which can take a moment and
 * could hang on a broken program. It does so only for a program chosen on purpose (loaded as saved,
 * picked with Browse, or saved with Apply), never for text being typed: a half-typed path may name
 * another user's file ({@link #runNextProbe}). Typing restarts a short delay; when it ends, the probe runs on a
 * background thread and its answer is shown only if no newer text was typed meanwhile
 * ({@link #generation}), so a slow answer never overwrites a newer one.
 */
final class NeovimLocationFieldEditor extends StringButtonFieldEditor {

    /** Typing pause before the probe runs, so it does not start a process for every key. */
    private static final int DELAY_MS = 400;

    /** Width at which the note and the help wrap, rather than widening the dialog. */
    private static final int WRAP_WIDTH = 400;

    /** The link under the note when the dedicated Neovim is current. */
    static final String REINSTALL = "Reinstall...";

    /**
     * What the field finds, in a {@link Label}, never a {@link Link}: the text holds paths (the
     * preference, a program on {@code PATH}, the entries of the plug-in's Neovim folder), and a Link
     * would turn an {@code <a>...</a>} in a folder's name into a link. The one link, Reinstall...,
     * is a control of its own ({@link #reinstall}).
     */
    private Label note;
    /** {@code <a>Reinstall...</a>} under the note, shown only while the dedicated Neovim is current. */
    private Link reinstall;
    private Button getNeovim;
    private Link help;
    private ControlDecoration errorMark;
    /** Bumped on every change; a probe's answer is used only if it still matches. */
    private final AtomicInteger generation = new AtomicInteger();
    /**
     * Whether the next change of the text may run the program it names for its version: true for
     * a value loaded as saved or picked with Browse, false for typing (see
     * {@link NvimProbe#describe}). Each change uses it once.
     */
    private boolean runNextProbe;

    NeovimLocationFieldEditor(String name, String labelText, Composite parent) {
        super(name, labelText, parent);
        setChangeButtonText("Browse...");
        setEmptyStringAllowed(true);
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        super.doFillIntoGrid(parent, numColumns);
        // No tooltip: the help below says what the field takes, the note what it finds.
        getTextControl().setMessage("Leave blank for the dedicated Neovim"); // shown while the field is empty, as the help says
        addErrorMark();

        note = new Label(parent, SWT.WRAP); // wraps at its width hint, as the help below does
        note.setLayoutData(fullRow(numColumns, WRAP_WIDTH));

        reinstall = new Link(parent, SWT.NONE);
        reinstall.setText("<a>" + REINSTALL + "</a>");
        reinstall.setToolTipText("Install the dedicated Neovim again, for a copy that is damaged");
        reinstall.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false, numColumns, 1));
        reinstall.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> GetNeovim.open(getShell())));
        setVisible(reinstall, false);

        // Hidden while the note offers Reinstall instead (show); after an install the note probes again.
        getNeovim = new Button(parent, SWT.PUSH);
        getNeovim.setText(GetNeovim.label());
        getNeovim.setToolTipText("Install the dedicated Neovim the plug-in needs, downloaded or from a file, "
                + "into " + NvimExecutable.dedicatedFolder());
        getNeovim.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false, numColumns, 1));
        getNeovim.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> GetNeovim.open(getShell())));
        Runnable afterInstall = () -> {
            if (!note.isDisposed()) {
                scheduleProbe(true);
            }
        };
        GetNeovim.addListener(afterInstall);
        getNeovim.addDisposeListener(e -> GetNeovim.removeListener(afterInstall));

        help = new Link(parent, SWT.NONE);
        help.setText(NeovimPreferencePage.NEOVIM_FIELD_HELP);
        help.setLayoutData(fullRow(numColumns, WRAP_WIDTH));
        help.addSelectionListener(SelectionListener.widgetSelectedAdapter(
                e -> ExternalBrowser.open(NeovimPreferencePage.FINDING_NEOVIM_URL)));
        getTextControl().addModifyListener(e -> {
            boolean run = runNextProbe;
            runNextProbe = false;
            scheduleProbe(run);
        });
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

    @Override
    protected void adjustForNumColumns(int numColumns) {
        super.adjustForNumColumns(numColumns);
        if (note != null) {
            ((GridData) note.getLayoutData()).horizontalSpan = numColumns;
            ((GridData) reinstall.getLayoutData()).horizontalSpan = numColumns;
            ((GridData) getNeovim.getLayoutData()).horizontalSpan = numColumns;
            ((GridData) help.getLayoutData()).horizontalSpan = numColumns;
        }
    }

    @Override
    public int getNumberOfControls() {
        return 3; // label, text, button; the note has a row of its own
    }

    /**
     * Browse: pick a Neovim program (a file, never a folder: nothing is searched), starting in the
     * current program's folder or the plug-in's own. Blank, the default, needs no browsing.
     */
    @Override
    protected String changePressed() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Neovim program");
        if (PlatformUtil.isWindows()) {
            dialog.setFilterExtensions(new String[] { "nvim.exe", "*.exe" });
            dialog.setFilterNames(new String[] { "Neovim (nvim.exe)", "Programs (*.exe)" });
        }
        Path start = startFolder(getStringValue());
        if (start != null) {
            dialog.setFilterPath(start.toString());
        }
        String picked = dialog.open(); // null when cancelled: the text stays as it is
        if (picked != null && picked.equals(getTextControl().getText())) {
            scheduleProbe(true); // the same program: no change follows, so ask its version here
            return null;
        }
        runNextProbe = picked != null; // a program picked on purpose: its version may be asked
        return picked;
    }

    /** The saved value: a program you chose before, so the note may ask its version. */
    @Override
    protected void doLoad() {
        runNextProbe = true;
        super.doLoad();
        runNextProbe = false; // in case the text did not change
    }

    /** Apply or OK: the program is chosen now, and the note shows its version. */
    @Override
    protected void doStore() {
        super.doStore();
        if (note != null && !note.isDisposed()) {
            scheduleProbe(true);
        }
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

    /**
     * Shows a note: its text, in the error color and with the error mark if it reports a problem; and
     * the Get Neovim button, or the Reinstall link instead when the dedicated Neovim is current.
     */
    private void show(NvimProbe.Note shown) {
        note.setText(labelText(shown));
        setVisible(getNeovim, !shown.current());
        setVisible(reinstall, shown.current());
        note.setForeground(shown.problem() ? errorColor() : null); // null: the theme's normal color
        if (shown.problem()) {
            errorMark.setDescriptionText(shown.text()); // shown when hovering the mark
            errorMark.show();
        } else {
            errorMark.hide();
        }
        // The note may now wrap to more or fewer lines. requestLayout lays out the note's parent and
        // every ancestor, so the page's group box grows or shrinks with it; a layout of the parent
        // alone would keep the group's old height and cut off the help below (seen 2026-10-30).
        note.requestLayout();
    }

    /**
     * The note as {@link Label} text: {@code &} doubled, as a Label takes a single one as a mnemonic
     * marker (a folder may be called {@code R&D}). Everything else shows as it is, {@code <a>} too.
     * Example: {@code Found: Neovim 0.12.6, /R&&D/bin/nvim}.
     */
    static String labelText(NvimProbe.Note shown) {
        return shown.text().replace("&", "&&");
    }

    /**
     * Shows or hides {@code control}, and lets the grid close up the row it leaves:
     * {@link GridData#exclude} takes a hidden control out of the layout, which visibility alone does not.
     */
    private static void setVisible(Control control, boolean visible) {
        if (control.getVisible() != visible) {
            control.setVisible(visible);
            ((GridData) control.getLayoutData()).exclude = !visible;
        }
    }

    /** The theme's error color: red in the light theme, a brighter red in the dark one. */
    private Color errorColor() {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.ERROR_COLOR);
        return color != null ? color : note.getDisplay().getSystemColor(SWT.COLOR_RED);
    }

    /** @param run whether the program the text names may be run for its version ({@link NvimProbe#describe}) */
    private void scheduleProbe(boolean run) {
        int mine = generation.incrementAndGet();
        show(new NvimProbe.Note("Looking for Neovim...", false));
        String text = getTextControl().getText();
        note.getDisplay().timerExec(DELAY_MS, () -> {
            if (mine != generation.get() || note.isDisposed()) {
                return; // typed again meanwhile
            }
            CompletableFuture.supplyAsync(() -> NvimProbe.describe(text, run)).thenAccept(result -> {
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

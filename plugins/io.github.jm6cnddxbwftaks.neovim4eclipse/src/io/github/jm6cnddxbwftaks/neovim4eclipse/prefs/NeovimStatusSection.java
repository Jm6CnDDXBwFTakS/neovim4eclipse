// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LabelText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * The Neovim group of the preference page: which Neovim this Eclipse runs, and nothing to edit. A
 * note says what it found, for example
 * {@code Found: dedicated Neovim 0.12.6, checked, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim},
 * or the program named by the startup option {@code -Dneovim4eclipse.nvim} ({@link NeovimOption});
 * then the <em>Get Neovim 0.12.6...</em> button ({@link GetNeovim}), and the help: how to run
 * another Neovim, with a link to the README section on it.
 *
 * <p>Until 0.2.1 this was the "Neovim program" field, with Browse. The program is now a startup
 * option, read once when Eclipse starts, so there is nothing here to type or apply (why:
 * {@link NeovimOption}). The note stays, as the one place that shows which Neovim runs.
 *
 * <p>When the note finds the dedicated Neovim, current ({@link NvimProbe.Note#current}), there is
 * nothing to get: the button is hidden, and a small <em>Reinstall...</em> link under the note
 * opens the same Get Neovim dialog, for a copy damaged in a way {@code --version} does not show.
 * Example:
 *
 * <pre>
 *   Found: dedicated Neovim 0.12.6, checked, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
 *   Reinstall...
 * </pre>
 *
 * The note is a {@link Label} and the link a {@link Link} of its own, not one {@code Link} for both:
 * a {@code Link} reads {@code <a>...</a>} in its text as a link, and the note shows paths that
 * anyone who can name a folder chooses ({@link #labelText}). A note that reports a problem (Neovim
 * would not start: {@link NvimProbe.Note#problem}) is shown in the theme's error color
 * ({@link JFacePreferences#ERROR_COLOR}, which dark themes brighten); the text says the same, so the
 * color is not needed to tell what is wrong.
 *
 * <h2>Why the note is computed off the UI thread</h2>
 * It may hash the dedicated Neovim (the first time) or run the named program's {@code --version}
 * ({@link NvimProbe#describe}), which can take a moment and could hang on a broken program. So it
 * runs on a background thread when the page opens and after each install, and its answer is shown
 * only if no newer probe started meanwhile ({@link #generation}).
 */
final class NeovimStatusSection {

    /** Width at which the note and the help wrap, rather than widening the dialog. */
    private static final int WRAP_WIDTH = 400;

    /** The link under the note when the dedicated Neovim is current. */
    static final String REINSTALL = "Reinstall...";

    /** The README section on where the plug-in looks for Neovim, opened by the link in the help. */
    static final String FINDING_NEOVIM_URL = "https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#finding-neovim";

    /**
     * The help under the note: what runs, when another Neovim may be needed, and how to name it. The
     * two cases are the ones the option is for: no official build to run, and a security fix in a
     * newer Neovim before Get Neovim offers it. The command is on a line of its own: wrapped with the
     * rest, it broke at the {@code /} of its path ({@code -Dneovim4eclipse.nvim=/} on one line,
     * {@code path/to/nvim} on the next). {@code <a>} marks the link.
     */
    static final String HELP = "Neovim for Eclipse runs the dedicated Neovim, which \"Get Neovim\" installs and the "
            + "plug-in checks at every start. Where it cannot run (a system with no official build), or when a newer "
            + "Neovim has a security fix that \"Get Neovim\" does not offer yet, start Eclipse with:\n"
            + "    " + NeovimOption.commandLine("/path/to/nvim") + "\n"
            + "to run another Neovim program: it is run unchecked and unsupported, so be sure it is Neovim. "
            + "<a>How Neovim for Eclipse finds Neovim</a>";

    /**
     * What the section found, in a {@link Label}, never a {@link Link}: the text holds paths (the
     * option's program, the entries of the plug-in's Neovim folder), and a Link would turn an
     * {@code <a>...</a>} in a folder's name into a link. The one link, Reinstall..., is a control of
     * its own ({@link #reinstall}).
     */
    private final Label note;
    /** {@code <a>Reinstall...</a>} under the note, shown only while the dedicated Neovim is current. */
    private final Link reinstall;
    private final Button getNeovim;
    /** Bumped on every probe; a probe's answer is used only if it still matches. */
    private final AtomicInteger generation = new AtomicInteger();
    /** The option's value ({@link NeovimOption#value}); tests give their own. */
    private final Supplier<String> configured;
    /** What the note says for a value: {@link NvimProbe#describe}, which looks on disk; tests give their own. */
    private final Function<String, NvimProbe.Note> describe;

    /** Fills {@code parent}, a one-column grid, and starts the first probe. */
    NeovimStatusSection(Composite parent) {
        this(parent, NeovimOption::value);
    }

    /**
     * As above, with the option's value given: the UI tests' Eclipse has one fixed value, so a test
     * of another (a missing program, a long path) passes its own.
     */
    NeovimStatusSection(Composite parent, Supplier<String> configured) {
        this(parent, configured, NvimProbe::describe);
    }

    /**
     * As above, with the note given too: the checks gallery shows "No dedicated Neovim yet" though
     * this machine has one, without touching the real plug-in folder. Example:
     * {@code new NeovimStatusSection(group, () -> "", value -> NvimProbe.note(...))}.
     */
    NeovimStatusSection(Composite parent, Supplier<String> configured, Function<String, NvimProbe.Note> describe) {
        this.configured = configured;
        this.describe = describe;
        note = new Label(parent, SWT.WRAP); // wraps at its width hint, as the help below does
        note.setLayoutData(fullRow(WRAP_WIDTH));

        reinstall = new Link(parent, SWT.NONE);
        reinstall.setText("<a>" + REINSTALL + "</a>");
        reinstall.setToolTipText("Install the dedicated Neovim again, for a copy that is damaged");
        reinstall.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false));
        reinstall.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> GetNeovim.open(parent.getShell())));
        setVisible(reinstall, false);

        // Hidden while the note offers Reinstall instead (show); after an install the note probes again.
        getNeovim = new Button(parent, SWT.PUSH);
        getNeovim.setText(GetNeovim.label());
        getNeovim.setToolTipText("Install the dedicated Neovim the plug-in needs, downloaded or from a file, "
                + "into " + NvimExecutable.dedicatedFolder());
        getNeovim.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false));
        getNeovim.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> GetNeovim.open(parent.getShell())));
        Runnable afterInstall = () -> {
            if (!note.isDisposed()) {
                probe();
            }
        };
        GetNeovim.addListener(afterInstall);
        getNeovim.addDisposeListener(e -> GetNeovim.removeListener(afterInstall));

        Link help = new Link(parent, SWT.NONE);
        help.setText(HELP);
        help.setLayoutData(fullRow(WRAP_WIDTH));
        help.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> ExternalBrowser.open(FINDING_NEOVIM_URL)));
        probe();
    }

    /** Layout data for a control on a row of its own; {@code widthHint} lets wrapping text wrap. */
    private static GridData fullRow(int widthHint) {
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.widthHint = widthHint;
        return data;
    }

    /**
     * Shows a note: its text, in the error color if it reports a problem; and the Get Neovim button,
     * or the Reinstall link instead when the dedicated Neovim is current.
     */
    private void show(NvimProbe.Note shown) {
        note.setText(labelText(shown));
        setVisible(getNeovim, !shown.current());
        setVisible(reinstall, shown.current());
        note.setForeground(shown.problem() ? errorColor() : null); // null: the theme's normal color
        // The note may now wrap to more or fewer lines. requestLayout lays out the note's parent and
        // every ancestor, so the page's group box grows or shrinks with it; a layout of the parent
        // alone would keep the group's old height and cut off the help below (seen 2026-10-30).
        note.requestLayout();
    }

    /**
     * The note as {@link Label} text: {@code &} doubled, as a Label takes a single one as a mnemonic
     * marker (a folder may be called {@code R&D}). Everything else shows as it is, {@code <a>} too.
     * Example: {@code Found: Neovim 0.12.6, /R&&D/bin/nvim, from -Dneovim4eclipse.nvim. ...}.
     */
    static String labelText(NvimProbe.Note shown) {
        return LabelText.literal(shown.text());
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

    /** Asks again which Neovim runs, off the UI thread, and shows the answer if it is still the newest. */
    private void probe() {
        int mine = generation.incrementAndGet();
        show(new NvimProbe.Note("Looking for Neovim...", false));
        String value = configured.get();
        CompletableFuture.supplyAsync(() -> describe.apply(value)).thenAccept(result -> {
            if (note.isDisposed()) {
                return;
            }
            note.getDisplay().asyncExec(() -> {
                if (!note.isDisposed() && mine == generation.get()) {
                    show(result);
                }
            });
        });
    }
}

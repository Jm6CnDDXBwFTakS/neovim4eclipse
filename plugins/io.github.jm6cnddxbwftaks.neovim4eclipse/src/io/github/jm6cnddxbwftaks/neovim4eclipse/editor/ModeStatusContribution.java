// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorSite;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeLabels;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * Vim's mode in the Eclipse status bar, with the macro being recorded ({@link StatusBarLabels}), as
 * the user chose it (<em>Window → Preferences → Neovim → Status Bar</em>). Neovim's status line is
 * drawn by each editor under its text ({@link StatusLineRow}), and its pending command by Neovim
 * itself (ui2).
 *
 * <p>The status line at the bottom of the window is managed by an {@code IStatusLineManager}, which
 * each editor reaches through its site ({@code getEditorSite().getActionBars()}). Anything shown
 * there is a {@link ContributionItem}: the manager calls {@link #fill(Composite)} to let the item
 * create its widgets, and calls it again whenever the status line is rebuilt
 * ({@code manager.update(true)} disposes every item's widgets and fills them anew). The controller
 * adds this item on attach ({@link #addTo}) and removes it on detach ({@link #removeFrom}). This
 * class decides what the labels say; {@link StatusBarLabels} owns the widgets and their widths.
 *
 * <p>Which labels exist changes in two ways: a preference (a part turned on or off), and the
 * {@code ECLIPSE} indicator, which shows even when the mode part is off, because it says where keys
 * go. Either
 * rebuilds the bar ({@link #refresh}); every other change only sets texts.
 *
 * <p>The preferences are read once and again only when one changes ({@link #refreshNow}), not with
 * every key. All state is used on the UI thread; the setters hand it over with {@code asyncExec}.
 */
public final class ModeStatusContribution extends ContributionItem {

    public static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.modeStatus";

    private static final Logger LOG = Logger.getLogger(ModeStatusContribution.class.getName());

    private IStatusLineManager manager;
    private StatusBarLabels labels;
    private Font boldFont;
    /** The parts the user chose (preferences, read by {@link #readPreferences}). UI thread. */
    private Set<StatusItem> chosen = StatusItem.defaults();
    /** Neovim's mode string ({@code nvim_get_mode().mode}); "" before Neovim said. */
    private String mode = "";
    /** Keys go to Eclipse: the mode label says {@code ECLIPSE}. */
    private boolean eclipse;
    /** Register being recorded into ("" when not recording); shown after the mode, as Vim does. */
    private String recording = "";

    /**
     * The item's id while a page editor shown from the editing Neovim is in front
     * ({@code PageStatus}): an id of its own, so the text editors' item and the pages' never stand
     * in for each other in one window's status line.
     */
    public static final String PAGE_ID = ID + ".page";

    /** The text editors' item ({@link #ID}). */
    public ModeStatusContribution() {
        this(ID);
    }

    /** An item of id {@code id}: the pages' is {@link #PAGE_ID}. */
    ModeStatusContribution(String id) {
        super(id);
        readPreferences();
    }

    /**
     * Adds this item to the status line of the editor's window, unless an item with the same id is
     * already there (another editor of this plug-in put it there and has not removed it yet).
     *
     * @param site the editor's site; {@code null} (an editor that is not fully created) does nothing
     */
    public void addTo(IEditorSite site) {
        try {
            if (site == null) {
                return;
            }
            IStatusLineManager statusLine = site.getActionBars().getStatusLineManager();
            if (statusLine.find(getId()) == null) {
                manager = statusLine;
                statusLine.add(this);
                statusLine.update(true);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not install mode status line", e);
        }
    }

    /** Removes the item with this id from the status line of the editor's window. */
    public void removeFrom(IEditorSite site) {
        try {
            if (site == null) {
                return;
            }
            IStatusLineManager statusLine = site.getActionBars().getStatusLineManager();
            statusLine.remove(getId());
            statusLine.update(true);
            manager = null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not remove mode status line", e);
        }
    }

    @Override
    public void fill(Composite parent) {
        if (boldFont == null && parent.getFont() != null) {
            FontData[] fds = parent.getFont().getFontData();
            for (FontData fd : fds) {
                fd.setStyle(SWT.BOLD);
            }
            boldFont = new Font(parent.getDisplay(), fds);
        }
        labels = new StatusBarLabels(parent, partsShown(), boldFont, modeTexts());
        showTexts();
    }

    /** Show that keys are handled by Eclipse, not Neovim. */
    public void setHostEclipseMode() {
        eclipse = true;
        recording = "";
        refresh();
    }

    /** Neovim's mode string, such as {@code i} or {@code niI}; keys go to Neovim again. */
    public void setMode(String nvimMode) {
        eclipse = false;
        mode = nvimMode == null ? "" : nvimMode;
        refresh();
    }

    /** @param register register being recorded into, or {@code null}/"" when not recording */
    public void setRecording(String register) {
        recording = register == null ? "" : register;
        refresh();
    }

    /** A status bar preference changed: reads them again and redraws. */
    public void refreshNow() {
        Display.getDefault().asyncExec(() -> {
            readPreferences();
            apply();
        });
    }

    /** Reads the status bar preferences into fields (defaults when the plug-in is not running). */
    private void readPreferences() {
        Activator activator = Activator.getDefault();
        if (activator == null) {
            return;
        }
        chosen = activator.getStatusItems();
    }

    /** The parts the user chose; only the mode while it says {@code ECLIPSE} ({@link StatusItem#shown}). */
    private Set<StatusItem> partsShown() {
        return StatusItem.shown(chosen, eclipse);
    }

    /**
     * The texts the mode's label is sized for: room for {@code rec @a} only while recording (the
     * label widens then, rather than always).
     */
    private List<String> modeTexts() {
        return ModeLabels.texts(!eclipse && !recording.isEmpty());
    }

    /** Applies the state on the UI thread; callers are on any thread. */
    private void refresh() {
        Display.getDefault().asyncExec(this::apply);
    }

    /**
     * Rebuilds the bar when the set of labels changed, else fits them and sets their texts. UI thread.
     */
    private void apply() {
        if (labels == null || labels.isDisposed()) {
            return; // not filled yet, or being refilled: fill() shows the state
        }
        if (!labels.parts().equals(partsShown())) {
            if (manager != null) {
                manager.markDirty();
                manager.update(true); // disposes the labels and calls fill() again
            }
            return;
        }
        labels.setModeTexts(modeTexts());
        showTexts();
    }

    /** Sets the mode label's text, tooltip and color. UI thread. */
    private void showTexts() {
        ModeLabels.Label label = eclipse ? ModeLabels.ECLIPSE : ModeLabels.of(mode);
        String register = eclipse ? "" : recording;
        labels.show(StatusItem.MODE, ModeLabels.text(label, register), ModeLabels.tooltip(label, register),
                Display.getDefault().getSystemColor(foregroundColorId(label, register)));
    }

    /**
     * SWT system color for the mode label, a simple cue that follows the mode: recording red,
     * Eclipse gray, Insert and Replace green, Visual yellow, the command line and operator-pending
     * blue, Select and the rest the bar's own color.
     */
    static int foregroundColorId(ModeLabels.Label label, String register) {
        if (register != null && !register.isEmpty()) {
            return SWT.COLOR_DARK_RED;
        }
        return switch (label.group()) {
            case ECLIPSE -> SWT.COLOR_DARK_GRAY;
            case VISUAL -> SWT.COLOR_DARK_YELLOW; // (S) V-BLOCK too
            case INSERT -> SWT.COLOR_DARK_GREEN; // (INSERT) too: Insert resumes after one command
            case PENDING -> SWT.COLOR_DARK_BLUE; // O-PENDING, COMMAND, EX
            case OTHER -> SWT.COLOR_WIDGET_FOREGROUND;
        };
    }

    @Override
    public void dispose() {
        if (boldFont != null && !boldFont.isDisposed()) {
            boldFont.dispose();
            boldFont = null;
        }
        labels = null;
        super.dispose();
    }
}

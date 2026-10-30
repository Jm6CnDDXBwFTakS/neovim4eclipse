// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.IntConsumer;
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
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.StatusInfo;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeLabels;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusLineFormat;

/**
 * Vim's status information in the Eclipse status bar: the mode (with the macro being recorded), the
 * pending command and Neovim's {@code 'statusline'}, each in a label of its own
 * ({@link StatusBarLabels}), as the user chose them (<em>Window → Preferences → Neovim → Status
 * Bar</em>).
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
 * {@code -- ECLIPSE --} indicator, which shows even when the mode part is off because it says
 * where keys go. Either rebuilds the bar ({@link #refresh}); every other change only sets texts.
 */
public final class ModeStatusContribution extends ContributionItem {

    public static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.modeStatus";

    /** Width of the pending-command label in characters: Vim's {@code 'showcmd'} area holds 10. */
    static final int CMD_CHARS = 10;

    private static final Logger LOG = Logger.getLogger(ModeStatusContribution.class.getName());

    /** Hears the statusline label's width in characters (Neovim's {@code maxwidth} for it). */
    private final IntConsumer statuslineColumns;

    private IStatusLineManager manager;
    private StatusBarLabels labels;
    private Font boldFont;
    /** Neovim's mode string ({@code nvim_get_mode().mode}); "" before Neovim said. */
    private String mode = "";
    /** Keys go to Eclipse: the mode label says {@code -- ECLIPSE --}. */
    private boolean eclipse;
    /** Register being recorded into ("" when not recording); shown after the mode, as Vim does. */
    private String recording = "";
    /** Pending command and statusline as reported by Neovim. */
    private StatusInfo info = StatusInfo.EMPTY;

    /** @param statuslineColumns hears the statusline label's width in characters whenever it changes */
    public ModeStatusContribution(IntConsumer statuslineColumns) {
        super(ID);
        this.statuslineColumns = statuslineColumns;
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
            if (statusLine.find(ID) == null) {
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
            statusLine.remove(ID);
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
        labels = new StatusBarLabels(parent, partsShown(), boldFont, widths(), statuslineColumns);
        showTexts();
    }

    /** Show that keys are handled by Eclipse, not Neovim. */
    public void setHostEclipseMode() {
        eclipse = true;
        recording = "";
        info = StatusInfo.EMPTY; // Neovim is not driving the editor, so its command/message/statusline are moot
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

    /** Pending command and statusline text from Neovim. */
    public void setStatusInfo(StatusInfo statusInfo) {
        info = statusInfo == null ? StatusInfo.EMPTY : statusInfo;
        refresh();
    }

    /** Redraws with the current preferences (which parts, mode style, widths). */
    public void refreshNow() {
        refresh();
    }

    /** The parts the user chose, plus the mode while it says {@code -- ECLIPSE --}. */
    private Set<StatusItem> partsShown() {
        Activator activator = Activator.getDefault();
        Set<StatusItem> parts = EnumSet.noneOf(StatusItem.class);
        parts.addAll(activator == null ? StatusItem.defaults() : activator.getStatusItems());
        if (eclipse) {
            parts.add(StatusItem.MODE);
        }
        return parts;
    }

    private static ModeLabels.Style style() {
        Activator activator = Activator.getDefault();
        return activator == null ? ModeLabels.Style.FULL
                : ModeLabels.Style.of(activator.getPreferenceStore().getString(PreferenceConstants.STATUS_MODE_STYLE));
    }

    /**
     * The labels' sizes: the mode as wide as the style's longest text, plus room for
     * {@code recording @a} only while recording (the label widens then, rather than always).
     */
    private StatusBarLabels.Widths widths() {
        Activator activator = Activator.getDefault();
        int statusline = activator == null ? PreferenceConstants.STATUS_STATUSLINE_WIDTH_DEFAULT
                : activator.getPreferenceStore().getInt(PreferenceConstants.STATUS_STATUSLINE_WIDTH);
        int share = activator == null ? PreferenceConstants.STATUS_MAX_SHARE_DEFAULT
                : activator.getPreferenceStore().getInt(PreferenceConstants.STATUS_MAX_SHARE);
        ModeLabels.Style style = style();
        int mode = ModeLabels.longest(style) + (eclipse || recording.isEmpty() ? 0 : ModeLabels.recordingLength(style));
        return new StatusBarLabels.Widths(mode, CMD_CHARS, statusline, share);
    }

    /**
     * Applies the state on the UI thread: rebuilds the bar when the set of labels changed, else fits
     * them to the preferences and sets their texts. Callers are on any thread.
     */
    private void refresh() {
        Display display = Display.getDefault();
        display.asyncExec(() -> {
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
            labels.setWidths(widths());
            showTexts();
        });
    }

    /** Sets the three labels' texts, tooltips and the mode's color. UI thread. */
    private void showTexts() {
        ModeLabels.Style style = style();
        ModeLabels.Label label = eclipse ? ModeLabels.ECLIPSE : ModeLabels.of(mode);
        String register = eclipse ? "" : recording;
        labels.show(StatusItem.MODE, ModeLabels.text(label, register, style), ModeLabels.tooltip(label, register, style),
                Display.getDefault().getSystemColor(foregroundColorId(label, register)));
        String cmd = StatusLineFormat.cleanText(info.cmd());
        labels.show(StatusItem.CMD, cmd, cmd.isEmpty() ? null : "Pending command: " + cmd, null);
        labels.show(StatusItem.STATUSLINE, StatusLineFormat.oneLine(info.statusline()), null, null);
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
        String name = label.name();
        if (label == ModeLabels.ECLIPSE) {
            return SWT.COLOR_DARK_GRAY;
        }
        if (name.contains("VISUAL")) {
            return SWT.COLOR_DARK_YELLOW; // (select) VISUAL too
        }
        if (name.contains("INSERT") || name.contains("REPLACE") || name.startsWith("(")) {
            return SWT.COLOR_DARK_GREEN; // (insert), (replace), (vreplace): Insert resumes after one command
        }
        if (name.equals("COMMAND") || name.equals("OPERATOR") || name.equals("EX")) {
            return SWT.COLOR_DARK_BLUE;
        }
        return SWT.COLOR_WIDGET_FOREGROUND;
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

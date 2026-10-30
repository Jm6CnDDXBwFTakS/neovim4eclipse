// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.SubContributionItem;
import org.eclipse.jface.action.SubContributionManager;
import org.eclipse.jface.resource.JFaceResources;
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
 * {@code -- ECLIPSE --} indicator, which shows alone (Neovim's command and statusline mean nothing
 * while keys go to Eclipse), even when the mode part is off, because it says where keys go. Either
 * rebuilds the bar ({@link #refresh}); every other change only sets texts.
 *
 * <p>The preferences are read once and again only when one changes ({@link #refreshNow}), not with
 * every key. All state is used on the UI thread; the setters hand it over with {@code asyncExec}.
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
    /** The parts the user chose (preferences, read by {@link #readPreferences}). UI thread. */
    private Set<StatusItem> chosen = StatusItem.defaults();
    /** The mode style (preference). UI thread. */
    private ModeLabels.Style style = ModeLabels.Style.FULL;
    /** The statusline's width in characters (preference). UI thread. */
    private int statuslineChars = PreferenceConstants.STATUS_STATUSLINE_WIDTH_DEFAULT;
    /** The most of the bar the labels may take, in percent (preference). UI thread. */
    private int maxShare = PreferenceConstants.STATUS_MAX_SHARE_DEFAULT;
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
            if (statusLine.find(ID) == null) {
                manager = statusLine;
                statusLine.add(this);
                statusLine.update(true);
            }
            // Each window has its own status bar, of its own width, and one Neovim serves them all:
            // the editor becoming active brings its window's statusline width along. Example: a wide
            // and a narrow window; switching editors between them switches the statusline's columns.
            ModeStatusContribution shown = shownIn(statusLine);
            int columns = shown == null || shown.labels == null ? -1 : shown.labels.statuslineColumns();
            if (columns > 0) {
                statuslineColumns.accept(columns);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not install mode status line", e);
        }
    }

    /**
     * The item of this id in {@code statusLine}'s window, whichever editor put it there: an editor's
     * manager is a {@link SubContributionManager} that adds items to the window's own manager wrapped
     * in a {@link SubContributionItem}.
     */
    private static ModeStatusContribution shownIn(IStatusLineManager statusLine) {
        IContributionManager window = statusLine;
        while (window instanceof SubContributionManager sub) {
            window = sub.getParent();
        }
        IContributionItem item = window.find(ID);
        if (item instanceof SubContributionItem wrapper) {
            item = wrapper.getInnerItem();
        }
        return item instanceof ModeStatusContribution contribution ? contribution : null;
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
        labels = new StatusBarLabels(parent, partsShown(), boldFont, JFaceResources.getTextFont(), widths(),
                statuslineColumns);
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
        style = ModeLabels.Style.of(activator.getPreferenceStore().getString(PreferenceConstants.STATUS_MODE_STYLE));
        statuslineChars = activator.getPreferenceStore().getInt(PreferenceConstants.STATUS_STATUSLINE_WIDTH);
        maxShare = activator.getPreferenceStore().getInt(PreferenceConstants.STATUS_MAX_SHARE);
    }

    /** The parts the user chose; only the mode while it says {@code -- ECLIPSE --}. */
    private Set<StatusItem> partsShown() {
        if (eclipse) {
            return EnumSet.of(StatusItem.MODE);
        }
        Set<StatusItem> parts = EnumSet.noneOf(StatusItem.class);
        parts.addAll(chosen);
        return parts;
    }

    /**
     * The labels' sizes: the mode as wide as the widest text of the style, with room for
     * {@code recording @a} only while recording (the label widens then, rather than always).
     */
    private StatusBarLabels.Widths widths() {
        return new StatusBarLabels.Widths(ModeLabels.texts(style, !eclipse && !recording.isEmpty()), CMD_CHARS,
                statuslineChars, maxShare);
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
        labels.setWidths(widths());
        showTexts();
    }

    /** Sets the three labels' texts, tooltips and the mode's color. UI thread. */
    private void showTexts() {
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

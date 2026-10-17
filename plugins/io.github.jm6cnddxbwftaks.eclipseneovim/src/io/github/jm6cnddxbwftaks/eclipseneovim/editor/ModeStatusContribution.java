// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.StatusLineLayoutData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorSite;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.StatusInfo;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.StatusItem;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.StatusLineFormat;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

/**
 * Vim-like mode indicator in the Eclipse status line.
 *
 * <p>The status line at the bottom of the window is managed by an {@code IStatusLineManager}, which
 * each editor reaches through its site ({@code getEditorSite().getActionBars()}). Anything shown
 * there is a {@link ContributionItem}: the manager calls {@link #fill(Composite)} to let the item
 * create its widgets, and calls it again when the status line is rebuilt. The controller adds this
 * item on attach ({@link #addTo}) and removes it on detach ({@link #removeFrom}).
 */
public final class ModeStatusContribution extends ContributionItem {

    public static final String ID = "io.github.jm6cnddxbwftaks.eclipseneovim.modeStatus";

    private static final Logger LOG = Logger.getLogger(ModeStatusContribution.class.getName());

    private CLabel label;
    private String modeText = "-- NORMAL --";
    /** Register being recorded into ("" when not recording); shown after the mode, as Vim does. */
    private String recording = "";
    /** Pending command, latest message and statusline as reported by Neovim. */
    private StatusInfo info = StatusInfo.EMPTY;
    private Font boldFont;

    public ModeStatusContribution() {
        super(ID);
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
            IStatusLineManager manager = site.getActionBars().getStatusLineManager();
            if (manager.find(ID) == null) {
                manager.add(this);
                manager.update(true);
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
            IStatusLineManager manager = site.getActionBars().getStatusLineManager();
            manager.remove(ID);
            manager.update(true);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not remove mode status line", e);
        }
    }

    @Override
    public void fill(Composite parent) {
        Label sep = new Label(parent, SWT.SEPARATOR);
        StatusLineLayoutData data = new StatusLineLayoutData();
        data.heightHint = parent.getSize().y;
        sep.setLayoutData(data);

        label = new CLabel(parent, SWT.CENTER);
        StatusLineLayoutData ld = new StatusLineLayoutData();
        ld.widthHint = 420; // room for mode + pending command + message; longer text is shortened with "..."
        label.setLayoutData(ld);
        if (boldFont == null && label.getFont() != null) {
            FontData[] fds = label.getFont().getFontData();
            for (FontData fd : fds) {
                fd.setStyle(SWT.BOLD);
            }
            boldFont = new Font(label.getDisplay(), fds);
            label.setFont(boldFont);
        }
        applyStyle(displayText());
    }

    /** Show that keys are handled by Eclipse, not Neovim. */
    public void setHostEclipseMode() {
        modeText = "-- ECLIPSE --";
        recording = "";
        info = StatusInfo.EMPTY; // Neovim is not driving the editor, so its command/message/statusline are moot
        refresh();
    }

    public void setMode(String nvimMode) {
        modeText = VimModes.statusText(nvimMode);
        refresh();
    }

    /** @param register register being recorded into, or {@code null}/"" when not recording */
    public void setRecording(String register) {
        recording = register == null ? "" : register;
        refresh();
    }

    /** Pending command, message and statusline text from Neovim. */
    public void setStatusInfo(StatusInfo statusInfo) {
        info = statusInfo == null ? StatusInfo.EMPTY : statusInfo;
        refresh();
    }

    /** Redraws with the current preference (which parts to show). */
    public void refreshNow() {
        refresh();
    }

    /**
     * The text shown: the parts the user enabled (preference <em>Neovim</em>), in the order mode, cmd,
     * msg, statusline. The "-- ECLIPSE --" indicator is always shown, because it says where keys go.
     */
    private String displayText() {
        String mode = recording.isEmpty() ? modeText : modeText + "  recording @" + recording;
        if (modeText.contains("ECLIPSE")) {
            return mode;
        }
        Activator activator = Activator.getDefault();
        Set<StatusItem> items = activator == null ? StatusItem.defaults() : activator.getStatusItems();
        return StatusLineFormat.compose(items, mode, info.cmd(), info.message(), info.statusline());
    }

    private void refresh() {
        if (label != null && !label.isDisposed()) {
            label.getDisplay().asyncExec(() -> {
                if (label != null && !label.isDisposed()) {
                    applyStyle(displayText());
                }
            });
        }
    }

    private void applyStyle(String text) {
        label.setText(text);
        // Simple color cues. They follow the mode, not the displayed text, which can now contain
        // arbitrary messages (a message mentioning "INSERT" must not turn the label green).
        label.setForeground(label.getDisplay().getSystemColor(foregroundColorId()));
        label.getParent().layout(true);
    }

    /** SWT system color id for the current mode. */
    private int foregroundColorId() {
        if (!recording.isEmpty()) {
            return SWT.COLOR_DARK_RED;
        }
        if (modeText.contains("ECLIPSE")) {
            return SWT.COLOR_DARK_GRAY;
        }
        if (modeText.contains("INSERT") || modeText.contains("REPLACE")) {
            return SWT.COLOR_DARK_GREEN;
        }
        if (modeText.contains("VISUAL")) {
            return SWT.COLOR_DARK_YELLOW;
        }
        if (modeText.contains("COMMAND") || modeText.contains("OPERATOR")) {
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
        label = null;
        super.dispose();
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.editor.ClipboardBridge;
import io.github.jm6cnddxbwftaks.eclipseneovim.editor.EditorAttachManager;
import io.github.jm6cnddxbwftaks.eclipseneovim.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.eclipseneovim.prefs.ToolbarVisibility;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.StatusItem;

/**
 * Bundle activator. Owns the single shared {@link NeovimSession} (one {@code nvim} process for
 * the whole IDE) and reacts to preference changes.
 *
 * <h2>What an activator is</h2>
 * {@code MANIFEST.MF} names this class in {@code Bundle-Activator}. OSGi calls {@link #start} when
 * the bundle starts and {@link #stop} when it stops (Eclipse shutting down, or the plug-in being
 * uninstalled). With {@code Bundle-ActivationPolicy: lazy} the bundle starts the first time one of
 * its classes is loaded; here that is {@link EarlyStartup}, or a handler from {@code plugin.xml}.
 *
 * <p>Extending {@link AbstractUIPlugin} adds a preference store ({@link #getPreferenceStore()}),
 * saved in the workspace's {@code .metadata}. The static {@link #getDefault()} is the usual way for
 * other classes to reach the running activator; it is {@code null} before start and after stop.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "io.github.jm6cnddxbwftaks.eclipseneovim";

    private static Activator plugin;

    private NeovimSession session;
    private IPropertyChangeListener preferenceListener;
    /** Copies the plug-in's JUL warnings and errors to the Error Log. */
    private ErrorLogHandler errorLogHandler;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        errorLogHandler = new ErrorLogHandler(getLog());
        errorLogHandler.install(PLUGIN_ID);
        EditorAttachManager.getInstance().installWorkbenchListeners();

        preferenceListener = event -> {
            String property = event.getProperty();
            if (PreferenceConstants.SHOW_TOOLBAR.equals(property)) {
                ToolbarVisibility.refresh();
            } else if (PreferenceConstants.NVIM_EXECUTABLE.equals(property)) {
                resetSession();
            } else if (PreferenceConstants.isStatusItemKey(property)) {
                applyStatusItems();
            } else if (PreferenceConstants.isCtrlKeyKey(property)) {
                EditorAttachManager.getInstance().refreshKeyContext();
            }
        };
        getPreferenceStore().addPropertyChangeListener(preferenceListener);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (preferenceListener != null) {
            getPreferenceStore().removePropertyChangeListener(preferenceListener);
            preferenceListener = null;
        }
        EditorAttachManager.getInstance().detachQuietly();
        shutdownSession();
        plugin = null;
        if (errorLogHandler != null) {
            errorLogHandler.uninstall();
            errorLogHandler = null;
        }
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    /** Configured Neovim executable (command on {@code PATH} or absolute path). */
    public String getNvimExecutable() {
        String path = getPreferenceStore().getString(PreferenceConstants.NVIM_EXECUTABLE);
        if (path == null || path.isBlank()) {
            return PreferenceConstants.NVIM_EXECUTABLE_DEFAULT;
        }
        return path.strip();
    }

    /** Which parts of the Vim status information the user chose to show in the status bar. */
    public Set<StatusItem> getStatusItems() {
        IPreferenceStore store = getPreferenceStore();
        Set<StatusItem> items = EnumSet.noneOf(StatusItem.class);
        if (store.getBoolean(PreferenceConstants.STATUS_ITEM_MODE)) {
            items.add(StatusItem.MODE);
        }
        if (store.getBoolean(PreferenceConstants.STATUS_ITEM_CMD)) {
            items.add(StatusItem.CMD);
        }
        if (store.getBoolean(PreferenceConstants.STATUS_ITEM_MSG)) {
            items.add(StatusItem.MSG);
        }
        if (store.getBoolean(PreferenceConstants.STATUS_ITEM_STATUSLINE)) {
            items.add(StatusItem.STATUSLINE);
        }
        return items;
    }

    /** Whether {@code Ctrl+letter} goes to Neovim (else Eclipse keeps it), as chosen in the preferences. */
    public boolean isCtrlKeyToNeovim(char letter) {
        return getPreferenceStore().getBoolean(PreferenceConstants.ctrlKeyToNeovim(letter));
    }

    /** A status item preference changed: tell the session what to fetch and redraw the status bar. */
    private void applyStatusItems() {
        NeovimSession current;
        synchronized (this) {
            current = session;
        }
        if (current != null) {
            current.setStatusItems(getStatusItems());
        }
        EditorAttachManager.getInstance().refreshStatusLine();
    }

    /** Returns the live session, starting {@code nvim} on first use or after it died. */
    public synchronized NeovimSession getOrCreateSession() throws Exception {
        if (session == null || !session.isAlive()) {
            session = NeovimSession.start(getNvimExecutable(), List.of(), ClipboardBridge.INSTANCE,
                    getBundle().getVersion().toString());
            session.setStatusItems(getStatusItems());
        }
        return session;
    }

    /**
     * Stops the current session and detaches the editor so the next attach starts a fresh
     * {@code nvim} with the current preferences, then re-attaches the active editor.
     */
    private synchronized void resetSession() {
        EditorAttachManager manager = EditorAttachManager.getInstance();
        manager.detachQuietly();
        shutdownSession();
        manager.attachActiveEditor();
    }

    private synchronized void shutdownSession() {
        if (session != null) {
            session.shutdown();
            session = null;
        }
    }
}

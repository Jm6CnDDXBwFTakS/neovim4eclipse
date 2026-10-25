// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.keys.IBindingService;
import org.osgi.service.prefs.BackingStoreException;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * The welcome shown when Neovim first starts in an editor after the plug-in is installed, and the
 * short "updated to x.y" note after an upgrade to a new minor version.
 *
 * <p>Which one (if any) and the text are decided by {@link WelcomeNote} (unit-tested, no Eclipse);
 * this class reads and writes the two preferences, moves to the UI thread and shows the dialog:
 *
 * <ul>
 * <li>{@link PreferenceConstants#SHOW_WELCOME}, in the plug-in's preference store (per workspace,
 * with a check box on the Neovim page), turns both notes off.</li>
 * <li>{@link PreferenceConstants#WELCOME_SHOWN_LINE}, in the {@link ConfigurationScope} (per
 * Eclipse installation), remembers which feature line was greeted. Eclipse has three such scopes:
 * {@code DefaultScope} (defaults, never saved), {@code InstanceScope} (the workspace, where the
 * preference store keeps everything else) and {@code ConfigurationScope} (the installation's
 * {@code configuration/} folder, shared by every workspace started from it).</li>
 * </ul>
 *
 * <p>The record is written before the dialog opens, so a crash or a killed Eclipse while it is up
 * does not bring it back on every start. Neovim's start is tried on every editor activation, so
 * {@link #afterStart()} also keeps a flag for this run: after the first check it costs nothing.
 */
final class WelcomeNotice {

    private static final Logger LOG = Logger.getLogger(WelcomeNotice.class.getName());

    /** Button indexes of the two dialogs, in the order given to {@link MessageDialog}. */
    private static final int WELCOME_PREFERENCES = 0;
    private static final int WELCOME_DOCUMENTATION = 1;
    private static final int UPDATED_WHATS_NEW = 0;

    /** Set after the first check of this Eclipse run; used on the UI thread only. */
    private static boolean checked;

    private WelcomeNotice() {
    }

    /**
     * Neovim started for an editor: show the welcome or the update note if one is due. Safe to call
     * from any thread; the work runs later on the UI thread, after the attach has finished.
     */
    static void afterStart() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (checked) {
                return;
            }
            checked = true;
            Activator plugin = Activator.getDefault();
            if (plugin == null || !plugin.getPreferenceStore().getBoolean(PreferenceConstants.SHOW_WELCOME)) {
                return;
            }
            String version = plugin.getBundle().getVersion().toString();
            IEclipsePreferences record = ConfigurationScope.INSTANCE.getNode(Activator.PLUGIN_ID);
            WelcomeNote.Kind kind = WelcomeNote.decide(record.get(PreferenceConstants.WELCOME_SHOWN_LINE, null),
                    version);
            if (kind == WelcomeNote.Kind.NONE) {
                return;
            }
            remember(record, WelcomeNote.featureLine(version));
            create(activeShell(), kind, version).open();
        });
    }

    /**
     * Builds the dialog without opening it (also for tests, which open it with
     * {@code setBlockOnOpen(false)}). Pressing a button acts on it before the dialog closes. UI
     * thread only.
     */
    static MessageDialog create(Shell shell, WelcomeNote.Kind kind, String version) {
        if (kind == WelcomeNote.Kind.UPDATED) {
            return new ActingDialog(shell, WelcomeNote.updatedMessage(version), new String[] { "What's New", "Close" },
                    button -> {
                        if (button == UPDATED_WHATS_NEW) {
                            openInBrowser(WelcomeNote.CHANGELOG_URL);
                        }
                    });
        }
        return new ActingDialog(shell, WelcomeNote.welcomeMessage(toggleKey()),
                new String[] { "Open Preferences", "Documentation", "Close" }, button -> {
                    if (button == WELCOME_PREFERENCES) {
                        // After the dialog closes, so the preferences are not stacked on top of it.
                        Display.getCurrent().asyncExec(() -> OpenPreferencesHandler.open(activeShell()));
                    } else if (button == WELCOME_DOCUMENTATION) {
                        openInBrowser(WelcomeNote.DOCUMENTATION_URL);
                    }
                });
    }

    /** The Neovim menu's Welcome entry: the welcome, whatever was shown before. UI thread only. */
    static void showWelcome(Shell shell) {
        create(shell, WelcomeNote.Kind.WELCOME, null).open();
    }

    /** The toggle's key binding, formatted for this OS ({@code Ctrl+Shift+M}; {@code ⇧⌘M} on macOS). */
    static String toggleKey() {
        return keyBinding(WelcomeNote.TOGGLE_COMMAND_ID, WelcomeNote.DEFAULT_TOGGLE_SEQUENCE);
    }

    /**
     * A command's key binding, formatted for this OS ({@code Ctrl+Shift+M}; {@code ⇧⌘M} on macOS).
     *
     * <p>First the binding the user's key scheme has now, which includes the user's own changes
     * (<em>Preferences › General › Keys</em>). Eclipse only answers that for bindings whose context
     * is active, and the toggle's context is the workbench window: while no workbench window is
     * active (another dialog has focus, or a test display without a window manager), it answers
     * {@code null}. Then the default from {@code plugin.xml} is named instead, which is right
     * unless the user rebound the key.
     *
     * @param commandId       the command, as in {@code plugin.xml}
     * @param defaultSequence its binding in {@code plugin.xml}, in Eclipse's notation ({@code M1+M2+M})
     * @return the binding, or {@code null} if none can be named
     */
    static String keyBinding(String commandId, String defaultSequence) {
        IBindingService bindings = PlatformUI.isWorkbenchRunning()
                ? PlatformUI.getWorkbench().getService(IBindingService.class)
                : null;
        String active = bindings == null ? null
                : bindings.getBestActiveBindingFormattedFor(commandId);
        if (active != null) {
            return active;
        }
        try {
            return KeySequence.getInstance(defaultSequence).format();
        } catch (ParseException e) { // a typo in the constant; PluginXmlConsistencyTest catches it first
            return null;
        }
    }

    private static void remember(IEclipsePreferences record, String line) {
        if (line == null) {
            return;
        }
        record.put(PreferenceConstants.WELCOME_SHOWN_LINE, line);
        try {
            record.flush(); // write it now: configuration scope is otherwise saved only at shutdown
        } catch (BackingStoreException e) {
            // The note may then show again on the next start; not worth more than a log line.
            LOG.log(Level.WARNING, "Could not save that the welcome was shown", e);
        }
    }

    private static void openInBrowser(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
        } catch (Exception e) { // PartInitException (no browser), or a malformed URL (a bug)
            LOG.log(Level.WARNING, "Could not open " + url, e);
        }
    }

    private static Shell activeShell() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getShell();
    }

    /** What a button press does; the index is the button's position. */
    @FunctionalInterface
    interface ButtonAction {
        void pressed(int button);
    }

    /**
     * A {@link MessageDialog} that runs an action for the pressed button. Acting inside
     * {@code buttonPressed}, instead of on {@code open()}'s return value, makes the dialog work the
     * same whether or not it blocks: the startup path blocks, tests do not.
     */
    private static final class ActingDialog extends MessageDialog {

        private final ButtonAction action;

        /**
         * The last button (Close) is the default, so Enter just dismisses the note. GTK shows the
         * default button rightmost, so the buttons' on-screen order varies by platform; their
         * indexes, which {@link ButtonAction} gets, do not.
         */
        ActingDialog(Shell shell, String message, String[] buttons, ButtonAction action) {
            super(shell, WelcomeNote.TITLE, null, message, MessageDialog.INFORMATION, buttons.length - 1, buttons);
            this.action = action;
        }

        @Override
        protected void buttonPressed(int buttonId) {
            super.buttonPressed(buttonId); // sets the return code and closes
            action.pressed(buttonId);
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.Bundle;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.ProblemReportDialog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.ExternalBrowser;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ProblemReport;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.RepairReport;

/**
 * Command "Neovim: Report a Problem" (the Neovim menu's <i>Report a Problem…</i>, Quick Access):
 * gathers what a bug report needs ({@link ProblemReport}) and shows it in
 * {@link ProblemReportDialog}, to copy into a GitHub issue.
 *
 * <h2>Where each fact comes from, and on which thread</h2>
 * On the UI thread, at once: the versions, the preferences that differ from their defaults, the
 * active editor's kind and mode. Off it, since each may wait: which Neovim the plug-in finds
 * ({@link NvimProbe#describe}, as the preference page says it; it may run the program's
 * {@code --version}) and the {@code init.lua} settings, asked from the running Neovim
 * ({@link NeovimSession#initSettingsAsync}, at most {@value #NEOVIM_WAIT_MS} ms: a Neovim stuck in
 * a prompt must not keep the report from opening). Then the dialog opens on the UI thread.
 */
public final class ReportProblemHandler extends AbstractHandler {

    private static final Logger LOG = Logger.getLogger(ReportProblemHandler.class.getName());
    /** How long the report waits for Neovim's answer about the settings. */
    static final int NEOVIM_WAIT_MS = 2000;
    /** GitHub's page that asks which kind of issue (the bug report template, or another). */
    static final String NEW_ISSUE_URL = RepairReport.ISSUES_URL + "/new/choose";

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        Display display = shell != null ? shell.getDisplay() : Display.getDefault();
        Activator plugin = Activator.getDefault();
        NeovimSession session = plugin == null ? null : plugin.currentSession();
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        String editor = controller == null || controller.isClosed() ? "" : describe(controller);
        List<String> preferences = plugin == null ? List.of() : changedPreferences(plugin.getPreferenceStore());
        List<String> warnings = plugin == null ? List.of() : plugin.recentWarnings();
        String pluginVersion = plugin == null ? "" : plugin.getBundle().getVersion().toString();
        String eclipse = eclipseVersion();
        String java = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
        String system = System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch") + ", SWT " + SWT.getPlatform();
        CompletableFuture.supplyAsync(() -> {
            String neovim;
            try {
                neovim = NvimProbe.describe(NeovimOption.value()).text();
            } catch (RuntimeException e) {
                // A broken setup is when a report matters most: say so in it, and open the dialog anyway.
                LOG.log(Level.WARNING, "Could not tell which Neovim the plug-in finds", e);
                neovim = "could not be found out (" + e.getClass().getSimpleName() + ")";
            }
            String running = session == null ? "" : session.neovimVersion();
            boolean startupErrors = session != null && !session.startupErrors().isEmpty();
            Map<String, String> settings = null;
            if (session != null) {
                try {
                    settings = session.initSettingsAsync(ProblemReport.INIT_SETTINGS).get(NEOVIM_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Neovim did not say which init.lua settings are set", e);
                }
            }
            String appName = System.getenv("NVIM_APPNAME");
            return new ProblemReport(pluginVersion, eclipse, java, system, neovim, running,
                    appName != null && !appName.isBlank(), startupErrors, settings, preferences, editor, warnings)
                    .text(System.getProperty("user.home"));
        }).whenComplete((report, error) -> {
            try {
                display.asyncExec(() -> showReport(shell, display, report, error));
            } catch (SWTException e) {
                LOG.log(Level.FINE, "Eclipse closed before the problem report was ready", e); // nothing to show it in
            }
        });
        return null;
    }

    /** Opens the dialog with {@code report}, or logs {@code error}. UI thread. */
    private static void showReport(Shell shell, Display display, String report, Throwable error) {
        if (display.isDisposed()) {
            return;
        }
        if (error != null) {
            LOG.log(Level.WARNING, "Could not gather the problem report", error);
            return;
        }
        ProblemReportDialog dialog = new ProblemReportDialog(shell != null && !shell.isDisposed() ? shell
                : display.getActiveShell(), report, () -> ExternalBrowser.open(NEW_ISSUE_URL));
        // Nothing waits for an answer (the buttons act themselves), so the dialog runs no event loop
        // of its own: a caller's loop, a UI test's wait say, keeps running while it is open.
        dialog.setBlockOnOpen(false);
        dialog.open();
    }

    /** The active editor's kind and Neovim mode: {@code a .java file in the Java Editor, Neovim mode on}. */
    private static String describe(NeovimEditorController controller) {
        ITextEditor editor = controller.getEditor();
        IEditorInput input = editor.getEditorInput();
        String name = editor.getEditorSite() == null ? null : editor.getEditorSite().getRegisteredName();
        return RepairReport.fileKind(input == null ? null : input.getName(), name)
                + ", Neovim mode " + (controller.isNeovimModeActive() ? "on" : "off");
    }

    /**
     * The README's Settings preferences that differ from their defaults, as {@code key = value}.
     * Example: {@code statusStatusline = true}, {@code ctrlKeyNormal.v = false}.
     */
    static List<String> changedPreferences(IPreferenceStore store) {
        List<String> keys = new ArrayList<>(List.of(PreferenceConstants.SHOW_TOGGLE_BUTTON, PreferenceConstants.SHOW_MENU,
                PreferenceConstants.FLASH_ON_ERROR, PreferenceConstants.SHOW_WELCOME, PreferenceConstants.STATUS_MODE,
                PreferenceConstants.STATUS_SHOWCMD, PreferenceConstants.STATUS_STATUSLINE,
                PreferenceConstants.STATUS_STATUSLINE_WIDTH, PreferenceConstants.STATUS_MAX_WIDTH_PERCENT));
        for (char letter = 'a'; letter <= 'z'; letter++) {
            keys.add(PreferenceConstants.ctrlKeyNormal(letter));
            keys.add(PreferenceConstants.ctrlKeyInsert(letter));
        }
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!store.isDefault(key)) {
                changed.add(key + " = " + store.getString(key));
            }
        }
        return changed;
    }

    /**
     * Eclipse's build and the product's name: {@code 4.41.0.I20260905-1800 (Eclipse IDE for Java
     * Developers)}. The build is what the launcher sets ({@code eclipse.buildId}), else the platform
     * feature's bundle version.
     */
    private static String eclipseVersion() {
        String version = System.getProperty("eclipse.buildId", "");
        if (version.isBlank()) {
            Bundle platform = Platform.getBundle("org.eclipse.platform");
            version = platform == null ? "" : platform.getVersion().toString();
        }
        IProduct product = Platform.getProduct();
        return product == null || product.getName() == null ? version : version + " (" + product.getName() + ")";
    }
}

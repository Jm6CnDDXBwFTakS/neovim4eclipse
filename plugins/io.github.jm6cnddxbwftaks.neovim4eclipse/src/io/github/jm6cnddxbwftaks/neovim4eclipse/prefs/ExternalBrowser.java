// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;

import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.HeadedMessageDialog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HttpsLinks;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LinkNotOpened;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * Opens a web page in Eclipse's external browser: the one link opener for the plug-in's dialogs,
 * notices and preference page. Through the workbench's browser support, not {@code Program.launch},
 * so the page opens in the browser chosen in <em>Window &gt; Preferences &gt; General &gt; Web
 * Browser</em>. Example: {@code ExternalBrowser.open(URI.create("https://neovim.io"))}.
 *
 * <p>When that browser cannot start (Eclipse throws), a dialog shows the link to copy, Eclipse's
 * reason and the fix, with a button to the Web Browser preferences ({@link LinkNotOpened}); the
 * failure is logged too. A browser that starts but ignores the link cannot be told apart from one
 * that opened it, so then nothing shows.
 *
 * <p>Only {@code https} pages open ({@link HttpsLinks}); anything else is logged and left: the
 * system would run a {@code file:} link's program on Windows.
 */
public final class ExternalBrowser {

    private static final Logger LOG = Logger.getLogger(ExternalBrowser.class.getName());

    /** Eclipse's own Web Browser preference page ({@code org.eclipse.ui.browser}). */
    static final String WEB_BROWSER_PAGE = "org.eclipse.ui.browser.preferencePage";

    private ExternalBrowser() {
    }

    /** Opens {@code url}. UI thread. */
    public static void open(URI url) {
        if (!HttpsLinks.isHttps(url)) {
            LOG.warning("Not opened, since only https links are: " + url);
            return;
        }
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(url.toURL());
        } catch (Exception e) { // PartInitException (the browser did not start), or a malformed URL (a bug)
            LOG.log(Level.WARNING, "Could not open " + url, e);
            showNotOpened(url, e.getMessage());
        }
    }

    /** Opens {@code url}, a constant such as {@code WelcomeNote.CHANGELOG_URL}. UI thread. */
    public static void open(String url) {
        open(URI.create(url));
    }

    /**
     * The dialog for a link that did not open, over the active window (Get Neovim's dialog, when the
     * link was in it). Its own message has no links, so it cannot fail the same way again.
     */
    private static void showNotOpened(URI url, String reason) {
        Display display = Display.getCurrent();
        Shell shell = display == null ? null : display.getActiveShell();
        HeadedMessageDialog dialog = new HeadedMessageDialog(shell, LinkNotOpened.TITLE, MessageDialog.WARNING,
                LinkNotOpened.message(url, reason, PlatformUtil.isMac()), 1,
                LinkNotOpened.BUTTONS.toArray(String[]::new));
        if (dialog.open() == 0) {
            PreferencesUtil.createPreferenceDialogOn(shell, WEB_BROWSER_PAGE, new String[] { WEB_BROWSER_PAGE }, null)
                    .open();
        }
    }
}

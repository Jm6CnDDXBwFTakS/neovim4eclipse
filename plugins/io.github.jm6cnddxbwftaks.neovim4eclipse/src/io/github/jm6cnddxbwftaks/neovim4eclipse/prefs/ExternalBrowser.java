// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.ui.PlatformUI;

/**
 * Opens a web page in Eclipse's external browser: the one link opener for the plug-in's dialogs,
 * notices and preference page. Through the workbench's browser support, not {@code Program.launch},
 * so the page opens in the browser chosen in <em>Window &gt; Preferences &gt; General &gt; Web
 * Browser</em>. Example: {@code ExternalBrowser.open(URI.create("https://neovim.io"))}.
 *
 * <p>A failure is logged, not shown: a link that does nothing is the worst that can happen, and the
 * URL is in the link's tooltip for the user to copy.
 */
public final class ExternalBrowser {

    private static final Logger LOG = Logger.getLogger(ExternalBrowser.class.getName());

    private ExternalBrowser() {
    }

    /** Opens {@code url}. UI thread. */
    public static void open(URI url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(url.toURL());
        } catch (Exception e) { // PartInitException (no browser), or a malformed URL (a bug)
            LOG.log(Level.WARNING, "Could not open " + url, e);
        }
    }

    /** Opens {@code url}, a constant such as {@code WelcomeNote.CHANGELOG_URL}. UI thread. */
    public static void open(String url) {
        open(URI.create(url));
    }
}

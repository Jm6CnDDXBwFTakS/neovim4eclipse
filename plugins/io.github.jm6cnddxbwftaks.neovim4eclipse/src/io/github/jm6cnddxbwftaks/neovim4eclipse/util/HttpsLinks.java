// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.URI;
import java.util.Locale;

/**
 * The one rule for a link the plug-in opens: an {@code https} web page with a host, nothing else.
 *
 * <p><b>Why.</b> Links are opened by the system ({@code Program.launch}) or Eclipse's browser
 * support, and both open more than web pages: on Windows {@code Program.launch("file:///C:/x/evil.exe")}
 * runs the program, and {@code javascript:}, {@code mailto:} or a custom scheme hand the text to
 * whatever application claims it. Every link today is a constant or comes from the plug-in's own
 * data file, so none is dangerous; the rule keeps it so when a link is ever built from text the
 * plug-in did not write (a path, a message from Neovim).
 *
 * <p>Examples: {@code https://github.com/neovim/neovim/releases/tag/v0.12.6} passes;
 * {@code http://neovim.io} (no TLS), {@code file:///tmp/x}, {@code https:relative} (no host) and
 * {@code javascript:alert(1)} do not.
 */
public final class HttpsLinks {

    private HttpsLinks() {
    }

    /** Whether {@code url} is an absolute {@code https} URL with a host. */
    public static boolean isHttps(URI url) {
        return url != null && url.getScheme() != null && url.getScheme().toLowerCase(Locale.ROOT).equals("https")
                && url.getHost() != null && !url.getHost().isEmpty();
    }

    /**
     * Returns {@code url}, or throws if it is not {@link #isHttps https}: for links the plug-in
     * itself builds, where anything else is a bug to find in a test, not at a click.
     */
    public static URI require(URI url) {
        if (!isHttps(url)) {
            throw new IllegalArgumentException("Not an https link: " + url);
        }
        return url;
    }
}

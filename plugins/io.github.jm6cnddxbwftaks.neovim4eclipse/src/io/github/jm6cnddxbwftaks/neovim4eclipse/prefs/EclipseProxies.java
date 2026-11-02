// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Eclipse's proxy settings (<em>Window &gt; Preferences &gt; General &gt; Network Connections</em>)
 * for Java's {@code HttpClient}, so <i>Get Neovim</i> downloads wherever Eclipse's own updater does.
 *
 * <h2>Why ask Eclipse for each address</h2>
 * Eclipse's proxy service ({@code org.eclipse.core.net}) also copies the active proxy into Java's
 * {@code https.proxyHost} properties, which a plain {@code HttpClient} would follow. But the updater
 * (p2, through ECF) asks the service itself, {@link IProxyService#select(URI)}, for each address,
 * and the two can differ: on Windows the service can evaluate a proxy auto-config script per address,
 * and on macOS, after switching from <em>Manual</em> to <em>Native</em>, the service still answers
 * with the manual proxy while the properties say "direct" (seen 2026-11-02). Asking the service the
 * same way gives the same answer as the updater. Eclipse's default, <em>Native</em>, is the system
 * proxy where Eclipse can read one (Linux: environment variables and GNOME's settings; Windows: the
 * Windows proxy settings); macOS has no reader, so there <em>Native</em> means no proxy.
 *
 * <h2>Passwords</h2>
 * {@link #authenticator()} answers a proxy's password request with the login stored on that page. Java
 * only asks it for a plain HTTP proxy connection, or for HTTPS when Eclipse runs with
 * {@code -Djdk.http.auth.tunneling.disabledSchemes=} ({@code NeovimDownload} explains the 407 that
 * comes otherwise). See docs/design/DOWNLOAD_NEOVIM.md, section 4.
 */
final class EclipseProxies {

    private EclipseProxies() {
    }

    /** A selector that asks Eclipse's proxy service for each address; Java's default without one. */
    static ProxySelector selector() {
        IProxyService service = service();
        if (service == null) {
            return ProxySelector.getDefault();
        }
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                List<Proxy> proxies = new ArrayList<>();
                for (IProxyData data : service.select(uri)) {
                    if (data.getHost() == null || data.getPort() <= 0) {
                        continue;
                    }
                    Proxy.Type type = IProxyData.SOCKS_PROXY_TYPE.equals(data.getType()) ? Proxy.Type.SOCKS
                            : Proxy.Type.HTTP;
                    proxies.add(new Proxy(type, InetSocketAddress.createUnresolved(data.getHost(), data.getPort())));
                }
                return proxies.isEmpty() ? List.of(Proxy.NO_PROXY) : proxies;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                // nothing to remember: the download reports the failure
            }
        };
    }

    /** Answers a proxy's password request with the login stored in Eclipse; {@code null} without a service. */
    static Authenticator authenticator() {
        IProxyService service = service();
        if (service == null) {
            return null;
        }
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null; // GitHub itself asks for no password
                }
                for (IProxyData data : service.getProxyData()) {
                    if (data.isRequiresAuthentication() && data.getUserId() != null && data.getPassword() != null
                            && getRequestingHost() != null && getRequestingHost().equalsIgnoreCase(data.getHost())) {
                        return new PasswordAuthentication(data.getUserId(), data.getPassword().toCharArray());
                    }
                }
                return null;
            }
        };
    }

    private static IProxyService service() {
        var bundle = FrameworkUtil.getBundle(EclipseProxies.class);
        BundleContext context = bundle == null ? null : bundle.getBundleContext();
        if (context == null) {
            return null;
        }
        ServiceReference<IProxyService> ref = context.getServiceReference(IProxyService.class);
        return ref == null ? null : context.getService(ref);
    }
}

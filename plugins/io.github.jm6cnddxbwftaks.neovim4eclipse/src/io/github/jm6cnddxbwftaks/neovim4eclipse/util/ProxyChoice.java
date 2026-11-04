// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * What Get Neovim's download does with the proxies Eclipse names for an address
 * ({@code prefs/EclipseProxies} asks Eclipse's proxy service, {@code IProxyService.select}, and
 * passes each answer here as an {@link Entry}). Pure, so the rules are unit-tested without Eclipse.
 *
 * <h2>The rule: HTTP proxies only, and never "direct" in place of a SOCKS proxy</h2>
 * Java's {@code java.net.http.HttpClient} uses only HTTP proxies (for {@code https://} too, through
 * {@code CONNECT}); handed a SOCKS proxy it ignores it and connects directly, past the proxy the
 * user set up. So:
 * <ul>
 *   <li>{@link #httpProxies}: the HTTP and HTTPS entries, as Java proxies, for the client's
 *       {@code ProxySelector}; SOCKS ones are left out.</li>
 *   <li>{@link #socksOnly}: whether Eclipse names only SOCKS proxies for the address. Get Neovim
 *       then stops with a message ({@code NeovimDownload.socksOnly}) instead of downloading
 *       directly. Example: <i>Network Connections</i>, Manual, with only SOCKS set to
 *       {@code 127.0.0.1:1080}.</li>
 * </ul>
 * An entry with no host or no port (an empty row on that page) counts as none.
 */
public final class ProxyChoice {

    /**
     * One proxy Eclipse names for an address.
     *
     * @param socks whether it is a SOCKS proxy ({@code IProxyData.SOCKS_PROXY_TYPE}); else HTTP or HTTPS
     * @param host  its host, or {@code null} when not set
     * @param port  its port, or 0 or less when not set
     */
    public record Entry(boolean socks, String host, int port) {
        boolean set() {
            return host != null && !host.isBlank() && port > 0;
        }
    }

    private ProxyChoice() {
    }

    /** The HTTP(S) proxies among {@code entries}, in order; {@code [Proxy.NO_PROXY]} when none. */
    public static List<Proxy> httpProxies(List<Entry> entries) {
        List<Proxy> proxies = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.set() && !entry.socks()) {
                proxies.add(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(entry.host(), entry.port())));
            }
        }
        return proxies.isEmpty() ? List.of(Proxy.NO_PROXY) : proxies;
    }

    /** Whether {@code entries} name a SOCKS proxy and no HTTP(S) one: the download must not go direct. */
    public static boolean socksOnly(List<Entry> entries) {
        boolean socks = false;
        for (Entry entry : entries) {
            if (!entry.set()) {
                continue;
            }
            if (!entry.socks()) {
                return false; // an HTTP(S) proxy is used for it
            }
            socks = true;
        }
        return socks;
    }
}

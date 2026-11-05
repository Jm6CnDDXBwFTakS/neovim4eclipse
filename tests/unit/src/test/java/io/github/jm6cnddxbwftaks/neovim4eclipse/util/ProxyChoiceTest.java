// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The proxies Get Neovim's download uses, down to the decision ({@code prefs/EclipseProxies} only
 * turns Eclipse's {@code IProxyData} into {@link ProxyChoice.Entry}).
 */
class ProxyChoiceTest {

    private static final ProxyChoice.Entry SOCKS = new ProxyChoice.Entry(true, "127.0.0.1", 1080);
    private static final ProxyChoice.Entry HTTPS = new ProxyChoice.Entry(false, "proxy.example", 3128);
    /** A row left empty on Network Connections: Eclipse still lists the type, with no host or port. */
    private static final ProxyChoice.Entry EMPTY_HTTP = new ProxyChoice.Entry(false, null, -1);

    @Test
    void onlyASocksProxyIsSocksOnlySoTheDownloadStopsInsteadOfGoingDirect() {
        assertTrue(ProxyChoice.socksOnly(List.of(SOCKS)));
        assertTrue(ProxyChoice.socksOnly(List.of(EMPTY_HTTP, SOCKS)), "an empty HTTP row is no HTTP proxy");
        assertEquals(List.of(Proxy.NO_PROXY), ProxyChoice.httpProxies(List.of(SOCKS)),
                "Java's client is never handed the SOCKS proxy");
    }

    @Test
    void anHttpProxyBesideSocksIsUsed() {
        assertFalse(ProxyChoice.socksOnly(List.of(SOCKS, HTTPS)));
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128))),
                ProxyChoice.httpProxies(List.of(SOCKS, HTTPS)));
    }

    @Test
    void noProxyIsDirectAndNotSocksOnly() {
        assertFalse(ProxyChoice.socksOnly(List.of()));
        assertFalse(ProxyChoice.socksOnly(List.of(EMPTY_HTTP)));
        assertEquals(List.of(Proxy.NO_PROXY), ProxyChoice.httpProxies(List.of()));
    }

    @Test
    void aSocksRowWithNoHostOrPortCountsAsNone() {
        assertFalse(ProxyChoice.socksOnly(List.of(new ProxyChoice.Entry(true, null, 1080))));
        assertFalse(ProxyChoice.socksOnly(List.of(new ProxyChoice.Entry(true, "127.0.0.1", 0))));
    }

    /** Any host Eclipse gives is passed on as it is, so a bad one fails at the proxy, never goes direct. */
    @Test
    void aBlankHostIsStillAProxyNotDirect() {
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(" ", 3128))),
                ProxyChoice.httpProxies(List.of(new ProxyChoice.Entry(false, " ", 3128))));
    }

    @Test
    void httpProxiesKeepEclipsesOrder() {
        ProxyChoice.Entry second = new ProxyChoice.Entry(false, "second.example", 8080);
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128)),
                new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("second.example", 8080))),
                ProxyChoice.httpProxies(List.of(HTTPS, EMPTY_HTTP, second)));
    }
}

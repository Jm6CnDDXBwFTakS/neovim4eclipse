// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The manual checks' proxy ({@code releng/tools/checks/Proxy}): how it reads a request line, and a
 * tunnel and a forwarded request through it to a server of the test's own on this machine.
 *
 * <p>Every socket read and thread join waits at most {@link TestWait}'s time, so a proxy that
 * stopped answering fails the test instead of hanging the build.
 */
class ProxyTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private Proxy proxy;
    private ServerSocket host;

    @BeforeEach
    void start() throws IOException {
        proxy = Proxy.start(0, log::add);
        host = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        host.setSoTimeout((int) TestWait.millis());
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        host.close();
    }

    // ---- request lines ----

    @Test
    void aConnectLineIsATunnelToItsHostAndPort() {
        Proxy.Request r = Proxy.Request.parse("CONNECT marketplace.eclipse.org:443 HTTP/1.1");
        assertEquals(new Proxy.Request("CONNECT", "marketplace.eclipse.org", 443, null), r);
        assertTrue(r.isTunnel());
        assertEquals("marketplace.eclipse.org:443", r.authority());
    }

    @Test
    void anIpv6TunnelKeepsItsBracketsOnlyInTheOutput() {
        Proxy.Request r = Proxy.Request.parse("CONNECT [::1]:8443 HTTP/1.1");
        assertEquals("::1", r.host());
        assertEquals("[::1]:8443", r.authority());
    }

    @Test
    void aPlainHttpUrlGoesToItsHostWithThePathAlone() {
        assertEquals(new Proxy.Request("GET", "example.org", 80, "/a/b?c=d"),
                Proxy.Request.parse("GET http://example.org/a/b?c=d HTTP/1.1"));
        assertEquals(new Proxy.Request("HEAD", "example.org", 8080, "/"),
                Proxy.Request.parse("HEAD http://example.org:8080 HTTP/1.1"));
        assertEquals(new Proxy.Request("GET", "example.org", 80, "/?x=1"),
                Proxy.Request.parse("GET http://example.org?x=1#top HTTP/1.1"), "the host ends at '?'; no fragment is sent");
    }

    @Test
    void aHostNameWithAnUnderscoreIsServed() {
        assertEquals(new Proxy.Request("CONNECT", "some_host.example.net", 443, null),
                Proxy.Request.parse("CONNECT some_host.example.net:443 HTTP/1.1"));
        assertEquals(new Proxy.Request("GET", "some_host.example.net", 80, "/"),
                Proxy.Request.parse("GET http://some_host.example.net HTTP/1.1"));
    }

    @Test
    void linesAProxyCannotServeAreRefused() {
        assertNull(Proxy.Request.parse("GET /a HTTP/1.1"), "a path alone is for a server");
        assertNull(Proxy.Request.parse("GET https://example.org/ HTTP/1.1"), "https goes through CONNECT");
        assertNull(Proxy.Request.parse("CONNECT example.org HTTP/1.1"), "a tunnel needs a port");
        assertNull(Proxy.Request.parse("CONNECT example.org:443"), "no HTTP version");
        assertNull(Proxy.Request.parse("CONNECT example.org:0 HTTP/1.1"), "no port 0");
        assertNull(Proxy.Request.parse("CONNECT example.org:x HTTP/1.1"));
        assertNull(Proxy.Request.parse("CONNECT ::1:443 HTTP/1.1"), "an IPv6 address needs brackets");
        assertNull(Proxy.Request.parse("nonsense"));
    }

    @Test
    void aForwardedRequestDropsTheProxysHeadersAndClosesAfterItsAnswer() {
        String head = "GET http://example.org/a HTTP/1.1\r\nHost: example.org\r\nProxy-Connection: keep-alive\r\n"
                + "Connection: keep-alive\r\nAccept: */*\r\n\r\n";
        assertEquals("GET /a HTTP/1.1\r\nHost: example.org\r\nAccept: */*\r\nConnection: close\r\n\r\n",
                Proxy.Request.forwarded(head, Proxy.Request.parse("GET http://example.org/a HTTP/1.1")));
    }

    @Test
    void aBodyIsAsLongAsItsContentLengthSays() {
        assertEquals(0, Proxy.Request.bodyLength("GET http://h/ HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertEquals(12, Proxy.Request.bodyLength("POST http://h/ HTTP/1.1\r\ncontent-length: 12\r\n\r\n"));
        assertEquals(-1, Proxy.Request.bodyLength("POST http://h/ HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"));
    }

    @Test
    void aLengthThatCannotBeTrustedIsInvalid() {
        for (String headers : List.of("Content-Length: -5", "Content-Length: x", "Content-Length: 4\r\nContent-Length: 5",
                "Content-Length: 4\r\nTransfer-Encoding: chunked")) {
            assertEquals(Proxy.Request.INVALID_LENGTH, Proxy.Request.bodyLength("POST http://h/ HTTP/1.1\r\n" + headers + "\r\n\r\n"),
                    headers);
        }
        assertEquals(4, Proxy.Request.bodyLength("POST http://h/ HTTP/1.1\r\nContent-Length: 4\r\nContent-Length: 4\r\n\r\n"),
                "the same length twice is that length");
    }

    @Test
    void theOutputShowsNoControlCharactersAndNoLongLines() {
        assertEquals("GET ?[2J HTTP/1.1", Proxy.printable("GET \u001b[2J HTTP/1.1"));
        assertEquals("GET ?2J HTTP/1.1", Proxy.printable("GET \u009b2J HTTP/1.1"), "an 8-bit escape too");
        assertEquals("x".repeat(200) + "...", Proxy.printable("x".repeat(300)));
    }

    @Test
    void aHeadEndsAtTheFirstBlankLine() throws IOException {
        InputStream in = new ByteArrayInputStream("CONNECT h:1 HTTP/1.1\r\nHost: h\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("CONNECT h:1 HTTP/1.1\r\nHost: h\r\n\r\n", Proxy.readHead(in));
        assertEquals("body", new String(in.readAllBytes(), StandardCharsets.ISO_8859_1), "the rest stays unread");
        assertNull(Proxy.readHead(new ByteArrayInputStream("CONNECT h:1 HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1))));
    }

    // ---- through the proxy ----

    /** A tunnel copies bytes both ways unread, as TLS needs: here a host that answers "pong" to "ping". */
    @Test
    void aTunnelCarriesBytesBothWays() throws Exception {
        Thread server = Thread.ofVirtual().start(() -> {
            try (Socket s = host.accept()) {
                s.setSoTimeout((int) TestWait.millis());
                byte[] ping = s.getInputStream().readNBytes(4);
                s.getOutputStream().write(new String(ping, StandardCharsets.ISO_8859_1).equals("ping")
                        ? "pong".getBytes(StandardCharsets.ISO_8859_1) : "????".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                // the test fails on the client side
            }
        });
        try (Socket client = toProxy()) {
            send(client, "CONNECT 127.0.0.1:" + host.getLocalPort() + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertEquals("HTTP/1.1 200 Connection established\r\n\r\n", Proxy.readHead(client.getInputStream()));
            send(client, "ping");
            assertEquals("pong", new String(client.getInputStream().readNBytes(4), StandardCharsets.ISO_8859_1));
        }
        joined(server);
        assertEquals(List.of("1  CONNECT 127.0.0.1:" + host.getLocalPort()), log);
    }

    @Test
    void aPlainRequestIsForwardedWithThePathAlone() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        Thread server = Thread.ofVirtual().start(() -> {
            try (Socket s = host.accept()) {
                s.setSoTimeout((int) TestWait.millis());
                got.add(Proxy.readHead(s.getInputStream()));
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                // the test fails on the client side
            }
        });
        try (Socket client = toProxy()) {
            send(client, "GET http://127.0.0.1:" + host.getLocalPort() + "/a HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\n");
            assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",
                    new String(client.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        joined(server);
        assertEquals(List.of("GET /a HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"), got);
    }

    /**
     * A client that reuses its connection sends a second request after the first one's body: it
     * must not reach this host (it may be meant for another). The host gets one request and its
     * body, then the client's side ends when the host closes.
     */
    @Test
    void onlyOneRequestAndItsBodyReachTheHost() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        Thread server = Thread.ofVirtual().start(() -> {
            try (Socket s = host.accept()) {
                s.setSoTimeout((int) TestWait.millis());
                InputStream in = s.getInputStream();
                got.add(Proxy.readHead(in));
                got.add(new String(in.readNBytes(4), StandardCharsets.ISO_8859_1));
                s.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                s.shutdownOutput();
                got.add(String.valueOf(in.read())); // -1: nothing more came from the proxy
            } catch (IOException e) {
                got.add("failed: " + e);
            }
        });
        try (Socket client = toProxy()) {
            send(client, "POST http://127.0.0.1:" + host.getLocalPort() + "/a HTTP/1.1\r\nContent-Length: 4\r\n\r\nbody"
                    + "GET http://example.org/next HTTP/1.1\r\n\r\n");
            assertEquals("HTTP/1.1 204 No Content\r\n\r\n",
                    new String(client.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        joined(server);
        assertEquals(List.of("POST /a HTTP/1.1\r\nContent-Length: 4\r\nConnection: close\r\n\r\n", "body", "-1"), got);
    }

    /**
     * A client that resets its tunnel: the proxy closes the host's side at once, rather than
     * waiting for the host to give up. The host here waits for bytes and sees the end.
     */
    @Test
    void aResetTunnelClosesTheHostsSideToo() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        Thread server = Thread.ofVirtual().start(() -> {
            try (Socket s = host.accept()) {
                s.setSoTimeout((int) TestWait.millis());
                got.add(String.valueOf(s.getInputStream().read()));
            } catch (IOException e) {
                got.add("failed: " + e); // a read timeout: the proxy kept the host's side open
            }
        });
        Socket client = toProxy();
        send(client, "CONNECT 127.0.0.1:" + host.getLocalPort() + " HTTP/1.1\r\n\r\n");
        assertEquals("HTTP/1.1 200 Connection established\r\n\r\n", Proxy.readHead(client.getInputStream()));
        client.setSoLinger(true, 0); // close with a reset, not an orderly end
        client.close();
        joined(server);
        assertTrue(got.size() == 1 && !got.get(0).startsWith("failed"), got::toString);
    }

    /**
     * Port 1 of this machine, where nothing listens unless its administrator starts something
     * there: the connection is refused at once, and the client gets 502.
     */
    @Test
    void aHostThatCannotBeReachedIsA502() throws Exception {
        try (Socket client = toProxy()) {
            send(client, "CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n");
            assertTrue(Proxy.readHead(client.getInputStream()).startsWith("HTTP/1.1 502 Bad Gateway\r\n"));
        }
        assertTrue(log.get(0).startsWith("1  CONNECT 127.0.0.1:1 failed: "), log::toString);
    }

    /** A request it cannot serve gets 400, and a line in the output saying so. */
    @Test
    void aRefusedRequestIsA400WithALine() throws Exception {
        try (Socket client = toProxy()) {
            send(client, "GET https://example.org/ HTTP/1.1\r\n\r\n");
            assertTrue(Proxy.readHead(client.getInputStream()).startsWith("HTTP/1.1 400 Bad Request\r\n"));
        }
        assertEquals(List.of("1  refused (400): GET https://example.org/ HTTP/1.1"), log);
    }

    /**
     * A client's escape code reaches the output as text, not as a terminal command. Every line
     * goes through the same {@code say}; a refused request line is the one that needs no network.
     */
    @Test
    void aClientCannotWriteControlCharactersToTheOutput() throws Exception {
        try (Socket client = toProxy()) {
            send(client, "GET /\u001b[2J HTTP/1.1\r\n\r\n");
            assertTrue(Proxy.readHead(client.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        assertEquals(List.of("1  refused (400): GET /?[2J HTTP/1.1"), log);
    }

    private static void joined(Thread server) throws InterruptedException {
        assertTrue(server.join(TestWait.duration()), "the test's server did not finish");
    }

    private Socket toProxy() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.port());
        socket.setSoTimeout((int) TestWait.millis());
        return socket;
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}

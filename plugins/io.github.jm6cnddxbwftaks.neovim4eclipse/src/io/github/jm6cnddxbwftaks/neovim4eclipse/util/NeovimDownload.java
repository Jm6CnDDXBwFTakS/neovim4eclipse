// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Downloads a release archive into a temporary file, for {@link NeovimInstaller} to check and unpack.
 *
 * <h2>The connection</h2>
 * Java's {@link HttpClient}, built by the caller: it follows GitHub's redirect from
 * {@code github.com} to {@code release-assets.githubusercontent.com}, and takes its proxy from a
 * {@code ProxySelector} the Eclipse side builds from Eclipse's own network settings
 * ({@code editor/EclipseProxies}), so the download works wherever Eclipse's own updater does.
 * Example: {@code client(proxySelector)} then {@code download(archive, client, folder, progress)}.
 *
 * <h2>A proxy that wants a password</h2>
 * Java refuses to send a Basic proxy password through an HTTPS tunnel unless
 * {@code jdk.http.auth.tunneling.disabledSchemes} is changed for the whole JVM, so behind such a
 * proxy the answer is HTTP 407 whatever the plug-in does; Eclipse's own updater gets the same
 * (docs/design/DOWNLOAD_NEOVIM.md, section 4). The error then names the {@code eclipse.ini} line that
 * changes it, and the dialog offers <i>Install from File…</i>. The plug-in never changes the setting
 * itself: it is a security default of the whole IDE, and the JDK reads it only once anyway.
 */
public final class NeovimDownload {

    /** The {@code eclipse.ini} line that lets Java send a proxy password through an HTTPS tunnel. */
    public static final String TUNNEL_AUTH_LINE = "-Djdk.http.auth.tunneling.disabledSchemes=";

    private NeovimDownload() {
    }

    /** A client that follows redirects and uses {@code proxies}; with an authenticator if one is given. */
    public static HttpClient client(java.net.ProxySelector proxies, java.net.Authenticator authenticator) {
        HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).proxy(proxies);
        if (authenticator != null) {
            b.authenticator(authenticator);
        }
        return b.build();
    }

    /**
     * Downloads {@code archive} into a new temporary file in {@code folder}.
     *
     * @return the file; the caller deletes it
     * @throws NeovimInstallException NETWORK or PROXY_PASSWORD with a message for the user, CANCELLED,
     *                                or FILES if the file cannot be written
     */
    public static Path download(NeovimRelease.Archive archive, HttpClient client, Path folder,
            InstallProgress progress) {
        Path file;
        try {
            Files.createDirectories(folder);
            file = Files.createTempFile(folder, PluginNeovim.DOWNLOAD_PREFIX, ".part");
        } catch (IOException e) {
            throw new NeovimInstallException(NeovimInstallException.Reason.FILES,
                    "Cannot write in " + folder + ": " + e.getMessage(), e);
        }
        boolean done = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(archive.url()).timeout(Duration.ofMinutes(10)).build();
            HttpResponse<InputStream> response = send(client, request, archive);
            try (InputStream in = response.body()) {
                int status = response.statusCode();
                if (status == 407) {
                    throw proxyPassword();
                }
                if (status != 200) {
                    throw new NeovimInstallException(NeovimInstallException.Reason.NETWORK,
                            "GitHub answered HTTP " + status + " for " + response.uri() + ".");
                }
                progress.step("Downloading " + archive.asset(), archive.size());
                try (OutputStream out = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) > 0;) {
                        progress.checkCancelled();
                        out.write(buffer, 0, n);
                        progress.worked(n);
                    }
                }
            } catch (IOException e) {
                throw new NeovimInstallException(NeovimInstallException.Reason.NETWORK,
                        "The download of " + archive.asset() + " broke off: " + e.getMessage(), e);
            }
            done = true;
            return file;
        } finally {
            if (!done) {
                deleteQuietly(file);
            }
        }
    }

    private static HttpResponse<InputStream> send(HttpClient client, HttpRequest request,
            NeovimRelease.Archive archive) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NeovimInstallException(NeovimInstallException.Reason.CANCELLED, "Cancelled.", e);
        } catch (IOException e) {
            throw new NeovimInstallException(NeovimInstallException.Reason.NETWORK, networkMessage(archive, e), e);
        }
    }

    /**
     * Why GitHub could not be reached, for the user. A proxy that wants a password makes Java's
     * tunnel fail with an {@code IOException} whose message contains 407 (before any response), so
     * that case is recognized here too.
     */
    static String networkMessage(NeovimRelease.Archive archive, IOException e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (detail.contains("407")) {
            throw proxyPassword();
        }
        if (e instanceof UnknownHostException) {
            return "Cannot find " + archive.url().getHost() + " (" + detail + "). Check the network connection, or "
                    + "Eclipse's proxy settings (Window > Preferences > General > Network Connections).";
        }
        if (e instanceof ConnectException || e instanceof HttpConnectTimeoutException
                || e instanceof HttpTimeoutException) {
            return "Cannot connect to download " + archive.asset() + " (" + detail + "). A firewall or proxy may "
                    + "block github.com or release-assets.githubusercontent.com, where GitHub sends the download; "
                    + "check Eclipse's proxy settings (Window > Preferences > General > Network Connections).";
        }
        return "The download of " + archive.asset() + " failed: " + detail;
    }

    private static NeovimInstallException proxyPassword() {
        return new NeovimInstallException(NeovimInstallException.Reason.PROXY_PASSWORD,
                "The proxy asks for a password (HTTP 407). Java does not send proxy passwords through an "
                        + "HTTPS connection unless Eclipse is started with this line in eclipse.ini, after -vmargs:\n"
                        + TUNNEL_AUTH_LINE + "\n"
                        + "Eclipse's own updater needs the same line. Then enter the proxy's user and password in "
                        + "Window > Preferences > General > Network Connections and try again. Or download the "
                        + "archive in a browser and use Install from File.");
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a leftover temporary file in the plug-in's folder: harmless, and the next install ignores it
        }
    }
}

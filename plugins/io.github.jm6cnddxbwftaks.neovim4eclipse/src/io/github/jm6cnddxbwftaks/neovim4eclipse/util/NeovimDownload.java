// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Downloads a release archive into a temporary file, for {@link NeovimInstaller} to check and unpack.
 *
 * <h2>The connection</h2>
 * Java's {@link HttpClient}, built by the caller: it follows GitHub's redirect from
 * {@code github.com} to {@code release-assets.githubusercontent.com}, and takes its proxy from a
 * {@code ProxySelector} the Eclipse side builds from Eclipse's own network settings
 * ({@code prefs/EclipseProxies}), so the download works wherever Eclipse's own updater does.
 * Example: {@code client(proxySelector)} then {@code download(archive, client, folder, progress)}.
 *
 * <h2>Cancel, a stalled connection, a body too long</h2>
 * The body is written to the file by the client's own threads ({@code sendAsync} with
 * {@code BodySubscribers.ofFile}), and the calling thread watches, every
 * {@value #POLL_MILLIS} ms, how far the file has grown. That thread is never stuck in a read, so it
 * can always act: on Cancel, when nothing arrived for {@link #STALL} (a connection that stays open
 * but sends nothing, which {@code HttpRequest.timeout} does not cover: it ends at the response's
 * headers), and when the file grows past the archive's pinned size (it cannot be the archive then,
 * and a broken or hostile server would otherwise fill the disk). Each time it cancels the request
 * ({@code CompletableFuture.cancel(true)}, which aborts the exchange) and deletes the file.
 * Example: GitHub sends 4 MB of the 10.8 MB, then nothing; after a minute the download fails with
 * "nothing arrived for 60 seconds", and Retry starts over.
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

    /** How long the download may go without a byte before it is given up as stalled. */
    static final Duration STALL = Duration.ofSeconds(60);

    /** How often the calling thread looks at the file, Cancel and the stall clock. */
    private static final long POLL_MILLIS = 250;

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
        return download(archive, client, folder, progress, STALL);
    }

    /** {@link #download(NeovimRelease.Archive, HttpClient, Path, InstallProgress)} with the stall time a test chooses. */
    static Path download(NeovimRelease.Archive archive, HttpClient client, Path folder, InstallProgress progress,
            Duration stall) {
        Path file;
        try {
            Files.createDirectories(folder);
            file = Files.createTempFile(folder, PluginNeovim.DOWNLOAD_PREFIX, ".part");
        } catch (IOException e) {
            throw new NeovimInstallException(NeovimInstallException.Reason.FILES,
                    "Cannot write in " + folder + ": " + e.getMessage(), e);
        }
        CompletableFuture<HttpResponse<Path>> future = null;
        boolean done = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(archive.url()).timeout(Duration.ofMinutes(10)).build();
            // Only a 200's body goes into the file; any other answer's body is dropped.
            future = client.sendAsync(request, info -> info.statusCode() == 200
                    ? HttpResponse.BodySubscribers.ofFile(file)
                    : HttpResponse.BodySubscribers.replacing(file));
            progress.step("Downloading " + archive.asset(), archive.size());
            HttpResponse<Path> response = watch(future, file, archive, progress, stall);
            int status = response.statusCode();
            if (status == 407) {
                throw proxyPassword();
            }
            if (status != 200) {
                throw new NeovimInstallException(NeovimInstallException.Reason.NETWORK,
                        "GitHub answered HTTP " + status + " for " + response.uri() + ".");
            }
            checkSize(file, archive); // the whole body may have come between two looks
            done = true;
            return file;
        } finally {
            if (!done) {
                if (future != null) {
                    future.cancel(true);
                }
                deleteQuietly(file);
            }
        }
    }

    /**
     * Waits for the response and its whole body, looking every {@value #POLL_MILLIS} ms at Cancel, the
     * file's growth (for the progress bar and the stall clock) and its size. The caller cancels the
     * request when this throws.
     */
    private static HttpResponse<Path> watch(CompletableFuture<HttpResponse<Path>> future, Path file,
            NeovimRelease.Archive archive, InstallProgress progress, Duration stall) {
        long seen = 0;
        long lastGrowth = System.nanoTime();
        while (true) {
            try {
                return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // still running: look below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NeovimInstallException(NeovimInstallException.Reason.CANCELLED, "Cancelled.", e);
            } catch (ExecutionException e) {
                throw failure(archive, e.getCause());
            }
            progress.checkCancelled();
            long size = checkSize(file, archive);
            if (size > seen) {
                progress.worked(size - seen);
                seen = size;
                lastGrowth = System.nanoTime();
            } else if (System.nanoTime() - lastGrowth > stall.toNanos()) {
                throw new NeovimInstallException(NeovimInstallException.Reason.NETWORK, "The download of "
                        + archive.asset() + " stalled: nothing arrived for " + stall.toSeconds() + " seconds, after "
                        + size + " of " + archive.size() + " bytes. Check the network connection, and try again.");
            }
        }
    }

    /**
     * The file's size so far.
     *
     * @throws NeovimInstallException NETWORK if it is larger than the archive can be
     */
    private static long checkSize(Path file, NeovimRelease.Archive archive) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0; // not created yet by the client, or being replaced: look again next time
        }
        if (size > archive.size()) {
            throw new NeovimInstallException(NeovimInstallException.Reason.NETWORK, "The download of "
                    + archive.asset() + " sent more than its " + archive.size() + " bytes, so it cannot be the "
                    + "archive; it was stopped.");
        }
        return size;
    }

    /** The exception for a request that failed: an I/O error in words for the user, or a bug passed on. */
    private static NeovimInstallException failure(NeovimRelease.Archive archive, Throwable cause) {
        if (cause instanceof NeovimInstallException e) {
            return e;
        }
        if (cause instanceof IOException e) {
            return new NeovimInstallException(NeovimInstallException.Reason.NETWORK, networkMessage(archive, e), e);
        }
        return new NeovimInstallException(NeovimInstallException.Reason.NETWORK,
                "The download of " + archive.asset() + " failed: " + cause, cause);
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

    /** Deletes a temporary file, ignoring a failure (a file still open on Windows, say). */
    public static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a leftover temporary file in the plug-in's folder: harmless, and the next install ignores it
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The Neovim release that <i>Get Neovim</i> installs, read from {@code neovim-downloads.properties}
 * beside this class: its version, and for each {@link NeovimPlatform} the archive's name, size and
 * SHA-256.
 *
 * <h2>Why a data file</h2>
 * Everything that changes from one Neovim release to the next is in that one file, rewritten by
 * {@code java releng/UpdateNeovimDownloads.java <version>} (docs/UPGRADING.md). Code holds no version,
 * URL, asset name or hash. Asset names are data too, because Neovim renames them now and then (0.10.4
 * renamed {@code nvim-linux64} to {@code nvim-linux-x86_64}).
 *
 * <h2>Why the hash is pinned</h2>
 * The plug-in installs only an archive whose SHA-256 equals the one in the file, whether it downloads
 * the archive or the user picks one ({@link NeovimInstaller}). The hash was compared with GitHub's
 * published digest when the file was written, so a changed or damaged archive, or a man-in-the-middle,
 * is refused instead of run. Example: {@code NeovimRelease.bundled().forPlatform(la)} gives
 * {@code nvim-linux-arm64.tar.gz}, 11367117 bytes, {@code 8f1f64a0…ee04c815}, from
 * {@code https://github.com/neovim/neovim/releases/download/v0.12.6/nvim-linux-arm64.tar.gz}.
 *
 * @param version   the Neovim version every archive holds
 * @param downloads the archives by platform code; a platform missing here is offered nothing
 */
public record NeovimRelease(NeovimVersion version, Map<String, Archive> downloads) {

    /** The data file's name, beside this class (in the bundle and in {@code tests/unit}). */
    public static final String FILE = "neovim-downloads.properties";

    /** Where every URL must point: Neovim's own GitHub releases, over https. */
    static final String URL_PREFIX = "https://github.com/neovim/neovim/releases/download/";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** The two archive formats Neovim publishes. */
    public enum Kind {
        /** {@code .tar.gz}: Linux and macOS. */
        TAR_GZ,
        /** {@code .zip}: Windows. */
        ZIP
    }

    /**
     * One release archive.
     *
     * @param platform the platform it is for
     * @param asset    its file name on GitHub, {@code nvim-linux-arm64.tar.gz}
     * @param url      where to download it
     * @param size     its size in bytes
     * @param sha256   its SHA-256, 64 lower-case hex digits
     */
    /**
     * One release archive.
     *
     * @param tree the {@link InstallTree tree hash} of the files inside it, which an unpacked
     *             install must match to be run (checked at every start)
     */
    public record Archive(NeovimPlatform platform, String asset, URI url, long size, String sha256, String tree) {

        /** The format, from the name. */
        public Kind kind() {
            return asset.endsWith(".zip") ? Kind.ZIP : Kind.TAR_GZ;
        }
    }

    private static volatile NeovimRelease bundled;

    /** The release in the plug-in's own data file (read once). */
    public static NeovimRelease bundled() {
        NeovimRelease r = bundled;
        if (r == null) {
            try (InputStream in = NeovimRelease.class.getResourceAsStream(FILE)) {
                if (in == null) {
                    throw new IllegalStateException(FILE + " is missing from the plug-in");
                }
                r = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (IllegalArgumentException e) {
                // Named, since NeovimVersion.MINIMUM is read from here when that class loads: the
                // error then surfaces as "could not initialize class NeovimVersion".
                throw new IllegalStateException("The plug-in's " + FILE + " is broken: " + e.getMessage(), e);
            }
            bundled = r;
        }
        return r;
    }

    /**
     * Reads and checks a data file.
     *
     * @throws IllegalArgumentException naming the first wrong or missing entry: the version is not
     *         {@code x.y.z}, a hash is not 64 hex digits, a size is not a positive number, or the
     *         URL does not point to Neovim's GitHub releases over https
     */
    public static NeovimRelease parse(Reader text) throws IOException {
        Properties p = new Properties();
        p.load(text);
        NeovimVersion version = NeovimVersion.fromVersionOutput("NVIM v" + required(p, "version"));
        if (version == null) {
            throw new IllegalArgumentException("version is not x.y.z: " + p.getProperty("version"));
        }
        String template = required(p, "url");
        Map<String, Archive> downloads = new LinkedHashMap<>();
        for (NeovimPlatform platform : NeovimPlatform.ALL) {
            String code = platform.code();
            if (p.getProperty(code + ".asset") == null) {
                continue;
            }
            String asset = required(p, code + ".asset");
            String sha = required(p, code + ".sha256").toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha).matches()) {
                throw new IllegalArgumentException(code + ".sha256 is not 64 hex digits: " + sha);
            }
            String tree = required(p, code + ".tree").toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(tree).matches()) {
                throw new IllegalArgumentException(code + ".tree is not 64 hex digits: " + tree);
            }
            long size;
            try {
                size = Long.parseLong(required(p, code + ".size"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(code + ".size is not a number: " + p.getProperty(code + ".size"));
            }
            if (size <= 0) {
                throw new IllegalArgumentException(code + ".size must be positive: " + size);
            }
            String url = template.replace("{version}", version.toString()).replace("{asset}", asset);
            if (!url.startsWith(URL_PREFIX)) {
                throw new IllegalArgumentException("url must start with " + URL_PREFIX + ": " + url);
            }
            downloads.put(code, new Archive(platform, asset, URI.create(url), size, sha, tree));
        }
        return new NeovimRelease(version, Map.copyOf(downloads));
    }

    /** The archive for {@code platform}, or empty if the release has none for it. */
    public Optional<Archive> forPlatform(NeovimPlatform platform) {
        return Optional.ofNullable(downloads.get(platform.code()));
    }

    /**
     * The archive with this SHA-256, for any platform: tells a user who picked the archive for another
     * platform which one they need.
     */
    public Optional<Archive> bySha256(String sha256) {
        String wanted = sha256.toLowerCase(Locale.ROOT);
        return downloads.values().stream().filter(a -> a.sha256().equals(wanted)).findFirst();
    }

    /** The release's page on GitHub, which lists every archive and its digest. */
    public URI releasePage() {
        return URI.create("https://github.com/neovim/neovim/releases/tag/v" + version);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(FILE + " has no " + key);
        }
        return value.strip();
    }
}

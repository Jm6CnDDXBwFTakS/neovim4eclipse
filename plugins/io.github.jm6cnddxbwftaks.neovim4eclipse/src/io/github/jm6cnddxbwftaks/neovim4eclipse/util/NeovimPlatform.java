// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A platform Neovim publishes a release archive for, named by a two-letter code: the operating
 * system's letter (l Linux, m macOS, w Windows) and the processor's (x x86_64, a arm64).
 *
 * <h2>Where the codes are used</h2>
 * <ul>
 *   <li>The plug-in's own Neovim lives in {@code neovim/<code>/} of its folder
 *       ({@link PluginFolders#neovimFolder()}): {@code neovim/la/bin/nvim} on Linux arm64. One folder
 *       per platform, so a home folder shared by an x86_64 and an arm64 machine keeps one Neovim for
 *       each.</li>
 *   <li>The keys of {@code neovim-downloads.properties} ({@link NeovimRelease}): {@code la.asset},
 *       {@code la.sha256}.</li>
 * </ul>
 * A third letter is kept free for a variant, for example {@code lxm} if Neovim ever publishes a musl
 * build. A platform without a code (32-bit, RISC-V, FreeBSD) is offered no download; a Neovim
 * installed some other way still works there.
 *
 * <h2>Which processor</h2>
 * Java reports the processor its own process runs as ({@code os.arch}). An x86_64 Eclipse on an
 * arm64 Mac or Windows machine runs under emulation, and a native arm64 Neovim is the better choice
 * there; {@link ProcessorProbe} asks the operating system for the real processor. Examples:
 *
 * <pre>
 *   NeovimPlatform.of("Linux", "amd64")        Optional[lx]
 *   NeovimPlatform.of("Mac OS X", "aarch64")   Optional[ma]
 *   NeovimPlatform.of("Windows 11", "amd64")   Optional[wx]
 *   NeovimPlatform.of("Linux", "riscv64")      Optional.empty
 * </pre>
 *
 * @param code        the code, {@code la}
 * @param os          the operating system for people, {@code Linux}
 * @param processor   the processor for people, {@code arm64}
 */
public record NeovimPlatform(String code, String os, String processor) {

    /** The six platforms, in the data file's order. */
    public static final List<NeovimPlatform> ALL = List.of(
            new NeovimPlatform("lx", "Linux", "x86_64"),
            new NeovimPlatform("la", "Linux", "arm64"),
            new NeovimPlatform("mx", "macOS", "x86_64"),
            new NeovimPlatform("ma", "macOS", "arm64"),
            new NeovimPlatform("wx", "Windows", "x86_64"),
            new NeovimPlatform("wa", "Windows", "arm64"));

    /** This process's platform by Java's own {@code os.name} and {@code os.arch}. */
    public static Optional<NeovimPlatform> current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * The platform for Java's names.
     *
     * @param osName {@code os.name}: {@code Linux}, {@code Mac OS X}, {@code Windows 11}
     * @param osArch {@code os.arch}, or a processor name from {@link ProcessorProbe}: {@code amd64},
     *               {@code x86_64}, {@code aarch64}, {@code arm64}
     */
    public static Optional<NeovimPlatform> of(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        char osLetter = os.startsWith("linux") ? 'l' : os.startsWith("mac") ? 'm' : os.startsWith("windows") ? 'w' : 0;
        char cpuLetter = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> 'x';
            case "aarch64", "arm64" -> 'a';
            default -> 0;
        };
        if (osLetter == 0 || cpuLetter == 0) {
            return Optional.empty();
        }
        return byCode("" + osLetter + cpuLetter);
    }

    /** The platform with this code, if it is one of {@link #ALL}. */
    public static Optional<NeovimPlatform> byCode(String code) {
        return ALL.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    /** Whether this is a Windows platform (a {@code .zip} archive, {@code nvim.exe}). */
    public boolean windows() {
        return code.startsWith("w");
    }

    /** {@code Linux arm64}, for messages. */
    public String describe() {
        return os + " " + processor;
    }

    @Override
    public String toString() {
        return code;
    }
}

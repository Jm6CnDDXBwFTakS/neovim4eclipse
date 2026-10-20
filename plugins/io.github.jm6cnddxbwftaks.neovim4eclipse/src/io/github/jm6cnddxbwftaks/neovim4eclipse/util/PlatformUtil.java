// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Locale;

/**
 * Operating-system detection (Windows / macOS / Linux-like).
 *
 * <p>The {@code String}-taking overloads exist so tests can exercise every OS
 * without changing the real {@code os.name} system property.
 */
public final class PlatformUtil {

    private static final String OS = System.getProperty("os.name", "");

    private PlatformUtil() {
    }

    public static boolean isWindows() {
        return isWindows(OS);
    }

    public static boolean isMac() {
        return isMac(OS);
    }

    public static boolean isLinux() {
        return isLinux(OS);
    }

    /** Human-readable family name for logs: {@code windows}, {@code macos}, {@code linux} or {@code unknown}. */
    public static String osFamily() {
        return osFamily(OS);
    }

    static boolean isWindows(String osName) {
        // startsWith, not contains("win"): "Darwin" contains "win" but is not Windows.
        return lower(osName).startsWith("windows");
    }

    static boolean isMac(String osName) {
        String os = lower(osName);
        return os.startsWith("mac") || os.contains("darwin");
    }

    static boolean isLinux(String osName) {
        String os = lower(osName);
        return os.contains("linux") || os.contains("unix") || os.contains("freebsd") || os.contains("sunos");
    }

    static String osFamily(String osName) {
        if (isWindows(osName)) {
            return "windows";
        }
        if (isMac(osName)) {
            return "macos";
        }
        if (isLinux(osName)) {
            return "linux";
        }
        return "unknown";
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}

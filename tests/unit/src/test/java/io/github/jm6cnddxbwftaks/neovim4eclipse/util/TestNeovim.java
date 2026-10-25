// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The Neovim the integration tests start: {@code -Dnvim.path=...} if given (a folder or the
 * absolute path of the program, as in the preference; a command name is refused), else blank.
 * Blank is searched exactly as the plug-in searches an empty preference
 * ({@link NvimExecutable#resolve}): {@code ~/.local/share/neovim4eclipse-plugin/neovim} first, then {@code PATH}, then the
 * usual install folders. So a Neovim extracted into {@code ~/.local/share/neovim4eclipse-plugin/neovim} needs no {@code -D}.
 *
 * <p>Example: {@code ./mvnw test -f tests/unit/pom.xml -Pintegration} finds
 * {@code ~/.local/share/neovim4eclipse-plugin/neovim/nvim-linux-x86_64/bin/nvim}; with
 * {@code -Dnvim.path=/opt/nvim-0.13.0} that one is used instead.
 */
public final class TestNeovim {

    /** The preference-style value to start Neovim with; see the class comment. */
    public static final String PATH = configured(System.getProperty("nvim.path"));

    private TestNeovim() {
    }

    /**
     * The property, or "" when it is unset. Maven passes an undefined {@code ${nvim.path}} on as the
     * literal text, or as {@code null}, depending on the plug-in; both mean unset.
     */
    static String configured(String property) {
        if (property == null || property.isBlank() || property.equals("null") || property.startsWith("${")) {
            return "";
        }
        return property.strip();
    }
}

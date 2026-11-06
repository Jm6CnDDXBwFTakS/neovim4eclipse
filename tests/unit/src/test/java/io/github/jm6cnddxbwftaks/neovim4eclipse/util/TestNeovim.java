// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The Neovim the integration tests start: {@code -Dnvim.path=...}, the absolute path of the program
 * ({@code <release>/bin/nvim}). The integration, timeoutReliant and all profiles require it (the
 * enforcer rule in this module's {@code pom.xml}): these tests run in a data folder of their own
 * under {@code target/}, without the dedicated Neovim a developer's machine has, as on a CI runner.
 *
 * <p>Example: {@code ./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/opt/nvim/bin/nvim},
 * or {@code java releng/tools/LikeCi.java}, which installs the pinned release under
 * {@code target/} and passes it.
 */
public final class TestNeovim {

    /** The preference-style value to start Neovim with; see the class comment. */
    public static final String PATH = configured(System.getProperty("nvim.path"));

    private TestNeovim() {
    }

    /**
     * Stops the test if there is no Neovim to start: a failure, not a skip, when {@code -Dnvim.path}
     * names one that is not there (a typo or a missing download would otherwise give a green run
     * with nothing tested); a skip, with the reason, only when it is not set at all (an IDE run of
     * one test class).
     */
    public static void require() {
        org.junit.jupiter.api.Assumptions.assumeTrue(!PATH.isEmpty(), "no -Dnvim.path given");
        org.junit.jupiter.api.Assertions.assertTrue(NvimExecutable.looksAvailable(PATH),
                "no Neovim program at -Dnvim.path=" + PATH);
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

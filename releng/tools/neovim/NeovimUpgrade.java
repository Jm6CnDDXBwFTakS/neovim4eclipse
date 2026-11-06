// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package neovim;

/**
 * Moves the plug-in to a new Neovim release: {@code java releng/tools/neovim/NeovimUpgrade.java 0.13.0}.
 * What it does and why: {@link NeovimUpgradeTool}; when to move: docs/UPGRADING.md, "Neovim".
 *
 * <p>This file only starts the tool, for the reason {@code release/Release.java} gives: the tool is
 * several source files, which {@code java} runs without a build only from Java 22, and on Java 21
 * this gets as far as a clear message.
 */
public final class NeovimUpgrade {

    /** The first Java whose {@code java} launcher runs a program of several source files. */
    static final int NEEDS_JAVA = 22;

    private NeovimUpgrade() {
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() < NEEDS_JAVA) {
            System.err.println("NeovimUpgrade: needs Java " + NEEDS_JAVA + " or newer to run (this is Java "
                    + Runtime.version().feature() + "); the plug-in itself still builds with 21. "
                    + "Install a newer JDK and run it with that one's java.");
            System.exit(2);
        }
        try {
            Class.forName("neovim.NeovimUpgradeTool").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

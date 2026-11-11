// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

/**
 * Moves the build's libraries, Maven plug-ins and GitHub Actions to their newest releases:
 * {@code java releng/tools/dependencies/Dependencies.java}.
 * What it does and why: {@link DependenciesTool}; when to move: docs/UPGRADING.md.
 *
 * <p>This file only starts the tool, for the reason {@code release/Release.java} gives: the tool is
 * several source files, which {@code java} runs without a build only from Java 22, and on Java 21
 * this gets as far as a clear message.
 */
public final class Dependencies {

    /** The first Java whose {@code java} launcher runs a program of several source files. */
    static final int NEEDS_JAVA = 22;

    private Dependencies() {
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() < NEEDS_JAVA) {
            System.err.println("Dependencies: needs Java " + NEEDS_JAVA + " or newer to run (this is Java "
                    + Runtime.version().feature() + "); the plug-in itself still builds with 21. "
                    + "Install a newer JDK and run it with that one's java.");
            System.exit(2);
        }
        try {
            Class.forName("dependencies.DependenciesTool").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

/**
 * Makes a release, step by step: {@code java releng/tools/release/Release.java} (the status and the
 * next step), or {@code … Release.java go} (every step from there, asking before each side effect).
 * The steps, the flags and why each step does what it does: {@link ReleaseTool}; the release
 * process itself: CONTRIBUTING.md, "Releasing".
 *
 * <h2>Why this file only starts the tool</h2>
 * The tool is several source files ({@code releng/tools/release/} and the helpers in
 * {@code releng/tools/common/}), run without a build step: from Java 22, {@code java} compiles the
 * files a source program uses from the folders of their packages (JEP 458, "multi-file source-code
 * programs"). Java 21 compiles only the one file it is given, so a direct use of
 * {@link ReleaseTool} here would end in a compiler error about a missing class. This file names
 * that class only by its name, as text, so on Java 21 the launch gets as far as a clear message.
 *
 * <p>The code itself uses only Java 21, the plug-in's level, so that {@code tests/unit} (built for
 * Java 21) can compile and test it.
 */
public final class Release {

    /** The first Java whose {@code java} launcher runs a program of several source files. */
    static final int NEEDS_JAVA = 22;

    private Release() {
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() < NEEDS_JAVA) {
            System.err.println("Release: needs Java " + NEEDS_JAVA + " or newer to run (this is Java "
                    + Runtime.version().feature() + "); the plug-in itself still builds with 21. "
                    + "Install a newer JDK and run it with that one's java.");
            System.exit(2);
        }
        try {
            Class.forName("release.ReleaseTool").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

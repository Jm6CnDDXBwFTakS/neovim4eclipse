// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package javarelease;

/**
 * Raises the Java release the plug-in needs:
 * {@code java releng/tools/javarelease/JavaUpgrade.java 25 --java <a JDK 25's home>}.
 * What it does and why: {@link JavaUpgradeTool}; when to raise it: docs/UPGRADING.md, "JDK / Java release".
 *
 * <p>This file only starts the tool, for the reason {@code release/Release.java} gives: the tool is
 * several source files, which {@code java} runs without a build only from Java 22, and on Java 21
 * this gets as far as a clear message.
 */
public final class JavaUpgrade {

    /** The first Java whose {@code java} launcher runs a program of several source files. */
    static final int NEEDS_JAVA = 22;

    private JavaUpgrade() {
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() < NEEDS_JAVA) {
            System.err.println("JavaUpgrade: needs Java " + NEEDS_JAVA + " or newer to run (this is Java "
                    + Runtime.version().feature() + "). Install a newer JDK and run it with that one's java.");
            System.exit(2);
        }
        try {
            Class.forName("javarelease.JavaUpgradeTool").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

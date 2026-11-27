// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package runner;

/**
 * Shows or moves the Ubuntu release CI's Linux jobs run on:
 * {@code java releng/tools/runner/UbuntuUpgrade.java} (status) or
 * {@code java releng/tools/runner/UbuntuUpgrade.java 28.04} (move). What it does and why:
 * {@link UbuntuUpgradeTool}; when to move it: docs/UPGRADING.md, "GitHub Actions and the runner".
 *
 * <p>This file only starts the tool, for the reason {@code release/Release.java} gives: the tool is
 * several source files, which {@code java} runs without a build only from Java 22, and on Java 21
 * this gets as far as a clear message.
 */
public final class UbuntuUpgrade {

    /** The first Java whose {@code java} launcher runs a program of several source files. */
    static final int NEEDS_JAVA = 22;

    private UbuntuUpgrade() {
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() < NEEDS_JAVA) {
            System.err.println("UbuntuUpgrade: needs Java " + NEEDS_JAVA + " or newer to run (this is Java "
                    + Runtime.version().feature() + "). Install a newer JDK and run it with that one's java.");
            System.exit(2);
        }
        try {
            Class.forName("runner.UbuntuUpgradeTool").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

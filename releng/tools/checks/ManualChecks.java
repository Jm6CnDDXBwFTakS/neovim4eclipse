// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

/**
 * The manual checks before a release: {@code java releng/tools/checks/ManualChecks.java due} (the
 * cards this release needs), {@code list}, {@code setup}, {@code launch <card>}, {@code json}. What
 * each does and why: {@link ManualChecksTool}; the cards: {@code docs/MANUAL_CHECKS.md}.
 *
 * <p>This file only starts the tool, for the reason {@code release/Release.java} gives: the tool is
 * several source files (it shares the Eclipse copy in {@code common/EclipseCopy} with
 * {@code InstallCheck}), which {@code java} runs without a build only from Java 22, and on Java 21
 * this gets as far as a clear message.
 */
public final class ManualChecks {

    /** The first Java whose {@code java} launcher runs a program of several source files. */
    static final int NEEDS_JAVA = 22;

    private ManualChecks() {
    }

    public static void main(String[] args) throws Throwable {
        if (Runtime.version().feature() < NEEDS_JAVA) {
            System.err.println("ManualChecks: needs Java " + NEEDS_JAVA + " or newer to run (this is Java "
                    + Runtime.version().feature() + "); the plug-in itself still builds with 21. "
                    + "Install a newer JDK and run it with that one's java.");
            System.exit(2);
        }
        try {
            Class.forName("checks.ManualChecksTool").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import java.time.Duration;

/**
 * How long a test waits for something it expects to happen, the same for every test module.
 *
 * <p>Why one value: a test that waits for Neovim, a thread or the event loop does not know how busy
 * the machine is. On a loaded CI runner an answer that usually takes 50 ms can take seconds, and a
 * test with its own short limit (2 s here, 5 s there) failed although nothing was wrong. One generous
 * limit, used by every wait, removes that guesswork. It costs nothing when the test passes: a wait
 * returns as soon as its condition holds, so the limit is only reached when something is broken.
 *
 * <p>Why a system property: to give a slow machine (or a run pinned to one core, see
 * CONTRIBUTING.md, "Tests on a loaded machine") more time without changing code, or a fast one less,
 * so that a hang fails sooner:
 *
 * <pre>
 * ./mvnw verify -Pui -Dtest.wait.seconds=60
 * </pre>
 *
 * The test POMs pass {@value #PROPERTY} on to the test JVM, as they do {@code nvim.path}. Every test
 * module compiles this file: it lives in the UI test fragment's {@code shared/} folder, which the
 * fragment lists in its build.properties ({@code source..}) and tests/unit, tests/text and tests/swt
 * add as a test source in their POMs.
 *
 * <p>Not for the opposite check, "nothing else happened": waiting the full limit there would make
 * every passing run slow. Those tests ask Neovim a question instead and check once the answer is
 * back, because Neovim sends its notifications in order before that answer (see
 * {@code NeovimBarrier} in tests/unit).
 */
public final class TestWait {

    /** The system property: whole seconds, for example {@code -Dtest.wait.seconds=60}. */
    public static final String PROPERTY = "test.wait.seconds";

    /** The limit when the property is not set: long enough for a loaded two-core CI runner. */
    public static final int DEFAULT_SECONDS = 15;

    private TestWait() {
    }

    /** The limit in milliseconds, for loops that compare against {@code System.currentTimeMillis()}. */
    public static long millis() {
        return seconds(System.getProperty(PROPERTY)) * 1000L;
    }

    /** The limit as a {@link Duration}, for APIs that take one. */
    public static Duration duration() {
        return Duration.ofMillis(millis());
    }

    /**
     * Parses the property's text. Unset means the default: Maven passes an undefined
     * {@code ${test.wait.seconds}} on as that literal text, as "null" or as nothing, depending on the
     * plug-in (as for {@code nvim.path} in {@code TestNeovim}).
     *
     * @throws IllegalArgumentException for text that is not a positive whole number, so a typo such
     *         as {@code -Dtest.wait.seconds=30s} stops the run instead of silently using 15
     */
    static long seconds(String text) {
        if (text == null || text.isBlank() || text.equals("null") || text.startsWith("${")) {
            return DEFAULT_SECONDS;
        }
        try {
            long seconds = Long.parseLong(text.trim());
            if (seconds > 0) {
                return seconds;
            }
        } catch (NumberFormatException e) {
            // reported below with the property's name
        }
        throw new IllegalArgumentException(PROPERTY + " must be a positive number of seconds, not '" + text + "'");
    }
}

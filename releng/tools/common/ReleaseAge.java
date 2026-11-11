// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.time.Duration;
import java.time.Instant;

/**
 * The upgrade tools' week-old rule: a release younger than {@link #MIN_AGE} is not moved to
 * unless {@code --allow-young}. A broken release is usually replaced within days, and a replaced
 * download would no longer match the hash a tool wrote down. The Maven tool refuses a young
 * release; the Dependency tool takes the newest one that is old enough. ({@code
 * releng/UpdateNeovimDownloads.java} keeps its own copy: it is a single file run without the
 * others.)
 */
public final class ReleaseAge {

    /** How old a release must be. */
    public static final Duration MIN_AGE = Duration.ofDays(7);

    private ReleaseAge() {
    }

    /** Whether something published at {@code published} is younger than {@link #MIN_AGE} on {@code now}. */
    public static boolean isYoung(Instant published, Instant now) {
        return Duration.between(published, now).compareTo(MIN_AGE) < 0;
    }
}

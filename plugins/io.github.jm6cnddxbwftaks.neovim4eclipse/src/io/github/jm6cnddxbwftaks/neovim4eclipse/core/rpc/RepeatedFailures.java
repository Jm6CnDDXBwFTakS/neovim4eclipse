// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs a handler's failure in full the first time, then only how often it happened.
 *
 * <p>Why: {@link RpcChannel} goes on reading after a handler of Neovim's notifications fails, so
 * that one bug does not stop everything after it. But the same bug then fails again with every
 * message of that kind. A {@code NoSuchMethodError} from a stale class in the redraw handler (see
 * AGENTS.md, "Pitfalls") fails dozens of times a second while the screen changes; a WARNING with a
 * stack trace each time would flood Eclipse's Error Log ({@code .metadata/.log}) and hide the
 * first, useful record.
 *
 * <p>So each failure is counted by what failed and the exception's class: the first is logged at
 * WARNING with its stack trace, the 10th, 100th, 1000th... at WARNING as a count only, and every
 * other one at FINE (with its stack trace, for a developer who turns FINE on). Example: the
 * notification {@code redraw} failing with {@code NoSuchMethodError} 250 times gives three WARNING
 * records: the first failure, "10 times", "100 times". A different exception, or another
 * notification, is logged in full again the first time.
 */
final class RepeatedFailures {

    private final Logger log;
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    RepeatedFailures(Logger log) {
        this.log = log;
    }

    /**
     * Logs {@code failure} of {@code what} (such as "Notification redraw from Neovim failed").
     *
     * @return how often this failure has happened, this time included
     */
    long log(String what, Throwable failure) {
        long count = counts.computeIfAbsent(what + " / " + failure.getClass().getName(), key -> new AtomicLong())
                .incrementAndGet();
        if (count == 1) {
            log.log(Level.WARNING, what, failure);
        } else if (isPowerOfTen(count)) {
            log.warning(() -> what + ": " + count + " times so far with " + failure.getClass().getName()
                    + " (logged in full the first time; each one at FINE)");
        } else {
            log.log(Level.FINE, what + " (" + count + " times)", failure);
        }
        return count;
    }

    /** 10, 100, 1000, ...; not 1. */
    static boolean isPowerOfTen(long n) {
        if (n < 10) {
            return false;
        }
        while (n % 10 == 0) {
            n /= 10;
        }
        return n == 1;
    }
}

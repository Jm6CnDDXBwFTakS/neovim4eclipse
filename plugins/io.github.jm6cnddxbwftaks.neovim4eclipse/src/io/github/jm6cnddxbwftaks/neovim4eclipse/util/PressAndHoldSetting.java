// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Whether macOS's press-and-hold is on: holding a letter shows its accent menu instead of repeating
 * it ({@link PressAndHold}). It is on unless turned off, and many Vim users turn it off to get key
 * repeat back:
 * <pre>
 *   defaults write -g ApplePressAndHoldEnabled -bool false
 * </pre>
 * Then a held letter repeats in the {@code :} box too, as before.
 *
 * <h2>How</h2>
 * {@code /usr/bin/defaults read -g ApplePressAndHoldEnabled} prints {@code 0} or {@code 1}, and fails
 * when the setting was never written (then it is on). The program is run by its full path, once per
 * Eclipse session on a background thread, as {@link ProcessorProbe} does. {@link #known()} never waits:
 * until the answer is in, or if asking fails, press-and-hold counts as on, macOS's default. Only the
 * system-wide setting is read; a setting for one application ({@code defaults write <app id> ...})
 * is not.
 */
public final class PressAndHoldSetting {

    /** macOS's {@code defaults}, by its full path (see {@link ProcessorProbe}). */
    static final String DEFAULTS = "/usr/bin/defaults";

    private static volatile CompletableFuture<Boolean> answer;

    private PressAndHoldSetting() {
    }

    /** Whether press-and-hold is on, as far as known yet; starts asking if nobody has. Never waits. */
    public static boolean known() {
        CompletableFuture<Boolean> a = answer;
        if (a == null) {
            synchronized (PressAndHoldSetting.class) {
                a = answer;
                if (a == null) {
                    a = CompletableFuture.supplyAsync(() -> probe(ProcessorProbe::run));
                    answer = a;
                }
            }
        }
        return !a.isDone() || a.join();
    }

    /**
     * Testable core: the setting from what {@code defaults} prints.
     *
     * @param run runs a command and gives its output, or {@code null} if it failed
     */
    static boolean probe(Function<List<String>, String> run) {
        return parse(run.apply(List.of(DEFAULTS, "read", "-g", "ApplePressAndHoldEnabled")));
    }

    /** {@code 0}, {@code false} or {@code NO} turn it off; anything else, or nothing, leaves it on. */
    static boolean parse(String output) {
        if (output == null) {
            return true;
        }
        String value = output.strip().toLowerCase(Locale.ROOT);
        return !(value.equals("0") || value.equals("false") || value.equals("no"));
    }
}

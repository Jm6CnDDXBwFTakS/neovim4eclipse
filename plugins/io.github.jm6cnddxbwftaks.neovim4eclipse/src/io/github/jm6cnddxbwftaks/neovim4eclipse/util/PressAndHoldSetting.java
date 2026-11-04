// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Whether macOS's press-and-hold is on: holding a letter shows its accent menu instead of repeating
 * it ({@link PressAndHold}). It is on unless turned off, and many Vim users turn it off to get key
 * repeat back, for every application or for Eclipse alone:
 * <pre>
 *   defaults write -g ApplePressAndHoldEnabled -bool false
 *   defaults write epp.package.java ApplePressAndHoldEnabled -bool false
 * </pre>
 * Then a held letter repeats in the {@code :} box too, as before. The second line names Eclipse by
 * its application id, which differs between the Eclipse packages ({@code epp.package.java},
 * {@code epp.package.committers}, ...): it is {@code CFBundleIdentifier} in
 * {@code Eclipse.app/Contents/Info.plist}.
 *
 * <h2>How</h2>
 * As macOS does for an application: its own setting if it has one, else the system-wide one.
 * {@code /usr/bin/defaults read <app id> ApplePressAndHoldEnabled}, then
 * {@code defaults read -g ApplePressAndHoldEnabled}, print {@code 0} or {@code 1}, and fail when the
 * setting was never written (then it is on). The application id is the one macOS gives a program it
 * starts as an application, in the environment variable {@code __CFBundleIdentifier}; without it
 * (Eclipse started some other way), only the system-wide setting is read. The program is run by its
 * full path, once per Eclipse session on a background thread, as {@link ProcessorProbe} does.
 * {@link #known()} never waits: until the answer is in, or if asking fails, press-and-hold counts as
 * on, macOS's default.
 */
public final class PressAndHoldSetting {

    /** macOS's {@code defaults}, by its full path (see {@link ProcessorProbe}). */
    static final String DEFAULTS = "/usr/bin/defaults";
    static final String KEY = "ApplePressAndHoldEnabled";
    /** An application id: letters, digits, dots, hyphens; not an option ({@code -g}). */
    private static final Pattern APP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*");

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
                    String appId = System.getenv("__CFBundleIdentifier");
                    a = CompletableFuture.supplyAsync(() -> probe(ProcessorProbe::run, appId));
                    answer = a;
                }
            }
        }
        return !a.isDone() || a.join();
    }

    /**
     * Testable core: the setting from what {@code defaults} prints.
     *
     * @param run   runs a command and gives its output, or {@code null} if it failed
     * @param appId the application's id, or {@code null}
     */
    static boolean probe(Function<List<String>, String> run, String appId) {
        if (appId != null && APP_ID.matcher(appId).matches()) {
            String own = run.apply(List.of(DEFAULTS, "read", appId, KEY));
            if (own != null) {
                return parse(own);
            }
        }
        return parse(run.apply(List.of(DEFAULTS, "read", "-g", KEY)));
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

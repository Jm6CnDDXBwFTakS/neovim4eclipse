// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A plain release's number, as libraries, Maven plug-ins and GitHub Actions write them: two to
 * four numbers with dots, {@code 3.6.1}, {@code 0.8.16}, {@code 1.8} or, from a tag,
 * {@code v6.2.0}. Anything else is not a release the tool moves to: {@code 4.0.0-beta-2},
 * {@code 3.6.0-M1} and {@code 2.0.0-rc.1} are previews, which Dependabot skips too.
 *
 * <p>{@code common/Version} is stricter (exactly three numbers) because it reads the plug-in's
 * own version; other projects' numbers vary, so this one is a list. Missing places count as 0:
 * {@code 3.6} and {@code 3.6.0} are the same release.
 */
public record ReleaseNumber(List<Integer> parts) implements Comparable<ReleaseNumber> {

    /** How far a move goes, in Dependabot's words ({@code update-types} in {@code dependabot.yml}). */
    public enum UpdateType {
        MAJOR("version-update:semver-major"), MINOR("version-update:semver-minor"), PATCH("version-update:semver-patch");

        /** The name {@code dependabot.yml} uses. */
        public final String yaml;

        UpdateType(String yaml) {
            this.yaml = yaml;
        }

        /** The type {@code dependabot.yml} names, or empty. */
        public static Optional<UpdateType> ofYaml(String name) {
            for (UpdateType t : values()) {
                if (t.yaml.equals(name)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    public ReleaseNumber {
        parts = List.copyOf(parts);
    }

    /** {@code 3.6.1} or {@code v6.2.0} as a release; empty for a preview or anything else. */
    public static Optional<ReleaseNumber> parse(String text) {
        String t = text.strip();
        if (t.startsWith("v")) {
            t = t.substring(1);
        }
        // At most 9 digits a place, so that every place fits an int.
        if (!t.matches("(0|[1-9]\\d{0,8})(\\.(0|[1-9]\\d{0,8})){1,3}")) {
            return Optional.empty();
        }
        List<Integer> parts = new ArrayList<>();
        for (String p : t.split("\\.")) {
            parts.add(Integer.parseInt(p));
        }
        return Optional.of(new ReleaseNumber(parts));
    }

    /** The first number: a move that changes it is a major one. */
    public int major() {
        return parts.get(0);
    }

    private int at(int i) {
        return i < parts.size() ? parts.get(i) : 0;
    }

    /**
     * What kind of move {@code this} to {@code newer} is, as Dependabot counts it: by the first
     * place that changes, so 0.8.15 to 0.9.0 is minor and 5.14.4 to 6.0.0 is major.
     */
    public UpdateType typeTo(ReleaseNumber newer) {
        if (newer.at(0) != at(0)) {
            return UpdateType.MAJOR;
        }
        return newer.at(1) != at(1) ? UpdateType.MINOR : UpdateType.PATCH;
    }

    @Override
    public int compareTo(ReleaseNumber other) {
        for (int i = 0; i < Math.max(parts.size(), other.parts.size()); i++) {
            int c = Integer.compare(at(i), other.at(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Whether the two name the same release ({@code 3.6} and {@code 3.6.0} do). */
    public boolean same(ReleaseNumber other) {
        return compareTo(other) == 0;
    }

    @Override
    public String toString() {
        return parts.stream().map(String::valueOf).collect(Collectors.joining("."));
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package plugins;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import common.ToolFailure;

/**
 * The pins of the Neovim plug-ins the tests run ({@code releng/test-plugins.properties}): for each
 * one its repository, the commit to take, the SHA-256 of that commit's archive, and its license.
 *
 * <p><b>Why pins.</b> A jump plug-in is somebody else's Lua, and the tests run it in a Neovim on
 * the developer's machine and in CI. So the tests never take "the newest": they take one commit
 * the maintainer has looked at, and the archive must have the hash recorded with it. Moving to a
 * newer commit is a change of one entry of that file, reviewed like any other change.
 *
 * <p>Pure (text in, values out); unit-tested in {@code PinsTest}. {@link TestPluginsTool} does the
 * downloading.
 */
public final class Pins {

    /** A name is used as a folder name and in a property key: letters, digits, {@code .}, {@code _}, {@code -}. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** Only https, and no characters that would need escaping in a URL or mean something to a shell. */
    private static final Pattern REPOSITORY = Pattern.compile("https://[A-Za-z0-9.-]+(/[A-Za-z0-9._-]+)+");
    private static final List<String> FIELDS = List.of("repository", "commit", "sha256", "license");

    private Pins() {
    }

    /**
     * One pin. Example: {@code Pin("flash", "https://github.com/folke/flash.nvim", "5f0f…",
     * "9a1c…", "Apache-2.0")}.
     *
     * @param name       the folder it is unpacked into, and the start of its keys
     * @param repository where it lives, an https URL without a trailing slash
     * @param commit     the full commit id to take
     * @param sha256     of {@link #archiveUrl()}'s bytes, lowercase hex
     * @param license    its SPDX name, as read in the repository at that commit
     */
    public record Pin(String name, String repository, String commit, String sha256, String license) {

        /**
         * The ZIP of the commit's files: GitHub and Codeberg (Forgejo) both serve
         * {@code <repository>/archive/<commit>.zip}. A ZIP, because Java unpacks one without a
         * library.
         */
        public String archiveUrl() {
            return repository + "/archive/" + commit + ".zip";
        }

        /** The marker written beside the unpacked files: what they were unpacked from. */
        public String marker() {
            return commit + " " + sha256;
        }
    }

    /** Stops the tool unless {@code commit} is a full commit id: it goes into a URL and a file name. */
    public static void checkCommit(String commit) {
        check(COMMIT, commit, "the commit", "a full commit id, 40 hex digits");
    }

    /**
     * The pins in the file's text, in the file's order. Lines are {@code name.field=value};
     * {@code #} starts a comment. A plug-in with a field missing, a value of the wrong form or an
     * unknown field stops the tool: half a pin must not be mistaken for a whole one.
     */
    public static List<Pin> parse(String text) {
        Map<String, Map<String, String>> byName = new LinkedHashMap<>();
        int number = 0;
        for (String raw : text.split("\\R", -1)) {
            number++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            int dot = eq < 0 ? -1 : line.lastIndexOf('.', eq);
            if (eq < 0 || dot <= 0) {
                throw new ToolFailure("line " + number + " is not name.field=value: " + line);
            }
            String name = line.substring(0, dot);
            String field = line.substring(dot + 1, eq).strip();
            if (!NAME.matcher(name).matches() || !FIELDS.contains(field)) {
                throw new ToolFailure("line " + number + ": a key is <name>.<field>, the field one of " + FIELDS + ": " + line);
            }
            if (byName.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(field, line.substring(eq + 1).strip()) != null) {
                throw new ToolFailure("line " + number + ": " + name + "." + field + " is given twice");
            }
        }
        List<Pin> pins = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : byName.entrySet()) {
            String name = e.getKey();
            Map<String, String> f = e.getValue();
            for (String field : FIELDS) {
                if (f.getOrDefault(field, "").isEmpty()) {
                    throw new ToolFailure(name + "." + field + " is missing");
                }
            }
            Pin pin = new Pin(name, f.get("repository"), f.get("commit"), f.get("sha256"), f.get("license"));
            check(REPOSITORY, pin.repository(), name + ".repository", "an https URL of a repository, no trailing slash");
            check(COMMIT, pin.commit(), name + ".commit", "a full commit id, 40 hex digits");
            check(SHA256, pin.sha256(), name + ".sha256", "64 hex digits, lowercase");
            pins.add(pin);
        }
        return List.copyOf(pins);
    }

    private static void check(Pattern form, String value, String key, String what) {
        if (!form.matcher(value).matches()) {
            throw new ToolFailure(key + " is not " + what + ": " + value);
        }
    }

    /** The pin named {@code name}, or a failure that lists the names there are. */
    public static Pin find(List<Pin> pins, String name) {
        return pins.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow(
                () -> new ToolFailure("no pin named " + name + "; there are " + pins.stream().map(Pin::name).toList()));
    }

    /**
     * {@code text} with {@code name}'s commit and hash replaced,
     * every other line as it was (comments, order, the other pins). Example: moving flash
     * rewrites the two lines {@code flash.commit=…} and {@code flash.sha256=…} and nothing else.
     */
    public static String withPin(String text, String name, String commit, String sha256) {
        find(parse(text), name); // the name must exist
        checkCommit(commit);
        check(SHA256, sha256, "the new hash", "64 hex digits, lowercase");
        String out = replaceValue(text, name + ".commit", commit);
        return replaceValue(out, name + ".sha256", sha256);
    }

    private static String replaceValue(String text, String key, String value) {
        return Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*=).*$").matcher(text).replaceFirst("$1" + value);
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import common.ToolFailure;

import dependencies.ReleaseNumber.UpdateType;

/**
 * The rules in {@code .github/dependabot.yml}, which the tool follows as Dependabot would: its
 * {@code groups} (which updates share a commit) and its {@code ignore} entries (which updates are
 * not made). The file stays the one place for both, so the tool and Dependabot cannot disagree.
 * Example, the part of the file the tool reads:
 * <pre>
 *   updates:
 *     - package-ecosystem: maven
 *       groups:
 *         build-tools:
 *           patterns:
 *             - "org.apache.maven.plugins:*"
 *       ignore:
 *         - dependency-name: "*"
 *           update-types: ["version-update:semver-major"]
 *         - dependency-name: "org.eclipse.platform:*"
 * </pre>
 * With these, {@code org.apache.maven.plugins:maven-surefire-plugin} 3.6.0 to 3.6.1 goes in the
 * {@code build-tools} commit, JUnit 5 to 6 is not made (a major), and the Eclipse jars are never
 * moved. A dependency's name is {@code groupId:artifactId} for Maven, {@code owner/repo} for an
 * action; {@code *} in a pattern stands for any text.
 *
 * <p>The file is read by a small reader for the part of YAML it uses (nested maps and lists,
 * {@code ["a", "b"]} lists, plain and quoted words), not a YAML library: the tools run from source
 * with no dependencies. Any key the tool does not know stops it, since a rule it skipped (an
 * {@code allow} list, a {@code cooldown}, {@code versions} to ignore) would make it update what
 * Dependabot would not.
 */
public final class DependabotRules {

    /** The repository path of the file. */
    public static final String FILE = ".github/dependabot.yml";

    /** A group: the dependencies whose names match one of {@code patterns} share a commit. */
    public record Group(String name, List<String> patterns) {
    }

    /** An ignore entry: no update of a matching dependency of these {@code types} (all of them when empty). */
    public record Ignore(String dependency, Set<UpdateType> types) {
    }

    /** One {@code package-ecosystem} entry: {@code maven} or {@code github-actions}. */
    public record Ecosystem(String name, List<Group> groups, List<Ignore> ignores) {

        /** The first group whose pattern matches {@code dependency}, as Dependabot takes it. */
        public Optional<String> groupOf(String dependency) {
            for (Group g : groups) {
                if (g.patterns().stream().anyMatch(p -> matches(p, dependency))) {
                    return Optional.of(g.name());
                }
            }
            return Optional.empty();
        }

        /** Whether an update of {@code dependency} of {@code type} is ignored. */
        public boolean ignores(String dependency, UpdateType type) {
            return ignores.stream().anyMatch(i -> matches(i.dependency(), dependency) && (i.types().isEmpty() || i.types().contains(type)));
        }

        /** Whether every update of {@code dependency} is ignored (an entry without {@code update-types}). */
        public boolean ignoresAll(String dependency) {
            return ignores.stream().anyMatch(i -> matches(i.dependency(), dependency) && i.types().isEmpty());
        }
    }

    /** Keys of an {@code updates} entry that change nothing about which versions are taken. */
    private static final Set<String> HARMLESS = Set.of("package-ecosystem", "directory", "directories", "schedule",
            "open-pull-requests-limit", "labels", "reviewers", "assignees", "milestone", "commit-message",
            "pull-request-branch-name", "rebase-strategy", "target-branch");

    private final Map<String, Ecosystem> ecosystems;

    private DependabotRules(Map<String, Ecosystem> ecosystems) {
        this.ecosystems = ecosystems;
    }

    /** The entry for {@code name} ({@code maven}, {@code github-actions}), or empty when the file has none. */
    public Optional<Ecosystem> ecosystem(String name) {
        return Optional.ofNullable(ecosystems.get(name));
    }

    /** Whether {@code name} matches {@code pattern}, where {@code *} stands for any text. */
    public static boolean matches(String pattern, String name) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return name.matches(regex.toString());
    }

    /** Reads the file's text; stops the tool on anything it cannot follow exactly. */
    public static DependabotRules parse(String yaml) {
        Object root = Yaml.parse(yaml);
        Map<String, Object> top = map(root, "the file");
        Object version = top.get("version");
        if (!"2".equals(version)) {
            throw failure("version: 2 expected, found " + version);
        }
        Map<String, Ecosystem> ecosystems = new LinkedHashMap<>();
        for (Object entry : list(top.get("updates"), "updates")) {
            Map<String, Object> update = map(entry, "an updates entry");
            String name = word(update.get("package-ecosystem"), "package-ecosystem");
            List<Group> groups = new ArrayList<>();
            List<Ignore> ignores = new ArrayList<>();
            for (Map.Entry<String, Object> e : update.entrySet()) {
                switch (e.getKey()) {
                    case "groups" -> {
                        for (Map.Entry<String, Object> g : map(e.getValue(), "groups").entrySet()) {
                            Map<String, Object> body = map(g.getValue(), "group " + g.getKey());
                            onlyKeys(body, Set.of("patterns"), "group " + g.getKey());
                            groups.add(new Group(g.getKey(), words(body.get("patterns"), "patterns of " + g.getKey())));
                        }
                    }
                    case "ignore" -> {
                        for (Object i : list(e.getValue(), "ignore")) {
                            Map<String, Object> body = map(i, "an ignore entry");
                            onlyKeys(body, Set.of("dependency-name", "update-types"), "an ignore entry");
                            Set<UpdateType> types = EnumSet.noneOf(UpdateType.class);
                            if (body.containsKey("update-types")) {
                                for (String t : words(body.get("update-types"), "update-types")) {
                                    types.add(UpdateType.ofYaml(t).orElseThrow(() -> failure("unknown update type " + t)));
                                }
                            }
                            ignores.add(new Ignore(word(body.get("dependency-name"), "dependency-name"), types));
                        }
                    }
                    default -> {
                        if (!HARMLESS.contains(e.getKey())) {
                            throw failure("the tool does not follow \"" + e.getKey() + "\" (in " + name + "); teach it, or the tool"
                                    + " would update what Dependabot does not");
                        }
                    }
                }
            }
            if (ecosystems.put(name, new Ecosystem(name, groups, ignores)) != null) {
                throw failure("two entries for " + name + " (the tool reads one per ecosystem)");
            }
        }
        return new DependabotRules(ecosystems);
    }

    private static void onlyKeys(Map<String, Object> body, Set<String> known, String where) {
        for (String key : body.keySet()) {
            if (!known.contains(key)) {
                throw failure("the tool does not follow \"" + key + "\" in " + where);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String what) {
        if (!(o instanceof Map)) {
            throw failure(what + " should be a map of keys");
        }
        return (Map<String, Object>) o;
    }

    private static List<?> list(Object o, String what) {
        if (!(o instanceof List<?> l)) {
            throw failure(what + " should be a list");
        }
        return l;
    }

    private static String word(Object o, String what) {
        if (!(o instanceof String s) || s.isEmpty()) {
            throw failure(what + " should be a word");
        }
        return s;
    }

    private static List<String> words(Object o, String what) {
        List<String> out = new ArrayList<>();
        for (Object w : list(o, what)) {
            out.add(word(w, what));
        }
        return out;
    }

    private static ToolFailure failure(String message) {
        return new ToolFailure(FILE + ": " + message);
    }

    /**
     * The part of YAML {@code dependabot.yml} uses, read into maps (in file order), lists and
     * strings. Example: {@code patterns: ["*"]} gives the map {@code {patterns=[*]}}. A tab, an
     * anchor ({@code &}), an alias ({@code *x}), a block text ({@code |}, {@code >}), several
     * documents ({@code ---}) or a flow map ({@code {}}) stops it.
     */
    static final class Yaml {

        /** A line without its comment: its indentation and its text. */
        private record Line(int number, int indent, String text) {
        }

        private final List<Line> lines;
        private int at;

        private Yaml(List<Line> lines) {
            this.lines = lines;
        }

        static Object parse(String text) {
            List<Line> lines = new ArrayList<>();
            int number = 0;
            for (String raw : text.lines().toList()) {
                number++;
                if (raw.indexOf('\t') >= 0) {
                    throw failure("line " + number + " has a tab; YAML indents with spaces");
                }
                String t = withoutComment(raw, number).stripTrailing();
                if (t.isBlank()) {
                    continue;
                }
                if (t.equals("---") || t.equals("...")) {
                    throw failure("line " + number + ": one document expected");
                }
                int indent = t.length() - t.stripLeading().length();
                lines.add(new Line(number, indent, t.strip()));
            }
            if (lines.isEmpty()) {
                throw failure("the file is empty");
            }
            Yaml y = new Yaml(lines);
            Object value = y.block(lines.get(0).indent());
            if (y.at < lines.size()) {
                throw failure("line " + lines.get(y.at).number() + " is not where its indentation puts it");
            }
            return value;
        }

        /** The text before a {@code #} that starts a comment: at the start, or after a space, outside quotes. */
        private static String withoutComment(String line, int number) {
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                    return line.substring(0, i);
                }
            }
            if (quote != 0) {
                throw failure("line " + number + " has a quote that is not closed");
            }
            return line;
        }

        private Object block(int indent) {
            Line first = lines.get(at);
            return first.text().equals("-") || first.text().startsWith("- ") ? sequence(indent) : mapping(indent);
        }

        private List<Object> sequence(int indent) {
            List<Object> items = new ArrayList<>();
            while (at < lines.size() && lines.get(at).indent() == indent
                    && (lines.get(at).text().equals("-") || lines.get(at).text().startsWith("- "))) {
                Line l = lines.get(at);
                String rest = l.text().substring(1).strip();
                if (rest.isEmpty()) {
                    at++;
                    items.add(nested(indent));
                } else if (keyOf(rest).isPresent()) {
                    // "- key: value": a map whose first key is on this line, two columns in.
                    lines.set(at, new Line(l.number(), indent + 2, rest));
                    items.add(mapping(indent + 2));
                } else {
                    at++;
                    items.add(scalar(rest, l.number()));
                }
            }
            return items;
        }

        private Map<String, Object> mapping(int indent) {
            Map<String, Object> map = new LinkedHashMap<>();
            while (at < lines.size() && lines.get(at).indent() == indent && !lines.get(at).text().startsWith("- ")) {
                Line l = lines.get(at);
                String key = keyOf(l.text()).orElseThrow(() -> failure("line " + l.number() + " should be \"key: value\": " + l.text()));
                String rest = l.text().substring(l.text().indexOf(':') + 1).strip();
                at++;
                Object value;
                if (!rest.isEmpty()) {
                    value = scalar(rest, l.number());
                } else if (at < lines.size() && lines.get(at).indent() == indent && lines.get(at).text().startsWith("- ")) {
                    value = sequence(indent); // a list may stand at its key's indentation
                } else {
                    value = nested(indent);
                }
                if (map.put(unquote(key, l.number()), value) != null) {
                    throw failure("line " + l.number() + ": " + key + " is there twice");
                }
            }
            return map;
        }

        /** The block under a key or a bare "-": deeper lines, or nothing. */
        private Object nested(int indent) {
            if (at < lines.size() && lines.get(at).indent() > indent) {
                return block(lines.get(at).indent());
            }
            return null;
        }

        /** The key of "key: value" or "key:", outside quotes; empty for a plain word. */
        private static Optional<String> keyOf(String text) {
            if (text.startsWith("\"") || text.startsWith("'") || text.startsWith("[")) {
                return Optional.empty();
            }
            int colon = text.indexOf(':');
            if (colon <= 0 || (colon + 1 < text.length() && text.charAt(colon + 1) != ' ')) {
                return Optional.empty();
            }
            return Optional.of(text.substring(0, colon).strip());
        }

        private static Object scalar(String text, int number) {
            if (text.startsWith("[")) {
                if (!text.endsWith("]")) {
                    throw failure("line " + number + ": a [list] must close on its line");
                }
                List<Object> items = new ArrayList<>();
                String inner = text.substring(1, text.length() - 1).strip();
                if (!inner.isEmpty()) {
                    for (String item : inner.split(",", -1)) {
                        items.add(unquote(item.strip(), number));
                    }
                }
                return items;
            }
            char c = text.charAt(0);
            if (c == '{' || c == '&' || c == '*' || c == '|' || c == '>' || c == '!' || c == '%' || c == '@' || c == '`') {
                throw failure("line " + number + " uses YAML the tool does not read: " + text);
            }
            return unquote(text, number);
        }

        private static String unquote(String text, int number) {
            if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')) {
                char q = text.charAt(0);
                if (text.charAt(text.length() - 1) != q || text.indexOf(q, 1) != text.length() - 1 || text.indexOf('\\') >= 0) {
                    throw failure("line " + number + ": a quoted word the tool cannot read: " + text);
                }
                return text.substring(1, text.length() - 1);
            }
            if (text.isEmpty() || text.startsWith("\"") || text.startsWith("'")) {
                throw failure("line " + number + ": an empty or unclosed word: " + text);
            }
            return text;
        }
    }
}

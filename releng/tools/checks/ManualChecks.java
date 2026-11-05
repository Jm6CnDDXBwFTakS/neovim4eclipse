// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The manual checks before a release ({@code docs/MANUAL_CHECKS.md}): lists them, says which a
 * release needs, prepares their folders and starts Eclipse for one. A single-file Java program, so
 * it runs the same on Linux, macOS and Windows with only the JDK the build already needs.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java releng/tools/checks/ManualChecks.java due [--since &lt;ref&gt;] [--all]
 *   java releng/tools/checks/ManualChecks.java list
 *   java releng/tools/checks/ManualChecks.java setup [--root &lt;folder&gt;]
 *   java releng/tools/checks/ManualChecks.java launch &lt;card&gt; [--root &lt;folder&gt;] [--eclipse &lt;program&gt;] [--zip &lt;file&gt;] [--own-eclipse] [--workspace ws2] [--yes]
 *   java releng/tools/checks/ManualChecks.java json            the cards as JSON, for a page that keeps ticks
 * </pre>
 *
 * <h2>Why the cards live in a Markdown file</h2>
 * The file is what a person reads and edits, and what a review sees next to the change that needs
 * a new card. This program reads the same file, so there is no second list to keep in step: a
 * card's {@code Areas} line (the files it depends on) drives {@code due}, its {@code Launch} line
 * drives {@code launch}. The format is described in the file itself, under "The format".
 *
 * <h2>How {@code due} decides</h2>
 * A card is due when a file one of its area globs matches changed since the last release tag, or
 * when its areas say {@code always}. Example: a release that changed only {@code util/StatusLineFormat.java}
 * needs the release cards and nothing about Get Neovim or the {@code :} box. The changed files are
 * {@code git diff --name-only <tag>}: the commits since the tag and what is not committed yet.
 *
 * <p>The parts that decide (parsing, globs, {@code due}, the HTML for {@code json}) are plain static
 * methods, tested in {@code tests/unit} ({@code ManualChecksTest}); {@link #main} only runs them.
 */
public final class ManualChecks {

    /** Where a relative area glob starts: the plug-in's source package. */
    static final String PLUGIN_SOURCES = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/";

    /** The checklist, from the repository's root. */
    static final Path CHECKLIST = Path.of("docs", "MANUAL_CHECKS.md");

    static final List<String> PLATFORMS = List.of("linux", "macos", "windows");
    static final List<String> LAUNCHES = List.of("fresh", "checks", "normal", "none");
    static final String ALWAYS = "always";

    private ManualChecks() {
    }

    /**
     * One card.
     *
     * @param section the {@code ##} heading it is under
     * @param on      platforms, or just {@code any}
     * @param areas   globs (see {@link #matches}), or just {@code always}
     * @param steps   the Markdown between the facts and {@code **Expect:**}
     * @param expect  the Markdown after {@code **Expect:**}
     */
    record Card(String id, String title, String section, List<String> on, String launch, List<String> areas,
            String steps, String expect) {

        boolean always() {
            return areas.equals(List.of(ALWAYS));
        }
    }

    /** Thrown for a checklist that does not follow the format; the message names the line. */
    static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FormatException(int line, String message) {
            super("docs/MANUAL_CHECKS.md, line " + line + ": " + message);
        }
    }

    // ---- Parsing ----

    private static final Pattern CARD = Pattern.compile("^### `([a-z0-9-]+)` (.+)$");
    private static final Pattern FACT = Pattern.compile("^- \\*\\*(On|Launch|Areas):\\*\\* (.+)$");
    private static final String EXPECT = "**Expect:**";

    /**
     * The cards of a checklist, in order. A {@code ###} heading of the form {@code ### `id` Title}
     * starts a card; the next {@code ##} or {@code ###} heading ends it. Other {@code ###} headings
     * (not starting with a backtick), and everything before the first card, are prose for people and
     * ignored here.
     *
     * @throws FormatException for a card without one of its three facts, an unknown platform or
     *         launch, a missing {@code **Expect:**}, an id used twice, or a {@code ### `} heading that
     *         is not a card's
     */
    static List<Card> parse(String markdown) {
        List<Card> cards = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String[] lines = markdown.split("\r?\n", -1);
        String section = "";
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("## ")) {
                section = line.substring(3).trim();
                i++;
                continue;
            }
            Matcher card = CARD.matcher(line);
            if (!card.matches()) {
                if (line.startsWith("### `")) {
                    // Meant as a card, but not one: dropping it quietly would take it out of due.
                    throw new FormatException(i + 1, "a card heading is ### `id` Title, the id in lowercase"
                            + " letters, digits and -: " + line);
                }
                i++;
                continue;
            }
            int start = i + 1;
            String id = card.group(1);
            if (!ids.add(id)) {
                throw new FormatException(start, "the id " + id + " is used twice");
            }
            Map<String, String> facts = new LinkedHashMap<>();
            List<String> body = new ArrayList<>();
            boolean inFence = false;
            i++;
            while (i < lines.length && (inFence || !(lines[i].startsWith("## ") || lines[i].startsWith("### ")))) {
                String l = lines[i];
                if (l.startsWith("```")) {
                    inFence = !inFence;
                }
                Matcher fact = FACT.matcher(l);
                if (!inFence && body.stream().allMatch(String::isBlank) && fact.matches()) {
                    facts.put(fact.group(1), fact.group(2).trim());
                } else {
                    body.add(l);
                }
                i++;
            }
            cards.add(card(start, id, card.group(2).trim(), section, facts, body));
        }
        return cards;
    }

    private static Card card(int line, String id, String title, String section, Map<String, String> facts,
            List<String> body) {
        for (String name : List.of("On", "Launch", "Areas")) {
            if (!facts.containsKey(name)) {
                throw new FormatException(line, id + " has no **" + name + ":** line");
            }
        }
        List<String> on = list(facts.get("On"));
        boolean any = on.equals(List.of("any"));
        if (!any && (on.isEmpty() || !PLATFORMS.containsAll(on))) {
            throw new FormatException(line, id + ": On must be any, or some of " + PLATFORMS + ", not " + on);
        }
        String launch = facts.get("Launch");
        if (!LAUNCHES.contains(launch)) {
            throw new FormatException(line, id + ": Launch must be one of " + LAUNCHES + ", not " + launch);
        }
        List<String> areas = list(facts.get("Areas"));
        if (areas.contains(ALWAYS) && areas.size() > 1) {
            throw new FormatException(line, id + ": always stands alone in Areas");
        }
        String text = String.join("\n", body).strip();
        int expect = text.indexOf(EXPECT);
        if (expect < 0) {
            throw new FormatException(line, id + " has no " + EXPECT);
        }
        return new Card(id, title, section, on, launch, areas, text.substring(0, expect).strip(),
                text.substring(expect + EXPECT.length()).strip());
    }

    /** {@code "a, b,c"} as {@code [a, b, c]}. */
    private static List<String> list(String text) {
        return Stream.of(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ---- Areas and due ----

    /**
     * Whether a repository path ({@code /}-separated, from the root, as {@code git} prints it)
     * matches an area glob. Without a leading {@code /} the glob is inside {@link #PLUGIN_SOURCES};
     * {@code **} matches any number of folders (none included), {@code *} anything within one name.
     * Examples: {@code util/Neovim*.java} matches the plug-in's {@code util/NeovimDownload.java};
     * {@code /pom.xml} matches only the root POM; {@code dialogs/**} anything below {@code dialogs/}.
     */
    static boolean matches(String glob, String path) {
        String full = glob.startsWith("/") ? glob.substring(1) : PLUGIN_SOURCES + glob;
        return globPattern(full).matcher(path).matches();
    }

    private static Pattern globPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                boolean slash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                regex.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * The cards due for {@code changed} (repository paths), each with why: {@code "always"}, or the
     * first changed file one of its globs matches. In checklist order.
     */
    static Map<Card, String> due(List<Card> cards, Collection<String> changed) {
        Map<Card, String> due = new LinkedHashMap<>();
        for (Card card : cards) {
            if (card.always()) {
                due.put(card, ALWAYS);
                continue;
            }
            changed.stream().filter(path -> card.areas().stream().anyMatch(glob -> matches(glob, path)))
                    .findFirst().ifPresent(path -> due.put(card, path));
        }
        return due;
    }

    /** Area globs that match none of {@code files}: a renamed or deleted file a card still names. */
    static List<String> unmatchedAreas(List<Card> cards, Collection<String> files) {
        List<String> unmatched = new ArrayList<>();
        for (Card card : cards) {
            if (!card.always()) {
                for (String glob : card.areas()) {
                    if (files.stream().noneMatch(path -> matches(glob, path))) {
                        unmatched.add(card.id() + ": " + glob);
                    }
                }
            }
        }
        return unmatched;
    }

    // ---- HTML and JSON, for a page that keeps ticks ----

    private static final Pattern ORDERED = Pattern.compile("^\\d+\\. (.*)$");

    /**
     * The small part of Markdown the checklist uses, as HTML: paragraphs, numbered and bulleted
     * lists (one level; an indented line continues the item), fenced code (a fence such as
     * {@code ```sh macos} becomes a block shown on macOS only), {@code `code`}, {@code **bold**},
     * {@code *italic*}, links, and {@code <kbd>} written as such. Other {@code <} and {@code &} are
     * escaped, so text cannot inject markup into the page.
     */
    static String html(String markdown) {
        StringBuilder out = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        String list = null; // "ol" or "ul" while inside one
        StringBuilder paragraph = new StringBuilder();
        StringBuilder item = null;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("```")) {
                item = flushItem(out, item);
                list = closeList(out, list);
                flushParagraph(out, paragraph);
                List<String> words = list(line.substring(3).replace(' ', ','));
                List<String> platforms = words.stream().filter(PLATFORMS::contains).toList();
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].startsWith("```"); i++) {
                    code.append(code.isEmpty() ? "" : "\n").append(lines[i]);
                }
                i++;
                String block = "<div class=\"snip\"><pre>" + escape(code.toString()) + "</pre></div>";
                out.append(platforms.isEmpty() ? block : "<div class=\"plat-only"
                        + platforms.stream().map(p -> " only-" + p).reduce("", String::concat) + "\">" + block + "</div>");
                continue;
            }
            Matcher ordered = ORDERED.matcher(line);
            boolean bullet = line.startsWith("- ");
            if (ordered.matches() || bullet) {
                flushParagraph(out, paragraph);
                String kind = bullet ? "ul" : "ol";
                item = flushItem(out, item);
                if (!kind.equals(list)) {
                    closeList(out, list);
                    out.append('<').append(kind).append('>');
                    list = kind;
                }
                item = new StringBuilder(bullet ? line.substring(2) : ordered.group(1));
            } else if (item != null && line.startsWith("  ") && !line.isBlank()) {
                item.append(' ').append(line.strip());
            } else if (line.isBlank()) {
                item = flushItem(out, item);
                list = closeList(out, list);
                flushParagraph(out, paragraph);
            } else {
                item = flushItem(out, item);
                list = closeList(out, list);
                paragraph.append(paragraph.isEmpty() ? "" : " ").append(line.strip());
            }
            i++;
        }
        flushItem(out, item);
        closeList(out, list);
        flushParagraph(out, paragraph);
        return out.toString();
    }

    private static StringBuilder flushItem(StringBuilder out, StringBuilder item) {
        if (item != null) {
            out.append("<li>").append(inline(item.toString())).append("</li>");
        }
        return null;
    }

    private static String closeList(StringBuilder out, String list) {
        if (list != null) {
            out.append("</").append(list).append('>');
        }
        return null;
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            out.append("<p>").append(inline(paragraph.toString())).append("</p>");
            paragraph.setLength(0);
        }
    }

    private static final Pattern INLINE = Pattern.compile(
            "`([^`]+)`|\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|\\[([^\\]]+)\\]\\(([^)]+)\\)|<kbd>(.*?)</kbd>");

    /** Inline Markdown as HTML; see {@link #html}. */
    static String inline(String text) {
        StringBuilder out = new StringBuilder();
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(escape(text.substring(last, m.start())));
            if (m.group(1) != null) {
                out.append("<code>").append(escape(m.group(1))).append("</code>");
            } else if (m.group(2) != null) {
                out.append("<b>").append(inline(m.group(2))).append("</b>");
            } else if (m.group(3) != null) {
                out.append("<i>").append(inline(m.group(3))).append("</i>");
            } else if (m.group(4) != null) {
                out.append(inline(m.group(4))); // links point into the repository: the text alone
            } else {
                out.append("<kbd>").append(escape(m.group(6))).append("</kbd>");
            }
            last = m.end();
        }
        return out.append(escape(text.substring(last))).toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The cards as a JSON array of sections, {@code [{"title", "checks": [{"id", "on", "t", "s",
     * "e", "launch"}]}]}, with {@code s} and {@code e} as HTML: the shape a tick page renders. The
     * launch command is put first in {@code s}, so the page says how to start each card. It is a
     * code block of its own ({@code <div class="snip"><pre>...</pre></div>}, as the Markdown's code
     * blocks are), so the page can give it a Copy button like the other commands.
     */
    static String json(List<Card> cards) {
        Map<String, List<Card>> sections = new LinkedHashMap<>();
        for (Card card : cards) {
            sections.computeIfAbsent(card.section(), s -> new ArrayList<>()).add(card);
        }
        StringBuilder out = new StringBuilder("[\n");
        int s = 0;
        for (Map.Entry<String, List<Card>> section : sections.entrySet()) {
            out.append(s++ == 0 ? "" : ",\n").append(" {\"title\": ").append(quote(section.getKey()))
                    .append(", \"checks\": [\n");
            int c = 0;
            for (Card card : section.getValue()) {
                String launch = card.launch().equals("none") ? ""
                        : "<div class=\"snip\"><pre>java releng/tools/checks/ManualChecks.java launch " + card.id() + "</pre></div>";
                out.append(c++ == 0 ? "" : ",\n").append("  {\"id\": ").append(quote(card.id()))
                        .append(", \"on\": [").append(String.join(", ", card.on().stream().map(ManualChecks::quote).toList()))
                        .append("], \"launch\": ").append(quote(card.launch()))
                        .append(", \"t\": ").append(quote(inline(card.title())))
                        .append(", \"s\": ").append(quote(launch + html(card.steps())))
                        .append(", \"e\": ").append(quote(html(card.expect()))).append('}');
            }
            out.append("\n ]}");
        }
        return out.append("\n]\n").toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    // ---- The commands ----

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println(usage());
            return;
        }
        if (!Files.isRegularFile(CHECKLIST)) {
            fail("run from the repository's root: " + CHECKLIST + " not found");
        }
        List<Card> cards;
        try {
            cards = parse(Files.readString(CHECKLIST));
        } catch (FormatException e) {
            fail(e.getMessage());
            return;
        }
        Options options = Options.of(args);
        switch (args[0]) {
            case "list" -> cards.forEach(card -> System.out.println(line(card, "")));
            case "due" -> due(cards, options);
            case "setup" -> {
                setup(options.root());
                System.out.println("Ready: " + options.root() + "\nImport " + options.root().resolve("projects")
                        + " once in each workspace: File > Import > General > Existing Projects into Workspace.");
            }
            case "launch" -> launch(cards, options);
            case "json" -> System.out.print(json(cards));
            default -> fail("unknown command " + args[0] + "\n" + usage());
        }
    }

    private static String usage() {
        return """
                Usage: java releng/tools/checks/ManualChecks.java <command>
                  due [--since <ref>] [--all]   the cards this release needs (docs/MANUAL_CHECKS.md)
                  list                          every card
                  setup [--root <folder>]       make the test project and folders (default ~/var/n4e-checks)
                  launch <card> [--root <folder>] [--eclipse <program>] [--zip <file>] [--own-eclipse]
                                [--workspace ws2] [--yes]
                                starts the checks' Eclipse, a copy of yours with the plug-in just built
                  json                          the cards as JSON, for a page that keeps ticks""";
    }

    private static String line(Card card, String why) {
        String on = String.join(" ", card.on());
        return String.format(Locale.ROOT, "%-24s %-22s %s%s", card.id(), on, card.title(),
                why.isEmpty() ? "" : "\n" + " ".repeat(25) + "because " + why);
    }

    /** The command line's options after the command; unknown ones stop the program. */
    record Options(String card, String since, boolean all, Path root, String eclipse, Path zip, boolean ownEclipse,
            String workspace, boolean yes) {

        static Options of(String[] args) {
            String card = null;
            String since = null;
            boolean all = false;
            Path root = Path.of(System.getProperty("user.home"), "var", "n4e-checks");
            String eclipse = System.getenv("ECLIPSE");
            Path zip = null;
            boolean ownEclipse = false;
            String workspace = "ws1";
            boolean yes = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--since" -> since = value(args, ++i);
                    case "--all" -> all = true;
                    case "--root" -> root = Path.of(value(args, ++i));
                    case "--eclipse" -> eclipse = value(args, ++i);
                    case "--zip" -> zip = Path.of(value(args, ++i)).toAbsolutePath();
                    case "--own-eclipse" -> ownEclipse = true;
                    case "--workspace" -> workspace = value(args, ++i);
                    case "--yes" -> yes = true;
                    default -> {
                        if (args[i].startsWith("-") || card != null) {
                            fail("unknown argument " + args[i] + "\n" + usage());
                        }
                        card = args[i];
                    }
                }
            }
            return new Options(card, since, all, root.toAbsolutePath(), eclipse, zip, ownEclipse, workspace, yes);
        }

        private static String value(String[] args, int i) {
            if (i >= args.length) {
                fail(args[i - 1] + " needs a value");
            }
            return args[i];
        }
    }

    private static void due(List<Card> cards, Options options) throws IOException, InterruptedException {
        if (options.all()) {
            cards.forEach(card -> System.out.println(line(card, "")));
            return;
        }
        String since = options.since() != null ? options.since()
                : git("describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD").strip();
        List<String> changed = git("diff", "--name-only", since).lines().filter(l -> !l.isBlank()).toList();
        Map<Card, String> due = due(cards, changed);
        System.out.println(due.size() + " of " + cards.size() + " cards are due: " + changed.size()
                + " files changed since " + since + ".\n");
        due.forEach((card, why) -> System.out.println(line(card,
                why.equals(ALWAYS) ? "every release" : why.replace(PLUGIN_SOURCES, "") + " changed")));
    }

    private static String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process git = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(git.getInputStream(), StandardCharsets.UTF_8))) {
            output = String.join("\n", reader.lines().toList());
        }
        if (git.waitFor() != 0) {
            fail("git " + String.join(" ", args) + " failed:\n" + output);
        }
        return output;
    }

    /** The folders and the one project the cards use, under {@code root}. Leaves what is there. */
    static void setup(Path root) throws IOException {
        Path alpha = root.resolve("projects/n4e-alpha");
        for (Path folder : List.of(root.resolve("xdg"), root.resolve("ws1"), root.resolve("ws2"), alpha.resolve("src"))) {
            Files.createDirectories(folder);
        }
        write(alpha.resolve(".project"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <projectDescription>
                	<name>n4e-alpha</name>
                	<comment></comment>
                	<projects></projects>
                	<buildSpec></buildSpec>
                	<natures></natures>
                </projectDescription>
                """);
        write(alpha.resolve("notes.txt"), """
                alphabet alpine alpaca alpha
                Type "alp" in insert mode, then Ctrl+N or Ctrl+P.
                foo bar baz
                indent me with Ctrl+T, unindent with Ctrl+D
                """);
        write(alpha.resolve("src/.keep"), "");
    }

    private static void write(Path file, String text) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, text);
        }
    }

    private static void launch(List<Card> cards, Options options) throws IOException, InterruptedException {
        Card card = cards.stream().filter(c -> c.id().equals(options.card())).findFirst()
                .orElseGet(() -> {
                    fail(options.card() == null ? "launch needs a card's id (see list)" : "no card " + options.card());
                    return null;
                });
        if (card.launch().equals("none")) {
            System.out.println(card.id() + " needs no Eclipse: see its steps in " + CHECKLIST + ".");
            return;
        }
        String here = currentPlatform();
        if (!card.on().contains("any") && !card.on().contains(here)) {
            fail(card.id() + " is a check for " + String.join(", ", card.on()) + ", not " + here);
        }
        Path root = options.root();
        if (!Files.isDirectory(root.resolve("projects/n4e-alpha"))) {
            setup(root);
        }
        Path xdg = root.resolve("xdg");
        Path workspace = root.resolve(options.workspace());
        if (ChecksEclipse.inUse(workspace)) {
            // Eclipse would only say "workspace in use"; say it here, before anything changes.
            fail("an Eclipse already runs in " + workspace + "; close it, or use --workspace ws2");
        }
        if (card.launch().equals("fresh") && isNotEmpty(xdg)) {
            if (!options.yes() && !confirm("Empty " + xdg + " (the checks' own plug-in folder) for a fresh start?")) {
                fail("stopped; nothing was changed (--yes answers yes)");
            }
            ChecksEclipse.refuseWhileRunning(root); // its Neovim may run from xdg
            try {
                deleteContents(xdg);
            } catch (UncheckedIOException e) {
                // Typically Windows: a Neovim from an earlier launch still runs from the folder.
                fail("could not empty " + xdg + " (" + e.getCause().getMessage() + "). Close the Eclipse that"
                        + " uses it (and its Neovim), then launch again; the folder may be partly emptied.");
            }
        }
        String yours = options.eclipse() != null ? options.eclipse() : defaultEclipse();
        String eclipse = options.ownEclipse() ? yours
                : ChecksEclipse.prepare(root, ChecksEclipse.program(yours), options.zip(), options.yes()).toString();
        List<String> command = List.of(eclipse, "-data", workspace.toString());
        // Eclipse's working folder is the root, not the repository: a JVM crash file (hs_err_pid*.log)
        // or anything else written by a relative path lands with the checks, not as an untracked file.
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        if (!card.launch().equals("normal")) {
            builder.environment().put("XDG_DATA_HOME", xdg.toString());
        }
        Path log = root.resolve("eclipse-" + card.id() + ".log");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        try {
            builder.start();
        } catch (IOException e) {
            fail("could not start " + eclipse + " (" + e.getMessage() + "); name it with --eclipse or $ECLIPSE");
        }
        System.out.println("Started for " + card.id() + ": " + String.join(" ", command)
                + (card.launch().equals("normal") ? "" : "\n  with XDG_DATA_HOME=" + xdg)
                + "\n  output in " + log + "\nSteps: " + CHECKLIST + ", " + card.id() + ".");
    }

    // ---- The checks' own Eclipse ----

    /**
     * The checks' own Eclipse: a copy of yours under {@code <root>/eclipse}, with the update site ZIP
     * just built installed into it, so {@code launch} always runs the version under test without
     * <i>Help &gt; Install New Software</i> by hand, and your own Eclipse is never changed.
     *
     * <h2>A copy, made cheap with hard links</h2>
     * Installing straight into your Eclipse would change the one you work in. A copy costs little:
     * the files under {@code plugins/} and {@code features/} (nearly all of an Eclipse, about 1 GB)
     * are <i>hard links</i> to yours, a second name for the same bytes, so they take no new space.
     * They are safe to share because p2 never rewrites a bundle in place: it adds new files and
     * deletes old ones, and deleting a link leaves your file alone. Everything p2 and Eclipse do
     * write ({@code configuration/}, {@code p2/}, {@code eclipse.ini}, {@code artifacts.xml}) is a
     * real copy. Where a link cannot be made (the root on another disk), the file is copied.
     * Example, on Linux: {@code /opt/eclipse/eclipse-committers-2026-09-R-linux-gtk-aarch64} becomes
     * {@code ~/var/n4e-checks/eclipse/eclipse-committers-2026-09-R-linux-gtk-aarch64}; on macOS
     * {@code /Applications/Eclipse.app} becomes {@code ~/var/n4e-checks/eclipse/Eclipse.app}.
     *
     * <h2>Installed with p2's director</h2>
     * The director ({@code -application org.eclipse.equinox.p2.director}) is <i>Install New
     * Software</i> without a window: it resolves the feature against the copy's own p2 profile, so a
     * dependency the plug-in needs and the Eclipse lacks fails here as it would for a user. It runs in
     * the copy, which then installs into itself (p2 takes the install folder from where it runs, not
     * from the copied profile). A ZIP already installed (same SHA-256, kept in
     * {@code <root>/eclipse-copy.properties}) is not installed again. A new one replaces the old
     * feature in one step ({@code -uninstallIU} and {@code -installIU} together), as a newer build
     * of the same bundle could not sit beside it.
     */
    static final class ChecksEclipse {

        /** The feature's installable unit, as p2 names a feature: its id plus {@code .feature.group}. */
        static final String FEATURE = "io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group";

        /** Where {@code ./mvnw verify} leaves the update site ZIP, from the repository's root. */
        static final Path SITE_TARGET = Path.of("releng", "io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite", "target");

        private static final String STATE = "eclipse-copy.properties";

        private ChecksEclipse() {
        }

        /**
         * Your Eclipse program as a path: as given if it names a file, else looked up on {@code PATH}
         * (the Linux default is the bare {@code eclipse}).
         */
        static Path program(String name) {
            Path given = Path.of(name);
            if (given.getNameCount() > 1 || Files.isRegularFile(given)) {
                return given.toAbsolutePath();
            }
            for (String folder : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                if (!folder.isEmpty() && Files.isExecutable(Path.of(folder, name))) {
                    return Path.of(folder, name);
                }
            }
            fail("no Eclipse program " + name + " on PATH; name it with --eclipse or $ECLIPSE");
            return null;
        }

        /**
         * The installation a program belongs to, the folder to copy: on macOS the {@code .app} around
         * it ({@code /Applications/Eclipse.app/Contents/MacOS/eclipse} gives {@code /Applications/Eclipse.app}),
         * elsewhere the program's folder ({@code C:\eclipse\eclipse.exe} gives {@code C:\eclipse}).
         */
        static Path installation(Path program) {
            for (Path folder = program.getParent(); folder != null; folder = folder.getParent()) {
                if (folder.getFileName() != null && folder.getFileName().toString().endsWith(".app")) {
                    return folder;
                }
            }
            return program.getParent();
        }

        /**
         * Whether a file of the installation may be a hard link to yours: one under the folder with
         * {@code eclipse.ini} ({@code inner}: empty, or {@code Contents/Eclipse} in a macOS app), in
         * {@code plugins/} or {@code features/}. Example: {@code plugins/org.eclipse.swt_3.130.jar}
         * yes, {@code configuration/config.ini} no.
         */
        static boolean shared(Path relative, Path inner) {
            return relative.startsWith(inner.resolve("plugins")) || relative.startsWith(inner.resolve("features"));
        }

        /** The feature's version in the director's {@code -listInstalledRoots} output, if installed. */
        static Optional<String> installedVersion(String listing) {
            return listing.lines().map(String::strip).filter(l -> l.startsWith(FEATURE + "/"))
                    .map(l -> l.substring(FEATURE.length() + 1)).findFirst();
        }

        /**
         * The director's arguments that install {@code zip}, after the program. Example:
         * {@code --launcher.suppressErrors -nosplash -application org.eclipse.equinox.p2.director
         * -repository jar:file:///.../neovim4eclipse-0.2.1-SNAPSHOT.zip!/ -uninstallIU <feature>
         * -installIU <feature> -data @none}. {@code --launcher.suppressErrors} keeps a failed start
         * on the terminal instead of in a dialog; {@code -data @none} gives it no workspace.
         */
        static List<String> installArguments(Path zip, boolean installed) {
            List<String> args = new ArrayList<>(List.of("--launcher.suppressErrors", "-nosplash", "-application",
                    "org.eclipse.equinox.p2.director", "-repository", "jar:" + zip.toUri() + "!/"));
            if (installed) {
                args.addAll(List.of("-uninstallIU", FEATURE));
            }
            args.addAll(List.of("-installIU", FEATURE, "-data", "@none"));
            return args;
        }

        /**
         * Makes or refreshes the copy of the Eclipse {@code yours} starts and installs the ZIP into it;
         * returns the copy's program, which {@code launch} starts.
         *
         * @param zip the update site ZIP, or {@code null} for the newest in {@link #SITE_TARGET}
         */
        static Path prepare(Path root, Path yours, Path zip, boolean yes) throws IOException, InterruptedException {
            Path real = yours.toRealPath();
            Path source = installation(real);
            Path copies = root.resolve("eclipse");
            Path copy = copies.resolve(source.getFileName().toString());
            Path stateFile = root.resolve(STATE);
            Properties state = new Properties();
            if (Files.isRegularFile(stateFile)) {
                try (var in = Files.newBufferedReader(stateFile)) {
                    state.load(in);
                }
            }
            if (!source.toString().equals(state.getProperty("source")) || !Files.isDirectory(copy)) {
                if (isNotEmpty(copies)) {
                    if (!yes && !confirm("Replace the checks' Eclipse in " + copies + " (a copy of "
                            + state.getProperty("source", "another Eclipse") + ") with a copy of " + source + "?")) {
                        fail("stopped; nothing was changed (--yes answers yes, --own-eclipse starts yours as it is)");
                    }
                    refuseWhileRunning(root);
                    deleteContents(copies); // links and copies only: your Eclipse keeps its files
                }
                System.out.println("Copying " + source + " to " + copy + " ...");
                Path inner = Files.isRegularFile(source.resolve("Contents/Eclipse/eclipse.ini"))
                        ? Path.of("Contents", "Eclipse") : Path.of("");
                long[] counts = copyTree(source, copy, inner);
                System.out.println("  " + counts[0] + " files linked, " + counts[1] + " copied ("
                        + counts[2] / (1024 * 1024) + " MB of new space)");
                state.clear();
                state.setProperty("source", source.toString());
                save(state, stateFile);
            }
            Path program = copy.resolve(source.relativize(real).toString());
            Path site = zip != null ? zip : newestZip();
            warnIfStale(site);
            String sum = sha256(site);
            if (sum.equals(state.getProperty("zip"))) {
                System.out.println("The checks' Eclipse already has " + site.getFileName() + ".");
                return program;
            }
            refuseWhileRunning(root);
            Path director = director(program);
            Path log = root.resolve("director.log");
            List<String> list = new ArrayList<>(List.of(director.toString(), "--launcher.suppressErrors", "-nosplash",
                    "-application", "org.eclipse.equinox.p2.director", "-listInstalledRoots", "-data", "@none"));
            Optional<String> installed = installedVersion(run(list, log));
            List<String> install = new ArrayList<>(List.of(director.toString()));
            install.addAll(installArguments(site, installed.isPresent()));
            String output = run(install, log);
            if (!output.contains("Operation completed")) {
                fail("installing " + site + " into " + copy + " failed:\n" + output + "\n(output in " + log + ")");
            }
            System.out.println("Installed " + site.getFileName() + " into the checks' Eclipse"
                    + installed.map(v -> " (it had " + v + ")").orElse("") + ".");
            state.setProperty("zip", sum);
            save(state, stateFile);
            return program;
        }

        /**
         * The program to run the director with: on Windows {@code eclipsec.exe} beside
         * {@code eclipse.exe}, the console launcher, since {@code eclipse.exe} detaches from the
         * terminal and its output is lost.
         */
        private static Path director(Path program) {
            Path console = program.resolveSibling("eclipsec.exe");
            return Files.isRegularFile(console) ? console : program;
        }

        /** Copies the tree, hard-linking the {@link #shared} files; returns {linked, copied, bytes copied}. */
        static long[] copyTree(Path source, Path target, Path inner) throws IOException {
            long[] counts = new long[3];
            Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(file);
                    Path to = target.resolve(relative.toString());
                    if (!attrs.isSymbolicLink() && shared(relative, inner)) {
                        try {
                            Files.createLink(to, file);
                            counts[0]++;
                            return java.nio.file.FileVisitResult.CONTINUE;
                        } catch (IOException | UnsupportedOperationException e) {
                            // Another disk, or a file system without hard links: copy instead.
                        }
                    }
                    Files.copy(file, to, java.nio.file.LinkOption.NOFOLLOW_LINKS,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    counts[1]++;
                    counts[2] += attrs.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return counts;
        }

        /**
         * Stops when an Eclipse started by {@code launch} still runs in any workspace under the root:
         * p2 must not change an installation in use, nor may its files be deleted, and a Neovim it
         * started may run from the {@code xdg} folder a {@code fresh} card empties (on macOS and
         * Linux that delete succeeds under the running program's feet).
         */
        static void refuseWhileRunning(Path root) throws IOException {
            if (!Files.isDirectory(root)) {
                return;
            }
            try (Stream<Path> folders = Files.list(root)) {
                for (Path workspace : folders.filter(Files::isDirectory).toList()) {
                    if (inUse(workspace)) {
                        fail("an Eclipse still runs in " + workspace + "; close it, then launch again");
                    }
                }
            }
        }

        /**
         * Whether an Eclipse runs in {@code workspace}: Eclipse holds an operating-system lock on
         * {@code <workspace>/.metadata/.lock} while it runs, so a lock this program cannot take means
         * one does. No lock file, no Eclipse.
         */
        static boolean inUse(Path workspace) {
            Path lock = workspace.resolve(".metadata/.lock");
            if (!Files.isRegularFile(lock)) {
                return false;
            }
            try (var channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE);
                    var held = channel.tryLock()) {
                return held == null;
            } catch (java.nio.channels.OverlappingFileLockException | IOException e) {
                return true; // held in this very program (a test), or not even writable: treat as in use
            }
        }

        /** The newest {@code neovim4eclipse-*.zip} in {@link #SITE_TARGET}. */
        private static Path newestZip() throws IOException {
            if (Files.isDirectory(SITE_TARGET)) {
                try (Stream<Path> files = Files.list(SITE_TARGET)) {
                    Optional<Path> newest = files.filter(f -> f.getFileName().toString().matches("neovim4eclipse-.*\\.zip"))
                            .max(Comparator.comparing(f -> f.toFile().lastModified()));
                    if (newest.isPresent()) {
                        return newest.get().toAbsolutePath();
                    }
                }
            }
            fail("no update site ZIP in " + SITE_TARGET + ": build it with ./mvnw verify -DskipTests (or name one with --zip)");
            return null;
        }

        /**
         * Warns when the ZIP may not hold the code under test: it is older than the last commit that
         * changed the plug-in or the feature, or those folders have changes not committed. A warning,
         * not a stop: a card about something else can run on an older build.
         */
        private static void warnIfStale(Path zip) throws IOException, InterruptedException {
            String[] built = { "plugins", "features" };
            String last = git("log", "-1", "--format=%ct", "--", built[0], built[1]).strip();
            boolean older = !last.isEmpty() && Files.getLastModifiedTime(zip).toMillis() / 1000 < Long.parseLong(last);
            boolean uncommitted = !git("status", "--porcelain", "--", built[0], built[1]).isBlank();
            if (older || uncommitted) {
                System.out.println("Warning: " + zip.getFileName() + " may not hold the code under test ("
                        + (older ? "older than the last commit to plugins/ or features/" : "uncommitted changes")
                        + "). Rebuild it with ./mvnw verify -DskipTests.");
            }
        }

        private static String sha256(Path file) throws IOException {
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("every JDK has SHA-256", e);
            }
        }

        /** Runs a command to its end; returns its output, also kept in {@code log}. */
        private static String run(List<String> command, Path log) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).directory(log.getParent().toFile()) // the root, as for Eclipse
                    .redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = String.join("\n", reader.lines().toList());
            }
            process.waitFor();
            Files.writeString(log, String.join(" ", command) + "\n" + output + "\n");
            return output;
        }

        private static void save(Properties state, Path file) throws IOException {
            try (var out = Files.newBufferedWriter(file)) {
                state.store(out, "The checks' Eclipse copy (ManualChecks launch): its source and the ZIP installed");
            }
        }
    }

    private static String defaultEclipse() {
        return switch (currentPlatform()) {
            case "macos" -> "/Applications/Eclipse.app/Contents/MacOS/eclipse";
            case "windows" -> "C:\\eclipse\\eclipse.exe";
            default -> "eclipse";
        };
    }

    /** This machine as an {@code On:} word: linux, macos or windows. */
    static String currentPlatform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("mac") ? "macos" : os.startsWith("windows") ? "windows" : "linux";
    }

    private static boolean isNotEmpty(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isPresent();
        }
    }

    private static void deleteContents(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(folder)).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** Asks y/N on the terminal; without one, says no (a script or an AI gets the --yes hint). */
    private static boolean confirm(String question) {
        if (System.console() == null) {
            System.out.println(question + " (no terminal to ask on)");
            return false;
        }
        String answer = System.console().readLine("%s [y/N] ", question);
        return answer != null && answer.trim().equalsIgnoreCase("y");
    }

    private static void fail(String message) {
        System.err.println("ManualChecks: " + message);
        System.exit(1);
    }
}

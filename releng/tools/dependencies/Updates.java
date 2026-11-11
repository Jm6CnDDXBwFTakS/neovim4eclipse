// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import common.Http;
import common.ReleaseAge;
import common.Shell;
import common.ToolFailure;
import common.VersionCopies;

import dependencies.ReleaseNumber.UpdateType;

/**
 * Finds the updates Dependabot would propose, by asking the same two places it asks: Maven
 * Central for the root {@code pom.xml}'s version properties, GitHub's releases for the actions
 * the workflows use. It follows {@code .github/dependabot.yml}'s rules ({@link DependabotRules})
 * and one of its own: a release must be a week old ({@link ReleaseAge}), as the Neovim and Maven
 * tools ask, since a broken release is usually replaced within days. A younger one is skipped for
 * the newest that is old enough, and named in a note.
 *
 * <p>Nothing here changes a file: the Dependency tool ({@link DependenciesTool}) makes the moves,
 * and {@code Release.java check} prints how many there are.
 */
public final class Updates {

    /** A move of one version property of the root POM, with the artifacts that use it and its copies. */
    public record MavenMove(String property, String from, String to, Set<PomVersions.Artifact> artifacts, List<VersionCopies.Copy> copies,
            Optional<String> group, boolean major) {
        /** The name in messages and commits: {@code jacoco}, {@code maven-surefire-plugin}. */
        public String label() {
            return Updates.label(property);
        }
    }

    /**
     * A move of one action, every line of {@code files} that uses it, to a release tag and the
     * commit {@code toSha} it points to. {@code major}: a major release, which {@code dependabot.yml}
     * does not leave out for actions, so the plan says to read its migration notes.
     */
    public record ActionMove(String repository, Set<String> files, Set<String> fromShas, String from, String toTag, String toSha,
            Optional<String> group, boolean major) {
    }

    /** What a search found: the moves, and notes on releases left out (too young, a major). */
    public record Found(List<MavenMove> maven, List<ActionMove> actions, List<String> notes, List<String> upToDate) {
        /** How many moves, of both kinds. */
        public int count() {
            return maven.size() + actions.size();
        }

        /** {@code jacoco 0.8.16, actions/setup-java v6.2.0}. */
        public String summary() {
            return String.join(", ", Stream.concat(maven.stream().map(m -> m.label() + " " + m.to()),
                    actions.stream().map(a -> a.repository() + " " + a.toTag())).toList());
        }
    }

    /**
     * What to move to: the newest release after {@code current} that the rules allow and that is
     * a week old. Example: at 3.6.0 with 3.6.1 (two weeks old) and 3.7.0 (two days old), the move
     * is to 3.6.1 and 3.7.0 is the young one; at 5.14.4 with 6.1.3 and majors ignored, there is no
     * move and 6.1.3 is the ignored one.
     *
     * @param ignored whether an update of a type is left out, by the rules
     * @param published when a release came out, looked up only for the releases that could be taken
     */
    public record Choice(Optional<ReleaseNumber> to, Optional<ReleaseNumber> young, Optional<ReleaseNumber> ignored) {

        public static Choice of(ReleaseNumber current, List<ReleaseNumber> releases, Predicate<UpdateType> ignored,
                Function<ReleaseNumber, Instant> published, Instant now, boolean allowYoung) {
            Optional<ReleaseNumber> young = Optional.empty();
            Optional<ReleaseNumber> left = Optional.empty();
            List<ReleaseNumber> newer = releases.stream().filter(r -> r.compareTo(current) > 0).sorted(Comparator.reverseOrder()).toList();
            for (ReleaseNumber r : newer) {
                if (ignored.test(current.typeTo(r))) {
                    left = left.isPresent() ? left : Optional.of(r);
                    continue;
                }
                if (!allowYoung && ReleaseAge.isYoung(published.apply(r), now)) {
                    young = young.isPresent() ? young : Optional.of(r);
                    continue;
                }
                return new Choice(Optional.of(r), young, left);
            }
            return new Choice(Optional.empty(), young, left);
        }
    }

    /** A property's name in messages: {@code jacoco.version} is {@code jacoco}. */
    static String label(String property) {
        return property.endsWith(".version") ? property.substring(0, property.length() - ".version".length()) : property;
    }

    private final Path root;
    private final Shell shell;
    private final DependabotRules rules;
    private final Map<String, List<VersionCopies.Copy>> copySections;
    private final boolean allowYoung;
    private final Set<String> majors;

    /**
     * @param majors the names given to {@code --major} (a label such as {@code junit}, or an
     *     action such as {@code actions/checkout}): for these, a major release is taken although
     *     the rules ignore majors
     */
    public Updates(Path root, Shell shell, boolean allowYoung, Set<String> majors) {
        this.root = root;
        this.shell = shell;
        this.rules = DependabotRules.parse(read(DependabotRules.FILE));
        this.copySections = VersionCopies.parse(read(VersionCopies.FILE));
        this.allowYoung = allowYoung;
        this.majors = majors;
    }

    /** Searches both kinds; network access to Maven Central, and to GitHub through {@code gh}. */
    public Found find() {
        List<String> notes = new ArrayList<>();
        List<String> upToDate = new ArrayList<>();
        Set<String> unusedMajors = new LinkedHashSet<>(majors);
        List<MavenMove> maven = rules.ecosystem("maven").map(e -> maven(e, notes, upToDate, unusedMajors)).orElse(List.of());
        List<ActionMove> actions = rules.ecosystem("github-actions").map(e -> actions(e, notes, upToDate, unusedMajors)).orElse(List.of());
        if (!unusedMajors.isEmpty()) {
            throw new ToolFailure("--major " + String.join(", ", unusedMajors) + ": no such dependency (names: the property without"
                    + " .version, such as junit, or an action, such as actions/checkout)");
        }
        return new Found(maven, actions, notes, upToDate);
    }

    private List<MavenMove> maven(DependabotRules.Ecosystem eco, List<String> notes, List<String> upToDate, Set<String> unusedMajors) {
        Map<String, String> properties = PomVersions.properties(read("pom.xml"));
        Map<String, Set<PomVersions.Artifact>> used = new TreeMap<>();
        for (String pom : poms()) {
            PomVersions.usedVersions(read(pom), pom, used);
        }
        List<MavenMove> moves = new ArrayList<>();
        for (Map.Entry<String, Set<PomVersions.Artifact>> e : used.entrySet()) {
            String property = e.getKey();
            String current = properties.get(property);
            if (current == null) {
                continue; // a property Maven sets itself, such as project.version
            }
            String label = label(property);
            Set<PomVersions.Artifact> artifacts = new LinkedHashSet<>(e.getValue());
            // The copies' artifacts count as the property's: the same rules, the same new release.
            List<VersionCopies.Copy> copies = copies(property, artifacts, current);
            List<String> names = artifacts.stream().map(Object::toString).toList();
            boolean major = unusedMajors.remove(label);
            if (names.stream().allMatch(eco::ignoresAll)) {
                if (major) {
                    throw ignoredEntirely(label);
                }
                continue; // org.eclipse.platform:*, which moves with the Eclipse release
            }
            if (names.stream().anyMatch(eco::ignoresAll)) {
                throw new ToolFailure(property + " is used by " + names + ", only some of which " + DependabotRules.FILE + " ignores");
            }
            Set<Optional<String>> groups = new LinkedHashSet<>(names.stream().map(eco::groupOf).toList());
            if (groups.size() > 1) {
                throw new ToolFailure(property + " is used by " + names + ", which are in different groups of " + DependabotRules.FILE);
            }
            ReleaseNumber now = ReleaseNumber.parse(current)
                    .orElseThrow(() -> new ToolFailure("pom.xml: " + property + "=" + current + " is not a plain release number"));
            // Only the releases every artifact has: Tycho's plug-ins come out together, but a
            // release missing one of them cannot be used.
            List<ReleaseNumber> common = null;
            for (PomVersions.Artifact a : artifacts) {
                List<ReleaseNumber> releases = PomVersions.releases(Http.text(a.folder() + "maven-metadata.xml"));
                common = common == null ? new ArrayList<>(releases) : new ArrayList<>(common.stream().filter(r -> releases.stream().anyMatch(r::same)).toList());
            }
            PomVersions.Artifact first = artifacts.iterator().next();
            Choice c = Choice.of(now, common, t -> !(major && t == UpdateType.MAJOR) && names.stream().anyMatch(n -> eco.ignores(n, t)),
                    r -> Http.lastModified(first.pom(r.toString()))
                            .orElseThrow(() -> new ToolFailure("Maven Central gives no date for " + first.pom(r.toString()))),
                    Instant.now(), allowYoung);
            note(label, now, c, String::valueOf, notes);
            if (c.to().isPresent()) {
                moves.add(new MavenMove(property, current, c.to().get().toString(), artifacts, copies, groups.iterator().next(),
                        now.typeTo(c.to().get()) == UpdateType.MAJOR));
            } else {
                upToDate.add(label + " " + current);
            }
        }
        return moves;
    }

    /**
     * The copies of {@code property}: the section named after it in {@code releng/version-copies.txt}
     * ({@code [tycho.version]}), each of which must hold {@code current} now. A copy in a POM adds
     * its artifacts of the property's groups to {@code artifacts}, so the new release must exist
     * for them too ({@code tycho-gpg-plugin} with Tycho's other plug-ins).
     */
    private List<VersionCopies.Copy> copies(String property, Set<PomVersions.Artifact> artifacts, String current) {
        List<VersionCopies.Copy> copies = copySections.getOrDefault(property, List.of());
        Set<String> groups = new LinkedHashSet<>(artifacts.stream().map(PomVersions.Artifact::group).toList());
        for (VersionCopies.Copy copy : copies) {
            String text = read(copy.file());
            List<String> found = VersionCopies.versionsIn(text, copy);
            if (found.isEmpty() || found.stream().anyMatch(v -> !v.equals(current))) {
                throw new ToolFailure(VersionCopies.FILE + " [" + property + "]: " + copy.file() + " should hold " + current + " where "
                        + copy.pattern() + " matches, but has " + (found.isEmpty() ? "no match" : found)
                        + " (VersionConsistencyTest says the same); put it right first");
            }
            if (copy.file().endsWith("pom.xml")) {
                artifacts.addAll(PomVersions.literalCopies(text, copy.file(), groups, current));
            }
        }
        return copies;
    }

    private List<ActionMove> actions(DependabotRules.Ecosystem eco, List<String> notes, List<String> upToDate, Set<String> unusedMajors) {
        Map<String, List<WorkflowActions.Use>> byRepository = new TreeMap<>();
        for (String file : workflows()) {
            for (WorkflowActions.Use u : WorkflowActions.uses(file, read(file))) {
                byRepository.computeIfAbsent(u.repository(), k -> new ArrayList<>()).add(u);
            }
        }
        List<ActionMove> moves = new ArrayList<>();
        for (Map.Entry<String, List<WorkflowActions.Use>> e : byRepository.entrySet()) {
            String repository = e.getKey();
            boolean major = unusedMajors.remove(repository);
            if (eco.ignoresAll(repository)) {
                if (major) {
                    throw ignoredEntirely(repository);
                }
                continue;
            }
            // Every line of the action moves; "from" is the oldest release among them.
            WorkflowActions.Use oldest = e.getValue().stream()
                    .min(Comparator.comparing(u -> ReleaseNumber.parse(u.release()).orElseThrow(
                            () -> new ToolFailure(u.file() + ":" + u.line() + ": " + u.release() + " is not a release number"))))
                    .orElseThrow();
            ReleaseNumber now = ReleaseNumber.parse(oldest.release()).orElseThrow();
            Map<ReleaseNumber, WorkflowActions.Tag> tags = new LinkedHashMap<>();
            for (WorkflowActions.Tag t : WorkflowActions.tags(shell.out(List.of("gh", "api", "repos/" + repository + "/releases?per_page=100",
                    "--jq", ".[] | select(.draft | not) | select(.prerelease | not) | \"\\(.tag_name)\\t\\(.published_at)\"")))) {
                t.number().ifPresent(n -> tags.putIfAbsent(n, t));
            }
            // The SHA the tag points to is what gets pinned, so it is looked up with the release's
            // date: a tag moved to a newer commit after the release stops the tool
            // (WorkflowActions.looksMoved), since the week the release has been out says nothing
            // about that commit.
            Map<ReleaseNumber, WorkflowActions.TagCommit> commits = new LinkedHashMap<>();
            Function<ReleaseNumber, Instant> age = r -> {
                commits.computeIfAbsent(r, k -> tagCommit(repository, tags.get(r)));
                return parseInstant(tags.get(r).published()).orElseThrow(); // tagCommit read it
            };
            Choice c = Choice.of(now, new ArrayList<>(tags.keySet()), t -> !(major && t == UpdateType.MAJOR) && eco.ignores(repository, t),
                    age, Instant.now(), allowYoung);
            c.to().ifPresent(age::apply); // with --allow-young, the choice asked for no date: the commit is still looked up and checked
            note(repository, now, c, r -> tags.get(r).name(), notes);
            Set<String> shas = new LinkedHashSet<>(e.getValue().stream().map(WorkflowActions.Use::sha).toList());
            Set<String> files = new LinkedHashSet<>(e.getValue().stream().map(WorkflowActions.Use::file).toList());
            if (c.to().isPresent()) {
                moves.add(new ActionMove(repository, files, shas, oldest.release(), tags.get(c.to().get()).name(),
                        commits.get(c.to().get()).sha(), eco.groupOf(repository), now.typeTo(c.to().get()) == UpdateType.MAJOR));
            } else {
                upToDate.add(repository + " " + oldest.release());
            }
        }
        return moves;
    }

    /**
     * The commit {@code tag} of {@code repository} points to now, through {@code gh}; stops the
     * tool when the tag looks moved after its release ({@link WorkflowActions#looksMoved}).
     */
    private WorkflowActions.TagCommit tagCommit(String repository, WorkflowActions.Tag tag) {
        String what = repository + " " + tag.name();
        Instant released = parseInstant(tag.published()).orElseThrow(() -> new ToolFailure(what + " has no publish date"));
        WorkflowActions.TagCommit commit = WorkflowActions.TagCommit.parse(shell.out(List.of("gh", "api",
                "repos/" + repository + "/commits/" + tag.name(), "--jq", "\"\\(.sha)\\t\\(.commit.committer.date)\"")), what);
        if (WorkflowActions.looksMoved(released, commit.date())) {
            throw new ToolFailure(what + " came out " + released + ", but its tag points to " + commit.sha() + ", a commit of "
                    + commit.date() + ": the tag was moved after the release. Check the release on GitHub before pinning anything"
                    + " of it; the tool does not");
        }
        return commit;
    }

    private static Optional<Instant> parseInstant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The notes on what {@code c} left out, the move from {@code now}; {@code shown} writes a
     * release as its source does ({@code 3.7.0}, {@code v6.2.0}).
     */
    private static void note(String name, ReleaseNumber now, Choice c, Function<ReleaseNumber, String> shown, List<String> notes) {
        c.young().ifPresent(y -> notes.add(name + " " + shown.apply(y) + " is out but less than a week old"
                + c.to().map(t -> "; taking " + shown.apply(t)).orElse("") + " (--allow-young takes it)"));
        c.ignored().ifPresent(i -> notes.add(now.typeTo(i) == UpdateType.MAJOR
                ? name + " " + shown.apply(i) + " is out, a major release " + DependabotRules.FILE
                        + " leaves out: a deliberate upgrade (docs/UPGRADING.md); --major " + name + " makes it"
                : name + " " + shown.apply(i) + " is out, a " + now.typeTo(i).name().toLowerCase(Locale.ROOT)
                        + " update " + DependabotRules.FILE + " leaves out (its ignore entries)"));
    }

    private static ToolFailure ignoredEntirely(String name) {
        return new ToolFailure("--major " + name + ": " + DependabotRules.FILE + " leaves out every update of it (it moves another way,"
                + " docs/UPGRADING.md), so the tool does not move it");
    }

    /** The reactor's POMs, tracked by git: the root one and every module's. */
    private List<String> poms() {
        return shell.out(List.of("git", "ls-files", "--", "pom.xml", "*/pom.xml")).lines().filter(l -> !l.isBlank()).toList();
    }

    /** The workflow files, tracked by git. */
    private List<String> workflows() {
        return shell.out(List.of("git", "ls-files", "--", WorkflowActions.FOLDER + "/*.yml", WorkflowActions.FOLDER + "/*.yaml")).lines()
                .filter(l -> !l.isBlank()).toList();
    }

    private String read(String file) {
        try {
            return Files.readString(root.resolve(file));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }
}

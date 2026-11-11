// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import common.ReleaseAge;
import common.Settings;
import common.ToolFailure;

import dependencies.ReleaseNumber.UpdateType;

/**
 * The Dependency tool's decisions that need no network ({@code releng/tools/dependencies}): which
 * version numbers are releases and how far a move goes, the rules read from
 * {@code dependabot.yml}, where the POMs keep their versions, how a pinned action is rewritten,
 * which release is taken (the week-old rule, ignored majors), and the embedded library's checks.
 * The last tests read the repository's own files, so a change that the tool could no longer
 * read (an action pinned to a tag, a key {@code dependabot.yml} gained) fails here first.
 */
class DependenciesTest {

    private static final Path ROOT = Path.of("../..");

    private static ReleaseNumber v(String text) {
        return ReleaseNumber.parse(text).orElseThrow();
    }

    @Test
    void onlyPlainReleaseNumbersCount() {
        assertEquals("3.6.1", v("3.6.1").toString());
        assertEquals("6.2.0", v("v6.2.0").toString()); // a tag
        assertEquals("1.8", v("1.8").toString());
        for (String preview : List.of("4.0.0-beta-2", "3.6.0-M1", "2.0.0-rc.1", "7", "v7", "1.2.3.4.5", "01.2", "")) {
            assertEquals(Optional.empty(), ReleaseNumber.parse(preview), preview);
        }
    }

    @Test
    void releasesCompareByNumberNotText() {
        assertTrue(v("3.10.0").compareTo(v("3.9.0")) > 0);
        assertTrue(v("3.6").same(v("3.6.0")));
        assertTrue(v("0.8.16").compareTo(v("0.8.15")) > 0);
    }

    @Test
    void theKindOfMoveIsTheFirstPlaceThatChanges() {
        assertEquals(UpdateType.PATCH, v("3.6.0").typeTo(v("3.6.1")));
        assertEquals(UpdateType.MINOR, v("3.6.3").typeTo(v("3.7.0")));
        assertEquals(UpdateType.MINOR, v("0.8.15").typeTo(v("0.9.0"))); // no special case for 0.x, as Dependabot
        assertEquals(UpdateType.MAJOR, v("5.14.4").typeTo(v("6.0.0")));
        assertEquals(Optional.of(UpdateType.MAJOR), UpdateType.ofYaml("version-update:semver-major"));
    }

    private static final String RULES = """
            version: 2
            updates:
              - package-ecosystem: maven
                directory: /
                schedule:
                  interval: weekly
                groups:
                  # a comment between keys
                  build-tools:
                    patterns:
                      - "org.apache.maven.plugins:*"
                      - "org.eclipse.tycho:*"
                ignore:
                  - dependency-name: "*"
                    update-types: ["version-update:semver-major"]
                  - dependency-name: "org.eclipse.platform:*"
              - package-ecosystem: github-actions
                directory: /
                groups:
                  actions:
                    patterns: ["*"]
            """;

    @Test
    void groupsAndIgnoresAreReadAsDependabotReadsThem() {
        DependabotRules rules = DependabotRules.parse(RULES);
        DependabotRules.Ecosystem maven = rules.ecosystem("maven").orElseThrow();
        assertEquals(Optional.of("build-tools"), maven.groupOf("org.apache.maven.plugins:maven-surefire-plugin"));
        assertEquals(Optional.empty(), maven.groupOf("org.jacoco:jacoco-maven-plugin"));
        assertTrue(maven.ignores("org.junit.jupiter:junit-jupiter", UpdateType.MAJOR));
        assertFalse(maven.ignores("org.junit.jupiter:junit-jupiter", UpdateType.MINOR));
        assertTrue(maven.ignoresAll("org.eclipse.platform:org.eclipse.swt"));
        assertFalse(maven.ignoresAll("org.msgpack:msgpack-core"));
        DependabotRules.Ecosystem actions = rules.ecosystem("github-actions").orElseThrow();
        assertEquals(Optional.of("actions"), actions.groupOf("actions/checkout"));
        assertFalse(actions.ignores("actions/checkout", UpdateType.MAJOR)); // no ignore entry there
        assertEquals(Optional.empty(), rules.ecosystem("npm"));
    }

    @Test
    void aStarStandsForAnyTextAndNothingElseIsSpecial() {
        assertTrue(DependabotRules.matches("org.eclipse.platform:*", "org.eclipse.platform:org.eclipse.text"));
        assertFalse(DependabotRules.matches("org.eclipse.platform:*", "orgXeclipse.platform:x")); // the dots are dots
        assertTrue(DependabotRules.matches("*", "actions/checkout"));
        assertFalse(DependabotRules.matches("actions/*-artifact", "actions/checkout"));
    }

    @Test
    void aRuleTheToolCannotFollowStopsIt() {
        // Each would change which versions are taken, so skipping it would update what Dependabot does not.
        for (String extra : List.of("    allow:\n      - dependency-name: \"x\"\n", "    cooldown:\n      default-days: 3\n",
                "    versioning-strategy: increase\n")) {
            String yaml = RULES.replace("    directory: /\n    schedule:", extra + "    directory: /\n    schedule:");
            assertThrows(ToolFailure.class, () -> DependabotRules.parse(yaml), extra);
        }
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("update-types: [\"version-update:semver-major\"]",
                "versions: [\">= 6\"]")));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("patterns: [\"*\"]",
                "patterns: [\"*\"]\n        exclude-patterns: [\"x\"]")));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("semver-major", "semver-huge")));
    }

    @Test
    void yamlBeyondTheFilesShapeIsRefusedNotGuessed() {
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("    directory: /\n", "\tdirectory: /\n")));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("interval: weekly", "interval: &w weekly")));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse("---\n" + RULES));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("version: 2", "version: 1")));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES.replace("\"org.eclipse.tycho:*\"", "\"org.eclipse.tycho:*")));
        assertThrows(ToolFailure.class, () -> DependabotRules.parse(RULES + "  - package-ecosystem: maven\n    directory: /\n"));
    }

    private static final String ROOT_POM = """
            <project>
              <properties>
                <tycho.version>5.0.4</tycho.version>
                <jacoco.version>0.8.15</jacoco.version>
                <junit.version>5.14.4</junit.version>
              </properties>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version>
                </dependency>
              </dependencies></dependencyManagement>
              <build><plugins>
                <plugin><groupId>org.eclipse.tycho</groupId><artifactId>tycho-maven-plugin</artifactId><version>${tycho.version}</version></plugin>
                <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.6.0</version></plugin>
                <plugin>
                  <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>${jacoco.version}</version>
                  <dependencies>
                    <dependency><groupId>org.jacoco</groupId><artifactId>org.jacoco.ant</artifactId><version>${jacoco.version}</version></dependency>
                  </dependencies>
                </plugin>
              </plugins></build>
            </project>
            """;

    @Test
    void eachPropertyKnowsTheArtifactsThatUseIt() {
        assertEquals(Map.of("tycho.version", "5.0.4", "jacoco.version", "0.8.15", "junit.version", "5.14.4"), PomVersions.properties(ROOT_POM));
        Map<String, Set<PomVersions.Artifact>> used = new LinkedHashMap<>();
        PomVersions.usedVersions(ROOT_POM, "pom.xml", used);
        PomVersions.usedVersions("<project><build><plugins><plugin><groupId>org.eclipse.tycho</groupId>"
                + "<artifactId>tycho-surefire-plugin</artifactId><version>${tycho.version}</version></plugin></plugins></build></project>",
                "tests/pom.xml", used);
        assertEquals(Set.of(new PomVersions.Artifact("org.eclipse.tycho", "tycho-maven-plugin"),
                new PomVersions.Artifact("org.eclipse.tycho", "tycho-surefire-plugin")), used.get("tycho.version"));
        assertEquals(Set.of(new PomVersions.Artifact("org.jacoco", "jacoco-maven-plugin"), new PomVersions.Artifact("org.jacoco", "org.jacoco.ant")),
                used.get("jacoco.version"));
        assertEquals(Set.of("tycho.version", "jacoco.version", "junit.version"), used.keySet()); // 3.6.0 is literal, not a property
    }

    @Test
    void aPropertyMovesOnlyWhereItIsWrittenOnce() {
        String moved = PomVersions.withProperty(ROOT_POM, "jacoco.version", "0.8.15", "0.8.16");
        assertTrue(moved.contains("<jacoco.version>0.8.16</jacoco.version>"));
        assertEquals(ROOT_POM, moved.replace("0.8.16", "0.8.15"));
        assertThrows(ToolFailure.class, () -> PomVersions.withProperty(ROOT_POM, "jacoco.version", "0.8.14", "0.8.16")); // not the value there
        assertThrows(ToolFailure.class, () -> PomVersions.withProperty(ROOT_POM + ROOT_POM, "jacoco.version", "0.8.15", "0.8.16"));
        assertThrows(ToolFailure.class, () -> PomVersions.withProperty(ROOT_POM, "a>b", "1", "2"));
    }

    private static final String SIGN_POM = """
            <project><build><plugins>
              <plugin>
                <groupId>org.eclipse.tycho</groupId>
                <artifactId>tycho-gpg-plugin</artifactId>
                <!-- Same as tycho.version in the root pom.xml. -->
                <version>5.0.4</version>
              </plugin>
            </plugins></build></project>
            """;

    @Test
    void aCopyMovesWithItsPropertyOnlyWhenNothingElseSharesTheNumber() {
        assertEquals(List.of(new PomVersions.Artifact("org.eclipse.tycho", "tycho-gpg-plugin")),
                PomVersions.literalCopies(SIGN_POM, "sign", Set.of("org.eclipse.tycho"), "5.0.4"));
        assertEquals(List.of(), PomVersions.literalCopies(SIGN_POM, "sign", Set.of("org.jacoco"), "5.0.4"));
        assertTrue(PomVersions.withCopies(SIGN_POM, "sign", 1, "5.0.4", "5.0.5").contains("<version>5.0.5</version>"));
        // Another artifact at the same number would move with it: refused.
        String two = SIGN_POM.replace("</plugins>", "<plugin><artifactId>x</artifactId><version>5.0.4</version></plugin></plugins>");
        assertThrows(ToolFailure.class, () -> PomVersions.withCopies(two, "sign", 1, "5.0.4", "5.0.5"));
    }

    @Test
    void centralsListGivesReleasesOnlyOldestFirst() {
        String metadata = "<metadata><versioning><versions><version>4.0.0-beta-2</version><version>3.6.1</version>"
                + "<version>3.6.0-M1</version><version>3.10.0</version><version>3.6.0</version></versions></versioning></metadata>";
        assertEquals(List.of(v("3.6.0"), v("3.6.1"), v("3.10.0")), PomVersions.releases(metadata));
    }

    @Test
    void aDoctypeIsNeverReadSoNoFileCanBePulledIn() {
        String xxe = "<!DOCTYPE m [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><metadata><version>&x;</version></metadata>";
        assertThrows(ToolFailure.class, () -> PomVersions.releases(xxe));
    }

    @Test
    void onlyRunTimeDependenciesOfALibraryCount() {
        String pom = """
                <project><dependencies>
                  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
                  <dependency><groupId>a</groupId><artifactId>provided</artifactId><scope>provided</scope></dependency>
                  <dependency><groupId>a</groupId><artifactId>optional</artifactId><optional>true</optional></dependency>
                  <dependency><groupId>a</groupId><artifactId>needed</artifactId></dependency>
                  <dependency><groupId>a</groupId><artifactId>runtime</artifactId><scope>runtime</scope></dependency>
                </dependencies></project>
                """;
        assertEquals(List.of(new PomVersions.Artifact("a", "needed"), new PomVersions.Artifact("a", "runtime")),
                PomVersions.runtimeDependencies(pom, "x"));
    }

    private static final String WORKFLOW = """
            jobs:
              build:
                steps:
                  - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
                  - uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0
                    with:
                      java-version: 21
                  # uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0, a comment
              site:
                uses: ./.github/workflows/build.yml
            """;

    @Test
    void pinnedActionsAreReadWithTheirRelease() {
        List<WorkflowActions.Use> uses = WorkflowActions.uses("w.yml", WORKFLOW);
        assertEquals(2, uses.size()); // the commented line and the repository's own workflow are not actions
        assertEquals(new WorkflowActions.Use("w.yml", 5, "actions/setup-java", "b843ebf9b913146b38da2eaa7aeddf76074ae1ad", "v6.1.0"),
                uses.get(1));
        assertEquals("github/codeql-action", new WorkflowActions.Use("w", 1, "github/codeql-action/init", "x", "v4.0.0").repository());
    }

    @Test
    void moreWordsAfterTheReleaseAreKeptNotMistakenForAnUnpinnedLine() {
        String line = "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1 (the last one for Node 20)\n";
        List<WorkflowActions.Use> uses = WorkflowActions.uses("w", line);
        assertEquals("v7.0.1", uses.get(0).release());
        String sha = "77dd5b2ce225ea11469928ac317850245a74ae78";
        assertEquals("      - uses: actions/checkout@" + sha + " # v7.1.0 (the last one for Node 20)\n",
                WorkflowActions.moved("w", line, "actions/checkout", "3d3c42e5aac5ba805825da76410c181273ba90b1", sha, "v7.1.0"));
        assertThrows(ToolFailure.class, () -> WorkflowActions.uses("w", "  - uses: actions/checkout@v7 # v7.0.1\n"));
    }

    @Test
    void anActionPinnedToATagOrWithoutItsReleaseStopsTheTool() {
        assertThrows(ToolFailure.class, () -> WorkflowActions.uses("w", "  - uses: actions/checkout@v7\n"));
        assertThrows(ToolFailure.class, () -> WorkflowActions.uses("w", "  - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1\n"));
        assertThrows(ToolFailure.class, () -> WorkflowActions.uses("w", "  - uses: actions/checkout@3d3c42e # v7.0.1\n"));
    }

    @Test
    void aMoveRewritesTheShaAndTheReleaseTogetherAndNothingElse() {
        String sha = "77dd5b2ce225ea11469928ac317850245a74ae78";
        String moved = WorkflowActions.moved("w.yml", WORKFLOW, "actions/setup-java", "b843ebf9b913146b38da2eaa7aeddf76074ae1ad", sha, "v6.2.0");
        assertTrue(moved.contains("      - uses: actions/setup-java@" + sha + " # v6.2.0\n        with:"));
        assertTrue(moved.contains("# uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0, a comment"));
        assertTrue(moved.contains("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1"));
        assertTrue(moved.endsWith("build.yml\n"));
        assertEquals(WORKFLOW.lines().count(), moved.lines().count());
        // What GitHub answered is checked before it goes into a workflow.
        assertThrows(ToolFailure.class, () -> WorkflowActions.moved("w", WORKFLOW, "actions/setup-java", "x", "not-a-sha", "v6.2.0"));
        assertThrows(ToolFailure.class, () -> WorkflowActions.moved("w", WORKFLOW, "actions/setup-java", "x", sha, "v6.2.0\nrun: evil"));
    }

    @Test
    void releaseTagsComeFromGhsLines() {
        List<WorkflowActions.Tag> tags = WorkflowActions.tags("v6.2.0\t2026-11-04T22:12:26Z\nnightly\t2026-11-01T00:00:00Z\n\nbroken line\n");
        assertEquals(2, tags.size());
        assertEquals(Optional.of(v("6.2.0")), tags.get(0).number());
        assertEquals(Optional.empty(), tags.get(1).number());
    }

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    void theNewestReleaseAWeekOldIsTakenAndAYoungerOneNoted() {
        Map<String, Instant> published = Map.of("3.6.1", daysAgo(20), "3.7.0", daysAgo(2));
        Updates.Choice c = Updates.Choice.of(v("3.6.0"), List.of(v("3.5.0"), v("3.6.0"), v("3.6.1"), v("3.7.0")), t -> false,
                r -> published.get(r.toString()), NOW, false);
        assertEquals(Optional.of(v("3.6.1")), c.to());
        assertEquals(Optional.of(v("3.7.0")), c.young());
        assertEquals(Optional.empty(), c.ignored());
        assertEquals(Optional.of(v("3.7.0")), Updates.Choice.of(v("3.6.0"), List.of(v("3.6.1"), v("3.7.0")), t -> false,
                r -> published.get(r.toString()), NOW, true).to()); // --allow-young
    }

    @Test
    void anIgnoredMajorIsNotedNotTakenAndItsDateNeverAskedFor() {
        Updates.Choice c = Updates.Choice.of(v("5.14.4"), List.of(v("5.14.4"), v("6.0.0"), v("6.1.3")), t -> t == UpdateType.MAJOR,
                r -> {
                    throw new AssertionError("no date needed for " + r);
                }, NOW, false);
        assertEquals(Optional.empty(), c.to());
        assertEquals(Optional.of(v("6.1.3")), c.ignored());
        assertEquals(Optional.empty(), Updates.Choice.of(v("1.0"), List.of(v("1.0"), v("0.9")), t -> false, r -> NOW, NOW, false).to());
    }

    @Test
    void theWeekIsCountedToTheSecond() {
        assertTrue(ReleaseAge.isYoung(NOW.minus(Duration.ofDays(7)).plusSeconds(1), NOW));
        assertFalse(ReleaseAge.isYoung(NOW.minus(Duration.ofDays(7)), NOW));
        assertEquals("maven-surefire-plugin", Updates.label("maven-surefire-plugin.version"));
    }

    private static Updates.MavenMove maven(String property, Optional<String> group) {
        return new Updates.MavenMove(property, "1.0.0", "1.1.0", Set.of(), Map.of(), group, false);
    }

    @Test
    void movesBecomeOneCommitPerGroupOnePerLoneDependencyAndOnePerMajor() {
        Updates.Found found = new Updates.Found(
                List.of(maven("maven-surefire-plugin.version", Optional.of("build-tools")), maven("jacoco.version", Optional.empty()),
                        maven("tycho.version", Optional.of("build-tools")), maven("junit.version", Optional.empty()),
                        maven("maven-clean-plugin.version", Optional.of("build-tools"))),
                List.of(new Updates.ActionMove("actions/checkout", Set.of("w"), Set.of("a"), "v7.0.1", "v7.1.0", Optional.of("actions"), false),
                        new Updates.ActionMove("actions/setup-java", Set.of("w"), Set.of("b"), "v6.1.0", "v6.2.0", Optional.of("actions"), false)),
                List.of(), List.of());
        List<DependenciesTool.Commit> commits = DependenciesTool.commits(found, Set.of("maven-clean-plugin"));
        assertEquals(List.of("build-tools", "jacoco", "junit", "maven-clean-plugin", "actions"),
                commits.stream().map(DependenciesTool.Commit::name).toList());
        assertEquals(List.of("maven-surefire-plugin.version", "tycho.version"),
                commits.get(0).maven().stream().map(Updates.MavenMove::property).toList()); // the major is not in its group's commit
        assertEquals(2, commits.get(4).size());
    }

    @Test
    void embeddedLicenseSettingsAndJarEntriesAreReadStrictly() {
        assertEquals(Map.of("NOTICE", "msgpack-java-NOTICE.txt", "LICENSE.txt", "Apache-2.0.txt"),
                Embedded.licenseFiles("NOTICE=msgpack-java-NOTICE.txt LICENSE.txt=Apache-2.0.txt"));
        assertThrows(ToolFailure.class, () -> Embedded.licenseFiles("NOTICE=../../etc/passwd"));
        assertEquals(List.of("META-INF/LICENSE", "NOTICE.txt", "META-INF/licenses/LICENCE-x"),
                Embedded.licenseEntries(List.of("META-INF/MANIFEST.MF", "META-INF/LICENSE", "NOTICE.txt", "org/x/Lic.class", "META-INF/licenses/LICENCE-x")));
        assertTrue(Embedded.sameText("a\r\nb\r\n\n", "a\nb"));
        assertFalse(Embedded.sameText("a\nb", "a\nc"));
    }

    @Test
    void theRepositorysOwnRulesWorkflowsAndPomsAreReadable() throws IOException {
        DependabotRules rules = DependabotRules.parse(Files.readString(ROOT.resolve(DependabotRules.FILE)));
        DependabotRules.Ecosystem maven = rules.ecosystem("maven").orElseThrow();
        assertTrue(maven.ignoresAll("org.eclipse.platform:org.eclipse.text"), "the Eclipse jars move with the Eclipse release");
        assertTrue(maven.ignoresAll("org.apache.maven:apache-maven"), "Maven moves with MavenUpgrade.java");
        assertTrue(maven.ignores("org.msgpack:msgpack-core", UpdateType.MAJOR));
        assertEquals(Optional.of("build-tools"), maven.groupOf("org.eclipse.tycho:tycho-maven-plugin"));
        assertTrue(rules.ecosystem("github-actions").isPresent());

        int actions = 0;
        try (Stream<Path> files = Files.list(ROOT.resolve(WorkflowActions.FOLDER))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                actions += WorkflowActions.uses(f.getFileName().toString(), Files.readString(f)).size(); // every action pinned, with its release
            }
        }
        assertTrue(actions > 0);

        Map<String, String> properties = PomVersions.properties(Files.readString(ROOT.resolve("pom.xml")));
        Map<String, Set<PomVersions.Artifact>> used = new LinkedHashMap<>();
        PomVersions.usedVersions(Files.readString(ROOT.resolve("pom.xml")), "pom.xml", used);
        for (String p : List.of("msgpack.version", "junit.version", "jacoco.version", "tycho.version")) {
            assertTrue(used.containsKey(p) && ReleaseNumber.parse(properties.get(p)).isPresent(), p);
        }
        // The copy the settings name: releng/sign/pom.xml's tycho-gpg-plugin.
        assertEquals(1, PomVersions.literalCopies(Files.readString(ROOT.resolve("releng/sign/pom.xml")), "releng/sign/pom.xml",
                Set.of("org.eclipse.tycho"), properties.get("tycho.version")).size());
        Settings settings = Settings.load(ROOT.resolve("releng/tools/dependencies"), Map.of());
        assertEquals(List.of("org.msgpack:msgpack-core"), Embedded.artifacts(settings));
        for (String license : Embedded.licenseFiles(settings.get("embedded.msgpack-core.licenses")).values()) {
            assertTrue(Files.isRegularFile(ROOT.resolve(Embedded.LICENSES).resolve(license)), license);
        }
    }
}

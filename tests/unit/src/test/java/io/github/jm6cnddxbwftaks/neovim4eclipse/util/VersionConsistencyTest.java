// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import common.EclipseRelease;
import common.VersionCopies;

/**
 * Keeps every copy of a version number in step with the root {@code pom.xml}.
 *
 * <p>Some files cannot use Maven properties: Tycho reads {@code MANIFEST.MF} and
 * {@code build.properties} as they are, and GitHub Actions reads the workflows. Those keep a literal
 * copy, and this test fails as soon as one of them, or the documentation, names a different version.
 * The fix is always the same: change the copy to match the root {@code pom.xml}, as described in
 * {@code docs/UPGRADING.md}.
 *
 * <p>Example: after setting {@code <java.release>25</java.release>}, this test lists every file that
 * still says {@code JavaSE-21}.
 *
 * <p>Neovim is the exception to "the root {@code pom.xml}": the plug-in needs its minimum at run
 * time, so the source of truth is {@link NeovimVersion#MINIMUM}, and CI and the docs are checked
 * against that.
 */
class VersionConsistencyTest {

    private static final String PLUGIN = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/";
    private static final String TEST_FRAGMENT = "tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/";
    private static final List<String> CHILD_POMS = List.of(
            PLUGIN + "pom.xml",
            TEST_FRAGMENT + "pom.xml",
            "tests/unit/pom.xml",
            "tests/text/pom.xml",
            "tests/swt/pom.xml",
            "features/io.github.jm6cnddxbwftaks.neovim4eclipse.feature/pom.xml",
            "releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/pom.xml");
    private static final List<String> WORKFLOWS = List.of(
            ".github/workflows/build.yml",
            ".github/workflows/release.yml",
            ".github/workflows/update-site.yml");
    /**
     * User-facing documents that state the supported Java and Eclipse versions. The feature's
     * description is one: Install New Software shows it before anyone installs.
     */
    private static final List<String> DOCS = List.of(
            "README.md", "CONTRIBUTING.md", "docs/HOWTO_ECLIPSE.md", "docs/LEARNING.md",
            "features/io.github.jm6cnddxbwftaks.neovim4eclipse.feature/feature.xml");

    /** "JDK 21", "Java 21", "Java 21+", "JavaSE-21": the number is the first group. */
    private static final Pattern JAVA_MENTION = Pattern.compile("\\b(?:JDK|Java|JavaSE-) ?(\\d+)\\b");
    /**
     * An Eclipse simultaneous release name such as 2024-12: releases come out in March, June,
     * September and December. A full date ("2026-12-01") is not a release name.
     */
    private static final Pattern ECLIPSE_RELEASE = Pattern.compile("\\b(20\\d\\d-(?:03|06|09|12))\\b(?!-\\d)");
    /**
     * A stated Neovim requirement: "Neovim 0.12.6", "Neovim 0.12.6+" or "0.12.6 or newer"; the
     * version is the first or the second group.
     */
    private static final Pattern NEOVIM_MENTION = Pattern.compile(
            "\\bNeovim (\\d+\\.\\d+(?:\\.\\d+)?)|\\b(\\d+\\.\\d+(?:\\.\\d+)?) or newer");
    /** Files that must state the Neovim minimum: the docs above, and the update site's category. */
    private static final List<String> NEOVIM_DOCS = List.of(
            "README.md", "CONTRIBUTING.md",
            "features/io.github.jm6cnddxbwftaks.neovim4eclipse.feature/feature.xml",
            "releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/category.xml");

    // ---- Java ----

    @Test
    void javaVersionFileMatchesTheRootPom() throws Exception {
        assertEquals(javaRelease(), read(".java-version").strip(),
                ".java-version (used by the GitHub workflows) must equal <java.release> in pom.xml");
    }

    @Test
    void manifestsRequireTheRootPomsJava() throws Exception {
        for (String manifest : List.of(PLUGIN + "META-INF/MANIFEST.MF", TEST_FRAGMENT + "META-INF/MANIFEST.MF")) {
            try (InputStream in = new FileInputStream(repoFile(manifest))) {
                String bree = new Manifest(in).getMainAttributes().getValue("Bundle-RequiredExecutionEnvironment");
                assertEquals("JavaSE-" + javaRelease(), bree, manifest + ": Bundle-RequiredExecutionEnvironment");
            }
        }
    }

    @Test
    void buildPropertiesCompileForTheRootPomsJava() throws Exception {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(repoFile(PLUGIN + "build.properties"))) {
            props.load(in);
        }
        assertEquals("JavaSE-" + javaRelease(), props.getProperty("jre.compilation.profile"),
                PLUGIN + "build.properties: jre.compilation.profile");
    }

    @Test
    void workflowsTakeTheJavaVersionFromTheFile() throws Exception {
        for (String workflow : WORKFLOWS) {
            String text = read(workflow);
            assertFalse(text.contains("java-version:"),
                    workflow + ": use 'java-version-file: .java-version' instead of a literal java-version");
            int setups = count(text, "actions/setup-java@");
            assertEquals(setups, count(text, "java-version-file: .java-version"),
                    workflow + ": every setup-java step needs java-version-file: .java-version");
        }
    }

    @Test
    void docsNameTheRootPomsJava() throws Exception {
        List<String> wrong = mentionsOtherThan(JAVA_MENTION, javaRelease());
        assertTrue(wrong.isEmpty(), "Java version in the docs differs from <java.release>: " + wrong);
    }

    // ---- Eclipse ----

    /** The oldest supported Eclipse: the release {@code <eclipse-repo.url>} in the root pom.xml names. */
    private static EclipseRelease oldestEclipse() throws Exception {
        String url = property("eclipse-repo.url");
        Matcher release = ECLIPSE_RELEASE.matcher(url);
        assertTrue(release.find(), "eclipse-repo.url should end in a release name such as 2024-12: " + url);
        return EclipseRelease.ofName(release.group(1));
    }

    /** The newest tested Eclipse: the second entry of the smoke job's matrix in update-site.yml. */
    private static EclipseRelease newestEclipse() throws Exception {
        List<EclipseRelease.SmokeEntry> entries = EclipseRelease.smokeEntries(read(".github/workflows/update-site.yml"));
        assertEquals(2, entries.size(), "update-site.yml's smoke matrix should have two entries, the oldest and the newest Eclipse");
        return EclipseRelease.ofNumber(entries.get(1).eclipse());
    }

    /**
     * The smoke job installs every release into the oldest supported Eclipse (its first entry) and
     * the newest tested one (its second): the first must be the release the plug-in is built
     * against, and each entry's download must be that entry's Platform. Example: {@code eclipse:
     * '4.34'} with {@code R-4.34-202411201800/eclipse-platform-4.34-linux-gtk-x86_64.tar.gz}.
     */
    @Test
    void theSmokeTestInstallsIntoTheOldestEclipseAndItsOwnDownloads() throws Exception {
        List<EclipseRelease.SmokeEntry> entries = EclipseRelease.smokeEntries(read(".github/workflows/update-site.yml"));
        assertEquals(oldestEclipse().number(), entries.get(0).eclipse(),
                "update-site.yml's first smoke entry should be the oldest supported Eclipse (eclipse-repo.url)");
        assertTrue(oldestEclipse().compareTo(newestEclipse()) < 0, "the newest tested Eclipse is not newer than the oldest");
        for (EclipseRelease.SmokeEntry e : entries) {
            assertTrue(e.platformUrl().contains("/R-" + e.eclipse() + "-") && e.platformUrl().endsWith(
                    "/eclipse-platform-" + e.eclipse() + "-linux-gtk-x86_64.tar.gz"), "smoke entry " + e.eclipse() + " downloads " + e.platformUrl());
        }
    }

    /**
     * Every copy of the two Eclipse releases that {@code releng/version-copies.txt} lists, each by
     * its name and by its number, holds that release, and every listed pattern still finds one.
     */
    @Test
    void theListedCopiesNameTheOldestAndNewestEclipse() throws Exception {
        Map<String, List<VersionCopies.Copy>> sections = VersionCopies.parse(read(VersionCopies.FILE));
        Map<String, String> expected = Map.of(
                "eclipse.oldest", oldestEclipse().name(), "eclipse.oldest.number", oldestEclipse().number(),
                "eclipse.newest", newestEclipse().name(), "eclipse.newest.number", newestEclipse().number());
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> section : expected.entrySet()) {
            List<VersionCopies.Copy> copies = sections.get(section.getKey());
            assertNotNull(copies, VersionCopies.FILE + " has no [" + section.getKey() + "] section");
            for (VersionCopies.Copy copy : copies) {
                List<String> found = VersionCopies.versionsIn(read(copy.file()), copy);
                if (found.isEmpty()) {
                    wrong.add(copy.file() + ": nothing matches " + copy.pattern());
                }
                for (String version : found) {
                    if (!version.equals(section.getValue())) {
                        wrong.add("[" + section.getKey() + "] " + copy.file() + ": " + copy.pattern() + " finds " + version
                                + ", not " + section.getValue());
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), "Eclipse copies in " + VersionCopies.FILE + " differ: " + wrong);
    }

    /**
     * A safety net for mentions no pattern lists: an Eclipse release name in the user-facing docs is
     * the oldest supported or the newest tested one. Example: "2025-06" in the README fails.
     */
    @Test
    void docsNameOnlyTheOldestAndNewestEclipse() throws Exception {
        List<String> wrong = mentionsOtherThan(ECLIPSE_RELEASE, Set.of(oldestEclipse().name(), newestEclipse().name()));
        assertTrue(wrong.isEmpty(), "Eclipse release in the docs is neither eclipse-repo.url's nor the newest smoke entry's: " + wrong);
    }

    // ---- Neovim ----

    /**
     * CI tests exactly the oldest supported Neovim, so the minimum is never an untested claim. Every
     * CI job installs Neovim with {@code java releng/UpdateNeovimDownloads.java install}, which takes
     * the version from {@code neovim-downloads.properties}; {@link #getNeovimInstallsTheMinimumNeovim}
     * ties that to {@link NeovimVersion#MINIMUM}. A download of its own in a workflow would bypass both.
     * The shell script that did this until 0.2.2 ({@code releng/install-neovim.sh}) must not come back:
     * its tree hash depended on the output format of the runner's {@code sha256sum}, which changed.
     */
    @Test
    void ciInstallsNeovimFromTheDataFile() throws Exception {
        String build = read(".github/workflows/build.yml");
        assertFalse(build.contains("releases/download"),
                "build.yml downloads Neovim itself: use java releng/UpdateNeovimDownloads.java install auto <folder>");
        assertEquals(3, build.split("java releng/UpdateNeovimDownloads.java install ", -1).length - 1,
                "build.yml: the build, integration and platforms jobs each install Neovim with "
                        + "java releng/UpdateNeovimDownloads.java install");
        assertFalse(new File(repoFile("releng/UpdateNeovimDownloads.java").getParentFile(), "install-neovim.sh").exists(),
                "releng/install-neovim.sh is back: CI installs Neovim with UpdateNeovimDownloads.java install");
    }

    /**
     * The minimum is the Neovim <i>Get Neovim</i> installs: {@link NeovimVersion#MINIMUM} is read
     * from {@code neovim-downloads.properties}, never written as a number of its own.
     */
    @Test
    void getNeovimInstallsTheMinimumNeovim() throws Exception {
        assertEquals(NeovimVersion.MINIMUM, NeovimRelease.bundled().version());
        assertFalse(read(PLUGIN + "src/io/github/jm6cnddxbwftaks/neovim4eclipse/util/NeovimVersion.java")
                .contains("MINIMUM = new NeovimVersion("), "NeovimVersion.MINIMUM is a number of its own again: it must"
                        + " be the data file's version (java releng/tools/neovim/NeovimUpgrade.java moves it)");
    }

    /**
     * Every copy of the Neovim version that {@code releng/version-copies.txt} lists holds the
     * minimum, and every listed pattern still finds one: a reworded sentence that no pattern
     * matches would no longer be rewritten by {@code java releng/tools/neovim/NeovimUpgrade.java}.
     */
    @Test
    void theListedCopiesNameTheMinimumNeovim() throws Exception {
        List<String> wrong = new ArrayList<>();
        List<VersionCopies.Copy> copies = VersionCopies.parse(read(VersionCopies.FILE)).get("neovim");
        assertNotNull(copies, VersionCopies.FILE + " has no [neovim] section");
        for (VersionCopies.Copy copy : copies) {
            List<String> found = VersionCopies.versionsIn(read(copy.file()), copy);
            if (found.isEmpty()) {
                wrong.add(copy.file() + ": nothing matches " + copy.pattern());
            }
            for (String version : found) {
                if (!version.equals(NeovimVersion.MINIMUM.toString())) {
                    wrong.add(copy.file() + ": " + copy.pattern() + " finds " + version);
                }
            }
        }
        assertTrue(wrong.isEmpty(), "Neovim copies in " + VersionCopies.FILE + " differ from " + NeovimVersion.MINIMUM + ": " + wrong);
    }

    @Test
    void docsNameTheMinimumNeovim() throws Exception {
        String expected = NeovimVersion.MINIMUM.toString();
        List<String> wrong = new ArrayList<>();
        for (String doc : NEOVIM_DOCS) {
            String[] lines = read(doc).split("\n", -1);
            boolean stated = false;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = NEOVIM_MENTION.matcher(lines[i]);
                while (m.find()) {
                    String version = m.group(1) != null ? m.group(1) : m.group(2);
                    if (version.equals(expected)) {
                        stated = true;
                    } else {
                        wrong.add(doc + ":" + (i + 1) + " '" + m.group() + "'");
                    }
                }
            }
            if (!stated) {
                wrong.add(doc + ": does not state Neovim " + expected);
            }
        }
        assertTrue(wrong.isEmpty(), "Neovim version in the docs differs from NeovimVersion.MINIMUM: " + wrong);
    }

    @Test
    void neovimPatternFindsBothWordings() {
        Matcher m = NEOVIM_MENTION.matcher("**Neovim 0.12.6 or newer**");
        assertTrue(m.find());
        assertEquals("0.12.6", m.group(1));
        m = NEOVIM_MENTION.matcher("(installed separately, 0.12.6 or newer)");
        assertTrue(m.find());
        assertEquals("0.12.6", m.group(2));
    }

    // ---- Maven plug-ins ----

    @Test
    void childPomsTakePluginVersionsFromTheRootPom() throws Exception {
        for (String pom : CHILD_POMS) {
            NodeList plugins = xml(pom).getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                Element plugin = (Element) plugins.item(i);
                String version = directChildText(plugin, "version");
                if (version != null) {
                    assertTrue(version.startsWith("${") && property(version.substring(2, version.length() - 1)) != null,
                            pom + ": " + directChildText(plugin, "artifactId") + " has version " + version
                                    + "; declare it in the root pom.xml (pluginManagement or a property) instead");
                }
            }
        }
    }

    /**
     * {@code releng/sign/pom.xml} has no parent (inheriting the root POM would load Tycho as a
     * build extension and resolve the whole target platform just to sign), so it holds a literal
     * copy of the Tycho version. Example: after setting {@code <tycho.version>5.1.0</tycho.version>},
     * this fails until the signing POM says 5.1.0 too.
     */
    @Test
    void signingPomUsesTheRootPomsTycho() throws Exception {
        String expected = property("tycho.version");
        assertNotNull(expected, "<tycho.version> missing from the root pom.xml");
        NodeList plugins = xml("releng/sign/pom.xml").getElementsByTagName("plugin");
        int found = 0;
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if ("tycho-gpg-plugin".equals(directChildText(plugin, "artifactId"))) {
                assertEquals(expected, directChildText(plugin, "version"),
                        "releng/sign/pom.xml: tycho-gpg-plugin's version differs from the root pom.xml's tycho.version");
                found++;
            }
        }
        assertEquals(1, found, "releng/sign/pom.xml should declare tycho-gpg-plugin once");
    }

    @Test
    void releasePatternIgnoresDates() {
        assertFalse(ECLIPSE_RELEASE.matcher("moves on 2026-10-19").find());
        assertFalse(ECLIPSE_RELEASE.matcher("on 2026-12-01").find());
        Matcher release = ECLIPSE_RELEASE.matcher("Eclipse 2025-06 (4.36)");
        assertTrue(release.find());
        assertEquals("2025-06", release.group(1));
        Matcher java = JAVA_MENTION.matcher("running on Java 21+");
        assertTrue(java.find());
        assertEquals("21", java.group(1));
    }

    // ---- helpers ----

    private static String javaRelease() throws Exception {
        String release = property("java.release");
        assertNotNull(release, "<java.release> missing from the root pom.xml");
        return release;
    }

    /** A {@code <properties>} entry of the root {@code pom.xml}, or {@code null}. */
    private static String property(String name) throws Exception {
        NodeList list = xml("pom.xml").getElementsByTagName("properties");
        for (int i = 0; i < list.getLength(); i++) {
            String value = directChildText((Element) list.item(i), name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Every match of {@code pattern} in {@link #DOCS} whose first group is not {@code expected}. */
    private static List<String> mentionsOtherThan(Pattern pattern, String expected) throws IOException {
        return mentionsOtherThan(pattern, Set.of(expected));
    }

    private static List<String> mentionsOtherThan(Pattern pattern, Set<String> expected) throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String doc : DOCS) {
            String[] lines = read(doc).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = pattern.matcher(lines[i]);
                while (m.find()) {
                    if (!expected.contains(m.group(1))) {
                        wrong.add(doc + ":" + (i + 1) + " '" + m.group() + "'");
                    }
                }
            }
        }
        return wrong;
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private static String directChildText(Element parent, String tag) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && child.getTagName().equals(tag)) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static Document xml(String relative) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(repoFile(relative));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repoFile(relative).toPath(), StandardCharsets.UTF_8);
    }

    /** A file of this repository, found by walking up from the working directory. */
    private static File repoFile(String relative) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile() && new File(dir, PLUGIN).isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(relative + " not found above " + System.getProperty("user.dir"));
    }
}

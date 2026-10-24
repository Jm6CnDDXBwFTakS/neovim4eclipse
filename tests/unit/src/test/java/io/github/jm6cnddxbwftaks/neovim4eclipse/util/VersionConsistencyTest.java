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
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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

    @Test
    void docsNameTheTargetEclipseRelease() throws Exception {
        String url = property("eclipse-repo.url");
        Matcher release = ECLIPSE_RELEASE.matcher(url);
        assertTrue(release.find(), "eclipse-repo.url should end in a release name such as 2024-12: " + url);
        List<String> wrong = mentionsOtherThan(ECLIPSE_RELEASE, release.group(1));
        assertTrue(wrong.isEmpty(), "Eclipse release in the docs differs from eclipse-repo.url: " + wrong);
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
        List<String> wrong = new ArrayList<>();
        for (String doc : DOCS) {
            String[] lines = read(doc).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = pattern.matcher(lines[i]);
                while (m.find()) {
                    if (!m.group(1).equals(expected)) {
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

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Keeps the repository importable into Eclipse: <em>File → Import → Maven → Existing Maven
 * Projects</em> (docs/HOWTO_ECLIPSE.md). The Maven build does not notice these mistakes; only the IDE
 * does, after a developer imports the projects, so they are checked here as plain files.
 *
 * <h2>build.properties: everything inside the project</h2>
 * PDE (Eclipse's plug-in tooling) reads each bundle's {@code build.properties} to set up the
 * project's classpath, and it resolves every folder there <em>inside</em> the project. A path that
 * leaves it ({@code ../shared/src/}) becomes a workspace path ({@code /shared}), which names no
 * project, so the import fails in "Updating classpath" with "Resource '/shared' does not exist".
 * Tycho, which builds the same bundle on the command line, takes the path as it is and succeeds, so
 * only this test catches it. Example: the UI test fragment once listed {@code ../shared/src/} in
 * {@code source..}; its helpers now live in its own {@code shared/} folder.
 *
 * <h2>build-helper source folders: they exist</h2>
 * The plain Maven test modules add sources from elsewhere with {@code build-helper-maven-plugin}
 * ({@code add-source}, {@code add-test-source}); m2e links each such folder into the project. A folder
 * that was moved is not an error for Maven (it compiles less, and a test may go missing), and m2e
 * links nothing, so the check is that each one exists.
 */
class ProjectLayoutTest {

    /** The repository root: the folder above the working directory that holds the root pom.xml and plugins/. */
    private static Path repo() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("plugins"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found above " + System.getProperty("user.dir"));
    }

    /** Files named {@code name} in the repository, outside build output and Git's own folder. */
    private static List<Path> filesNamed(String name) throws IOException {
        try (Stream<Path> files = Files.walk(repo())) {
            return files.filter(p -> p.getFileName().toString().equals(name))
                    .filter(p -> !isIgnored(repo().relativize(p)))
                    .sorted()
                    .toList();
        }
    }

    private static boolean isIgnored(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("target") || name.equals(".git") || name.equals("bin") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    /** The comma-separated entries of a {@code build.properties} key, trimmed; empty when the key is absent. */
    static List<String> entries(Properties properties, String key) {
        String value = properties.getProperty(key);
        List<String> entries = new ArrayList<>();
        if (value != null) {
            for (String entry : value.split(",")) {
                if (!entry.isBlank()) {
                    entries.add(entry.trim());
                }
            }
        }
        return entries;
    }

    /** Whether a {@code build.properties} path stays inside its project: relative, with no {@code ..}. */
    static boolean staysInside(String entry) {
        Path path = Path.of(entry);
        if (path.isAbsolute() || entry.startsWith("/") || entry.startsWith("\\")) {
            return false;
        }
        for (Path part : path) {
            if (part.toString().equals("..")) {
                return false;
            }
        }
        return true;
    }

    @Test
    void theCheckKnowsAnOutsidePath() {
        assertFalse(staysInside("../shared/src/"), "the path that broke the import");
        assertFalse(staysInside("/abs/src/"));
        assertFalse(staysInside("src/../../x/"));
        assertTrue(staysInside("src/"));
        assertTrue(staysInside("."));
        assertTrue(staysInside("META-INF/"));
    }

    @Test
    void everyBuildPropertiesPathStaysInsideItsProject() throws IOException {
        List<Path> files = filesNamed("build.properties");
        assertTrue(files.size() >= 3, "the plug-in, the UI test fragment and the feature: " + files);
        List<String> outside = new ArrayList<>();
        for (Path file : files) {
            Properties properties = new Properties();
            try (Reader in = Files.newBufferedReader(file)) {
                properties.load(in); // handles the "\" line continuations
            }
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("source.") && !key.startsWith("output.") && !key.equals("bin.includes")) {
                    continue;
                }
                for (String entry : entries(properties, key)) {
                    if (!staysInside(entry)) {
                        outside.add(repo().relativize(file) + ": " + key + " = " + entry);
                    }
                }
            }
        }
        assertEquals(List.of(), outside, "PDE resolves these inside the project, so the Eclipse import fails "
                + "(\"Resource '/...' does not exist\"); move the folder into the project");
    }

    @Test
    void everySourceFolderOfABundleExists() throws IOException {
        // Source folders must exist: a missing one compiles nothing in Tycho and is an error in PDE.
        // (output.. and bin.includes may name folders the build makes: bin/, lib/.)
        List<String> missing = new ArrayList<>();
        for (Path file : filesNamed("build.properties")) {
            Properties properties = new Properties();
            try (Reader in = Files.newBufferedReader(file)) {
                properties.load(in);
            }
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("source.")) {
                    for (String entry : entries(properties, key)) {
                        if (!Files.isDirectory(file.getParent().resolve(entry))) {
                            missing.add(repo().relativize(file) + ": " + key + " = " + entry);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyBuildHelperSourceFolderExists() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Path pom : filesNamed("pom.xml")) {
            NodeList plugins = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
                    .getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                Element plugin = (Element) plugins.item(i);
                NodeList ids = plugin.getElementsByTagName("artifactId");
                if (ids.getLength() == 0 || !"build-helper-maven-plugin".equals(ids.item(0).getTextContent().trim())) {
                    continue;
                }
                NodeList sources = plugin.getElementsByTagName("source");
                for (int s = 0; s < sources.getLength(); s++) {
                    String source = sources.item(s).getTextContent().trim();
                    Path folder = Path.of(source.replace("${project.basedir}", pom.getParent().toString())
                            .replace("${basedir}", pom.getParent().toString()));
                    checked++;
                    if (!Files.isDirectory(folder.normalize())) {
                        missing.add(repo().relativize(pom) + ": " + source);
                    }
                }
            }
        }
        assertTrue(checked >= 6, "tests/unit, tests/text and tests/swt each add two folders; found " + checked);
        assertEquals(List.of(), missing, "build-helper adds these folders, which do not exist");
    }
}

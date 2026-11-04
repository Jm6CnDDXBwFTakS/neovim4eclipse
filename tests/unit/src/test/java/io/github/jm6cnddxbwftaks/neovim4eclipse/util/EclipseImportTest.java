// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Keeps the Maven modules importable into Eclipse, which the command-line build cannot check.
 *
 * <p><b>The rule:</b> no POM may name one folder both as a source folder and as a resource folder.
 * Maven handles that fine, so {@code ./mvnw verify} and CI pass. Eclipse's m2e, though, turns each
 * folder into exactly one classpath entry, and for a folder given twice it keeps the resource
 * folder's {@code excluding="**"}: the source folder then compiles nothing. That happened once
 * (6040ed9): {@code tests/unit} added the plug-in's {@code src/} as a source folder (for its
 * classes) and as a resource folder (for {@code neovim-downloads.properties}), and after an import
 * the IDE showed over a thousand "cannot be resolved" errors.
 *
 * <p><b>The fix when this fails:</b> copy the data files with maven-resources-plugin's
 * {@code copy-resources} goal, as {@code tests/unit/pom.xml} does. It copies files into
 * {@code target/classes} without registering a folder, so it is left out of the check below.
 *
 * <p>What counts (example: {@link #pomWithResourceIn(String)}):
 * <ul>
 *   <li>Source folders: {@code <sourceDirectory>}, {@code <testSourceDirectory>}, and every
 *       {@code <source>} (build-helper's {@code add-source} and {@code add-test-source}).</li>
 *   <li>Resource folders: the {@code <directory>} of every {@code <resource>} or
 *       {@code <testResource>} (in {@code <build>}, or build-helper's {@code add-resource}),
 *       except inside maven-resources-plugin (its {@code copy-resources} only copies).</li>
 * </ul>
 * Paths are compared after {@code ${project.basedir}} (or {@code ${basedir}}) is replaced by the
 * module's folder and {@code ..} is resolved, so two spellings of one folder still match.
 */
class EclipseImportTest {

    /** Every POM below the root, the same list as {@code VersionConsistencyTest}'s. */
    private static final List<String> POMS = List.of(
            "pom.xml",
            "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/pom.xml",
            "tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/pom.xml",
            "tests/unit/pom.xml",
            "tests/text/pom.xml",
            "tests/swt/pom.xml",
            "features/io.github.jm6cnddxbwftaks.neovim4eclipse.feature/pom.xml",
            "releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/pom.xml");

    @Test
    void noPomNamesAFolderAsBothSourceAndResource() throws Exception {
        List<String> clashes = new ArrayList<>();
        for (String relative : POMS) {
            File pom = repoFile(relative);
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom);
            for (String folder : sharedFolders(doc, pom.getParentFile())) {
                clashes.add(relative + ": " + folder);
            }
        }
        assertTrue(clashes.isEmpty(), "A folder is both a source and a resource folder, which Eclipse's m2e "
                + "turns into a source folder that compiles nothing (see this class's javadoc; copy the "
                + "files with maven-resources-plugin's copy-resources instead): " + clashes);
    }

    /** The check itself: the POM that broke the Eclipse import (6040ed9) fails it, the fix passes. */
    @Test
    void theOldUnitPomIsCaught() throws Exception {
        File module = new File("/repo/tests/unit").getAbsoluteFile();
        String src = new File("/repo/plugins/p/src").getAbsolutePath();
        assertEquals(Set.of(src), sharedFolders(parse(pomWithResourceIn("build-helper-maven-plugin")), module),
                "build-helper's add-resource over a source folder is the clash");
        assertEquals(Set.of(), sharedFolders(parse(pomWithResourceIn("maven-resources-plugin")), module),
                "maven-resources-plugin's copy-resources registers no folder");
    }

    /**
     * A POM that adds {@code ../../plugins/p/src} as a source folder (build-helper) and names it as a
     * resource {@code <directory>} in the plug-in {@code artifactId}, spelled differently
     * ({@code ${basedir}}, a trailing slash) to show that the check compares folders, not text.
     */
    private static String pomWithResourceIn(String artifactId) {
        return """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals><goal>add-source</goal></goals>
                            <configuration>
                              <sources><source>${project.basedir}/../../plugins/p/src</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <artifactId>%s</artifactId>
                        <executions>
                          <execution>
                            <configuration>
                              <resources>
                                <resource><directory>${basedir}/../../plugins/p/src/</directory></resource>
                              </resources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(artifactId);
    }

    /**
     * The folders {@code doc} names both as a source and as a resource folder, as absolute paths.
     *
     * @param module the POM's own folder, which {@code ${project.basedir}} stands for
     */
    static Set<String> sharedFolders(Document doc, File module) {
        Set<String> sources = new LinkedHashSet<>();
        for (String tag : List.of("sourceDirectory", "testSourceDirectory", "source")) {
            NodeList list = doc.getElementsByTagName(tag);
            for (int i = 0; i < list.getLength(); i++) {
                sources.add(folder(list.item(i).getTextContent(), module));
            }
        }
        Set<String> shared = new LinkedHashSet<>();
        for (String tag : List.of("resource", "testResource")) {
            NodeList list = doc.getElementsByTagName(tag);
            for (int i = 0; i < list.getLength(); i++) {
                Element resource = (Element) list.item(i);
                if (insideResourcesPlugin(resource)) {
                    continue;
                }
                NodeList directories = resource.getElementsByTagName("directory");
                for (int j = 0; j < directories.getLength(); j++) {
                    String folder = folder(directories.item(j).getTextContent(), module);
                    if (sources.contains(folder)) {
                        shared.add(folder);
                    }
                }
            }
        }
        return shared;
    }

    /** Whether {@code node} sits inside a {@code <plugin>} whose artifactId is maven-resources-plugin. */
    private static boolean insideResourcesPlugin(Node node) {
        for (Node n = node.getParentNode(); n != null; n = n.getParentNode()) {
            if (n instanceof Element e && e.getTagName().equals("plugin")) {
                NodeList ids = e.getElementsByTagName("artifactId");
                // The plug-in's own artifactId is its first one; later ones belong to nested dependencies.
                return ids.getLength() > 0 && ids.item(0).getTextContent().trim().equals("maven-resources-plugin");
            }
        }
        return false;
    }

    /** A POM path as an absolute, normalized path: "${project.basedir}/../x/" in tests/unit gives tests/x. */
    private static String folder(String text, File module) {
        String path = text.trim().replace("${project.basedir}", module.getPath()).replace("${basedir}", module.getPath());
        // resolve keeps an absolute path as it is and puts a relative one under the module, as Maven does.
        return module.toPath().resolve(path).normalize().toString();
    }

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** A file of this repository, found by walking up from the working directory. */
    private static File repoFile(String relative) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile() && new File(dir, "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse").isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(relative + " not found above " + System.getProperty("user.dir"));
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Manifest;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Keeps the embedded third-party jars defined in one place. Versions live only in the root
 * {@code pom.xml}; the plug-in's {@code pom.xml} copies the jars into {@code lib/} without version
 * numbers, and {@code MANIFEST.MF} lists them. A jar copied but not on {@code Bundle-ClassPath}
 * (or the reverse) only fails at runtime inside Eclipse, so it is checked here.
 */
class BundleClassPathConsistencyTest {

    private static final String PLUGIN = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/";

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

    private static Document xml(String relative) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(repoFile(relative));
    }

    private static String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent().trim();
    }

    /** The {@code artifactItem}s of the plug-in's {@code maven-dependency-plugin} execution. */
    private static List<Element> artifactItems() throws Exception {
        NodeList list = xml(PLUGIN + "pom.xml").getElementsByTagName("artifactItem");
        List<Element> items = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            items.add((Element) list.item(i));
        }
        return items;
    }

    private static Set<String> manifestLibEntries() throws Exception {
        try (InputStream in = new FileInputStream(repoFile(PLUGIN + "META-INF/MANIFEST.MF"))) {
            String classPath = new Manifest(in).getMainAttributes().getValue("Bundle-ClassPath");
            Set<String> libs = new TreeSet<>();
            for (String entry : classPath.split(",")) {
                if (entry.trim().startsWith("lib/")) {
                    libs.add(entry.trim());
                }
            }
            return libs;
        }
    }

    /** {@code groupId:artifactId} of every dependency managed by the root {@code pom.xml}. */
    private static Set<String> managedInRoot() throws Exception {
        Element management = (Element) xml("pom.xml").getElementsByTagName("dependencyManagement").item(0);
        NodeList deps = management.getElementsByTagName("dependency");
        Set<String> managed = new TreeSet<>();
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            managed.add(childText(dep, "groupId") + ":" + childText(dep, "artifactId"));
        }
        return managed;
    }

    @Test
    void copiedJarsAreExactlyTheManifestBundleClassPath() throws Exception {
        Set<String> copied = new TreeSet<>();
        for (Element item : artifactItems()) {
            copied.add("lib/" + childText(item, "artifactId") + ".jar");
        }
        assertEquals(manifestLibEntries(), copied,
                "plug-in pom.xml artifactItems and MANIFEST.MF Bundle-ClassPath must list the same jars");
    }

    @Test
    void versionsComeOnlyFromTheRootPom() throws Exception {
        Set<String> managed = managedInRoot();
        for (Element item : artifactItems()) {
            String id = childText(item, "groupId") + ":" + childText(item, "artifactId");
            assertEquals(null, childText(item, "version"), id + ": set its version in the root pom.xml");
            assertTrue(managed.contains(id), id + " is missing from the root pom.xml dependencyManagement");
        }
    }

    @Test
    void manifestNamesCarryNoVersion() throws Exception {
        for (String entry : manifestLibEntries()) {
            assertTrue(!entry.matches(".*-\\d+(\\.\\d+)*\\.jar"),
                    entry + ": use version-less jar names; versions belong in the root pom.xml");
        }
    }
}

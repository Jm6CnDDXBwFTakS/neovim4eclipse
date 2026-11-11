// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import common.ToolFailure;

/**
 * Where the build's Maven versions are, as pure functions over the POMs' text: every library and
 * plug-in version is a property of the root {@code pom.xml} ({@code <jacoco.version>0.8.15</jacoco.version>}),
 * used as {@code ${jacoco.version}} by the artifacts that need it, in any of the reactor's POMs.
 * One property can serve several artifacts (Tycho's plug-ins all use {@code ${tycho.version}}),
 * so a new version is taken only when every one of them has it on Maven Central.
 *
 * <p>A <i>copy</i> is a literal version in a POM that cannot use the property: {@code releng/sign/pom.xml}
 * has no parent (it signs releases without loading the target platform), so its
 * {@code tycho-gpg-plugin} names {@code 5.0.4} itself. {@code releng/version-copies.txt} lists the
 * copies of each property in a section named after it, and they move with it.
 */
public final class PomVersions {

    /** Maven Central, where the versions are read. */
    public static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    /** An artifact, {@code groupId:artifactId} as Dependabot names it. */
    public record Artifact(String group, String id) {
        @Override
        public String toString() {
            return group + ":" + id;
        }

        /** Its folder on Central: {@code …/org/jacoco/jacoco-maven-plugin/}. */
        public String folder() {
            return CENTRAL + group.replace('.', '/') + "/" + id + "/";
        }

        /** Its POM at {@code version} on Central, whose date tells when that version came out. */
        public String pom(String version) {
            return folder() + version + "/" + id + "-" + version + ".pom";
        }

        /** Its jar at {@code version} on Central. */
        public String jar(String version) {
            return folder() + version + "/" + id + "-" + version + ".jar";
        }
    }

    /** The elements that name an artifact and its version: a plug-in, a dependency, a jar the build copies. */
    private static final Set<String> ARTIFACT_ELEMENTS = Set.of("plugin", "dependency", "artifactItem", "extension");

    /** A plug-in without a {@code <groupId>} is one of Maven's own. */
    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    private static final Pattern USE = Pattern.compile("\\$\\{([\\w.-]+)}");
    private static final Pattern NAME = Pattern.compile("[\\w.-]+");

    private PomVersions() {
    }

    /** The {@code <properties>} of a POM, in file order: {@code jacoco.version=0.8.15}. */
    public static Map<String, String> properties(String pomXml) {
        Map<String, String> out = new LinkedHashMap<>();
        Element project = parse(pomXml, "the POM").getDocumentElement();
        for (Element props : children(project, "properties")) {
            for (Element p : children(props, null)) {
                out.put(p.getTagName(), p.getTextContent().strip());
            }
        }
        return out;
    }

    /**
     * Each property a POM uses as an artifact's version, with the artifacts that use it:
     * {@code jacoco.version} to {@code org.jacoco:jacoco-maven-plugin} in the root POM and
     * {@code org.jacoco:org.jacoco.ant} in the UI tests' POM. Adds to {@code into}, so the
     * reactor's POMs are read one after the other.
     */
    public static void usedVersions(String pomXml, String where, Map<String, Set<Artifact>> into) {
        for (Map.Entry<Artifact, String> e : versions(pomXml, where).entrySet()) {
            Matcher m = USE.matcher(e.getValue());
            if (m.matches()) {
                into.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
    }

    /**
     * The artifacts of a POM that name {@code version} literally and belong to one of {@code groups}:
     * in {@code releng/sign/pom.xml} at Tycho 5.0.4, {@code org.eclipse.tycho:tycho-gpg-plugin}.
     */
    public static List<Artifact> literalCopies(String pomXml, String where, Set<String> groups, String version) {
        List<Artifact> out = new ArrayList<>();
        for (Map.Entry<Artifact, String> e : versions(pomXml, where).entrySet()) {
            if (e.getValue().equals(version) && groups.contains(e.getKey().group())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Every artifact a POM names with a version, and that version as written ({@code ${tycho.version}} or {@code 5.0.4}). */
    private static Map<Artifact, String> versions(String pomXml, String where) {
        Map<Artifact, String> out = new LinkedHashMap<>();
        collect(parse(pomXml, where).getDocumentElement(), where, out);
        return out;
    }

    private static void collect(Element e, String where, Map<Artifact, String> out) {
        if (ARTIFACT_ELEMENTS.contains(e.getTagName())) {
            Optional<String> id = text(e, "artifactId");
            Optional<String> version = text(e, "version");
            if (id.isPresent() && version.isPresent()) {
                String group = text(e, "groupId").orElse(e.getTagName().equals("plugin") ? DEFAULT_PLUGIN_GROUP : "");
                if (group.isEmpty()) {
                    throw new ToolFailure(where + ": <" + e.getTagName() + "> " + id.get() + " has a version but no groupId");
                }
                Artifact a = new Artifact(group, id.get());
                String old = out.put(a, version.get());
                if (old != null && !old.equals(version.get())) {
                    throw new ToolFailure(where + ": " + a + " has two versions, " + old + " and " + version.get());
                }
            }
        }
        for (Element child : children(e, null)) {
            collect(child, where, out);
        }
    }

    /**
     * The root POM with {@code property} set from {@code from} to {@code to}:
     * {@code <jacoco.version>0.8.15</jacoco.version>} becomes {@code …0.8.16…}. It must be
     * written that way exactly once, in {@code <properties>}, else the tool stops rather than guess.
     */
    public static String withProperty(String pomXml, String property, String from, String to) {
        if (!NAME.matcher(property).matches()) {
            throw new ToolFailure("not a property name: " + property);
        }
        String old = "<" + property + ">" + from + "</" + property + ">";
        return replaceOnce(pomXml, old, "<" + property + ">" + to + "</" + property + ">", "pom.xml");
    }

    /**
     * The versions a {@code maven-metadata.xml} on Central lists that are plain releases, oldest
     * first ({@link ReleaseNumber#parse}); {@code 4.0.0-beta-2} and {@code 3.6.0-M1} are left out.
     */
    public static List<ReleaseNumber> releases(String metadata) {
        List<ReleaseNumber> out = new ArrayList<>();
        Element root = parse(metadata, "maven-metadata.xml").getDocumentElement();
        NodeList versions = root.getElementsByTagName("version");
        for (int i = 0; i < versions.getLength(); i++) {
            ReleaseNumber.parse(versions.item(i).getTextContent()).ifPresent(out::add);
        }
        out.sort(null);
        return out;
    }

    /**
     * The dependencies a library's POM brings along at run time: not {@code test} or
     * {@code provided}, not {@code optional}. The plug-in copies only the jars it lists into
     * {@code lib/}, so a new one there must be added by hand (docs/UPGRADING.md).
     */
    public static List<Artifact> runtimeDependencies(String pomXml, String where) {
        List<Artifact> out = new ArrayList<>();
        Element project = parse(pomXml, where).getDocumentElement();
        for (Element deps : children(project, "dependencies")) {
            for (Element d : children(deps, "dependency")) {
                String scope = text(d, "scope").orElse("compile");
                boolean optional = text(d, "optional").orElse("false").equals("true");
                if (!optional && !scope.equals("test") && !scope.equals("provided")) {
                    out.add(new Artifact(text(d, "groupId").orElse("?"), text(d, "artifactId").orElse("?")));
                }
            }
        }
        return out;
    }

    static String replaceOnce(String text, String old, String now, String where) {
        int found = occurrences(text, old);
        if (found != 1) {
            throw new ToolFailure(where + " has " + found + " × " + old + ", expected exactly one");
        }
        return text.replace(old, now);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    /** XML without a DTD: a POM or metadata file from the network could otherwise read local files (XXE). */
    private static org.w3c.dom.Document parse(String xml, String where) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
            // The exception says what is wrong; the default handler would also print "[Fatal Error]" on stderr.
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ToolFailure("cannot read " + where + " as XML: " + e.getMessage());
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (name == null || e.getTagName().equals(name))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Optional<String> text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0).getTextContent().strip());
    }
}

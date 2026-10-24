// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Keeps {@code plugin.xml} in step with {@link VimCtrlKeys}. A missing handler or binding fails
 * silently at runtime (the key simply does nothing), so it is checked here instead.
 */
class PluginXmlConsistencyTest {

    private static Document load() throws Exception {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/plugin.xml");
            if (candidate.isFile()) {
                return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(candidate);
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("plugin.xml not found above " + System.getProperty("user.dir"));
    }

    /** The plug-in's folder, found the same way as {@link #load()}. */
    private static File pluginDir() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse");
            if (new File(candidate, "plugin.xml").isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("plug-in folder not found above " + System.getProperty("user.dir"));
    }

    private static Element extension(Document doc, String point) {
        NodeList list = doc.getElementsByTagName("extension");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            if (point.equals(e.getAttribute("point"))) {
                return e;
            }
        }
        throw new AssertionError("no extension point " + point);
    }

    private static Set<String> attributes(Element extension, String tag, String attribute) {
        Set<String> values = new HashSet<>();
        NodeList list = extension.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            values.add(((Element) list.item(i)).getAttribute(attribute));
        }
        return values;
    }

    @Test
    void theWelcomeNamesTheToggleKeyThatPluginXmlBinds() throws Exception {
        NodeList list = extension(load(), "org.eclipse.ui.bindings").getElementsByTagName("key");
        Set<String> sequences = new HashSet<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            if (WelcomeNote.TOGGLE_COMMAND_ID.equals(e.getAttribute("commandId"))) {
                sequences.add(e.getAttribute("sequence"));
            }
        }
        assertEquals(Set.of(WelcomeNote.DEFAULT_TOGGLE_SEQUENCE), sequences,
                "WelcomeNote.DEFAULT_TOGGLE_SEQUENCE must be the toggle's binding in plugin.xml");
    }

    /**
     * Every {@code icon="..."} in plugin.xml names a 16x16 PNG that exists, has a 32x32 {@code @2x}
     * twin for 200% screens, and lies in a folder that build.properties packages. A missing file or
     * folder fails silently at runtime: the button just shows no picture once installed.
     */
    @Test
    void everyIconExistsInBothSizesAndIsPackaged() throws Exception {
        File plugin = pluginDir();
        String binIncludes = Files.readString(new File(plugin, "build.properties").toPath());
        NodeList all = load().getElementsByTagName("*");
        int icons = 0;
        for (int i = 0; i < all.getLength(); i++) {
            String icon = ((Element) all.item(i)).getAttribute("icon");
            if (icon.isEmpty()) {
                continue;
            }
            icons++;
            assertIcon(new File(plugin, icon), 16);
            assertIcon(new File(plugin, icon.replaceFirst("\\.png$", "@2x.png")), 32);
            String folder = icon.substring(0, icon.indexOf('/') + 1);
            assertTrue(binIncludes.contains(folder), "build.properties bin.includes lacks " + folder);
        }
        assertTrue(icons > 0, "no icon= in plugin.xml (the toolbar button has one)");
    }

    private static void assertIcon(File file, int size) throws Exception {
        assertTrue(file.isFile(), "missing " + file);
        BufferedImage image = ImageIO.read(file);
        assertEquals(size + "x" + size, image.getWidth() + "x" + image.getHeight(), file.getName());
        assertTrue(image.getColorModel().hasAlpha(), file.getName() + " has no transparency");
    }

    @Test
    void everyCtrlKeyHasACommand() throws Exception {
        Set<String> commands = attributes(extension(load(), "org.eclipse.ui.commands"), "command", "id");
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertTrue(commands.contains(VimCtrlKeys.commandId(letter)), "missing command for Ctrl-" + letter);
        }
    }

    @Test
    void everyCtrlKeyCommandHasTheSendKeyHandler() throws Exception {
        Map<String, String> handlers = new HashMap<>();
        NodeList list = extension(load(), "org.eclipse.ui.handlers").getElementsByTagName("handler");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            handlers.put(e.getAttribute("commandId"), e.getAttribute("class"));
        }
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertEquals("io.github.jm6cnddxbwftaks.neovim4eclipse.editor.SendKeyHandler",
                    handlers.get(VimCtrlKeys.commandId(letter)), "handler for Ctrl-" + letter);
        }
    }

    @Test
    void everyCtrlKeyIsBoundOnWindowsLinuxAndMacInItsOwnContext() throws Exception {
        NodeList list = extension(load(), "org.eclipse.ui.bindings").getElementsByTagName("key");
        Map<String, String> sequences = new HashMap<>(); // "commandId|platform" -> sequence
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            String commandId = e.getAttribute("commandId");
            if (VimCtrlKeys.isCtrlKeyCommand(commandId)) {
                char letter = commandId.charAt(commandId.length() - 1);
                assertEquals(VimCtrlKeys.contextId(letter), e.getAttribute("contextId"), "context of " + commandId);
                sequences.put(e.getAttribute("commandId") + "|" + e.getAttribute("platform"), e.getAttribute("sequence"));
            }
        }
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            String id = VimCtrlKeys.commandId(letter);
            String upper = String.valueOf(Character.toUpperCase(letter));
            assertEquals("M1+" + upper, sequences.get(id + "|win32"), "win32 Ctrl-" + letter);
            assertEquals("M1+" + upper, sequences.get(id + "|gtk"), "gtk Ctrl-" + letter);
            // On macOS M1 is Command (Paste etc. must keep working); the Control key is M4.
            assertEquals("M4+" + upper, sequences.get(id + "|cocoa"), "cocoa Ctrl-" + letter);
        }
    }

    @Test
    void noCommandParametersAreUsed() throws Exception {
        // Parameterized key bindings did not deliver their parameter on Linux; see VimCtrlKeys.
        Document doc = load();
        assertEquals(0, doc.getElementsByTagName("commandParameter").getLength());
    }

    @Test
    void theNeovimContextIsDefinedUnderTheTextEditorScope() throws Exception {
        NodeList list = extension(load(), "org.eclipse.ui.contexts").getElementsByTagName("context");
        boolean found = false;
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            if ("io.github.jm6cnddxbwftaks.neovim4eclipse.context.normal".equals(e.getAttribute("id"))) {
                found = true;
                assertEquals("org.eclipse.ui.textEditorScope", e.getAttribute("parentId"));
            }
        }
        assertTrue(found);
        assertFalse(attributes(extension(load(), "org.eclipse.ui.bindings"), "key", "contextId").isEmpty());
    }

    /**
     * A {@code visibleWhen} test of a property no tester declares is never true: the menu or button
     * would vanish for good, with only a log entry to say why. The menu and the toolbar button each
     * have one ({@code showMenu}, {@code showToolbar}).
     */
    @Test
    void everyVisibleWhenPropertyIsDeclaredByThePropertyTester() throws Exception {
        Document doc = load();
        Element tester = (Element) extension(doc, "org.eclipse.core.expressions.propertyTesters")
                .getElementsByTagName("propertyTester").item(0);
        Set<String> declared = new HashSet<>();
        for (String property : tester.getAttribute("properties").split(",")) {
            declared.add(tester.getAttribute("namespace") + "." + property.strip());
        }
        Set<String> used = attributes(extension(doc, "org.eclipse.ui.menus"), "test", "property");
        assertEquals(Set.of("io.github.jm6cnddxbwftaks.neovim4eclipse.showMenu",
                "io.github.jm6cnddxbwftaks.neovim4eclipse.showToolbar"), used);
        assertTrue(declared.containsAll(used), "declared: " + declared + ", used: " + used);
    }

    @Test
    void everyCtrlKeyHasItsOwnContextUnderTheNeovimContext() throws Exception {
        Map<String, String> parents = new HashMap<>();
        NodeList list = extension(load(), "org.eclipse.ui.contexts").getElementsByTagName("context");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            parents.put(e.getAttribute("id"), e.getAttribute("parentId"));
        }
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertEquals(VimCtrlKeys.NORMAL_CONTEXT_ID, parents.get(VimCtrlKeys.contextId(letter)), "context of Ctrl-" + letter);
        }
    }
}

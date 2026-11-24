// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import common.ToolFailure;
import plugins.Pins.Pin;

/** {@link Pins}: the pins file is read whole or not at all, and a pin is moved by changing two lines. */
class PinsTest {

    /** Made-up values: a real commit or hash is written in the pins file only. */
    private static final String COMMIT = "c1".repeat(20);
    private static final String SHA = "d2".repeat(32);
    private static final String TEXT = """
            # a comment

            flash.repository=https://github.com/folke/flash.nvim
            flash.commit=%s
            flash.sha256=%s
            flash.license=Apache-2.0

            mini.jump2d.repository=https://github.com/echasnovski/mini.jump2d
            mini.jump2d.commit=%s
            mini.jump2d.sha256=%s
            mini.jump2d.license=MIT
            """.formatted(COMMIT, SHA, COMMIT.replace('1', '3'), SHA.replace('2', '4'));

    @Test
    void thePinsAreReadInTheFilesOrder() {
        List<Pin> pins = Pins.parse(TEXT);
        assertEquals(List.of("flash", "mini.jump2d"), pins.stream().map(Pin::name).toList(), "a name may hold a dot");
        Pin flash = pins.get(0);
        assertEquals(new Pin("flash", "https://github.com/folke/flash.nvim", COMMIT, SHA, "Apache-2.0"), flash);
        assertEquals("https://github.com/folke/flash.nvim/archive/" + COMMIT + ".zip", flash.archiveUrl());
        assertEquals(COMMIT + " " + SHA, flash.marker());
        assertEquals("https://github.com/echasnovski/mini.jump2d", pins.get(1).repository());
    }

    /** Half a pin, or a value of the wrong form, stops the tool: it must not be taken for a whole one. */
    @Test
    void anIncompleteOrMalformedPinIsRefused() {
        refused(TEXT.replace("flash.license=Apache-2.0\n", ""), "flash.license is missing");
        refused(TEXT.replace(COMMIT, "c1c1c1c"), "flash.commit");
        refused(TEXT.replace(SHA, SHA.toUpperCase()), "flash.sha256");
        refused(TEXT.replace("https://github.com/folke/flash.nvim", "http://github.com/folke/flash.nvim"), "flash.repository");
        refused(TEXT.replace("https://github.com/folke/flash.nvim", "https://github.com/folke/flash.nvim;rm -rf x"), "flash.repository");
        refused(TEXT + "flash.commit=" + COMMIT + "\n", "given twice");
        refused(TEXT + "flash.branch=main\n", "the field one of");
        refused(TEXT + "no equals sign\n", "not name.field=value");
        // One form only: the tests find a pin's commit by searching the file for "name.commit=".
        refused(TEXT.replace("flash.commit=", "flash.commit = "), "around the =");
        refused(TEXT.replace("flash.commit=", "  flash.commit="), "before the name");
        refused(TEXT.replace("flash.commit=", "flash. commit="), "the field one of");
        refused(TEXT.replace("flash.commit=", "flash.commit=\u2003"), "flash.commit");
        // A plug-in named as one of the tool's own folders would be unpacked over it.
        refused(TEXT.replace("flash.", "vetting."), "the tool uses itself");
        refused(TEXT.replace("flash.", "x.pin."), "the tool uses itself");
        refused(TEXT.replace("flash.", "Vetting."), "the tool uses itself");
        refused(TEXT + TEXT.substring(0, TEXT.indexOf("mini.")).replace("flash.", "Flash."), "only in case");
    }

    @Test
    void movingAPinChangesItsTwoLinesAndNothingElse() {
        String commit = "a".repeat(40);
        String sha = "b".repeat(64);
        String moved = Pins.withPin(TEXT, "flash", commit, sha);
        assertEquals(TEXT.replace(COMMIT, commit).replace(SHA, sha), moved);
        assertEquals(new Pin("flash", "https://github.com/folke/flash.nvim", commit, sha, "Apache-2.0"), Pins.find(Pins.parse(moved), "flash"));
        assertEquals(Pins.parse(TEXT).get(1), Pins.parse(moved).get(1), "the other plug-in's pin is as it was");
        assertThrows(ToolFailure.class, () -> Pins.withPin(TEXT, "leap", commit, sha), "no such pin");
        assertThrows(ToolFailure.class, () -> Pins.withPin(TEXT, "flash", "abc", sha), "no commit id");
    }

    /**
     * The repository's own file is complete, and it is the only place a pinned commit is written:
     * the four jump plug-ins the editor knows by default each have a pin, and so have the plug-in
     * of the gallery's pictures and those of the tests of floating windows that take the keyboard
     * (a picker with its library, a plug-in manager's window).
     */
    @Test
    void theRepositorysFileIsCompleteAndPinsEachPlugInATestRuns() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(TestPluginsTool.PINS_FILE))) {
            root = root.getParent();
        }
        assertTrue(root != null, "the repository's root holds " + TestPluginsTool.PINS_FILE);
        List<Pin> pins = Pins.parse(Files.readString(root.resolve(TestPluginsTool.PINS_FILE)));
        assertEquals(List.of("flash", "leap", "hop", "mini.jump2d", "which-key", "telescope", "plenary", "lazy", "fzf-lua"),
                pins.stream().map(Pin::name).toList());
    }

    private static void refused(String text, String inMessage) {
        ToolFailure f = assertThrows(ToolFailure.class, () -> Pins.parse(text));
        assertTrue(f.getMessage().contains(inMessage), f.getMessage());
    }
}

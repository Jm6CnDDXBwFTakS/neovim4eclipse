// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.Ask;
import common.Folders;
import common.Http;
import common.ToolFailure;

/**
 * {@link TestPluginsTool}'s rules that need no network: what an archive must look like, what
 * counts as a changed file, and that {@code accept} opens only the archive that was vetted.
 * The archives are made here, in a temporary folder; nothing is downloaded.
 */
class TestPluginsToolTest {

    /** Made-up values, as in {@link PinsTest}. */
    private static final String COMMIT = "c1".repeat(20);
    private static final String PINS = """
            flash.repository=https://github.com/folke/flash.nvim
            flash.commit=%s
            flash.sha256=%s
            flash.license=Apache-2.0
            """.formatted(COMMIT, "d2".repeat(32));

    /** A repository's archive holds one top folder; it becomes the plug-in's folder, with {@code lua/} right under it. */
    @Test
    void anArchiveOfOneTopFolderIsUnpackedAsThePlugInsFolder(@TempDir Path dir) throws IOException {
        Path archive = zip(dir.resolve("a.zip"), Map.of("flash.nvim-abc/lua/flash.lua", "return {}", "flash.nvim-abc/LICENSE", "Apache"));
        Path folder = dir.resolve("flash");
        Files.createDirectories(folder.resolve("old"));
        TestPluginsTool.unpack("a.zip", Files.readAllBytes(archive), folder);
        assertEquals("return {}", Files.readString(folder.resolve("lua/flash.lua")));
        assertFalse(Files.exists(folder.resolve("old")), "what was there before is replaced, not mixed in");
        assertFalse(Files.exists(dir.resolve("flash.unpacking")), "the scratch folder is gone");
    }

    /**
     * Files at the top, or two folders, are no repository's archive; neither is one with an entry
     * that would land outside its folder, or bytes that are no ZIP at all (an empty one). Nothing
     * is left as the plug-in's folder, and nothing of the scratch folder.
     */
    @Test
    void anArchiveOfAnotherShapeIsRefused(@TempDir Path dir) throws IOException {
        Path flat = zip(dir.resolve("flat.zip"), Map.of("init.lua", "x", "lua/a.lua", "y"));
        Path two = zip(dir.resolve("two.zip"), Map.of("a/x.lua", "x", "b/y.lua", "y"));
        Path outside = zip(dir.resolve("outside.zip"), Map.of("plug/lua/a.lua", "x", "plug/../../escaped.lua", "y"));
        Path noZip = Files.writeString(dir.resolve("no.zip"), "not a ZIP");
        Path folder = dir.resolve("plug");
        Map<Path, String> messages = Map.of(flat, "one top folder", two, "one top folder", outside, "outside its folder", noZip, "one top folder");
        for (Path archive : List.of(flat, two, outside, noZip)) {
            byte[] bytes = Files.readAllBytes(archive);
            ToolFailure f = assertThrows(ToolFailure.class, () -> TestPluginsTool.unpack(archive.getFileName().toString(), bytes, folder));
            assertTrue(f.getMessage().contains(messages.get(archive)), f.getMessage());
            assertFalse(Files.exists(dir.resolveSibling("escaped.lua")) || Files.exists(dir.resolve("escaped.lua")), "nothing written outside");
            assertFalse(Files.exists(folder), "no folder a test could take for the plug-in");
            assertFalse(Files.exists(dir.resolve("plug.unpacking")), "the scratch folder is gone after a refusal too");
        }
    }

    /** An archive is unpacked only up to a limit of bytes: a small ZIP can hold gigabytes of zeros. */
    @Test
    void unpackingStopsAtTheLimit(@TempDir Path dir) throws IOException {
        byte[] bytes = Files.readAllBytes(zip(dir.resolve("big.zip"), Map.of("plug/a.lua", "x".repeat(600), "plug/b.lua", "y".repeat(600))));
        ToolFailure f = assertThrows(ToolFailure.class, () -> Folders.unzip("big.zip", bytes, dir.resolve("out"), 1000));
        assertTrue(f.getMessage().contains("more than 1000 bytes"), f.getMessage());
        Folders.unzip("big.zip", bytes, dir.resolve("fits"), 1200);
        assertEquals(600, Files.size(dir.resolve("fits/plug/b.lua")));
    }

    /**
     * {@code fetch} with the archive already downloaded (so no network is asked): the pinned hash
     * unpacks it and writes the marker; another hash deletes the archive and unpacks nothing.
     */
    @Test
    void fetchUnpacksOnlyAnArchiveWithThePinnedHash(@TempDir Path root) throws IOException {
        Path folder = root.resolve(TestPluginsTool.FOLDER);
        Path archive = zip(folder.resolve("archives").resolve("flash-" + COMMIT + ".zip"), Map.of("flash.nvim-x/lua/flash.lua", "return {}"));
        String sha = Http.hash(archive, "SHA-256");
        TestPluginsTool tool = new TestPluginsTool(root, new Ask(Ask.Mode.YES, "rerun"));

        write(root.resolve(TestPluginsTool.PINS_FILE), PINS);
        ToolFailure f = assertThrows(ToolFailure.class, tool::fetch);
        assertTrue(f.getMessage().contains("it was deleted, not unpacked"), f.getMessage());
        assertFalse(Files.exists(archive) || Files.exists(folder.resolve("flash")) || Files.exists(folder.resolve("flash.pin")));

        zip(archive, Map.of("flash.nvim-x/lua/flash.lua", "return {}"));
        write(root.resolve(TestPluginsTool.PINS_FILE), PINS.replace("d2".repeat(32), sha));
        tool.fetch();
        assertTrue(Files.isRegularFile(folder.resolve("flash/lua/flash.lua")));
        assertEquals(COMMIT + " " + sha, Files.readString(folder.resolve("flash.pin")).strip());
    }

    /** What {@code accept} lists: a file only in the new commit (+), only in the old (-), or with other bytes (~). */
    @Test
    void theDifferencesNameAddedRemovedAndChangedFiles(@TempDir Path dir) throws IOException {
        Path before = dir.resolve("before");
        Path after = dir.resolve("after");
        write(before.resolve("lua/same.lua"), "1");
        write(after.resolve("lua/same.lua"), "1");
        write(before.resolve("lua/changed.lua"), "1");
        write(after.resolve("lua/changed.lua"), "2");
        write(before.resolve("gone.txt"), "1");
        write(after.resolve("new.txt"), "1");
        assertEquals(List.of("~ lua/changed.lua", "+ new.txt", "- gone.txt"), TestPluginsTool.differences(before, after));
        assertEquals(List.of(), TestPluginsTool.differences(before, before));
    }

    /**
     * {@code accept} is given the hash that was vetted. A file with another hash (a second
     * {@code update} brought other bytes, or the file was replaced) is not opened and no pin is
     * written; with the right hash the archive is unpacked and the pin's two lines are written.
     */
    @Test
    void acceptOpensOnlyTheArchiveWithTheVettedHash(@TempDir Path root) throws IOException {
        String next = "a".repeat(40);
        write(root.resolve(TestPluginsTool.PINS_FILE), PINS);
        Path archive = zip(root.resolve(TestPluginsTool.FOLDER).resolve("vetting").resolve("flash-" + next + ".zip"),
                Map.of("flash.nvim-" + next + "/lua/flash.lua", "return {}"));
        Path unpacked = archive.resolveSibling("flash-" + next);
        String sha = Http.hash(archive, "SHA-256");
        TestPluginsTool tool = new TestPluginsTool(root, new Ask(Ask.Mode.YES, "rerun"));

        ToolFailure f = assertThrows(ToolFailure.class, () -> tool.accept("flash", next, "b".repeat(64)));
        assertTrue(f.getMessage().contains("was not opened"), f.getMessage());
        assertFalse(Files.exists(unpacked), "nothing unpacked");
        assertEquals(PINS, Files.readString(root.resolve(TestPluginsTool.PINS_FILE)), "no pin written");
        f = assertThrows(ToolFailure.class, () -> tool.accept("flash", next, "not a hash"));
        assertTrue(f.getMessage().contains("64 hex digits"), f.getMessage());
        assertThrows(ToolFailure.class, () -> tool.accept("flash", "b".repeat(40), sha), "a commit update never downloaded");

        tool.accept("flash", next, sha);
        assertTrue(Files.isRegularFile(unpacked.resolve("lua/flash.lua")));
        String written = Files.readString(root.resolve(TestPluginsTool.PINS_FILE));
        assertTrue(written.contains("flash.commit=" + next + "\n") && written.contains("flash.sha256=" + sha + "\n"), written);
    }

    /**
     * The tool takes the commit the maintainer names and no other form of it: a short id or a
     * tag is refused before anything is asked of the network (the pins file is not even needed
     * to be right about the repository: no download starts).
     */
    @Test
    void updateTakesOnlyAFullCommitId(@TempDir Path root) throws IOException {
        write(root.resolve(TestPluginsTool.PINS_FILE), PINS);
        TestPluginsTool tool = new TestPluginsTool(root, new Ask(Ask.Mode.YES, "rerun"));
        for (String notACommit : List.of("abc1234", "v2.1.0", "main", "A".repeat(40))) {
            ToolFailure f = assertThrows(ToolFailure.class, () -> tool.update("flash", notACommit));
            assertTrue(f.getMessage().contains("full commit id"), f.getMessage());
        }
        assertThrows(ToolFailure.class, () -> tool.update("leap", COMMIT), "no such pin");
        assertFalse(Files.exists(root.resolve(TestPluginsTool.FOLDER)), "nothing was downloaded");
    }

    /** A dry run of {@code accept} shows everything and writes no pin; so does accepting what is pinned already. */
    @Test
    void acceptWritesNothingInADryRunOrForThePinItHas(@TempDir Path root) throws IOException {
        String next = "a".repeat(40);
        write(root.resolve(TestPluginsTool.PINS_FILE), PINS);
        Path vetting = root.resolve(TestPluginsTool.FOLDER).resolve("vetting");
        String sha = Http.hash(zip(vetting.resolve("flash-" + next + ".zip"), Map.of("flash.nvim-x/lua/flash.lua", "return {}")), "SHA-256");
        new TestPluginsTool(root, new Ask(Ask.Mode.DRY_RUN, "rerun")).accept("flash", next, sha);
        assertEquals(PINS, Files.readString(root.resolve(TestPluginsTool.PINS_FILE)), "a dry run writes no pin");

        String same = Http.hash(zip(vetting.resolve("flash-" + COMMIT + ".zip"), Map.of("flash.nvim-x/lua/flash.lua", "return {}")), "SHA-256");
        String pinned = PINS.replace("d2".repeat(32), same);
        write(root.resolve(TestPluginsTool.PINS_FILE), pinned);
        long before = Files.getLastModifiedTime(root.resolve(TestPluginsTool.PINS_FILE)).toMillis();
        new TestPluginsTool(root, new Ask(Ask.Mode.YES, "rerun")).accept("flash", COMMIT, same);
        assertEquals(pinned, Files.readString(root.resolve(TestPluginsTool.PINS_FILE)));
        assertEquals(before, Files.getLastModifiedTime(root.resolve(TestPluginsTool.PINS_FILE)).toMillis(), "the file was not written again");
    }

    /** Only a person moves a pin: {@code accept} refuses {@code --yes}; a dry run, which writes nothing, is allowed. */
    @Test
    void acceptTakesNoYes() {
        ToolFailure f = assertThrows(ToolFailure.class, () -> TestPluginsTool.modeFor("accept", Ask.Mode.YES));
        assertTrue(f.getMessage().contains("takes no --yes"), f.getMessage());
        assertEquals(Ask.Mode.ASK, TestPluginsTool.modeFor("accept", Ask.Mode.ASK));
        assertEquals(Ask.Mode.DRY_RUN, TestPluginsTool.modeFor("accept", Ask.Mode.DRY_RUN));
        assertEquals(Ask.Mode.YES, TestPluginsTool.modeFor("fetch", Ask.Mode.YES), "the other steps ask nothing; the flag does no harm");
    }

    private static Path zip(Path file, Map<String, String> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}

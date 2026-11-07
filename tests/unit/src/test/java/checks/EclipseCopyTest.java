// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.EclipseCopy;

/**
 * {@link EclipseCopy}, the copy of your Eclipse that {@code ManualChecks launch} and
 * {@code InstallCheck} install into: what is hard-linked and what copied, symbolic links that must
 * not lead back into your Eclipse, and the director's listing.
 */
class EclipseCopyTest {

    /** The folder to copy is the program's, or on macOS the app bundle around it. */
    @Test
    void theInstallationIsTheProgramsFolderOrTheMacApp() {
        assertEquals(Path.of("/opt/eclipse"), EclipseCopy.installation(Path.of("/opt/eclipse/eclipse")));
        assertEquals(Path.of("/Applications/Eclipse.app"),
                EclipseCopy.installation(Path.of("/Applications/Eclipse.app/Contents/MacOS/eclipse")));
    }

    /** Only bundles and features are hard links; what p2 and Eclipse write is a copy of its own. */
    @Test
    void onlyPluginsAndFeaturesAreShared() {
        Path top = Path.of("");
        assertTrue(EclipseCopy.shared(Path.of("plugins/org.eclipse.swt.jar"), top));
        assertTrue(EclipseCopy.shared(Path.of("features/f_1/feature.xml"), top));
        assertFalse(EclipseCopy.shared(Path.of("configuration/config.ini"), top));
        assertFalse(EclipseCopy.shared(Path.of("p2/org.eclipse.equinox.p2.engine/x.profile.gz"), top));
        assertFalse(EclipseCopy.shared(Path.of("eclipse.ini"), top));
        Path mac = Path.of("Contents/Eclipse");
        assertTrue(EclipseCopy.shared(Path.of("Contents/Eclipse/plugins/a.jar"), mac));
        assertFalse(EclipseCopy.shared(Path.of("Contents/Eclipse/eclipse.ini"), mac));
        assertFalse(EclipseCopy.shared(Path.of("configuration/plugins/a"), top), "only the top plugins/");
    }

    @Test
    void copyTreeLinksTheSharedFilesAndCopiesTheRest(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("eclipse");
        Files.createDirectories(source.resolve("plugins/b_1"));
        Files.createDirectories(source.resolve("configuration"));
        Files.writeString(source.resolve("plugins/a.jar"), "a");
        Files.writeString(source.resolve("plugins/b_1/b.txt"), "b");
        Files.writeString(source.resolve("configuration/config.ini"), "c");
        Files.createDirectories(source.resolve("configuration/org.eclipse.osgi/1"));
        Files.writeString(source.resolve("configuration/org.eclipse.osgi/1/cache"), "rebuilt by Eclipse");
        Path copy = dir.resolve("copy/eclipse");
        long[] counts = EclipseCopy.copyTree(source, copy, Path.of(""));
        assertEquals(3, counts[0] + counts[1], "three files in all: the OSGi cache is left out");
        assertFalse(Files.exists(copy.resolve("configuration/org.eclipse.osgi")));
        assertEquals("b", Files.readString(copy.resolve("plugins/b_1/b.txt")));
        Files.writeString(copy.resolve("configuration/config.ini"), "changed");
        assertEquals("c", Files.readString(source.resolve("configuration/config.ini")), "a copy, not a link");
        if (counts[0] == 2) { // a file system with hard links (all the developer machines)
            assertTrue(Files.isSameFile(source.resolve("plugins/a.jar"), copy.resolve("plugins/a.jar")));
        }
    }

    /**
     * The symbolic links of a real copy: an absolute one into the source points into the copy, a
     * relative one stays relative; without the right to make links (Windows), each is copied as
     * what it points to, a file or a whole folder, and never as a hard link to the source's link.
     * Skipped where the test itself may not make links.
     */
    @Test
    void symbolicLinksNeverLeadBackIntoTheSource(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("eclipse").toAbsolutePath();
        Files.createDirectories(source.resolve("plugins/lib"));
        Files.writeString(source.resolve("plugins/lib/real.txt"), "real");
        Files.writeString(source.resolve("eclipse.ini"), "-vmargs");
        try {
            Files.createSymbolicLink(source.resolve("plugins/abs.txt"), source.resolve("plugins/lib/real.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("this account may not make symbolic links: " + e);
        }
        Files.createSymbolicLink(source.resolve("plugins/rel.txt"), Path.of("lib", "real.txt"));
        Files.createSymbolicLink(source.resolve("folder"), source.resolve("plugins/lib"));
        Files.createSymbolicLink(source.resolve("dangling"), source.resolve("gone"));

        Path linked = dir.resolve("linked").toAbsolutePath();
        EclipseCopy.copyTree(source, linked, Path.of(""));
        assertEquals(linked.resolve("plugins/lib/real.txt"), Files.readSymbolicLink(linked.resolve("plugins/abs.txt")));
        assertEquals(Path.of("lib", "real.txt"), Files.readSymbolicLink(linked.resolve("plugins/rel.txt")));
        assertEquals(linked.resolve("plugins/lib"), Files.readSymbolicLink(linked.resolve("folder")));

        Path copied = dir.resolve("copied").toAbsolutePath();
        EclipseCopy.copyTree(source, copied, Path.of(""), false);
        for (String name : List.of("plugins/abs.txt", "plugins/rel.txt")) {
            Path file = copied.resolve(name);
            assertFalse(Files.isSymbolicLink(file), name);
            assertEquals("real", Files.readString(file));
            assertFalse(Files.isSameFile(file, source.resolve("plugins/lib/real.txt")), name + ": not a hard link");
        }
        assertEquals("real", Files.readString(copied.resolve("folder/real.txt")), "a folder link is copied with its files");
        assertFalse(Files.exists(copied.resolve("dangling"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "a link to nothing");
        Files.writeString(copied.resolve("plugins/abs.txt"), "changed");
        assertEquals("real", Files.readString(source.resolve("plugins/lib/real.txt")), "writing the copy leaves the source");
    }

    @Test
    void theInstalledVersionIsReadFromTheDirectorsListing() {
        String feature = EclipseCopy.FEATURE;
        assertEquals("0.2.1.202611051917", EclipseCopy.installedVersion(
                "INFO: noise\norg.eclipse.jdt.feature.group/3.20\n" + feature + "/0.2.1.202611051917\n").orElseThrow());
        assertTrue(EclipseCopy.installedVersion("org.eclipse.jdt.feature.group/3.20").isEmpty());
    }

    /** A workspace whose lock someone holds is in use; without a lock file, or once released, it is not. */
    @Test
    void aHeldWorkspaceLockMeansAnEclipseRunsThere(@TempDir Path workspace) throws IOException {
        assertFalse(EclipseCopy.inUse(workspace), "no lock file");
        Path lock = workspace.resolve(".metadata/.lock");
        Files.createDirectories(lock.getParent());
        Files.createFile(lock);
        assertFalse(EclipseCopy.inUse(workspace), "a lock file nobody holds: Eclipse was closed");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock held = channel.lock()) {
            assertTrue(EclipseCopy.inUse(workspace));
        }
        assertFalse(EclipseCopy.inUse(workspace), "released");
    }

    /** A folder is copied only if it is an Eclipse: eclipse.ini and plugins/, also inside a macOS app. */
    @Test
    void onlyAnEclipseInstallationIsCopied(@TempDir Path dir) throws IOException {
        Path bin = dir.resolve("bin"); // the folder of a wrapper script named eclipse on PATH
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("eclipse"), "#!/bin/sh");
        assertFalse(EclipseCopy.isEclipse(bin));
        Path linux = dir.resolve("eclipse");
        Files.createDirectories(linux.resolve("plugins"));
        Files.writeString(linux.resolve("eclipse.ini"), "-vmargs");
        assertTrue(EclipseCopy.isEclipse(linux));
        assertEquals(Path.of(""), EclipseCopy.inner(linux));
        Path app = dir.resolve("Eclipse.app");
        Files.createDirectories(app.resolve("Contents/Eclipse/plugins"));
        Files.writeString(app.resolve("Contents/Eclipse/eclipse.ini"), "-vmargs");
        assertTrue(EclipseCopy.isEclipse(app));
        assertEquals(Path.of("Contents", "Eclipse"), EclipseCopy.inner(app));
    }

    @Test
    void theOsgiCacheIsLeftOutOfTheCopy() {
        Path top = Path.of("");
        assertTrue(EclipseCopy.skipped(Path.of("configuration/org.eclipse.osgi/1/data"), top));
        assertFalse(EclipseCopy.skipped(Path.of("configuration/config.ini"), top));
        assertTrue(EclipseCopy.skipped(Path.of("Contents/Eclipse/configuration/org.eclipse.osgi"),
                Path.of("Contents", "Eclipse")));
    }

    /** An absolute link into your Eclipse points into the copy instead; other links stay as they are. */
    @Test
    void absoluteLinksIntoTheSourcePointIntoTheCopy() {
        Path source = Path.of("/opt/eclipse").toAbsolutePath();
        Path copy = Path.of("/home/you/c").toAbsolutePath();
        assertEquals(copy.resolve("plugins/x"),
                EclipseCopy.linkTarget(source.resolve("plugins/x"), source, copy));
        assertEquals(Path.of("../lib/y"), EclipseCopy.linkTarget(Path.of("../lib/y"), source, copy));
        Path elsewhere = Path.of("/usr/lib/z").toAbsolutePath();
        assertEquals(elsewhere, EclipseCopy.linkTarget(elsewhere, source, copy));
    }
}

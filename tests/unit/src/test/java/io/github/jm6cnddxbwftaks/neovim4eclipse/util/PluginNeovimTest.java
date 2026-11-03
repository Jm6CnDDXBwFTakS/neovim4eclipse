// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The plug-in's Neovim folder layout ({@link PluginNeovim}) and the processor probe's parsing. */
class PluginNeovimTest {

    @TempDir
    Path tmp;

    private static Optional<NeovimPlatform> code(String code) {
        return NeovimPlatform.byCode(code);
    }

    @Test
    void anInstallIsTheProgramWithItsRuntime() throws IOException {
        Path home = tmp.resolve("la");
        assertFalse(PluginNeovim.isInstall(home, false));
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/nvim"));
        assertFalse(PluginNeovim.isInstall(home, false), "no share/nvim/runtime");
        Files.createDirectories(home.resolve("share/nvim/runtime"));
        assertTrue(PluginNeovim.isInstall(home, false));
        assertFalse(PluginNeovim.isInstall(home, true), "Windows wants bin\\nvim.exe");
    }

    @Test
    void searchHomesPutTheRealProcessorFirst() {
        Path f = tmp;
        assertEquals(List.of(f.resolve("ma"), f.resolve("mx")),
                PluginNeovim.searchHomes(f, code("ma"), code("mx")), "emulated x86_64 on Apple silicon");
        assertEquals(List.of(f.resolve("lx"), f.resolve("la")),
                PluginNeovim.searchHomes(f, code("lx"), code("lx")));
        assertEquals(List.of(), PluginNeovim.searchHomes(f, Optional.empty(), Optional.empty()),
                "a platform without a code has no plug-in Neovim");
    }

    @Test
    void unusedEntriesAreEverythingButThePlatformFolders() throws IOException {
        for (String name : List.of("la", "lx.new", "ma.old", "current", "nvim-linux-arm64_0.12.6")) {
            Files.createDirectories(tmp.resolve(name));
        }
        Files.createFile(tmp.resolve(PluginNeovim.LOCK));
        Files.createFile(tmp.resolve("notes.txt"));
        Files.createDirectories(tmp.resolve("la.old-12345")); // set aside by a swap, deleted at the next start
        Files.createFile(tmp.resolve(PluginNeovim.DOWNLOAD_PREFIX + "1.part"));
        for (String name : List.of(".DS_Store", "._la", "desktop.ini", "Thumbs.db")) { // the file managers'
            Files.createFile(tmp.resolve(name));
        }
        assertEquals(List.of("current", "notes.txt", "nvim-linux-arm64_0.12.6"), PluginNeovim.unusedEntries(tmp));
        assertEquals(List.of(), PluginNeovim.unusedEntries(tmp.resolve("missing")));
    }

    @Test
    void macOsAsksSysctl() {
        assertEquals(code("ma"), ProcessorProbe.probe("Mac OS X", "x86_64", null, cmd -> "1\n"), "Rosetta 2");
        assertEquals(code("mx"), ProcessorProbe.probe("Mac OS X", "x86_64", null, cmd -> "0\n"));
        assertEquals(code("mx"), ProcessorProbe.probe("Mac OS X", "x86_64", null, cmd -> null), "sysctl failed: Java's");
    }

    @Test
    void windowsReadsTheRegistry() {
        String arm = """

                HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment
                    PROCESSOR_ARCHITECTURE    REG_SZ    ARM64

                """;
        assertEquals(code("wa"), ProcessorProbe.probe("Windows 11", "amd64", "C:\\Windows", cmd -> arm), "x64 emulation");
        assertEquals(code("wx"), ProcessorProbe.probe("Windows 11", "amd64", "C:\\Windows", cmd -> arm.replace("ARM64", "AMD64")));
        assertEquals(code("wx"), ProcessorProbe.probe("Windows 11", "amd64", "C:\\Windows", cmd -> "garbage"));
    }

    /** Never by bare name: Windows would run a reg.exe from the current folder first. */
    @Test
    void theProgramsRunByTheirFullPath() {
        List<List<String>> ran = new ArrayList<>();
        ProcessorProbe.probe("Mac OS X", "x86_64", null, cmd -> {
            ran.add(cmd);
            return null;
        });
        ProcessorProbe.probe("Windows 11", "amd64", "D:\\Win", cmd -> {
            ran.add(cmd);
            return null;
        });
        assertEquals("/usr/sbin/sysctl", ran.get(0).get(0));
        assertEquals("D:\\Win\\System32\\reg.exe", ran.get(1).get(0));
        assertEquals("C:\\Windows\\System32\\reg.exe", ProcessorProbe.reg(null), "no SystemRoot");
        assertEquals("C:\\Windows\\System32\\reg.exe", ProcessorProbe.reg("Windows"), "not absolute");
        assertEquals("C:\\Windows\\System32\\reg.exe", ProcessorProbe.reg("C:\\Windows\\"), "a trailing backslash");
    }

    @Test
    void linuxTakesJavasWord() {
        assertEquals(code("la"), ProcessorProbe.probe("Linux", "aarch64", null, cmd -> {
            throw new AssertionError("no command on Linux");
        }));
        assertEquals(Optional.empty(), ProcessorProbe.probe("Linux", "riscv64", null, cmd -> null));
    }
}

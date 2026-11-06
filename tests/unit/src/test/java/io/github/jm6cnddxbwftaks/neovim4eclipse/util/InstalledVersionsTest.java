// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * {@link InstalledVersions}' "Slow first runs": a shell script stands in for Neovim, printing what
 * {@code nvim --version} prints. Shell scripts, so not on Windows; the logic is the same there.
 */
@DisabledOnOs(OS.WINDOWS)
class InstalledVersionsTest {

    private static final String ANSWER = "echo 'NVIM v0.12.6'\n";

    @TempDir
    Path tmp;

    private Path script(String body) throws IOException {
        Path program = tmp.resolve("nvim");
        Files.writeString(program, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }

    /**
     * A first run slower than the first limit (a virus scanner holding a fresh program) is tried
     * again with the second, longer one. The script always takes 0.5 s, so the 0.1 s limit always
     * times out; the second limit is the tests' usual wait.
     */
    @Test
    void aSlowFirstRunIsTriedAgainWithMoreTime() throws IOException {
        Path program = script("sleep 0.5\n" + ANSWER);
        assertEquals(new NeovimVersion(0, 12, 6), InstalledVersions.of(program, 100, TestWait.millis()));
    }

    /**
     * A program that answers neither time gives no version, and that is not remembered: the same
     * file (same modification time) answering later gives its version.
     */
    @Test
    void noAnswerInTimeIsNotRemembered() throws IOException {
        Path program = script("exec sleep 30\n"); // exec: the kill stops the sleep too
        FileTime time = Files.getLastModifiedTime(program);
        assertNull(InstalledVersions.of(program, 100, 100));
        Files.writeString(program, "#!/bin/sh\n" + ANSWER);
        Files.setLastModifiedTime(program, time);
        assertEquals(new NeovimVersion(0, 12, 6), InstalledVersions.of(program, TestWait.millis(), TestWait.millis()));
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.ClipboardPort.Selection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * Eclipse as Neovim's clipboard provider, against a real {@code nvim}: {@code "+p} and {@code "*p}
 * paste what another program copied, {@code "+y} copies, and a user's own {@code g:clipboard} wins.
 * The system clipboard is a map. Skipped when no {@code nvim} is available.
 */
@Tag("integration")
class ClipboardIntegrationTest {

    private static final String NVIM = System.getProperty("nvim.path", "nvim");
    private static final String NL = System.lineSeparator();

    /** The system clipboard; records every copy so that double writes would show. */
    private static final class FakeClipboard implements ClipboardPort {
        final Map<Selection, String> texts = new ConcurrentHashMap<>();
        final List<String> copies = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void copy(Selection selection, String text) {
            texts.put(selection, text);
            copies.add(selection + ":" + text);
        }

        @Override
        public Optional<String> paste(Selection selection) {
            return Optional.ofNullable(texts.get(selection));
        }
    }

    private final FakeClipboard system = new FakeClipboard();

    private NeovimSession start(String... extraArgs) throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        List<String> args = new ArrayList<>(List.of("--clean"));
        args.addAll(List.of(extraArgs));
        return NeovimSession.start(NVIM, args, system, "0.0.0");
    }

    private static void keys(NeovimSession s, String... keys) throws Exception {
        for (String key : keys) {
            s.input(key);
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    @Test
    void plusPPastesLinesCopiedInAnotherProgram() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("a", "b"));
            system.texts.put(Selection.CLIPBOARD, "from" + NL + "elsewhere" + NL);
            keys(s, "\"", "+", "p");
            assertEquals(List.of("a", "from", "elsewhere", "b"), s.getBufferLines(), "a final line break: whole lines");
        }
    }

    @Test
    void plusPPastesCharactersCopiedInAnotherProgram() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abc"));
            system.texts.put(Selection.CLIPBOARD, "XY");
            keys(s, "\"", "+", "p");
            assertEquals(List.of("aXYbc"), s.getBufferLines());
        }
    }

    @Test
    void starPPastesThePrimarySelection() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abc"));
            system.texts.put(Selection.CLIPBOARD, "clipboard");
            system.texts.put(Selection.PRIMARY, "primary");
            keys(s, "\"", "*", "P");
            assertEquals(List.of("primaryabc"), s.getBufferLines());
        }
    }

    @Test
    void plusYCopiesOnceAndPastesBackAsLines() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one", "two"));
            keys(s, "\"", "+", "y", "y");
            waitFor(() -> !system.copies.isEmpty(), "the copy");
            Thread.sleep(200); // a second (racing) write from the yank hook would arrive by now
            assertEquals(List.of("CLIPBOARD:one" + NL), system.copies);

            keys(s, "j", "\"", "+", "p");
            assertEquals(List.of("one", "two", "one"), s.getBufferLines());
        }
    }

    @Test
    void aBlockCopiedWithPlusYPastesBackAsABlock() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("ab", "cd"));
            keys(s, "<C-v>", "j", "\"", "+", "y"); // the first column of both lines
            waitFor(() -> !system.copies.isEmpty(), "the copy");
            keys(s, "$", "\"", "+", "p");
            assertEquals(List.of("aba", "cdc"), s.getBufferLines());
        }
    }

    @Test
    void aPlainYankIsStillCopiedAndPastesBackAsLines() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("x", "y"));
            keys(s, "y", "y");
            waitFor(() -> !system.copies.isEmpty(), "the copy");
            assertEquals(List.of("CLIPBOARD:x" + NL), system.copies);

            keys(s, "j", "\"", "+", "p");
            assertEquals(List.of("x", "y", "x"), s.getBufferLines());
        }
    }

    @Test
    void withUnnamedplusPlainPPastesFromTheSystemClipboard() throws Exception {
        try (NeovimSession s = start("--cmd", "set clipboard=unnamedplus")) {
            s.setBufferLines(List.of("abc"));
            system.texts.put(Selection.CLIPBOARD, "Z");
            keys(s, "p");
            assertEquals(List.of("aZbc"), s.getBufferLines());

            keys(s, "y", "y"); // one write, by the provider (the yank hook stands down)
            waitFor(() -> !system.copies.isEmpty(), "the copy");
            Thread.sleep(200);
            assertEquals(List.of("CLIPBOARD:aZbc" + NL), system.copies);
        }
    }

    @Test
    void anEmptyClipboardPastesNothing() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("abc"));
            keys(s, "\"", "+", "p");
            assertEquals(List.of("abc"), s.getBufferLines());
            assertTrue(s.isAlive());
        }
    }

    @Test
    void aUsersOwnClipboardProviderIsKept() throws Exception {
        // v:false is Neovim's own "use the built-in tools" setting; any g:clipboard counts.
        try (NeovimSession s = start("--cmd", "let g:clipboard = v:false")) {
            s.setBufferLines(List.of("abc"));
            system.texts.put(Selection.CLIPBOARD, "Z");
            keys(s, "\"", "+", "y", "y");
            keys(s, "\"", "+", "p");
            Thread.sleep(200);
            assertEquals(List.of(), system.copies, "Eclipse is not the provider");
            assertTrue(!s.getBufferLines().contains("aZbc"));
        }
    }
}

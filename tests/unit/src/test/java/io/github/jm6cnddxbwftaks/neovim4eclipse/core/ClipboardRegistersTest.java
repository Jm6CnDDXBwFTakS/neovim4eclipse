// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.ClipboardPort.Selection;

/**
 * How Neovim's {@code "+} and {@code "*} registers map to the system clipboard: the text written,
 * which selection, and the type a paste comes back with. The clipboard is a map here.
 */
class ClipboardRegistersTest {

    private static final String BLOCK = "\u00162"; // Ctrl-V and the block width, Neovim's block type

    /** The system clipboard: one text per selection; another program "copies" by putting text here. */
    private static final class FakeClipboard implements ClipboardPort {
        final Map<Selection, String> texts = new EnumMap<>(Selection.class);

        @Override
        public void copy(Selection selection, String text) {
            texts.put(selection, text);
        }

        @Override
        public Optional<String> paste(Selection selection) {
            return Optional.ofNullable(texts.get(selection));
        }
    }

    private final FakeClipboard system = new FakeClipboard();
    private final ClipboardRegisters registers = new ClipboardRegisters(system, "\n");

    @Test
    void plusIsTheClipboardAndStarThePrimarySelection() {
        registers.copy("+", List.of("plus"), "v");
        registers.copy("*", List.of("star"), "v");

        assertEquals(Map.of(Selection.CLIPBOARD, "plus", Selection.PRIMARY, "star"), system.texts);
    }

    @Test
    void wholeLinesEndWithALineBreakOnTheClipboard() {
        registers.copy("+", List.of("one", "two", ""), "V"); // Neovim adds the final empty line

        assertEquals("one\ntwo\n", system.texts.get(Selection.CLIPBOARD));
    }

    @Test
    void neovimsOwnCopyPastesBackWithItsType() {
        registers.copy("+", List.of("one", "two", ""), "V");
        assertEquals(new ClipboardRegisters.Paste(List.of("one", "two", ""), "V"), registers.paste("+"));

        registers.copy("+", List.of("ab", "cd", ""), BLOCK);
        assertEquals(BLOCK, registers.paste("+").regtype(), "a block stays a block");
    }

    @Test
    void textFromAnotherProgramIsSplitAndNeovimDecidesTheType() {
        registers.copy("+", List.of("old", ""), "V");
        system.texts.put(Selection.CLIPBOARD, "copied\r\nelsewhere\n"); // e.g. from a browser on Windows

        ClipboardRegisters.Paste paste = registers.paste("+");

        assertEquals(List.of("copied", "elsewhere", ""), paste.lines(), "a final empty line: Neovim pastes lines");
        assertNull(paste.regtype());
        assertEquals(List.of("copied", "elsewhere", ""), paste.toRpc(), "just the lines, no type");
    }

    @Test
    void theCopyIsRecognizedWhenWindowsLineBreaksComeBack() {
        ClipboardRegisters onWindows = new ClipboardRegisters(system, "\r\n");
        onWindows.copy("+", List.of("one", "two", ""), "V");
        assertEquals("one\r\ntwo\r\n", system.texts.get(Selection.CLIPBOARD));

        system.texts.put(Selection.CLIPBOARD, "one\ntwo\n"); // a program rewrote the line breaks

        assertEquals("V", onWindows.paste("+").regtype());
    }

    @Test
    void anEmptyOrUnreadableClipboardPastesNothing() {
        assertEquals(List.of(), registers.paste("+").toRpc());
        system.texts.put(Selection.CLIPBOARD, "");
        assertEquals(List.of(), registers.paste("+").toRpc());
    }

    @Test
    void eachSelectionRemembersItsOwnCopy() {
        registers.copy("+", List.of("line", ""), "V");
        system.texts.put(Selection.PRIMARY, "line\n"); // the same text, but selected elsewhere

        assertNull(registers.paste("*").regtype());
        assertEquals("V", registers.paste("+").regtype());
    }

    @Test
    void aPlainYankIsCopiedToTheClipboardInTheProvidersForm() {
        registers.copyYank(List.of("word"), "v");
        assertEquals("word", system.texts.get(Selection.CLIPBOARD));

        registers.copyYank(List.of("a", "b"), "V");
        assertEquals("a\nb\n", system.texts.get(Selection.CLIPBOARD));
        assertEquals(new ClipboardRegisters.Paste(List.of("a", "b", ""), "V"), registers.paste("+"),
                "yy then \"+p pastes a line");

        registers.copyYank(List.of("ab", "cd"), BLOCK);
        assertEquals("ab\ncd\n", system.texts.get(Selection.CLIPBOARD));
        assertEquals(BLOCK, registers.paste("+").regtype());
    }
}

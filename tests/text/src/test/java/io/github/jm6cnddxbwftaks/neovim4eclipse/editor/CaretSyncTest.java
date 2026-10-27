// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.CursorPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.CursorPos;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;

/**
 * {@link CaretSync} with a {@link FakeCaret} and a recording {@link CursorPort}: no widget, no
 * Neovim. Neovim positions are a 1-based line and a 0-based UTF-8 byte column; caret positions a
 * 1-based line and a 0-based UTF-16 column.
 */
class CaretSyncTest {

    /** Records cursor moves as "async line:byteCol" or "now line:byteCol". */
    private static final class RecordingCursor implements CursorPort {
        final List<String> moves = new ArrayList<>();

        @Override
        public void setCursorAsync(int line1Based, int col0Based) {
            moves.add("async " + line1Based + ":" + col0Based);
        }

        @Override
        public void setCursor(int line1Based, int col0Based) {
            moves.add("now " + line1Based + ":" + col0Based);
        }
    }

    private final IDocument document = new Document("alpha\nbeta\naé b\n");
    private final FakeCaret caret = new FakeCaret(document);
    private final RecordingCursor neovim = new RecordingCursor();
    private boolean neovimModeOn = true;
    private boolean applyingRemote;
    private final CaretSync sync = new CaretSync(caret, neovim, UiExecutor.IMMEDIATE, () -> document,
            () -> neovimModeOn, () -> applyingRemote);

    @AfterEach
    void dispose() {
        sync.dispose();
    }

    @Test
    void neovimsCursorMovesTheCaretWithoutAnEcho() {
        sync.showNeovimCursor(new CursorPos(2, 1));
        caret.assertCaretPosition(2, 1);
        assertEquals(List.of(), neovim.moves);
    }

    @Test
    void neovimsByteColumnBecomesACharColumn() {
        sync.showNeovimCursor(new CursorPos(3, 3)); // "aé b": byte 3 is the space after the 2-byte é
        caret.assertCaretPosition(3, 2);
    }

    @Test
    void neovimsCursorClearsEclipsesSelection() {
        caret.userSelects(0, 5);
        neovim.moves.clear();
        sync.showNeovimCursor(new CursorPos(1, 0));
        caret.assertSelectionRange(0, 0);
    }

    @Test
    void aCursorInCodeThatStaysFoldedLeavesTheCaret() {
        caret.userMovesTo(1, 2);
        caret.hidden = true;
        sync.showNeovimCursor(new CursorPos(2, 0));
        caret.assertCaretPosition(1, 2);
    }

    @Test
    void whileEclipseTypesNeovimsCursorReportsAreDropped() {
        boolean[] typing = { true };
        sync.dropNeovimCursorWhile(() -> typing[0]);
        caret.userMovesTo(3, 2); // Eclipse typed: the caret is past the end of Neovim's old line
        sync.onNeovimCursor(new CursorPos(3, 1)); // Neovim clamped it and reported it back
        caret.assertCaretPosition(3, 2);

        typing[0] = false; // Esc: Neovim's lines are right again
        sync.onNeovimCursor(new CursorPos(1, 0));
        caret.assertCaretPosition(1, 0);
    }

    @Test
    void theCaretRestoreAfterARemoteChangeStillApplies() {
        sync.dropNeovimCursorWhile(() -> true);
        sync.showNeovimCursor(new CursorPos(2, 1)); // the synchronizer's restore, not a report
        caret.assertCaretPosition(2, 1);
    }

    @Test
    void theUsersCaretMoveReachesNeovimInBytes() {
        caret.userMovesTo(3, 2);
        assertEquals(List.of("async 3:3"), neovim.moves);
    }

    @Test
    void aSelectionSendsNeovimToItsStart() {
        caret.userSelects(6, 4); // "beta", caret after it
        caret.assertSelectionRange(6, 4);
        assertEquals(List.of("async 2:0"), neovim.moves);
    }

    @Test
    void aSelectionSendsItsStartWhereverTheCaretIs() {
        VimText marked = VimText.parse("alpha\n«be|ta»\n"); // caret inside the selection
        IDocument doc = marked.document();
        FakeCaret placed = marked.fakeCaret(doc);
        RecordingCursor cursor = new RecordingCursor();
        CaretSync other = new CaretSync(placed, cursor, UiExecutor.IMMEDIATE, () -> doc, () -> true, () -> false);
        try {
            other.sendEclipseCaretNow();
            assertEquals(List.of("now 2:0"), cursor.moves);
        } finally {
            other.dispose();
        }
    }

    @Test
    void nothingIsSentWhileNeovimModeIsOff() {
        neovimModeOn = false;
        caret.userMovesTo(2, 0);
        assertEquals(List.of(), neovim.moves);
    }

    @Test
    void nothingIsSentWhileAChangeFromNeovimIsApplied() {
        applyingRemote = true;
        caret.userMovesTo(2, 0); // e.g. a document rewrite moved the caret
        assertEquals(List.of(), neovim.moves);
    }

    @Test
    void sendEclipseCaretNowUsesTheBlockingCall() {
        caret.userMovesTo(2, 3);
        neovim.moves.clear();
        sync.sendEclipseCaretNow();
        assertEquals(List.of("now 2:3"), neovim.moves);
    }

    @Test
    void disposeStopsListening() {
        assertEquals(1, caret.listenerCount());
        sync.dispose();
        assertEquals(0, caret.listenerCount());
        sync.showNeovimCursor(new CursorPos(2, 0));
        caret.assertCaretPosition(1, 0);
    }
}

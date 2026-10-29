// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;

/**
 * The attach push without Neovim or an editor: one fake records the calls of both sides in order,
 * so each test reads as "the push does these steps, in this order". Queued work is held until the
 * test runs it, as the session's input thread would run it later.
 */
class DocumentPushTest {

    private static final Indentation INDENT = new Indentation(false, 4, true);

    /** Both sides of the push, recording into one list. */
    private static class Fake implements DocumentPush.Neovim, DocumentPush.Mirror, DocumentPush.Caret {
        final List<String> calls = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        /** The name of a step that throws. */
        String failing = "";

        private void step(String name) throws Exception {
            calls.add(name);
            if (name.startsWith(failing) && !failing.isEmpty()) {
                throw new Exception(name + " failed");
            }
        }

        @Override
        public void queue(Runnable work) {
            calls.add("queue");
            if (failing.equals("queue")) {
                throw new IllegalStateException("session closed");
            }
            queued.add(work);
        }

        @Override
        public void showDocumentBuffer() throws Exception {
            step("showBuffer");
        }

        @Override
        public void setIndentation(int tabWidth, Indentation indentation) throws Exception {
            step("indent " + tabWidth);
        }

        @Override
        public void setCursor(int line1, int col0) throws Exception {
            step("cursor " + line1 + ":" + col0);
        }

        @Override
        public void takeCursorFromNeovim() {
            calls.add("take Neovim's cursor");
        }

        @Override
        public void stopRemoteListening() {
            calls.add("stop listening");
        }

        @Override
        public void pushDocumentToNeovim() {
            calls.add("push");
            if (failing.equals("push")) {
                throw new IllegalStateException("push failed");
            }
        }

        @Override
        public void startRemoteListening() {
            calls.add("start listening");
        }

        @Override
        public void ignoreNeovimCursor() {
            calls.add("ignore cursor reports");
        }

        @Override
        public void followNeovimCursor() {
            calls.add("follow cursor reports");
        }

        /** The input thread gets to the queued work. */
        void runQueued() {
            List<Runnable> work = List.copyOf(queued);
            queued.clear();
            work.forEach(Runnable::run);
        }
    }

    private final Fake fake = new Fake();
    private final DocumentPush push = new DocumentPush(fake, fake, fake);

    @Test
    void listeningStopsAtOnceAndTheRestWaitsForTheInputThread() {
        push.start(4, INDENT, 2, 3, false);

        assertEquals(List.of("stop listening", "ignore cursor reports", "queue"), fake.calls,
                "nothing reaches Neovim from the UI thread");
    }

    /**
     * Cursor reports are ignored until the cursor is placed: a new buffer reports line 1 when it is
     * shown, and the caret would go there and back, scrolling the editor (DocumentPush's comment).
     */
    @Test
    void theStepsRunInOrderAndTheCaretBecomesNeovimsCursor() {
        push.start(4, INDENT, 2, 3, false);
        fake.runQueued();

        assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "showBuffer", "indent 4", "push",
                "start listening", "cursor 2:3", "follow cursor reports"), fake.calls);
    }

    /** Neither side changed since the editor was detached: the push is skipped, the rest is not. */
    @Test
    void aDocumentInSyncWithItsBufferIsNotPushedAgain() {
        Fake unchanged = new Fake() {
            @Override
            public long documentStamp() {
                return 42;
            }

            @Override
            public boolean bufferInSyncAt(long stamp) {
                return stamp == 42;
            }
        };
        new DocumentPush(unchanged, unchanged, unchanged).start(4, INDENT, 2, 3, false);
        unchanged.runQueued();

        assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "showBuffer", "indent 4",
                "start listening", "cursor 2:3", "follow cursor reports"), unchanged.calls);
    }

    /**
     * The stamp is read when the push runs, not when it was queued: an Eclipse change made while the
     * push waited for the input thread is pushed.
     */
    @Test
    void aChangeWhileThePushWaitsInTheQueueIsPushed() {
        long[] stamp = { 42 };
        Fake changing = new Fake() {
            @Override
            public long documentStamp() {
                return stamp[0];
            }

            @Override
            public boolean bufferInSyncAt(long at) {
                return at == 42;
            }
        };
        new DocumentPush(changing, changing, changing).start(4, INDENT, 2, 3, false);
        stamp[0] = 43; // Eclipse changed the document meanwhile
        changing.runQueued();

        assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "showBuffer", "indent 4", "push",
                "start listening", "cursor 2:3", "follow cursor reports"), changing.calls);
    }

    @Test
    void whenNeovimJumpedIntoTheFileTheCaretFollowsNeovimInstead() {
        push.start(4, INDENT, 2, 3, true);
        fake.runQueued();

        assertEquals(List.of("stop listening", "queue", "showBuffer", "indent 4", "push", "start listening",
                "take Neovim's cursor"), fake.calls);
    }

    @Test
    void aFailedBufferSwitchPushesNothingButListensAgain() {
        try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) {
            fake.failing = "showBuffer";
            push.start(4, INDENT, 2, 3, false);
            fake.runQueued();

            // No push: it would overwrite the buffer Neovim is in, another file's.
            assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "showBuffer", "start listening",
                    "follow cursor reports"), fake.calls);
            log.assertLogged(Level.SEVERE, "Could not switch Neovim to the document's buffer");
        }
    }

    @Test
    void aFailedIndentSettingDoesNotStopThePush() {
        try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) {
            fake.failing = "indent";
            push.start(4, INDENT, 2, 3, false);
            fake.runQueued();

            assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "showBuffer", "indent 4", "push",
                    "start listening", "cursor 2:3", "follow cursor reports"), fake.calls);
            log.assertLogged(Level.WARNING, "Could not set Neovim's tab and indent options");
        }
    }

    @Test
    void aFailedPushStillListensAgain() {
        fake.failing = "push";
        push.start(4, INDENT, 2, 3, false);

        assertThrows(IllegalStateException.class, fake::runQueued); // the input thread logs it
        assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "showBuffer", "indent 4", "push",
                "start listening", "follow cursor reports"), fake.calls, "the caret follows Neovim again");
    }

    @Test
    void aFailedCursorMoveIsOnlyLogged() {
        try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) {
            fake.failing = "cursor";
            push.start(4, INDENT, 2, 3, false);
            fake.runQueued();

            assertEquals(List.of("cursor 2:3", "follow cursor reports"),
                    fake.calls.subList(fake.calls.size() - 2, fake.calls.size()));
            log.assertLogged(Level.WARNING, "Failed to set Neovim cursor after push");
        }
    }

    @Test
    void whenThePushCannotBeQueuedListeningResumesAtOnce() {
        try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) {
            fake.failing = "queue";
            push.start(4, INDENT, 2, 3, false);

            assertEquals(List.of("stop listening", "ignore cursor reports", "queue", "start listening",
                    "follow cursor reports"), fake.calls);
            log.assertLogged(Level.WARNING, "Document push could not be scheduled");
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DiagnosticsPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;

/**
 * {@link DiagnosticsSync} with a real JFace {@link AnnotationModel} on a {@link Document}, a fake
 * session that records the lists, and a UI thread whose timed work runs only when the test says so
 * (the debounce). What Neovim does with a list is {@code diagnostics_spec.lua}; the workbench's
 * annotation types are the UI test's.
 */
class DiagnosticsSyncTest {

    /** Records each list sent. */
    static final class FakeSession implements DiagnosticsPort {
        final List<List<Diagnostic>> sent = new ArrayList<>();

        @Override
        public void setDiagnosticsAsync(DocumentBuffer buffer, List<Diagnostic> diagnostics) {
            sent.add(diagnostics);
        }

        @Override
        public boolean problemsEnabled() {
            return true; // the editor starts no sync when off
        }

        List<Diagnostic> last() {
            return sent.get(sent.size() - 1);
        }
    }

    /** The UI thread is the test's; timed work waits in {@link #timed} until {@link #runTimers}. */
    static final class HeldTimers implements UiExecutor {
        final List<Runnable> timed = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isUiThread() {
            return true;
        }

        @Override
        public void asyncExec(Runnable work) {
            work.run();
        }

        @Override
        public void syncExec(Runnable work) {
            work.run();
        }

        @Override
        public void timerExec(int delayMs, Runnable work) {
            delays.add(delayMs);
            timed.add(work);
        }

        void runTimers() {
            List<Runnable> due = new ArrayList<>(timed);
            timed.clear();
            due.forEach(Runnable::run);
        }
    }

    /** Annotation types as a test stand-in for the workbench's: "error", "warning", "info"; others are no problems. */
    private static final Function<Annotation, Severity> SEVERITY = a -> switch (a.getType()) {
        case "error" -> Severity.ERROR;
        case "warning" -> Severity.WARN;
        case "info" -> Severity.INFO;
        default -> null;
    };

    private final Document document = new Document("int x;\nString é = 1;\nfoo();\n");
    private final AnnotationModel model = new AnnotationModel();
    private final FakeSession session = new FakeSession();
    private final HeldTimers ui = new HeldTimers();
    private DiagnosticsSync sync;

    @BeforeEach
    void connect() {
        model.connect(document); // positions follow the document's edits, as in an editor
    }

    @AfterEach
    void close() {
        if (sync != null) {
            sync.close();
        }
        model.disconnect(document);
    }

    private DiagnosticsSync start() {
        sync = new DiagnosticsSync(document, model, new DocumentBuffer("A.java"), session, ui, SEVERITY);
        sync.start();
        // AnnotationModel tells a listener about the model as soon as it is added: one more send is
        // scheduled, which finds the list unchanged. Settled here so that each test starts quiet.
        ui.runTimers();
        ui.delays.clear();
        return sync;
    }

    private Annotation add(String type, String message, int offset, int length) {
        Annotation annotation = new Annotation(type, false, message);
        model.addAnnotation(annotation, new Position(offset, length));
        return annotation;
    }

    @Test
    void startSendsTheProblemsThereAreNow() {
        add("error", "Type mismatch", 18, 1); // the "1" after "é": byte column 12
        start();
        assertEquals(List.of(List.of(new Diagnostic(1, 12, 1, 13, Severity.ERROR, "Type mismatch"))), session.sent);
    }

    @Test
    void aDocumentWithoutProblemsSendsAnEmptyList() {
        // Empty, not nothing: a buffer kept from before (a restarted sync) must lose its old list.
        start();
        assertEquals(List.of(List.of()), session.sent);
    }

    @Test
    void annotationsThatAreNoProblemsAreLeftOut() {
        add("occurrence", "x", 4, 1);
        add("task", "JM6 check", 0, 3);
        add("warning", "unused", 4, 1);
        start();
        assertEquals(List.of(new Diagnostic(0, 4, 0, 5, Severity.WARN, "unused")), session.last());
    }

    @Test
    void changesInARowAreSentOnceAfterTheDebounce() {
        start();
        add("error", "one", 0, 3);
        add("warning", "two", 21, 3);
        add("info", "three", 7, 6);
        assertEquals(1, session.sent.size(), "nothing more before the timer");
        assertEquals(List.of(DiagnosticsSync.DEBOUNCE_MS), ui.delays, "one timer for the three changes");
        ui.runTimers();
        assertEquals(List.of(
                new Diagnostic(0, 0, 0, 3, Severity.ERROR, "one"),
                new Diagnostic(1, 0, 1, 6, Severity.INFO, "three"),
                new Diagnostic(2, 0, 2, 3, Severity.WARN, "two")), session.last());
    }

    @Test
    void aChangeAfterTheSendSchedulesAnother() {
        start();
        add("error", "one", 0, 3);
        ui.runTimers();
        Annotation gone = model.getAnnotationIterator().next();
        model.removeAnnotation(gone);
        assertEquals(1, ui.timed.size());
        ui.runTimers();
        assertEquals(List.of(), session.last(), "the fixed problem is gone from Neovim too");
        assertEquals(3, session.sent.size());
    }

    @Test
    void anUnchangedListIsNotSentAgain() {
        add("error", "one", 0, 3);
        start();
        // Typing moves Eclipse's positions; Neovim's diagnostics move with the text by themselves.
        // A change of the model that leaves the same problems in the same places sends nothing.
        Annotation info = add("occurrence", "x", 4, 1);
        model.removeAnnotation(info);
        ui.runTimers();
        assertEquals(1, session.sent.size());
    }

    @Test
    void positionsFollowTheTextAsItIsEdited() throws Exception {
        add("error", "foo is undefined", 21, 3); // "foo" on line 2
        start();
        document.replace(0, 0, "// new first line\n");
        add("info", "trigger", 0, 2); // a model change, as a reconcile after typing would make
        ui.runTimers();
        assertTrue(session.last().contains(new Diagnostic(3, 0, 3, 3, Severity.ERROR, "foo is undefined")),
                "now on line 3: " + session.last());
    }

    @Test
    void aDeletedAnnotationOrPositionIsLeftOut() {
        Annotation marked = add("error", "fixed by a quick fix", 0, 3);
        marked.markDeleted(true); // JDT keeps it until the next reconcile
        start();
        assertEquals(List.of(), session.last());
    }

    @Test
    void nothingIsSentAfterClose() {
        start();
        add("error", "one", 0, 3);
        sync.close();
        ui.runTimers(); // the send scheduled before closing
        add("error", "two", 7, 3);
        ui.runTimers();
        assertEquals(1, session.sent.size());
        sync = null;
    }
}

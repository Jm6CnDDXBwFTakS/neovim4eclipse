// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ISynchronizable;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelListener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DiagnosticsPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.DiagnosticPositions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.DiagnosticPositions.Problem;

/**
 * Sends the problems Eclipse shows in one document to its Neovim buffer, as diagnostics, and again
 * each time they change; so that Neovim's own {@code ]d} / {@code [d} move between them. The editor
 * keeps one per document that has a buffer, next to its {@link UnattachedDocumentSync}.
 *
 * <h2>Where the problems come from</h2>
 * The editor's <em>annotation model</em> ({@code IDocumentProvider.getAnnotationModel}): the marks
 * Eclipse draws over the text and in the rulers. It holds the problems the user sees, unsaved ones
 * included: markers a build saved ({@code MarkerAnnotation}) and, in a Java editor, the problems JDT
 * finds while typing, before any save (not markers at all). It also holds annotations that are not
 * problems (search results, occurrences, bookmarks, tasks); the {@code severity} function given to the
 * constructor tells which are, because only the workbench knows the annotation types and their
 * subtypes ({@code editor/ProblemAnnotations}).
 *
 * <h2>When</h2>
 * <ul>
 *   <li>At {@link #start}: the problems the document has now.</li>
 *   <li>After a change of the model, <em>debounced</em>: changes in a row (JDT reconciles some 500 ms
 *       after the last key and changes many annotations at once) become one list, sent
 *       {@value #DEBOUNCE_MS} ms after the first. A list equal to the last one sent is not sent
 *       again: typing moves positions, but Neovim's diagnostics move with the text by themselves
 *       (extmarks), so only a real change of the problems needs a new list.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * Annotation models change on any thread (JDT's reconciler has its own); the listener only schedules.
 * The model and the document are read on the UI thread, under the model's lock when it has one
 * ({@link ISynchronizable}), and the list goes to Neovim with an {@code *Async} call: no blocking RPC
 * on the UI thread.
 *
 * <p>Example: {@code A.java} has an unused import on line 3 and a type error on line 40. Neovim's
 * buffer gets a warning at line index 2 and an error at 39; {@code ]d} from line 1 goes to line 3.
 * Fixing the type error removes its annotation, and some 300 ms later Neovim's list holds the warning
 * alone.
 */
public final class DiagnosticsSync implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DiagnosticsSync.class.getName());

    /** How long changes are collected before one list is sent, in ms. */
    static final int DEBOUNCE_MS = 300;

    private final IDocument document;
    private final IAnnotationModel model;
    private final DocumentBuffer buffer;
    private final DiagnosticsPort session;
    private final UiExecutor ui;
    private final Function<Annotation, Severity> severity;
    private final IAnnotationModelListener listener = changed -> sendSoon();
    /** A send is scheduled and has not read the model yet. */
    private final AtomicBoolean sendPending = new AtomicBoolean(false);
    /** UI thread only. The list sent last; {@code null} before the first. */
    private List<Diagnostic> lastSent;
    private volatile boolean closed;

    /**
     * @param document the document the annotations are placed in
     * @param model    the document's annotation model, as its editor's document provider gives it
     * @param buffer   the document's Neovim buffer
     * @param session  where the lists go
     * @param ui       the UI thread ({@code SwtUiExecutor} in the editor)
     * @param severity the severity of an annotation that is a problem, {@code null} for any other
     */
    public DiagnosticsSync(IDocument document, IAnnotationModel model, DocumentBuffer buffer,
            DiagnosticsPort session, UiExecutor ui, Function<Annotation, Severity> severity) {
        this.document = document;
        this.model = model;
        this.buffer = buffer;
        this.session = session;
        this.ui = ui;
        this.severity = severity;
    }

    /**
     * Starts listening and sends the problems the document has now. UI thread. Listening first, so
     * that no change falls between the read and the listener; JFace's {@code AnnotationModel} calls a
     * new listener at once, and the send that schedules finds the list unchanged.
     */
    public void start() {
        model.addAnnotationModelListener(listener);
        send();
    }

    /** Any thread (the model's listener). One send is scheduled for any number of changes. */
    private void sendSoon() {
        if (closed || !ui.isAvailable() || !sendPending.compareAndSet(false, true)) {
            return;
        }
        ui.timerExec(DEBOUNCE_MS, this::send);
    }

    /** Reads the problems and sends them, unless they are the list sent last. UI thread. */
    private void send() {
        sendPending.set(false); // before reading: a change from now on schedules another send
        if (closed) {
            return;
        }
        List<Diagnostic> diagnostics;
        try {
            diagnostics = DiagnosticPositions.toDiagnostics(problems(), lines());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the problems of " + describe(), e);
            return;
        }
        if (diagnostics.equals(lastSent)) {
            return;
        }
        lastSent = diagnostics;
        session.setDiagnosticsAsync(buffer, diagnostics);
    }

    /** The model's problems, read under its lock if it has one (another thread may change it meanwhile). */
    List<Problem> problems() {
        Object lock = model instanceof ISynchronizable synchronizable ? synchronizable.getLockObject() : null;
        if (lock == null) {
            return readProblems();
        }
        synchronized (lock) {
            return readProblems();
        }
    }

    private List<Problem> readProblems() {
        List<Problem> problems = new ArrayList<>();
        for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();) {
            Annotation annotation = it.next();
            if (annotation.isMarkedDeleted()) {
                continue; // JDT keeps a problem fixed by a quick fix until the next reconcile, marked deleted
            }
            Severity level = severity.apply(annotation);
            Position position = level == null ? null : model.getPosition(annotation);
            if (position == null || position.isDeleted()) {
                continue;
            }
            problems.add(new Problem(position.getOffset(), position.getLength(), level, annotation.getText()));
        }
        return problems;
    }

    /** The document's lines as {@link DiagnosticPositions} reads them, counted as Neovim does. */
    private DiagnosticPositions.Lines lines() {
        int neovimLines = UnattachedDocumentSync.neovimLineCount(document, FinalNewlines.of(document));
        return new DiagnosticPositions.Lines() {
            @Override
            public int length() {
                return document.getLength();
            }

            @Override
            public int lineCount() {
                return neovimLines;
            }

            @Override
            public int lineOf(int offset) {
                try {
                    return document.getLineOfOffset(offset);
                } catch (BadLocationException e) {
                    return document.getNumberOfLines() - 1;
                }
            }

            @Override
            public int lineStart(int line) {
                try {
                    return document.getLineOffset(line);
                } catch (BadLocationException e) {
                    return document.getLength();
                }
            }

            @Override
            public String lineText(int line) {
                try {
                    IRegion region = document.getLineInformation(line); // without the line break
                    return document.get(region.getOffset(), region.getLength());
                } catch (BadLocationException e) {
                    return "";
                }
            }
        };
    }

    private String describe() {
        return buffer.name().isEmpty() ? "buffer " + buffer.bufnr() : buffer.name();
    }

    /**
     * Stops listening (the document's last editor closed, or Neovim was restarted). Neovim's list is
     * left as it is: a wiped buffer takes its diagnostics with it, and a new Neovim has none.
     */
    @Override
    public void close() {
        closed = true;
        model.removeAnnotationModelListener(listener);
    }
}

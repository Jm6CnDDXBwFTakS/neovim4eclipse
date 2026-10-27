// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Folding with Neovim's {@code z} commands in an editor that folds (docs/design/FOLDING.md), here
 * {@link TestFoldingEditor}, so that no Java tooling is needed: Neovim's {@code zc} and {@code zo}
 * collapse and expand Eclipse's folds, a fold collapsed in Eclipse is closed in Neovim, {@code j}
 * steps over a closed fold, and an editor that cannot fold turns Neovim's folding off. The parts are
 * tested on their own in {@code FoldRegionsTest}, {@code folds_spec.lua} and {@code FoldMirrorTest}.
 */
class FoldingEndToEndTest extends EndToEndTestBase {

    /** Folds: 1-9 (the class), 2-5 (m) and 6-8 (n). */
    private static final String SOURCE = """
            class A {
              void m() {
                one
                two
              }
              void n() {
                three
              }
            }
            after
            """;

    private ITextEditor foldEditor;
    private StyledText foldWidget;

    @BeforeEach
    void openFoldingEditor() throws Exception {
        IFile file = project.getFile("A.fold");
        file.create(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)), true, null);
        foldEditor = (ITextEditor) IDE.openEditor(page(), file, "io.github.jm6cnddxbwftaks.neovim4eclipse.tests.foldingEditor", true);
        foldWidget = widgetOf(foldEditor);
        waitUntil("Neovim to attach to the folding editor", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == foldEditor && !c.isClosed();
        });
        waitUntil("the folds in Neovim", TIMEOUT_MS,
                () -> Boolean.TRUE.equals(SessionProbe.lua(session, "return vim.b.neovim4eclipse_folds == true")));
        settle();
    }

    @AfterEach
    void closeFoldingEditor() {
        if (foldEditor != null) {
            foldEditor.getSite().getPage().closeEditor(foldEditor, false);
        }
    }

    /** The fold annotation starting on {@code line1} (1-based). */
    private ProjectionAnnotation foldOn(int line1) {
        ProjectionAnnotationModel model = model();
        try {
            int offset = documentOf(foldEditor).getLineOffset(line1 - 1);
            for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();) {
                Annotation annotation = it.next();
                Position position = model.getPosition(annotation);
                if (annotation instanceof ProjectionAnnotation fold && position != null && position.getOffset() == offset) {
                    return fold;
                }
            }
        } catch (BadLocationException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("no fold on line " + line1);
    }

    private ProjectionAnnotationModel model() {
        ISourceViewer viewer = (ISourceViewer) foldEditor.getAdapter(org.eclipse.jface.text.ITextOperationTarget.class);
        return ((ProjectionViewer) viewer).getProjectionAnnotationModel();
    }

    /** Eclipse's caret line, 1-based. */
    private int caretLine() {
        return ((ITextSelection) foldEditor.getSelectionProvider().getSelection()).getStartLine() + 1;
    }

    private boolean closedInNeovim(int line1) {
        Object start = SessionProbe.lua(session, "return vim.fn.foldclosed(" + line1 + ")");
        return start instanceof Number n && n.intValue() == line1;
    }

    @Test
    void zcCollapsesTheFoldInEclipseAndJStepsOverIt() {
        typeSlowly(foldWidget, "2Gzc");
        waitUntil("m collapsed in Eclipse", TIMEOUT_MS, () -> foldOn(2).isCollapsed());

        typeSlowly(foldWidget, "j");
        waitUntil("the caret after the fold", TIMEOUT_MS, () -> caretLine() == 6, () -> "; it is on line " + caretLine());
    }

    @Test
    void zoExpandsItAgain() {
        typeSlowly(foldWidget, "2Gzc");
        waitUntil("m collapsed", TIMEOUT_MS, () -> foldOn(2).isCollapsed());
        typeSlowly(foldWidget, "zo");
        waitUntil("m expanded", TIMEOUT_MS, () -> !foldOn(2).isCollapsed());
        typeSlowly(foldWidget, "j");
        waitUntil("the caret inside m", TIMEOUT_MS, () -> caretLine() == 3, () -> "; it is on line " + caretLine());
    }

    @Test
    void aFoldCollapsedInEclipseIsClosedInNeovimAndJStepsOverIt() throws Exception {
        typeSlowly(foldWidget, "6G");
        model().collapse(foldOn(2)); // as the ruler's minus does
        waitUntil("m closed in Neovim", TIMEOUT_MS, () -> closedInNeovim(2));

        typeSlowly(foldWidget, "gg");
        typeSlowly(foldWidget, "jj");
        waitUntil("the caret on n, past the closed m", TIMEOUT_MS, () -> caretLine() == 6,
                () -> "; it is on line " + caretLine());
    }

    @Test
    void zMCollapsesTheClassAndZoShowsTheMethodsAsTheyWere() {
        model().collapse(foldOn(6)); // n collapsed in Eclipse, m open
        waitUntil("n closed in Neovim", TIMEOUT_MS, () -> closedInNeovim(6));
        typeSlowly(foldWidget, "zM");
        waitUntil("the class collapsed", TIMEOUT_MS, () -> foldOn(1).isCollapsed());
        typeSlowly(foldWidget, "ggzo");
        waitUntil("the class expanded", TIMEOUT_MS, () -> !foldOn(1).isCollapsed());
        // zM closed every fold in Neovim, the methods too: Neovim reports them after zo.
        waitUntil("both methods collapsed", TIMEOUT_MS, () -> foldOn(2).isCollapsed() && foldOn(6).isCollapsed());
    }

    @Test
    void zfShowsAnErrorInTheMessageLineAndMakesNoFold() {
        typeSlowly(foldWidget, "Gzf"); // nothing after it: the next key clears the message line
        waitUntil("the error in the message line", TIMEOUT_MS,
                () -> messageText(foldEditor).contains("zf: Eclipse defines the folds in this editor"),
                () -> "; it reads " + messageText(foldEditor));
        assertEquals(0, ((Number) SessionProbe.lua(session, "return vim.fn.foldlevel(10)")).intValue());
    }

    @Test
    void anEditorThatCannotFoldTurnsNeovimsFoldingOff() {
        // The plain Text Editor of test.txt: no folds, so Neovim's window gets 'nofoldenable' there,
        // and a 'foldmethod' of the user's cannot hide lines Eclipse shows.
        foldEditor.getSite().getPage().closeEditor(foldEditor, false);
        foldEditor = null;
        page().activate(editor);
        waitUntil("Neovim back in test.txt", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        waitUntil("folding off in Neovim's window", TIMEOUT_MS,
                () -> Boolean.FALSE.equals(SessionProbe.lua(session, "return vim.wo.foldenable")));
        assertEquals(null, SessionProbe.lua(session, "return vim.b.neovim4eclipse_folds"));
    }
}

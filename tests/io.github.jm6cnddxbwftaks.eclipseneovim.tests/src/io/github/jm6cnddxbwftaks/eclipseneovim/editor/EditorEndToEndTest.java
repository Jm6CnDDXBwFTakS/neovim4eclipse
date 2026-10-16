// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * Real editor, real Neovim: opens a file in Eclipse's text editor, waits for the plug-in to attach,
 * types keys into the editor widget and checks the document. Skipped when no {@code nvim} can be
 * found ({@code -Dnvim.path=...} selects one).
 *
 * <p>Keys are delivered with {@code notifyListeners(SWT.KeyDown)}, which runs the editor's own key
 * handling, the plug-in's {@code KeyInterceptor} included, but not the workbench's key-binding
 * dispatcher; Ctrl keys are therefore not covered here.
 */
class EditorEndToEndTest {

    private static final long TIMEOUT_MS = 15_000;

    private IProject project;
    private ITextEditor editor;
    private StyledText widget;
    private IDocument document;
    private NeovimSession session;

    @BeforeEach
    void openEditor() throws Exception {
        String nvimPath = System.getProperty("nvim.path", "");
        if (!nvimPath.isBlank()) {
            Activator.getDefault().getPreferenceStore().setValue(PreferenceConstants.NVIM_EXECUTABLE, nvimPath);
        }
        assumeTrue(NvimExecutable.looksAvailable(Activator.getDefault().getNvimExecutable()),
                "nvim not found; set -Dnvim.path");

        project = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e");
        if (!project.exists()) {
            project.create(null);
        }
        project.open(null);
        IFile file = project.getFile("test.txt");
        byte[] content = "alpha\nbeta\ngamma\n".getBytes(StandardCharsets.UTF_8);
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(content), true, false, null);
        } else {
            file.create(new ByteArrayInputStream(content), true, null);
        }

        EditorAttachManager.getInstance().installWorkbenchListeners();
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        editor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor", true);
        document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        widget = (StyledText) editor.getAdapter(Control.class);

        waitUntil("Neovim to attach", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        session = Activator.getDefault().getOrCreateSession();
        // One Neovim serves the whole IDE, so a previous test may have left it in another mode.
        session.inputAsync("<Esc>");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        UiTestSupport.spin(300); // let the initial document push and cursor placement finish
    }

    @AfterEach
    void closeEditor() throws Exception {
        if (editor != null) {
            editor.getSite().getPage().closeEditor(editor, false);
        }
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    private void expectText(String expected) {
        waitUntil("document to read " + expected.replace("\n", "\\n"), TIMEOUT_MS,
                () -> expected.equals(document.get()),
                () -> "; it reads " + document.get().replace("\n", "\\n") + " (mode " + session.getMode()
                        + ", caret " + widget.getCaretOffset() + ")");
    }

    @Test
    void ddDeletesTheFirstLine() {
        type(widget, "gg");
        type(widget, "dd");
        expectText("beta\ngamma\n");
    }

    @Test
    void xDeletesTheCharacterUnderTheCursor() {
        type(widget, "gg0x");
        expectText("lpha\nbeta\ngamma\n");
    }

    @Test
    void typedTextIsRepeatedWithDot() {
        type(widget, "gg");
        type(widget, 'A');
        // Wait until Eclipse may type (the buffer sync for entering insert mode has landed), so that
        // this test covers Eclipse's typing plus the replay into Neovim at Esc.
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, '!'); // typed by Eclipse, not Neovim
        assertFalse(session.isNeovimDrivenInsert());
        expectText("alpha!\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        type(widget, "j.");
        expectText("alpha!\nbeta!\ngamma\n");
    }

    /**
     * A key typed as soon as insert mode is reported, before the editor has received Neovim's
     * buffer sync for it. Eclipse used to type it, and the sync then overwrote it.
     */
    @Test
    void textTypedRightAfterEnteringInsertModeIsKept() {
        type(widget, "gg");
        type(widget, 'A');
        waitUntil("insert mode", TIMEOUT_MS, session::isTypingMode);
        type(widget, "!?"); // no pause: Neovim takes over this insert
        expectText("alpha!?\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        type(widget, "j.");
        expectText("alpha!?\nbeta!?\ngamma\n");
    }

    /** Keys typed before Neovim even reports insert mode reach Neovim in order, so they are inserted too. */
    @Test
    void textTypedTogetherWithTheInsertKeyIsKept() {
        type(widget, "ggA!");
        expectText("alpha!\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
    }

    @Test
    void undoRevertsNeovimsChange() {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");
        type(widget, 'u');
        expectText("alpha\nbeta\ngamma\n");
        assertEquals("n", session.getMode());
    }
}

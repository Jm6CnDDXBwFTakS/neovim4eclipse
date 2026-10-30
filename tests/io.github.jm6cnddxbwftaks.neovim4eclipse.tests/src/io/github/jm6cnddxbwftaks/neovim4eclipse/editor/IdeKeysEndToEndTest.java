// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeKeys;

/**
 * The IDE keys of {@link IdeKeys} in the plain Text Editor (no Java tooling): what each finds to run
 * there, and the message for one that finds nothing. The Java editor's commands are in
 * {@code JavaEditorEndToEndTest} (heavyJava); Neovim's mappings in {@code setup_spec.lua}.
 */
class IdeKeysEndToEndTest extends EndToEndTestBase {

    private IdeKeys.Choice choice(String action) {
        return IdeActions.choose(action, editor, editor.getAdapter(ITextViewer.class), widget.getCaretOffset());
    }

    private static IdeKeys.Choice message(String text) {
        return new IdeKeys.Choice(IdeKeys.Choice.Kind.MESSAGE, text);
    }

    /** The command ids the table holds as strings are the platform's. */
    @Test
    void theSharedCommandIdsAreThePlatforms() {
        assertEquals(ITextEditorActionDefinitionIds.QUICK_ASSIST, IdeKeys.QUICK_ASSIST);
        assertEquals(ITextEditorActionDefinitionIds.SHOW_INFORMATION, IdeKeys.SHOW_INFORMATION);
        assertEquals(ITextEditorActionDefinitionIds.OPEN_HYPERLINK, IdeKeys.OPEN_HYPERLINK);
    }

    /**
     * A text file: hover and Quick Fix are the text editor's own; Open Declaration is Neovim's
     * {@code gd}; the others say they are not available.
     */
    @Test
    void aTextFileHasHoverAndQuickFixAndNeovimsGd() {
        assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.COMMAND, IdeKeys.SHOW_INFORMATION), choice(IdeAction.HOVER));
        assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.COMMAND, IdeKeys.QUICK_ASSIST), choice(IdeAction.QUICK_FIX));
        assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.NEOVIM, "gd"), choice(IdeAction.DEFINITION));
        assertEquals(message("grn: Rename is not available in this editor"), choice(IdeAction.RENAME));
        assertEquals(message("grr: References is not available in this editor"), choice(IdeAction.REFERENCES));
        assertEquals(message("gri: Open Implementation is not available in this editor"), choice(IdeAction.IMPLEMENTATION));
        assertEquals(message("grt: Open Declared Type is not available in this editor"), choice(IdeAction.TYPE_DEFINITION));
        assertEquals(message("gO: Quick Outline is not available in this editor"), choice(IdeAction.OUTLINE));
    }

    /** {@code grn} typed in a text file shows its message in the message line. */
    @Test
    void grnInATextFileSaysRenameIsNotAvailable() {
        typeSlowly("grn");
        waitUntil("the message", TIMEOUT_MS, () -> "grn: Rename is not available in this editor".equals(messageText(editor)),
                () -> "; the message line shows \"" + messageText(editor) + "\"");
    }

    /**
     * Visual {@code gra} runs Quick Fix with Neovim's visual selection as the editor's selection, so a
     * Quick Assist works on the selected text (Extract Local Variable, in Java). Seen from the
     * command's execution: the editor's selection when Quick Fix starts.
     */
    @Test
    void visualGraRunsQuickFixOnTheSelection() {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        List<String> selections = new CopyOnWriteArrayList<>();
        IExecutionListener listener = new IExecutionListener() {
            @Override
            public void preExecute(String commandId, ExecutionEvent event) {
                if (IdeKeys.QUICK_ASSIST.equals(commandId)) {
                    selections.add(((ITextSelection) editor.getSelectionProvider().getSelection()).getText());
                }
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue) {
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception) {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception) {
            }
        };
        commands.addExecutionListener(listener);
        Shell window = editor.getSite().getShell();
        Set<Shell> before = Set.of(window.getDisplay().getShells());
        try {
            typeSlowly("gg0vegra");
            waitUntil("Quick Fix to run", TIMEOUT_MS, () -> !selections.isEmpty());
            assertEquals(List.of("alpha"), selections, "on the visual selection");
        } finally {
            commands.removeExecutionListener(listener);
            for (Shell shell : window.getDisplay().getShells()) { // Quick Fix's popup, if it opened one
                if (!before.contains(shell) && !shell.isDisposed()) {
                    shell.close();
                }
            }
            page().activate(editor);
            session.inputAsync("<Esc>");
            settle();
        }
    }

    /** A command that fails while running says so in the message line, with the key and its label. */
    @Test
    void aCommandThatFailsWhileRunningSaysSo() {
        IdeActions.EclipseEditor failing = new IdeActions.EclipseEditor(editor, editor.getAdapter(ITextViewer.class),
                widget.getCaretOffset(), IdeAction.REFERENCES, IdeKeys.COMMANDS.get(IdeAction.REFERENCES));
        assertTrue(failing.execute(TestFailingHandler.COMMAND), "it ran (and failed): no next way");
        waitUntil("the message", TIMEOUT_MS, () -> "grr: References failed: boom".equals(messageText(editor)),
                () -> "; the message line shows \"" + messageText(editor) + "\"");
    }

    /** A link that throws when opened says so in the message line, and counts as opened. */
    @Test
    void aLinkThatFailsToOpenSaysSo() throws Exception {
        ITextEditor links = openFile("links.txt", TestHyperlinkDetector.FAILING + "\n");
        IdeActions.EclipseEditor failing = new IdeActions.EclipseEditor(links, links.getAdapter(ITextViewer.class), 1,
                IdeAction.TYPE_DEFINITION, IdeKeys.COMMANDS.get(IdeAction.TYPE_DEFINITION));
        assertTrue(failing.linkAt(TestHyperlinkDetector.ID));
        assertTrue(failing.openLink(TestHyperlinkDetector.ID), "found, and failed: no next way");
        waitUntil("the message", TIMEOUT_MS,
                () -> "grt: Open Declared Type failed: cannot open FAILME".equals(messageText(links)),
                () -> "; the message line shows \"" + messageText(links) + "\"");
        page().closeEditor(links, false);
    }

    /**
     * The text page of a multi-page editor (here {@link TestMultiPageEditor}, like the POM editor)
     * finds the text editor's commands, as a plain Text Editor does. (Checked 2026-10-29: asking the
     * outer editor's context gives the same answer, since Eclipse looks the handler up from the
     * active part's innermost context either way; so this guards the result, not which context
     * {@code runsHere} asks.)
     */
    @Test
    void theTextPageOfAMultiPageEditorFindsItsOwnCommands() throws Exception {
        IFile file = createFile("pom.mpage", "<project/>\n");
        IEditorPart multiPage = IDE.openEditor(page(), file, true);
        assertTrue(multiPage instanceof TestMultiPageEditor, "opened in " + multiPage);
        ITextEditor sourcePage = EditorAttachManager.textEditorOf(multiPage);
        waitForAttach(sourcePage, "the Source page");
        try {
            assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.COMMAND, IdeKeys.SHOW_INFORMATION),
                    IdeActions.choose(IdeAction.HOVER, sourcePage, sourcePage.getAdapter(ITextViewer.class), 0));
            assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.COMMAND, IdeKeys.QUICK_ASSIST),
                    IdeActions.choose(IdeAction.QUICK_FIX, sourcePage, sourcePage.getAdapter(ITextViewer.class), 0));
        } finally {
            page().closeEditor(multiPage, false);
        }
    }
}

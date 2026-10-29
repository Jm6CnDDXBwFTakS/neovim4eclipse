// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
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

    private IdeActions.Choice choice(String action) {
        return IdeActions.choose(action, editor, editor.getAdapter(ITextViewer.class), widget.getCaretOffset());
    }

    private static IdeActions.Choice message(String text) {
        return new IdeActions.Choice(IdeActions.Choice.Kind.MESSAGE, text);
    }

    /** The two command ids the table holds as strings are the platform's. */
    @Test
    void theSharedCommandIdsAreThePlatforms() {
        assertEquals(ITextEditorActionDefinitionIds.QUICK_ASSIST, IdeKeys.QUICK_ASSIST);
        assertEquals(ITextEditorActionDefinitionIds.SHOW_INFORMATION, IdeKeys.SHOW_INFORMATION);
    }

    /**
     * A text file: hover and Quick Fix are the text editor's own; Open Declaration is Neovim's
     * {@code gd}; the others say they are not available.
     */
    @Test
    void aTextFileHasHoverAndQuickFixAndNeovimsGd() {
        assertEquals(new IdeActions.Choice(IdeActions.Choice.Kind.COMMAND, IdeKeys.SHOW_INFORMATION), choice(IdeAction.HOVER));
        assertEquals(new IdeActions.Choice(IdeActions.Choice.Kind.COMMAND, IdeKeys.QUICK_ASSIST), choice(IdeAction.QUICK_FIX));
        assertEquals(new IdeActions.Choice(IdeActions.Choice.Kind.NEOVIM, "gd"), choice(IdeAction.DEFINITION));
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
        try {
            typeSlowly("gg0vegra");
            waitUntil("Quick Fix to run", TIMEOUT_MS, () -> !selections.isEmpty());
            assertEquals(List.of("alpha"), selections, "on the visual selection");
        } finally {
            commands.removeExecutionListener(listener);
            for (Shell shell : window.getDisplay().getShells()) { // Quick Fix's popup, if it opened one
                if (shell != window && !shell.isDisposed() && shell.isVisible()) {
                    shell.close();
                }
            }
            page().activate(editor);
            session.inputAsync("<Esc>");
            settle();
        }
    }
}

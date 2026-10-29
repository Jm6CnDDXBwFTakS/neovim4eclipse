// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeKeys;

/**
 * The IDE keys of {@link IdeKeys} in the plain Text Editor (no Java tooling): what each finds to run
 * there. Neovim's mappings are in {@code setup_spec.lua}.
 */
class IdeKeysEndToEndTest extends EndToEndTestBase {

    private IdeActions.Choice choice(String action) {
        return IdeActions.choose(action, editor, editor.getAdapter(ITextViewer.class), widget.getCaretOffset());
    }

    /** The command id the table holds as a string is the platform's. */
    @Test
    void theSharedCommandIdIsThePlatforms() {
        assertEquals(ITextEditorActionDefinitionIds.SHOW_INFORMATION, IdeKeys.SHOW_INFORMATION);
    }

    /** A text file: hover is the text editor's own; Open Declaration is Neovim's {@code gd}. */
    @Test
    void aTextFileHasHoverAndNeovimsGd() {
        assertEquals(new IdeActions.Choice(IdeActions.Choice.Kind.COMMAND, IdeKeys.SHOW_INFORMATION), choice(IdeAction.HOVER));
        assertEquals(new IdeActions.Choice(IdeActions.Choice.Kind.NEOVIM, "gd"), choice(IdeAction.DEFINITION));
    }
}

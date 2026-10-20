// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.keys.IBindingService;

/**
 * Asks the workbench which command a key runs right now.
 *
 * <p>Eclipse resolves a key through its <em>bindings</em>: a binding ties a key sequence to a
 * command in a context, and the active contexts (the editor's, the plug-in's Vim contexts) decide
 * which binding wins. {@code IBindingService.getPerfectMatch} answers that question for the current
 * state. Example: in the Java editor {@code commandIdFor("F3")} is
 * {@code org.eclipse.jdt.ui.edit.text.java.open.editor} (Open Declaration).
 *
 * <p>UI thread only.
 */
final class KeyBindings {

    private KeyBindings() {
    }

    /**
     * @param keySequence in Eclipse's notation: {@code "F3"}, {@code "M1+V"} (Ctrl+V; Cmd+V on macOS)
     * @return the id of the command the key would run now, or {@code null} if it runs none
     * @throws IllegalStateException if the workbench has no binding service
     * @throws ParseException        if {@code keySequence} is not valid notation
     */
    static String commandIdFor(String keySequence) throws ParseException {
        IBindingService bindings = PlatformUI.getWorkbench().getService(IBindingService.class);
        if (bindings == null) {
            throw new IllegalStateException("No IBindingService; cannot resolve " + keySequence);
        }
        Binding binding = bindings.getPerfectMatch(KeySequence.getInstance(keySequence));
        return binding == null || binding.getParameterizedCommand() == null
                ? null : binding.getParameterizedCommand().getId();
    }
}

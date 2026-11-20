// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionInfo;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Makes Eclipse's Paste act on Neovim's command line while one is open, and Copy, Cut and Select
 * All do nothing then, instead of acting on the document behind the line.
 *
 * <h2>Why</h2>
 * Neovim draws its command line itself (ui2, {@code LuaScripts.UI2}), over the editor's last row;
 * the editor's text widget keeps the keyboard, and the keys go to Neovim. But Eclipse turns a key
 * such as {@code Cmd+V} (on macOS; {@code Shift+Insert}, or {@code Ctrl+V} where the Ctrl Keys page
 * leaves it to Eclipse) into the command {@code org.eclipse.ui.edit.paste} before any widget sees
 * the key, and runs the command's <em>handler</em>: for an editor, the editor's Paste action, which
 * would paste into the document while the user is typing {@code :e }.
 *
 * <h2>How</h2>
 * While the line is open, a handler of our own is activated for each of the four commands in the
 * editor site's {@link IHandlerService}. When several handlers are active for one command, the
 * workbench picks the one whose activation expression depends on the most specific variable; the
 * editor's handlers depend on the active part. Ours, {@link #WHILE_OPEN}, says it depends on the
 * focus control, which ranks above everything except menus ({@code ISources}), so ours wins; it is
 * always true, because it is activated only while the line is open and deactivated when it closes.
 *
 * <p>Example: {@code :e } typed, then Cmd+V: the workbench runs our paste handler, which sends
 * the clipboard's text to Neovim as a paste ({@code nvim_paste}), and Neovim's line shows
 * {@code :e src/Main.java}. Copy and Cut copy nothing: the line's text is Neovim's, and a yank
 * there ({@code Ctrl-R}) is the Vim way. UI thread only.
 */
final class CommandLineEditHandlers {

    private static final Logger LOG = Logger.getLogger(CommandLineEditHandlers.class.getName());

    /**
     * True whenever evaluated; ranked as depending on the focus control (see the class comment).
     * Declaring the variable access is what sets the rank; the value is not needed.
     */
    private static final Expression WHILE_OPEN = new Expression() {
        @Override
        public EvaluationResult evaluate(IEvaluationContext context) {
            return EvaluationResult.TRUE;
        }

        @Override
        public void collectExpressionInfo(ExpressionInfo info) {
            info.addVariableNameAccess(ISources.ACTIVE_FOCUS_CONTROL_NAME);
        }
    };

    private final IHandlerService service;
    private final Runnable paste;
    private final List<IHandlerActivation> activations = new ArrayList<>();

    /**
     * @param service the editor site's handler service; {@code null} (no workbench) does nothing
     * @param paste   sends the clipboard's text to Neovim's line
     */
    CommandLineEditHandlers(IHandlerService service, Runnable paste) {
        this.service = service;
        this.paste = paste;
    }

    /** Activates the handlers while a command line is {@code open}, deactivates them when it is not. UI thread. */
    void update(boolean open) {
        if (service == null || open == !activations.isEmpty()) {
            return;
        }
        try {
            if (open) {
                activate("org.eclipse.ui.edit.paste", paste);
                activate("org.eclipse.ui.edit.copy", () -> { });
                activate("org.eclipse.ui.edit.cut", () -> { });
                activate("org.eclipse.ui.edit.selectAll", () -> { });
            } else {
                deactivate();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Command-line copy/paste handlers could not be switched", e);
        }
    }

    /** Deactivates the handlers for good. UI thread. */
    void dispose() {
        if (service != null) {
            deactivate();
        }
    }

    private void activate(String commandId, Runnable action) {
        activations.add(service.activateHandler(commandId, new AbstractHandler() {
            @Override
            public Object execute(ExecutionEvent event) {
                action.run();
                return null;
            }
        }, WHILE_OPEN));
    }

    private void deactivate() {
        if (!activations.isEmpty()) {
            service.deactivateHandlers(activations);
            activations.clear();
        }
    }
}

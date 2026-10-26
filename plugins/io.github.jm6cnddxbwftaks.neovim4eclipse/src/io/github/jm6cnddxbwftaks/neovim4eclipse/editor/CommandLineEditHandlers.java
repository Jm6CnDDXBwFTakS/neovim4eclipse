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
 * Makes Eclipse's Copy, Paste, Cut and Select All commands act on the {@code :} box while it is open,
 * not on the editor behind it.
 *
 * <h2>Why</h2>
 * Eclipse turns a key such as {@code Ctrl+V} (Cmd+V on macOS, also Shift+Insert) into the command
 * {@code org.eclipse.ui.edit.paste} before any widget sees the key, and runs the command's
 * <em>handler</em>: for an editor, the editor's Paste action, which pastes into the document. The box
 * is a child of the editor's text widget, so its keys would paste into the file.
 *
 * <h2>How</h2>
 * While the box is open, a handler of our own is activated for each of the four commands in the
 * editor site's {@link IHandlerService}. When several handlers are active for one command, the
 * workbench picks the one whose activation expression depends on the most specific variable; the
 * editor's handlers depend on the active part. Ours, {@link #WHILE_OPEN}, says it depends on the
 * focus control, which ranks above everything except menus ({@code ISources}), so ours wins; it is
 * always true, because it is activated only while the box is open and deactivated when it closes.
 *
 * <p>Example: {@code :e } typed, then Cmd+V: the workbench runs our paste handler,
 * {@link CommandLineBox#paste} pastes into the box, the box refuses the edit and sends the text to
 * Neovim as a paste, and Neovim's line shows {@code :e src/Main.java}.
 *
 * <p>Cut copies only: the line is Neovim's, and the box deletes nothing itself. UI thread only.
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
    private final CommandLineBox box;
    private final List<IHandlerActivation> activations = new ArrayList<>();

    /** @param service the editor site's handler service; {@code null} (no workbench) does nothing */
    CommandLineEditHandlers(IHandlerService service, CommandLineBox box) {
        this.service = service;
        this.box = box;
    }

    /** Activates the handlers while the box is open, deactivates them when it is closed. UI thread. */
    void update() {
        boolean wanted = box.isOpen();
        if (service == null || wanted == !activations.isEmpty()) {
            return;
        }
        try {
            if (wanted) {
                activate("org.eclipse.ui.edit.paste", box::paste);
                activate("org.eclipse.ui.edit.copy", box::copy);
                activate("org.eclipse.ui.edit.cut", box::copy);
                activate("org.eclipse.ui.edit.selectAll", box::selectAll);
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

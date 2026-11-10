// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * Eclipse's <i>Window &gt; Editor &gt; Toggle Split Editor</i> (Ctrl+_ one above the other,
 * Ctrl+{ side by side) as a Vim split, in an editor Neovim drives: {@code :split} or
 * {@code :vsplit}, a new editor stack beside this one (docs/design/SPLITS.md). Pressed while the
 * editor area shows more than one editor, it runs {@code :only}, so the key still toggles. In any
 * other editor, or with the hidden preference {@link PreferenceConstants#ECLIPSE_SPLITS} set, it
 * does what Eclipse's own handler does: two halves of the one editor.
 *
 * <p><b>Why both handlers can be registered.</b> A command has one handler at a time, chosen
 * from the registered ones by their {@code activeWhen} expressions. Eclipse registers its own
 * handler ({@code org.eclipse.ui.internal.SplitHandler}) with none, only an {@code enabledWhen},
 * so it is the default; this one has an {@code activeWhen} (an editor is active), and a handler
 * whose expression holds wins over one without. Eclipse's handler is internal API, so its
 * behavior is done here again, by the same public means: it toggles a tag on the editor's model
 * part ({@link IPresentationEngine#SPLIT_HORIZONTAL} or {@link IPresentationEngine#SPLIT_VERTICAL}),
 * and Eclipse's splitter add-on makes or removes the halves.
 *
 * <p><b>Which is which.</b> The command's parameter {@code Splitter.isHorizontal} is {@code true}
 * for Ctrl+_, which splits along a horizontal line, so the halves are one above the other, as
 * {@code :split} has them; {@code false} for Ctrl+{, side by side, as {@code :vsplit}.
 */
public final class SplitEditorHandler extends AbstractHandler {

    /** The command's parameter: {@code "true"} for one above the other. */
    static final String HORIZONTAL_PARAMETER = "Splitter.isHorizontal";

    @Override
    public Object execute(ExecutionEvent event) {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor == null) {
            return null;
        }
        boolean oneAboveTheOther = "true".equals(event.getParameter(HORIZONTAL_PARAMETER));
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        NeovimSession session = Activator.getDefault().currentSession();
        boolean neovim = controller != null && controller.isNeovimModeActive() && session != null
                && EditorAttachManager.getInstance().workbenchPartOf(controller.getEditor()) == editor;
        // Read each time (a local preference, no Neovim call), so a change applies at once.
        boolean eclipseSplits = Activator.getDefault().getPreferenceStore().getBoolean(PreferenceConstants.ECLIPSE_SPLITS);
        if (neovim && !eclipseSplits) {
            if (EditorSplits.isSplit(editor)) {
                EditorSplits.only(editor);
            } else {
                session.splitWindowAsync(!oneAboveTheOther);
            }
        } else {
            toggleHalves(editor, oneAboveTheOther);
        }
        return null;
    }

    /**
     * Eclipse's own Toggle Split Editor: the halves of one editor, made or taken away. As Eclipse's
     * handler does it: the stack's front tab (the editor, or the composite part holding its two
     * halves) is searched for the editor's model part, whose split tag is then toggled; a split the
     * other way round is turned into this one.
     */
    static void toggleHalves(IEditorPart editor, boolean oneAboveTheOther) {
        MPart part = editor.getSite().getService(MPart.class);
        EModelService models = editor.getSite().getService(EModelService.class);
        MPartStack stack = EditorSplits.stackOf(part);
        if (part == null || models == null || stack == null || stack.getSelectedElement() == null) {
            return;
        }
        MUIElement front = stack.getSelectedElement();
        List<MPart> found = models.findElements(front, part.getElementId(), MPart.class, null);
        MPart target = found.isEmpty() ? part : found.get(0);
        String wanted = oneAboveTheOther ? IPresentationEngine.SPLIT_HORIZONTAL : IPresentationEngine.SPLIT_VERTICAL;
        String other = oneAboveTheOther ? IPresentationEngine.SPLIT_VERTICAL : IPresentationEngine.SPLIT_HORIZONTAL;
        Shell shell = editor.getSite().getShell();
        shell.setRedraw(false);
        try {
            if (target.getTags().contains(wanted)) {
                target.getTags().remove(wanted);
            } else {
                target.getTags().remove(other);
                target.getTags().add(wanted);
            }
        } finally {
            shell.setRedraw(true);
        }
    }
}

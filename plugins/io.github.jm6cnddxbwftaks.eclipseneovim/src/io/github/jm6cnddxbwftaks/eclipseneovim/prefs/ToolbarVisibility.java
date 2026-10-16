// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IEvaluationService;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;

/**
 * Helpers for the toolbar show/hide preference: reads it, and makes Eclipse re-evaluate the
 * {@code visibleWhen} expression that uses it (see {@link NeovimPropertyTester}).
 */
public final class ToolbarVisibility {

    private ToolbarVisibility() {
    }

    public static boolean isShowToolbar() {
        Activator activator = Activator.getDefault();
        if (activator == null) {
            return PreferenceConstants.SHOW_TOOLBAR_DEFAULT;
        }
        return activator.getPreferenceStore().getBoolean(PreferenceConstants.SHOW_TOOLBAR);
    }

    /**
     * Force Eclipse to re-check {@code visibleWhen} on menu/toolbar contributions.
     */
    public static void refresh() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return;
            }
            IEvaluationService eval = PlatformUI.getWorkbench().getService(IEvaluationService.class);
            if (eval != null) {
                eval.requestEvaluation("io.github.jm6cnddxbwftaks.eclipseneovim.showToolbar");
            }
        } catch (Exception ignored) {
            // Workbench may be shutting down
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IEvaluationService;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Helpers for the preferences that show or hide the plug-in's contributions, the <em>Neovim</em>
 * menu in the menu bar and the <em>NVIM</em> toolbar button: reads them, and makes Eclipse
 * re-evaluate the {@code visibleWhen} expressions that use them (see {@link NeovimPropertyTester}).
 */
public final class ContributionVisibility {

    /** The properties {@link NeovimPropertyTester} answers, as {@code plugin.xml} names them. */
    static final String SHOW_TOOLBAR_PROPERTY = "io.github.jm6cnddxbwftaks.neovim4eclipse.showToolbar";
    static final String SHOW_MENU_PROPERTY = "io.github.jm6cnddxbwftaks.neovim4eclipse.showMenu";

    private ContributionVisibility() {
    }

    public static boolean isShowToolbar() {
        return preference(PreferenceConstants.SHOW_TOOLBAR, PreferenceConstants.SHOW_TOOLBAR_DEFAULT);
    }

    public static boolean isShowMenu() {
        return preference(PreferenceConstants.SHOW_MENU, PreferenceConstants.SHOW_MENU_DEFAULT);
    }

    private static boolean preference(String key, boolean fallback) {
        Activator activator = Activator.getDefault();
        if (activator == null) {
            return fallback;
        }
        return activator.getPreferenceStore().getBoolean(key);
    }

    /**
     * Force Eclipse to re-check {@code visibleWhen} on the menu and toolbar contributions. Eclipse
     * caches each expression's answer until a property it uses is reported as changed.
     */
    public static void refresh() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return;
            }
            IEvaluationService eval = PlatformUI.getWorkbench().getService(IEvaluationService.class);
            if (eval != null) {
                eval.requestEvaluation(SHOW_TOOLBAR_PROPERTY);
                eval.requestEvaluation(SHOW_MENU_PROPERTY);
            }
        } catch (Exception ignored) {
            // Workbench may be shutting down
        }
    }
}

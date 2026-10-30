// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * The status bar in a real window (docs/design/STATUS_BAR.md): the mode in the chosen style, a label
 * per part, Neovim's statusline fitted to its label, and {@code -- ECLIPSE --} when keys go to
 * Eclipse. The texts are {@code ModeLabelsTest}; the widths {@code StatusBarLabelsTest}.
 */
class StatusBarEndToEndTest extends EndToEndTestBase {

    private static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    @AfterEach
    void restorePreferences() {
        for (String key : new String[] { PreferenceConstants.STATUS_MODE_STYLE, PreferenceConstants.STATUS_ITEM_MODE,
                PreferenceConstants.STATUS_ITEM_STATUSLINE, PreferenceConstants.STATUS_STATUSLINE_WIDTH }) {
            store().setToDefault(key);
        }
        // No controller when the test failed with the editor gone: the preferences are restored
        // anyway, and the test's own failure is the one reported.
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller != null) {
            controller.setNeovimModeActive(true);
            settle();
        }
    }

    private void waitForLabel(String text) {
        waitUntil("the status bar showing " + text, TIMEOUT_MS, () -> statusLineTexts(editor).contains(text),
                () -> "; it shows " + statusLineTexts(editor));
    }

    @Test
    void theModeInEachStyle() {
        waitForLabel("-- NORMAL --");
        typeSlowly("i");
        waitForLabel("-- INSERT --");
        store().setValue(PreferenceConstants.STATUS_MODE_STYLE, "letters");
        waitForLabel("-I-");
        store().setValue(PreferenceConstants.STATUS_MODE_STYLE, "short");
        waitForLabel("INSERT");
        UiTestSupport.escape(widget);
        waitForLabel("NORMAL");
    }

    /** Neovim fits its statusline into the label: at most the columns asked for, its end (the position) kept. */
    @Test
    void theStatuslineFitsItsLabel() {
        store().setValue(PreferenceConstants.STATUS_STATUSLINE_WIDTH, 24);
        store().setValue(PreferenceConstants.STATUS_ITEM_STATUSLINE, true);
        typeSlowly("l");
        waitUntil("a statusline ending in Neovim's position", TIMEOUT_MS,
                () -> statusLineTexts(editor).stream().anyMatch(t -> t.endsWith("All") || t.endsWith("Top")),
                () -> "; the bar shows " + statusLineTexts(editor));
        String statusline = statusLineTexts(editor).stream().filter(t -> t.endsWith("All") || t.endsWith("Top"))
                .findFirst().orElseThrow();
        assertTrue(statusline.length() <= 24, "at most 24 columns: \"" + statusline + "\"");
    }

    /** The indicator says where keys go, so it shows even with the mode part turned off. */
    @Test
    void eclipseShowsEvenWithTheModeOff() {
        store().setValue(PreferenceConstants.STATUS_ITEM_MODE, false);
        waitUntil("no mode label", TIMEOUT_MS, () -> !statusLineTexts(editor).contains("-- NORMAL --"),
                () -> "; it shows " + statusLineTexts(editor));
        EditorAttachManager.getInstance().getActiveController().setNeovimModeActive(false);
        waitForLabel("-- ECLIPSE --");
        EditorAttachManager.getInstance().getActiveController().setNeovimModeActive(true);
        waitUntil("the indicator gone again", TIMEOUT_MS, () -> !statusLineTexts(editor).contains("-- ECLIPSE --"),
                () -> "; it shows " + statusLineTexts(editor));
    }
}

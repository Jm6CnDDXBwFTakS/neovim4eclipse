// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * Neovim's messages and errors in the line along the bottom of the editor ({@link MessageLine}),
 * from a real key to what is drawn: errors marked as errors, plain messages not, the next key
 * hiding them, nothing left in the status bar, and the opt-in flash.
 */
class MessageLineEndToEndTest extends EndToEndTestBase {

    private MessageLine line() {
        return messageLineOf(editor);
    }

    private void waitForMessage(String text) {
        waitUntil("\"" + text + "\" in the message line", TIMEOUT_MS, () -> line().text().equals(text),
                () -> "; it shows \"" + line().text() + "\"");
    }

    @Test
    void anErrorFromNeovimShowsInTheEditorAsAnError() {
        runInBox(widget, "s/zzz/y/");

        waitForMessage("E486: Pattern not found: zzz");
        assertTrue(line().isError());
        assertTrue(statusLineTexts(editor).stream().noneMatch(text -> text.contains("E486")),
                "not in the status bar: " + statusLineTexts(editor));
    }

    @Test
    void aPlainMessageIsNotAnError() {
        typeSlowly("3yy");

        waitForMessage("3 lines yanked");
        assertFalse(line().isError());
    }

    /** {@code n} twice with no match: the second error shows again after the first key hid it. */
    @Test
    void theSameErrorTwiceShowsTwice() {
        runInBox(widget, "s/zzz/y/");
        waitForMessage("E486: Pattern not found: zzz");
        typeSlowly("j");
        waitUntil("the message line to hide", TIMEOUT_MS, () -> !line().isShowing());

        runInBox(widget, "s/zzz/y/");

        waitForMessage("E486: Pattern not found: zzz");
    }

    @Test
    void withTheFlashOnAnErrorFlashesTheEditor() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PreferenceConstants.FLASH_ON_ERROR, true);
        try {
            int before = line().flashCount();
            runInBox(widget, "s/zzz/y/");

            waitForMessage("E486: Pattern not found: zzz");
            // Counted, not caught in the act: the flash lasts 150 ms, and a slow CI machine (macOS)
            // could start and end it between two checks of isFlashing().
            waitUntil("the flash", TIMEOUT_MS, () -> line().flashCount() > before);
            waitUntil("the flash to end", TIMEOUT_MS, () -> !line().isFlashing());
        } finally {
            store.setToDefault(PreferenceConstants.FLASH_ON_ERROR);
        }
    }
}

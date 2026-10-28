// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.ui.model.application.ui.menu.MItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.ide.IDE;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * The Neovim toolbar button as the user sees it, the SWT {@link ToolItem} itself: pressed in Neovim
 * mode, raised in Eclipse mode, greyed out without Neovim, with a tooltip that names the mode, and
 * a color logo in Neovim mode, a grey one in Eclipse mode.
 * {@code ToggleButtonStateTest} covers the decision; this covers the wiring (plugin.xml's
 * {@code style="toggle"}, the handler's {@code IElementUpdater} and enablement, and the refresh).
 */
class ToggleButtonEndToEndTest extends EndToEndTestBase {

    @Test
    void theButtonFollowsTheModeAndGreysOutWithoutNeovim() throws Exception {
        ToolItem button = toggleButton();
        expectButton(button, true, true, "Neovim mode: ");

        runCommand(WelcomeNote.TOGGLE_COMMAND_ID); // the key binding
        expectButton(button, true, false, "Eclipse mode: ");
        waitUntil("the grey logo", TIMEOUT_MS, () -> !hasColor(button.getImage()));

        // A click: SWT flips a toggle item's selection itself, then reports it.
        button.setSelection(true);
        button.notifyListeners(SWT.Selection, new Event());
        expectButton(button, true, true, "Neovim mode: ");
        waitUntil("the color logo", TIMEOUT_MS, () -> hasColor(button.getImage()));

        EditorAttachManager.getInstance().detachQuietly();
        expectButton(button, false, false, "Neovim mode unavailable (");
        assertFalse(PlatformUI.getWorkbench().getService(ICommandService.class)
                .getCommand(WelcomeNote.TOGGLE_COMMAND_ID).isEnabled(), "the key binding still works");

        EditorAttachManager.getInstance().attach(editor, true);
        expectButton(button, true, true, "Neovim mode: ");
    }

    /**
     * The toolbar keeps its own copy of the button's "enabled" and re-reads it from the handler only
     * when asked to. Here the copy is made stale on purpose (enabled while the handler says
     * disabled), as a busy machine could leave it; a refresh request must then grey the button out
     * by itself, without an unrelated change in Eclipse (a selection, a key binding context) to set
     * off the re-read. See {@code ToggleButtonRefresh.requestEnablementUpdate}.
     */
    @Test
    void aRefreshGreysTheButtonOutEvenWhenTheToolbarsCopyIsStale() throws Exception {
        ToolItem button = toggleButton();
        expectButton(button, true, true, "Neovim mode: ");
        EditorAttachManager.getInstance().detachQuietly();
        expectButton(button, false, false, "Neovim mode unavailable (");

        // The toolbar's copy: the model item the button was rendered from
        // (AbstractPartRenderer.OWNING_ME, "modelElement"). Setting it redraws the button enabled.
        MItem model = (MItem) button.getData("modelElement");
        assertNotNull(model, "the button's model item");
        model.setEnabled(true);
        waitUntil("the stale copy shown", TIMEOUT_MS, button::getEnabled);

        ToggleButtonRefresh.request();
        expectButton(button, false, false, "Neovim mode unavailable (");

        EditorAttachManager.getInstance().attach(editor, true);
        expectButton(button, true, true, "Neovim mode: ");
    }

    /**
     * A page without text (a manifest's Overview, here {@link TestMultiPageEditor}'s): the button
     * greys out and says why, without offering the attach key, which cannot help there.
     */
    @Test
    void aPageWithoutTextGreysTheButtonOutAndSaysWhy() throws Exception {
        ToolItem button = toggleButton();
        TestMultiPageEditor multiPage = (TestMultiPageEditor) IDE.openEditor(page(),
                createFile("pom.mpage", "<project/>\n"), true);
        try {
            waitForAttach(EditorAttachManager.textEditorOf(multiPage), "the Source page");
            expectButton(button, true, true, "Neovim mode: ");

            multiPage.showOverview();
            expectButton(button, false, false, "Neovim mode unavailable: not a text editor.");
        } finally {
            page().closeEditor(multiPage, false);
        }
        expectButton(button, true, true, "Neovim mode: "); // back in test.txt
    }

    /**
     * Whether the image has colored pixels (red, green and blue clearly apart), as the logo has and
     * its grey twin has not. A whole image, not one pixel: the shape may scale differently.
     */
    private static boolean hasColor(Image image) {
        if (image == null || image.isDisposed()) {
            return false;
        }
        ImageData data = image.getImageData();
        int colored = 0;
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                if (data.getAlpha(x, y) < 128) {
                    continue;
                }
                RGB rgb = data.palette.getRGB(data.getPixel(x, y));
                int max = Math.max(rgb.red, Math.max(rgb.green, rgb.blue));
                int min = Math.min(rgb.red, Math.min(rgb.green, rgb.blue));
                if (max - min > 40) {
                    colored++;
                }
            }
        }
        return colored > 4;
    }

    private static void expectButton(ToolItem button, boolean enabled, boolean pressed, String tooltipStart) {
        waitUntil("the button: enabled=" + enabled + ", pressed=" + pressed + ", tooltip " + tooltipStart,
                TIMEOUT_MS,
                () -> button.getEnabled() == enabled && button.getSelection() == pressed
                        && button.getToolTipText() != null && button.getToolTipText().startsWith(tooltipStart),
                () -> "enabled=" + button.getEnabled() + ", pressed=" + button.getSelection()
                        + ", tooltip=" + button.getToolTipText());
    }

    /** The toolbar item of the toggle command, found by its tooltip (the only text it has). */
    private static ToolItem toggleButton() {
        String[] tooltips = { "Neovim mode", "Eclipse mode", "Toggle Neovim / Eclipse" };
        List<ToolItem> items = new ArrayList<>();
        waitUntil("the Neovim toolbar button", TIMEOUT_MS, () -> {
            items.clear();
            Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
            collectToolItems(shell, items);
            items.removeIf(item -> {
                String tip = item.getToolTipText();
                for (String start : tooltips) {
                    if (tip != null && tip.startsWith(start)) {
                        return false;
                    }
                }
                return true;
            });
            return items.size() == 1;
        });
        assertNotNull(items.get(0));
        return items.get(0);
    }

    private static void collectToolItems(Control control, List<ToolItem> into) {
        if (control instanceof ToolBar bar) {
            for (ToolItem item : bar.getItems()) {
                into.add(item);
            }
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collectToolItems(child, into);
            }
        }
    }
}

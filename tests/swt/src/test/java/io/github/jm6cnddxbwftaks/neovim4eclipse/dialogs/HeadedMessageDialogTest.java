// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Point;

/**
 * The plug-in's laid-out dialog on real SWT widgets, without a workbench: the headings in bold, a
 * value in a read-only field with Copy, links that go to the opener, and the buttons' answers. The
 * dialog opens without blocking ({@code setBlockOnOpen(false)}), so the test reads its widgets and
 * presses them with synthetic events.
 */
class HeadedMessageDialogTest {

    /** As long as a SHA-256, which is wider than the text column at the default width. */
    private static final String HASH = "2e58d259ffcc6145e5112ea02330126c6b57da09087bb9265fb2fa9aa10b485c";
    private static final URI PAGE = URI.create("https://github.com/neovim/neovim/releases/tag/v0.12.6");
    private static final URI REPORT = URI.create("https://www.virustotal.com/gui/file/" + HASH);

    private static final HeadedMessage MESSAGE = new HeadedMessage("Install Neovim?",
            List.of(new Point("Download", "From github.com."),
                    new Point("SHA-256", "Checked before unpacking.").withCopy(HASH)
                            .withLinks(new HeadedMessage.Link("Release page", PAGE),
                                    new HeadedMessage.Link("VirusTotal", REPORT))),
            "The end.");

    private final List<URI> opened = new ArrayList<>();
    private HeadedMessageDialog dialog;

    private HeadedMessageDialog open() {
        dialog = new HeadedMessageDialog(null, "Get Neovim", MessageDialog.QUESTION, MESSAGE, 0, "&Download",
                "Cancel").openLinksWith(opened::add);
        dialog.setBlockOnOpen(false);
        dialog.open();
        return dialog;
    }

    @AfterEach
    void close() {
        if (dialog != null && dialog.getShell() != null) {
            dialog.close();
        }
    }

    @Test
    void theHeadingsAreBoldAndTheTextsBesideThem() {
        open();
        Label heading = label("SHA-256");
        assertTrue((heading.getFont().getFontData()[0].getStyle() & SWT.BOLD) != 0, "the heading is bold");
        Label text = label("Checked before unpacking.");
        assertTrue(toDisplayX(text) > toDisplayX(heading), "the text is right of it");
        assertEquals(toDisplayX(label("From github.com.")), toDisplayX(text), "the texts line up in one column");
    }

    @Test
    void aValueShowsWholeInAReadOnlyFieldInTheTextFont() {
        open();
        Text field = find(Text.class).get(0);
        assertEquals(HASH, field.getText());
        assertTrue((field.getStyle() & SWT.READ_ONLY) != 0, "read-only");
        assertEquals(JFaceResources.getTextFont(), field.getFont(), "fixed width, so the hash reads digit by digit");
        assertTrue(field.getSize().x >= field.computeSize(SWT.DEFAULT, SWT.DEFAULT).x,
                "the text widened to the hash: " + field.getSize().x + " < " + field.computeSize(SWT.DEFAULT,
                        SWT.DEFAULT).x);
    }

    @Test
    void copyPutsTheValueOnTheClipboardAndSaysSo() {
        open();
        Button copy = find(Button.class).stream().filter(b -> b.getText().equals("Copy")).findFirst().orElseThrow();
        copy.notifyListeners(SWT.Selection, new Event());
        assertEquals("Copied", copy.getText());
        Clipboard clipboard = new Clipboard(Display.getDefault());
        try {
            assertEquals(HASH, clipboard.getContents(TextTransfer.getInstance()));
        } finally {
            clipboard.dispose();
        }
    }

    @Test
    void theLinksShareALineAndEachGoesToTheOpener() {
        open();
        Link links = find(Link.class).get(0);
        assertEquals("<a href=\"0\">Release page</a>" + HeadedMessageDialog.LINK_SEPARATOR
                + "<a href=\"1\">VirusTotal</a>", links.getText());
        Event click = new Event();
        click.text = "1";
        links.notifyListeners(SWT.Selection, click);
        assertEquals(List.of(REPORT), opened, "the second link's URL, not the system browser");
    }

    @Test
    void anAmpersandShowsAsWrittenNotAsAMnemonic() {
        dialog = new HeadedMessageDialog(null, "Get Neovim", MessageDialog.INFORMATION, new HeadedMessage("A & B",
                List.of(new Point("Q&A", "Tom & Jerry").withLinks(new HeadedMessage.Link("R&D", PAGE))), "C & D"),
                0, "Close");
        dialog.setBlockOnOpen(false);
        dialog.open();
        // SWT shows "&&" as one "&"; a single "&" would vanish and underline the next letter.
        for (String text : List.of("A && B", "Q&&A", "Tom && Jerry", "C && D")) {
            label(text);
        }
        assertEquals("<a href=\"0\">R&&D</a>", find(Link.class).get(0).getText());
        assertEquals("Tom && Jerry", HeadedMessageDialog.literal("Tom & Jerry"));
    }

    @Test
    void aButtonAnswersItsIndex() {
        open();
        Button cancel = find(Button.class).stream().filter(b -> b.getText().equals("Cancel")).findFirst()
                .orElseThrow();
        cancel.notifyListeners(SWT.Selection, new Event());
        assertEquals(1, dialog.getReturnCode());
        assertEquals(SWT.DEFAULT, HeadedMessageDialog.CLOSED, "what MessageDialog answers for Esc");
    }

    private Label label(String text) {
        return find(Label.class).stream().filter(l -> l.getText().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no label " + text));
    }

    private static int toDisplayX(Control control) {
        return control.toDisplay(0, 0).x;
    }

    private <T extends Control> List<T> find(Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(dialog.getShell(), type, found);
        return found;
    }

    private static <T extends Control> void collect(Control control, Class<T> type, List<T> found) {
        if (type.isInstance(control)) {
            found.add(type.cast(control));
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, type, found);
            }
        }
    }
}

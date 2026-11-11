// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.LayoutCheck;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * {@link ActingDialog} and {@link ActingHeadedDialog}, as the welcome and the update note use them
 * ({@code editor/WelcomeNotice}), with {@link WelcomeNote}'s texts and buttons: a press runs the
 * action with the button's index once the dialog has closed, Close is the default, and all the text
 * fits. What the buttons do in Eclipse, and the toggle key the welcome names, are the UI tests'
 * ({@code WelcomeNoticeTest}).
 */
class ActingDialogsTest {

    /** A key as the welcome names it; which one the workbench binds is not this test's. */
    private static final String KEY = "Ctrl+Shift+M";

    /** A dialog takes its icon from the display when it is built, so this thread must be the UI thread first. */
    @BeforeAll
    static void display() {
        Display.getDefault();
    }

    @Test
    void aPressRunsTheActionForItsButtonOnceTheDialogHasClosed() {
        for (Function<ButtonAction, MessageDialog> dialog : List.<Function<ButtonAction, MessageDialog>>of(
                action -> welcome(action), action -> updateNote(action))) {
            List<Integer> pressed = new ArrayList<>();
            List<Boolean> closed = new ArrayList<>();
            MessageDialog[] opened = new MessageDialog[1];
            opened[0] = open(dialog.apply(button -> {
                pressed.add(button);
                closed.add(opened[0].getShell() == null);
            }));
            String first = opened[0] instanceof ActingHeadedDialog ? "Open Preferences" : "What's New";
            button(opened[0].getShell(), first).notifyListeners(SWT.Selection, null);
            assertEquals(List.of(0), pressed, first);
            assertEquals(List.of(true), closed, "the action runs after the dialog closed");
        }
    }

    /** Close, the last button, is the default: Enter just dismisses the note. */
    @Test
    void closeIsTheDefaultButton() {
        for (MessageDialog dialog : List.of(welcome(button -> { }), updateNote(button -> { }))) {
            open(dialog);
            try {
                assertEquals("Close", dialog.getShell().getDefaultButton().getText());
            } finally {
                dialog.close();
            }
        }
    }

    /**
     * Each note shows all its text ({@link LayoutCheck}), with its buttons in order (sorted: GTK puts
     * the default button last on the screen).
     */
    @Test
    void theWelcomeAndTheUpdateNoteShowAllTheirText() {
        MessageDialog welcome = open(welcome(button -> { }));
        try {
            assertEquals(List.of("Close", "Documentation", "Open Preferences"), sortedButtons(welcome.getShell()));
            assertTrue(labels(welcome.getShell()).contains(KEY + ", the Neovim toolbar button"), labels(welcome.getShell()));
            assertEquals(List.of(), LayoutCheck.problems(welcome.getShell()), "the welcome");
        } finally {
            welcome.close();
        }
        MessageDialog update = open(updateNote(button -> { }));
        try {
            assertEquals(List.of("Close", "What's New"), sortedButtons(update.getShell()));
            assertTrue(labels(update.getShell()).contains("updated to 0.2.0."), labels(update.getShell()));
            assertEquals(List.of(), LayoutCheck.problems(update.getShell()), "the update note");
        } finally {
            update.close();
        }
    }

    private static MessageDialog welcome(ButtonAction action) {
        return new ActingHeadedDialog(null, WelcomeNote.TITLE, WelcomeNote.welcome(KEY), WelcomeNote.WELCOME_BUTTONS, action);
    }

    private static MessageDialog updateNote(ButtonAction action) {
        return new ActingDialog(null, WelcomeNote.TITLE, WelcomeNote.updatedMessage("0.2.0.v20261101-1200"),
                WelcomeNote.UPDATED_BUTTONS, action);
    }

    /** Opens {@code dialog} without blocking, as the tests must, and lets its layout settle. */
    private static MessageDialog open(MessageDialog dialog) {
        Display display = Display.getDefault();
        dialog.setBlockOnOpen(false);
        dialog.open();
        while (display.readAndDispatch()) {
            // let the layout settle
        }
        return dialog;
    }

    private static List<String> sortedButtons(Shell shell) {
        return all(shell, Button.class).stream().map(Button::getText).sorted().toList();
    }

    private static String labels(Shell shell) {
        StringBuilder text = new StringBuilder();
        all(shell, Label.class).forEach(label -> text.append(label.getText()).append('\n'));
        return text.toString();
    }

    private static Button button(Shell shell, String text) {
        return all(shell, Button.class).stream().filter(b -> text.equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static <T extends Control> List<T> all(Control root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                found.addAll(all(child, type));
            }
        }
        return found;
    }
}

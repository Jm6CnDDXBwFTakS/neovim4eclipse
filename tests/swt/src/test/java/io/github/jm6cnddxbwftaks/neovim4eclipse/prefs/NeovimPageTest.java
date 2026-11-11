// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.all;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.find;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.press;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.pushButton;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.CopyField;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.LayoutCheck;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GetNeovimText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * The Neovim page ({@link NeovimPage}) and its first group, {@link NeovimStatusSection}: which
 * Neovim runs (the note, Get Neovim and the help) in the first group, the check boxes in the
 * second, and the first growing when the note wraps to more lines (before, the help was cut off:
 * the note laid out only its own group, which kept its old height).
 *
 * <p>Every note is made in memory ({@link NvimProbe#note}, the function the page's probe ends in),
 * so no test looks at this machine's Neovim or the plug-in's folder; which note a value gets is
 * {@code NvimProbeTest}'s (tests/unit), and the real probe on disk {@code PreferencePagesUiTest}'s
 * (UI tests). The section's button and links go to {@link RecordingActions}, which keeps what they
 * asked for instead of opening a dialog or a browser.
 */
class NeovimPageTest {

    /** The plug-in's Neovim folder as the notes name it: any path will do, nothing is read there. */
    private static final Path DEDICATED = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim");
    private static final NeovimVersion OFFERED = NeovimRelease.bundled().version();

    /** No dedicated Neovim yet: a problem, with the Get Neovim button. */
    private static final NvimProbe.Note NONE = note("", NvimExecutable.Kind.NO_DEDICATED, null);
    /** The verified dedicated Neovim: no button, the Reinstall link instead. */
    private static final NvimProbe.Note CURRENT = note(DEDICATED.resolve("la/bin/nvim").toString(),
            NvimExecutable.Kind.DEDICATED, null);

    private Shell shell;
    private PreferenceStore store;
    private RecordingActions actions;
    private NeovimPage page;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        store = PageTesting.store();
        actions = new RecordingActions();
        page = new NeovimPage(store, group -> new NeovimStatusSection(group, () -> "", value -> CURRENT, actions));
        page.createControl(shell);
        shell.setSize(600, 500); // narrow enough that a long note wraps
        shell.layout(true, true);
    }

    @AfterEach
    void close() {
        page.dispose();
        shell.dispose();
    }

    @Test
    void whichNeovimRunsAndTheCheckBoxesAreInTwoGroups() {
        Group[] groups = page.groups();
        assertEquals("Neovim", groups[0].getText());
        assertEquals("Workbench", groups[1].getText());
        assertTrue(find(groups[0], Label.class, l -> true) != null
                && find(groups[0], Link.class, link -> link.getText().equals(NeovimStatusSection.HELP)) != null,
                "the note and the help in the first group");
        List<Text> fields = all(groups[0], Text.class, t -> true);
        assertTrue(fields.size() == 1 && fields.get(0).getText().equals(NeovimStatusSection.HELP_COMMAND)
                && (fields.get(0).getStyle() & SWT.READ_ONLY) != 0,
                "no field for the program, a startup option: the one field is the help's command, read-only");
        assertTrue(groups[1].getBounds().y >= groups[0].getBounds().y + groups[0].getBounds().height,
                "the second group below the first");
    }

    /**
     * The Workbench group has a box for each preference: the toolbar button, the menu, the flash, the
     * welcome. Apply writes a changed box to the store, which the plug-in listens to (the menu follows
     * the stored value: {@code MenuVisibilityTest}).
     */
    @Test
    void theWorkbenchGroupHasABoxPerPreferenceAndApplyStoresThem() {
        List<String> labels = all(page.groups()[1], Button.class, b -> (b.getStyle() & SWT.CHECK) != 0).stream()
                .map(Button::getText).toList();
        assertEquals(List.of("Show the Neovim button on the main toolbar", "Show Neovim menu in the menu bar",
                "Flash the editor on errors",
                "Show a welcome after installing, and a note after updates to a new minor version"), labels);
        Button menu = find(page.groups()[1], Button.class, b -> b.getText().equals("Show Neovim menu in the menu bar"));
        assertEquals(PreferenceConstants.SHOW_MENU_DEFAULT, menu.getSelection(), "the default, as nothing is stored");

        menu.setSelection(!menu.getSelection());
        press(menu);
        press(pushButton(shell, "Apply"));

        assertEquals(!PreferenceConstants.SHOW_MENU_DEFAULT, store.getBoolean(PreferenceConstants.SHOW_MENU));
    }

    /**
     * The help's command line is a row of its own, a field with a Copy button as in the dialogs, a
     * little apart from the sentence above it; the link to the README's section on it right under
     * it with no gap, one line, in line with the field; then the last paragraph, a little apart again
     * (NeovimStatusSection, HELP_LEAD). The link opens that section.
     */
    @Test
    void theHelpsCommandIsACopyFieldWithItsLinkUnderIt() {
        Group group = page.groups()[0];
        Label lead = find(group, Label.class, l -> l.getText().equals(NeovimStatusSection.HELP_LEAD));
        Text command = find(group, Text.class, t -> t.getText().equals(NeovimStatusSection.HELP_COMMAND));
        Link help = find(group, Link.class, link -> link.getText().equals(NeovimStatusSection.HELP));
        Label tail = find(group, Label.class, l -> l.getText().equals(NeovimStatusSection.HELP_TAIL));
        assertTrue(lead != null && command != null && help != null && tail != null, "the help's four rows");
        assertTrue(command.getText().startsWith("-vmargs -Dneovim4eclipse.nvim="), command.getText());
        Composite row = command.getParent();
        Button copy = find(row, Button.class, b -> b.getText().equals(CopyField.COPY));
        assertNotNull(copy, "a Copy button beside the command");
        waitUntil("the help to be laid out", () -> tail.getBounds().height > 0 && row.getBounds().height > 0);
        // On the screen: the field's row and the link sit in a block of their own, and a group's own
        // children are placed in its client area, whose origin is not where Display.map puts the
        // group's coordinates on every platform (12 px lower on macOS, below its title). The screen
        // is one frame for all four.
        Rectangle above = onScreen(lead);
        Rectangle line = onScreen(row);
        Rectangle link = onScreen(help);
        Rectangle below = onScreen(tail);
        assertTrue(line.y >= above.y + above.height + 2, "room above the command: " + above + ", " + line);
        assertTrue(link.y >= line.y + line.height - 1 && link.y <= line.y + line.height + 1 && link.x == line.x,
                "the link right under the field, no gap, in line: " + line + ", " + link);
        assertTrue(below.y >= link.y + link.height + 2, "room above the last paragraph: " + link + ", " + below);
        assertTrue(line.x > above.x, "the command is indented");
        copy.notifyListeners(SWT.Selection, new Event());
        assertEquals(CopyField.COPIED, copy.getText());
        Clipboard clipboard = new Clipboard(copy.getDisplay());
        try {
            assertEquals(NeovimStatusSection.HELP_COMMAND, clipboard.getContents(TextTransfer.getInstance()));
        } finally {
            clipboard.dispose();
        }

        help.notifyListeners(SWT.Selection, new Event());
        assertEquals(List.of(NeovimStatusSection.OWN_NEOVIM_URL), actions.links);
    }

    /** A note that wraps to several lines grows its group, so the help under it stays whole. */
    @Test
    void aLongNoteGrowsTheGroupAndKeepsTheHelpVisible() {
        Group group = sectionFor(note("/no/such/folder/with/a/rather/long/name/so/that/the/note/wraps/onto/several/lines/nvim",
                NvimExecutable.Kind.MISSING, null));
        Label help = find(group, Label.class, l -> l.getText().equals(NeovimStatusSection.HELP_TAIL));
        waitUntil("the note to report the missing program",
                () -> find(group, Label.class, label -> label.getText().startsWith("No Neovim program at")) != null);
        waitUntil("the help to lie inside its group", () -> {
            Rectangle area = group.getClientArea();
            Rectangle bounds = help.getBounds();
            return bounds.height > 0 && bounds.y + bounds.height <= area.y + area.height;
        });
    }

    /**
     * The note decides what is offered ({@link NvimProbe.Note#getNeovim}, {@link NvimProbe.Note#current}):
     * the Get Neovim button, the Reinstall link, or neither (a refused program while the dedicated
     * Neovim checks out: removing the option is the fix). Both open Get Neovim over the page's shell.
     */
    @Test
    void theButtonAndTheReinstallLinkFollowTheNote() {
        Group offered = sectionFor(NONE);
        waitForNote(offered, NONE);
        Button getNeovim = find(offered, Button.class, b -> b.getText().equals(GetNeovimText.label(NeovimRelease.bundled())));
        assertNotNull(getNeovim, "no Get Neovim button");
        assertTrue(getNeovim.getVisible() && !reinstall(offered).getVisible(), "the button, not the link");
        press(getNeovim);
        assertEquals(List.of(offered.getShell()), actions.opened, "Get Neovim over the section's window");

        Group current = sectionFor(CURRENT);
        waitForNote(current, CURRENT);
        assertTrue(!button(current).getVisible() && reinstall(current).getVisible(), "the link, not the button");
        reinstall(current).notifyListeners(SWT.Selection, new Event());
        assertEquals(2, actions.opened.size(), "Reinstall opens Get Neovim too");

        NvimProbe.Note refused = note("/no/such/nvim", NvimExecutable.Kind.MISSING, null).withGetNeovim(false);
        Group neither = sectionFor(refused);
        waitForNote(neither, refused);
        assertTrue(!button(neither).getVisible() && !reinstall(neither).getVisible(), "neither");
    }

    /**
     * After an install the section asks again which Neovim runs, as the dialog has just changed it;
     * once the section is gone it no longer listens.
     */
    @Test
    void anInstallMakesTheSectionLookAgain() {
        actions = new RecordingActions(); // not the page's, which listens too
        AtomicReference<NvimProbe.Note> answer = new AtomicReference<>(NONE);
        Group group = section(answer::get);
        waitForNote(group, NONE);
        assertEquals(1, actions.listeners.size(), "the section listens for installs");

        answer.set(CURRENT);
        List.copyOf(actions.listeners).forEach(Runnable::run); // as Get Neovim does after an install, on the UI thread
        waitForNote(group, CURRENT);

        group.getShell().dispose();
        assertEquals(List.of(), actions.listeners, "no listener left behind");
    }

    /**
     * A folder named with {@code <a>...</a>} shows as text: the note is a Label, so the name cannot
     * become a link (in a Link it would, and its click would open Get Neovim).
     */
    @Test
    void aFolderNameWithLinkMarkupStaysText() {
        Group group = sectionFor(note("/no/<a>such</a>/nvim", NvimExecutable.Kind.MISSING, null));
        waitUntil("the note to report the missing program",
                () -> find(group, Label.class, label -> label.getText().startsWith("No Neovim program at")) != null);
        Label note = find(group, Label.class, label -> label.getText().startsWith("No Neovim program at"));
        assertTrue(note.getText().contains("<a>such</a>"), note.getText());
        assertTrue(find(group, Link.class, link -> link.getText().contains("such")) == null, "no Link shows the path");
    }

    /**
     * Each note the checks gallery pictures fits its group: no text cut off, over the window's edge or
     * on top of another ({@link LayoutCheck}), at the page's width, with the window as tall as its
     * text. This takes over the gallery card's "nothing cut off" for the page, on every platform the
     * tests run on. The notes: no dedicated Neovim (error color, with the Get Neovim button), the
     * dedicated Neovim with entries the plug-in no longer uses, a named program, and the three values
     * the option refuses.
     */
    @Test
    void everyNoteTheGalleryShowsFitsItsGroup() {
        List<NvimProbe.Note> notes = List.of(NONE,
                CURRENT.withUnused(NvimProbe.unusedLines(List.of("nvim-linux-x86_64", "nvim.old"), DEDICATED)),
                note("/opt/neovim/bin/nvim", NvimExecutable.Kind.PROGRAM, OFFERED),
                note("/opt/neovim", NvimExecutable.Kind.FOLDER, null),
                note("nvim", NvimExecutable.Kind.NOT_ABSOLUTE, null),
                note("/opt/neovim/missing/bin/nvim", NvimExecutable.Kind.MISSING, null));
        for (NvimProbe.Note note : notes) {
            Shell window = new Shell(shell);
            try {
                window.setLayout(new GridLayout());
                Group group = group(window);
                new NeovimStatusSection(group, () -> "", value -> note, actions);
                window.setSize(640, 600); // the gallery's width
                window.open(); // wrapped text is measured right only once it is shown (GTK)
                waitForNote(group, note);
                // As tall as the wrapped text, as the page grows its group (computeSize at the inside's width).
                window.setSize(640, window.computeSize(window.getClientArea().width, SWT.DEFAULT).y);
                window.layout(true, true);
                Display display = window.getDisplay();
                while (display.readAndDispatch()) {
                    // the note's requestLayout, and the resize, run before the check
                }
                assertEquals(List.of(), LayoutCheck.problems(window), "the note " + note.text());
            } finally {
                window.dispose();
            }
        }
    }

    /** The note for {@code configured} of {@code kind}, as the probe would make it, with no disk. */
    private static NvimProbe.Note note(String configured, NvimExecutable.Kind kind, NeovimVersion version) {
        return NvimProbe.note(new NvimExecutable.Resolved(configured, kind), version, Optional.empty(), DEDICATED, OFFERED);
    }

    /** A Neovim group as the page lays it out, in its own window, showing {@code note}. */
    private Group sectionFor(NvimProbe.Note note) {
        return section(() -> note);
    }

    /** As {@link #sectionFor}, with the note read at each probe. */
    private Group section(Supplier<NvimProbe.Note> note) {
        Shell window = new Shell(shell);
        window.setLayout(new GridLayout());
        Group group = group(window);
        new NeovimStatusSection(group, () -> "", value -> note.get(), actions);
        window.setSize(600, 500);
        window.layout(true, true);
        return group;
    }

    private static Group group(Shell window) {
        Group group = new Group(window, SWT.NONE);
        group.setText("Neovim");
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        group.setLayout(new GridLayout());
        return group;
    }

    /** Waits until the section in {@code group} shows {@code note}, after "Looking for Neovim...". */
    private static void waitForNote(Group group, NvimProbe.Note note) {
        waitUntil("the note " + note.text(), () -> find(group, Label.class,
                label -> label.getText().equals(note.text().replace("&", "&&"))) != null);
    }

    private static Button button(Group group) {
        return find(group, Button.class, b -> b.getText().equals(GetNeovimText.label(NeovimRelease.bundled())));
    }

    private static Link reinstall(Group group) {
        return find(group, Link.class, link -> link.getText().equals("<a>" + NeovimStatusSection.REINSTALL + "</a>"));
    }

    /** {@code control}'s bounds on the screen, however deep in its window it is. */
    private static Rectangle onScreen(Control control) {
        return control.getDisplay().map(control.getParent(), null, control.getBounds());
    }

    /** The section's actions, kept instead of done: no dialog opens and no browser starts. */
    private static final class RecordingActions implements NeovimStatusSection.Actions {

        /** The shell each Get Neovim was asked over, in order. */
        final List<Shell> opened = new ArrayList<>();
        /** The links asked for, in order. */
        final List<String> links = new ArrayList<>();
        /** The install listeners now added. */
        final List<Runnable> listeners = new ArrayList<>();

        @Override
        public void openGetNeovim(Shell over) {
            opened.add(over);
        }

        @Override
        public void addInstallListener(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void removeInstallListener(Runnable listener) {
            listeners.remove(listener);
        }

        @Override
        public void openLink(String url) {
            links.add(url);
        }
    }
}

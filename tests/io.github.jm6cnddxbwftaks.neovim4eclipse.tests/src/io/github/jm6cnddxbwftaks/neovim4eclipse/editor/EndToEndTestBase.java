// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.requireKeyboardFocus;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.StatusLineManager;
import org.eclipse.jface.action.SubContributionManager;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;

/**
 * The setup shared by the end-to-end tests: a real editor and a real Neovim. Before each test it
 * opens {@code test.txt} ("alpha\nbeta\ngamma\n") in Eclipse's text editor, in a fresh project,
 * waits for the plug-in to attach and for Neovim to be in normal mode; afterwards it closes the
 * editor and deletes the project. Tests are skipped when no {@code nvim} can be found
 * ({@code -Dnvim.path=...} selects one).
 *
 * <p>Keys are delivered with {@code notifyListeners(SWT.KeyDown)} ({@link UiTestSupport#type}),
 * which runs the editor's own key handling, the plug-in's {@code KeyInterceptor} included, but not
 * the workbench's key-binding dispatcher; Ctrl keys are therefore sent to Neovim directly
 * ({@code session.inputAsync("<C-v>")}) or replaced by running the Eclipse command they are bound to.
 *
 * <p>The helpers wait by running the event loop, never by sleeping (see {@link UiTestSupport}).
 *
 * <p>Keyboard focus: the setup makes sure the editor has it ({@link UiTestSupport#requireKeyboardFocus}),
 * so a test that starts without it fails at once with "lost keyboard focus" and where the focus is.
 */
abstract class EndToEndTestBase {

    static final long TIMEOUT_MS = 15_000;

    /** The text editor's ID, as in Open With › Text Editor. */
    static final String TEXT_EDITOR = "org.eclipse.ui.DefaultTextEditor";

    protected IProject project;
    protected ITextEditor editor;
    protected StyledText widget;
    protected IDocument document;
    protected NeovimSession session;

    @BeforeEach
    void openEditor() throws Exception {
        String nvimPath = nvimPathProperty();
        if (!nvimPath.isBlank()) {
            Activator.getDefault().getPreferenceStore().setValue(PreferenceConstants.NVIM_EXECUTABLE, nvimPath);
        }
        assumeTrue(NvimExecutable.looksAvailable(Activator.getDefault().getNvimExecutable()),
                "nvim not found; set -Dnvim.path");

        project = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e");
        if (!project.exists()) {
            project.create(null);
        }
        project.open(null);
        IFile file = project.getFile("test.txt");
        byte[] content = "alpha\nbeta\ngamma\n".getBytes(StandardCharsets.UTF_8);
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(content), true, false, null);
        } else {
            file.create(new ByteArrayInputStream(content), true, null);
        }

        EditorAttachManager.getInstance().installWorkbenchListeners();
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        editor = (ITextEditor) IDE.openEditor(page, file, TEXT_EDITOR, true);
        document = documentOf(editor);
        widget = widgetOf(editor);

        waitUntil("Neovim to attach", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        // Focus-dependent behavior (the Vim key context, the : box closing on focus loss) needs the
        // editor to have keyboard focus in the active window. An earlier test's dialog or shell can
        // leave no window active (xvfb has no window manager to hand the focus back), and a developer
        // may be typing in another window; fail here with a "lost keyboard focus" message instead of
        // a timeout later. See UiTestSupport.requireKeyboardFocus.
        requireKeyboardFocus(widget, TIMEOUT_MS);
        session = Activator.getDefault().getOrCreateSession();
        // One Neovim serves the whole IDE, so a previous test may have left it in another mode.
        session.inputAsync("<Esc>");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        settle(); // let the initial document push and cursor placement finish
    }

    /**
     * {@code -Dnvim.path}, or "" when it was not given. Without it the pom.xml passes an empty value,
     * which tycho-surefire turns into the text {@code "null"}; taken as a path, that made every test
     * here skip ("nvim not found") although nvim was on the PATH.
     */
    private static String nvimPathProperty() {
        String value = System.getProperty("nvim.path", "");
        return "null".equals(value) ? "" : value;
    }

    @AfterEach
    void closeEditor() throws Exception {
        if (editor != null) {
            editor.getSite().getPage().closeEditor(editor, false);
        }
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    /** The active workbench page. */
    static IWorkbenchPage page() {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    /**
     * Runs an Eclipse command by its ID, as its menu item or key binding would (for example
     * {@code org.eclipse.ui.file.revert}, File › Revert): synthetic keys miss the key bindings.
     */
    static void runCommand(String commandId) throws Exception {
        PlatformUI.getWorkbench().getService(IHandlerService.class).executeCommand(commandId, null);
    }

    /** Waits until test.txt's document reads {@code expected}. */
    void expectText(String expected) {
        expectText(editor, expected);
    }

    /** Waits until {@code target}'s document reads {@code expected}. */
    void expectText(ITextEditor target, String expected) {
        IDocument targetDocument = documentOf(target);
        waitUntil("document to read " + expected.replace("\n", "\\n"), TIMEOUT_MS,
                () -> expected.equals(targetDocument.get()),
                () -> "; it reads " + targetDocument.get().replace("\n", "\\n") + " (mode " + session.getMode()
                        + ", caret " + widgetOf(target).getCaretOffset() + ")");
    }

    /** The file's contents on disk. */
    String fileText(String name) {
        try (var in = project.getFile(name).getContents(true)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("cannot read " + name, e);
        }
    }

    static IDocument documentOf(ITextEditor textEditor) {
        return textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
    }

    static StyledText widgetOf(ITextEditor textEditor) {
        return (StyledText) textEditor.getAdapter(Control.class);
    }

    /** Neovim's cursor line, asked from Neovim itself (not the last one Eclipse heard of). */
    int cursorLine() {
        try {
            return session.getCursor().line1Based();
        } catch (Exception e) {
            throw new AssertionError("Neovim did not answer", e);
        }
    }

    /** Opens other.txt (one line, "other") and waits until Neovim is attached to it. */
    ITextEditor openOtherFile() throws Exception {
        return openFile("other.txt", "other\n");
    }

    /** Creates {@code name} in the test project, opens it and waits until Neovim is attached to it. */
    ITextEditor openFile(String name, String content) throws Exception {
        IFile file = createFile(name, content);
        ITextEditor opened = (ITextEditor) IDE.openEditor(editor.getSite().getPage(), file, TEXT_EDITOR, true);
        waitForAttach(opened, name);
        return opened;
    }

    /** Creates {@code name} in the test project, without opening it. */
    IFile createFile(String name, String content) throws Exception {
        IFile file = project.getFile(name);
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }

    void waitForAttach(ITextEditor target, String what) {
        waitUntil("Neovim to attach to " + what, TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == target && !c.isClosed();
        });
        settle();
    }

    /** Types one key at a time, letting each selection update reach the widget before the next key. */
    void typeSlowly(String keys) {
        typeSlowly(widget, keys);
    }

    void typeSlowly(StyledText target, String keys) {
        for (char key : keys.toCharArray()) {
            type(target, key);
            settle();
        }
    }

    /**
     * Waits until Neovim has handled every key and push so far and the editor shows the result (see
     * {@link UiTestSupport#settle}): no fixed sleep, so the test waits only as long as needed.
     */
    void settle() {
        UiTestSupport.settle(session, TIMEOUT_MS);
    }

    /**
     * The texts shown in the workbench window's status line. {@link HostCommands#showError} writes
     * through the editor's action bars, a {@code SubStatusLineManager}; its parent is the window's
     * {@link StatusLineManager}, whose control holds the message as a {@link CLabel}.
     */
    static List<String> statusLineTexts(ITextEditor target) {
        IContributionManager manager = target.getEditorSite().getActionBars().getStatusLineManager();
        while (manager instanceof SubContributionManager sub) {
            manager = sub.getParent();
        }
        List<String> texts = new ArrayList<>();
        if (manager instanceof StatusLineManager window && window.getControl() != null) {
            collectLabels(window.getControl(), texts);
        } else {
            texts.add("(no status line control: " + manager + ")");
        }
        return texts;
    }

    /** The message line of {@code target} while Neovim is attached to it; else {@code null}. */
    static MessageLine messageLineOf(ITextEditor target) {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        return controller != null && controller.getEditor() == target ? controller.messageLine() : null;
    }

    /** The message shown along the bottom of {@code target}; "" when none is. */
    static String messageText(ITextEditor target) {
        MessageLine line = messageLineOf(target);
        return line == null ? "" : line.text();
    }

    private static void collectLabels(Control control, List<String> texts) {
        if (control instanceof CLabel label && label.getText() != null && !label.getText().isEmpty()) {
            texts.add(label.getText());
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collectLabels(child, texts);
            }
        }
    }

    /**
     * Types {@code :} into {@code target}, waits for the box, and types {@code command} after it.
     *
     * <p>Waits until the box shows the command, because the insert may land a moment later. On
     * macOS a text field selects its whole content (the {@code :}) when it gains focus, so the
     * insert would replace the prefix. The box's prefix guard refuses that edit and applies it after
     * the prefix in a later {@code asyncExec} (see {@code CommandLineBox.protectPrefix}). An Enter
     * sent before then would submit an empty command line.
     */
    Text openBox(StyledText target, String command) {
        type(target, ':');
        waitUntil("the : box to open", TIMEOUT_MS, () -> textChildOf(target) != null);
        Text box = textChildOf(target);
        box.insert(command);
        waitUntil("the command in the : box", TIMEOUT_MS, () -> box.getText().equals(":" + command),
                () -> "; the box shows " + box.getText());
        return box;
    }

    /** Runs {@code command} through the box, as typed: {@code :}, the command, Enter. */
    void runInBox(StyledText target, String command) {
        Text box = openBox(target, command);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
        settle();
    }

    /** The first {@link Text} below {@code parent}: the {@code :} box, which opens inside the editor. */
    static Text textChildOf(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Text text && !text.isDisposed()) {
                return text;
            }
            if (child instanceof Composite composite) {
                Text found = textChildOf(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * While {@code control} exists, closes every modal dialog that opens and returns their titles. A
     * timer keeps running inside a dialog's own event loop, so this also ends a dialog that would
     * otherwise block the test forever.
     */
    static List<String> closeModalDialogsWhile(Control control) {
        Shell owner = control.getShell();
        Display display = Display.getCurrent();
        List<String> titles = new ArrayList<>();
        int modal = SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;
        Runnable check = new Runnable() {
            @Override
            public void run() {
                if (control.isDisposed()) {
                    return;
                }
                for (Shell shell : display.getShells()) {
                    if (shell != owner && shell.isVisible() && (shell.getStyle() & modal) != 0) {
                        titles.add(shell.getText());
                        shell.close();
                    }
                }
                display.timerExec(200, this);
            }
        };
        display.timerExec(200, check);
        return titles;
    }
}

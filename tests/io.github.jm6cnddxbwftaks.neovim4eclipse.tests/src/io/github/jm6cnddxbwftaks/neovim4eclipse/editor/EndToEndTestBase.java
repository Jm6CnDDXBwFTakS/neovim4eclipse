// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.requireKeyboardFocus;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.StatusLineManager;
import org.eclipse.jface.action.SubContributionManager;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.wizards.IWizardDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.TextRepairs;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
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
 * While Neovim's command line is open ({@link #openCommandLine}) the widget keeps the focus, and
 * every key still goes through it.
 *
 * <p><b>The text check.</b> Every test also checks that Eclipse's text and Neovim's agree, two ways:
 * <ul>
 *   <li>A push that repairs a drift in Neovim's buffer fails the test ({@link TextRepairs}: a line
 *       no change explains differed). Captured from the start of the setup to the end of the
 *       clean-up.</li>
 *   <li>Right after the test's own code, before any clean-up, every open editor's document is
 *       compared with its buffer ({@link TextCheckAtEnd}), for a drift no push has repaired yet. A
 *       test that ends in another mode is first brought back to normal mode with {@code Esc}, which
 *       is also when Neovim gets the text typed in insert mode. {@link TextMayDiffer} leaves a test
 *       out of this comparison.</li>
 * </ul>
 * Why: before the check, the push at {@code Esc} repaired any difference silently, so a bug that
 * made the two copies drift passed every test that did not look at the line it hit.
 */
@ExtendWith(EndToEndTestBase.TextCheckAtEnd.class)
abstract class EndToEndTestBase {

    /**
     * How long a helper waits for Neovim or the workbench before the test fails: 15 s unless
     * {@code -Dtest.wait.seconds} says otherwise (see {@link TestWait}, shared with the other test
     * modules through build.properties).
     */
    static final long TIMEOUT_MS = TestWait.millis();

    /** The text editor's ID, as in Open With › Text Editor. */
    static final String TEXT_EDITOR = "org.eclipse.ui.DefaultTextEditor";

    protected IProject project;
    protected ITextEditor editor;
    protected StyledText widget;
    protected IDocument document;
    protected NeovimSession session;
    /** The repairs pushes make during the test ({@link TextRepairs}); each fails the test. */
    private ExpectedLog repairs;
    /**
     * What {@link EditorSplits} logs during the test; a warning fails it. A window command that
     * throws is only logged (the user sees nothing), and Eclipse often hides the damage, as when
     * it brings the one editor left to the front by itself.
     */
    private ExpectedLog splitsLog;

    @BeforeEach
    void openEditor() throws Exception {
        repairs = ExpectedLog.of(TextRepairs.class, Level.FINE);
        splitsLog = ExpectedLog.of(EditorSplits.class);
        // An error dialog fails the test instead of stalling the run (UiTestSupport.watchForErrorDialogs).
        UiTestSupport.watchForErrorDialogs();
        // -Dnvim.path reaches the plug-in as its startup option -Dneovim4eclipse.nvim (the test pom's
        // nvim-path profile), set before the test Eclipse started; the pom requires it, as the tests'
        // own data folder holds no dedicated Neovim. A failure, not a skip: a wrong path would
        // otherwise give a green run with nothing tested.
        assertTrue(NvimExecutable.looksAvailable(Activator.getDefault().getNvimExecutable()),
                "no Neovim program at -Dnvim.path=" + System.getProperty("nvim.path"));

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
        // Focus-dependent behavior (the Vim key context, the keys typed into Neovim's command line) needs the
        // editor to have keyboard focus in the active window. An earlier test's dialog or shell can
        // leave no window active (xvfb has no window manager to hand the focus back), and a developer
        // may be typing in another window; fail here with a "lost keyboard focus" message instead of
        // a timeout later. See UiTestSupport.requireKeyboardFocus.
        if (takesTheFront()) {
            requireKeyboardFocus(widget, TIMEOUT_MS);
        } else {
            UiTestSupport.focusWithoutTakingTheFront(widget, TIMEOUT_MS);
        }
        session = Activator.getDefault().getOrCreateSession();
        allowLongSwapPaths(session);
        // One Neovim serves the whole IDE, so a previous test may have left it in another mode.
        session.inputAsync("<Esc>");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        // After the Esc: it ends the rest of a mapping a failed test left Neovim waiting for, which
        // would hold back this request (Neovim answers it only once it has the whole mapping).
        waitForTheRestOfAMapping(session);
        settle(); // let the initial document push and cursor placement finish
        waitForNeovimIn(file);
    }

    /**
     * Lets Neovim wait for the rest of a mapping longer than any test waits for anything (twice
     * {@link TestWait}'s limit), instead of its default {@code 'timeoutlen'} of 1 s.
     *
     * <p>Why: after a key that may begin a longer mapping, Neovim waits {@code 'timeoutlen'} for the
     * next one, and when none comes it gives up on the mapping and runs its own keys instead. A test
     * types one key at a time and lets Neovim finish each ({@link #typeSlowly}), and on a loaded
     * machine that gap can be longer than a second. Example: the plug-in's {@code ZZ} (Eclipse
     * saves and closes the editor) became Neovim's own {@code ZZ}, which quits the one Neovim every
     * later test uses; {@code grn} (Eclipse's Rename) became Neovim's {@code gr}, which replaces the
     * character under the cursor with {@code n}. A person types a mapping's keys within the second;
     * a test machine may not.
     *
     * <p>Longer than any test wait, so that no test can come to depend on the wait running out: one
     * that did would time out first. The one test about {@code 'timeoutlen'} itself
     * ({@code NeovimFoldsIntegrationTest.aSlowZEIsRefusedToo}) has a Neovim of its own. Set before
     * every test, as a restarted Neovim starts with the default again.
     */
    private static void waitForTheRestOfAMapping(NeovimSession session) {
        SessionProbe.lua(session, "vim.o.timeoutlen = " + TestWait.mappingMillis() + "; return 0");
    }

    /**
     * On Windows, lets the tests' swap files have paths longer than Windows' limit of 259
     * characters (MAX_PATH), by putting the long-path prefix {@code \\?\} in front of each folder
     * in Neovim's {@code 'directory'}.
     *
     * <p>Why only the tests: their swap folder is deep, inside the checkout's {@code target/}
     * ({@code XDG_STATE_HOME} in the pom). With a {@code 'directory'} ending in {@code \\}, Neovim
     * names a swap file after the whole path of its file, and that of an unnamed buffer after the
     * working folder. So the swap file of the unnamed buffer, here a 152-character folder and a
     * 107-character name, came to 263 characters, and a {@code :bufdo} that changed it failed with
     * E303 "Unable to open swap file". A user's swap folder is about 55 characters, so the
     * plug-in leaves the setting alone. The prefix costs {@code :recover} and
     * {@code swapfilelist()}: they search the folder with a file pattern, which does not read
     * {@code \\?\} paths. Tests never recover; the warning about an existing swap file (E325)
     * still works.
     *
     * <p>Example: {@code C:\ws\target\neovim-home\state\nvim\swap\\} becomes
     * {@code \\?\C:\ws\target\neovim-home\state\nvim\swap\\}. Windows does not turn {@code /}
     * into {@code \} after the prefix, so the forward slashes go first: {@code \\?\C:\...\swap//}
     * still fails with E303. Neovim's {@code .} (the file's own folder) and relative entries are
     * kept as they are, as is an entry that already has the prefix (a restarted Neovim gets the
     * default again and is set once more).
     */
    private static void allowLongSwapPaths(NeovimSession session) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        String directory = String.valueOf(SessionProbe.lua(session, "return vim.o.directory"));
        String prefixed = withLongPathPrefix(directory);
        if (!prefixed.equals(directory)) {
            // A Lua long string: the backslashes stay as they are, with no escaping.
            SessionProbe.lua(session, "vim.o.directory = [==[" + prefixed + "]==]; return 0");
        }
    }

    /**
     * The value of {@code 'directory'} with {@code \\?\} in front of each absolute folder, for
     * {@link #allowLongSwapPaths}. A trailing {@code //} or {@code \\} (swap files named after the
     * whole path) becomes {@code \\}, a single one {@code \}. Entries are split at the commas
     * that are not escaped ({@code \,} is a comma inside a folder name).
     */
    static String withLongPathPrefix(String directory) {
        List<String> entries = new ArrayList<>();
        for (String entry : directory.split("(?<!\\\\),", -1)) {
            if (entry.matches("[A-Za-z]:[\\\\/].*")) {
                boolean wholePathNames = entry.matches(".*[\\\\/]{2}");
                String folder = entry.replace('/', '\\').replaceAll("\\\\+$", "");
                entry = "\\\\?\\" + folder + (wholePathNames ? "\\\\" : "\\");
            }
            entries.add(entry);
        }
        return String.join(",", entries);
    }

    /**
     * Waits until Neovim's current buffer is {@code file}'s. The attach queues the switch to the
     * editor's window and buffer on the session, and {@link #settle} waits for that queue; but a
     * check that asks Neovim directly ({@link SessionProbe#lua}) does not wait in it. On one core the
     * switch could still be pending, and a script such as {@code vim.cmd('edit!')} then ran in the
     * unnamed start buffer and failed with E32 "No file name". Keys typed into the editor cannot
     * meet this: they wait in the same queue as the switch.
     *
     * <p>The names are compared with the one rule for paths ({@link FilePaths#same}): Neovim may
     * spell {@code C:\ws\e2e\test.txt} as {@code C:/ws/e2e/test.txt} or in another case.
     */
    void waitForNeovimIn(IFile file) {
        Path expected = Path.of(file.getLocation().toOSString());
        String[] current = { "" };
        waitUntil("Neovim's current buffer to be " + file.getName(), TIMEOUT_MS, () -> {
            current[0] = String.valueOf(SessionProbe.lua(session, "return vim.api.nvim_buf_get_name(0)"));
            return !current[0].isEmpty() && FilePaths.same(Path.of(current[0]), expected);
        }, () -> "; it is '" + current[0] + "'");
    }

    @AfterEach
    void closeEditor() throws Exception {
        // An error dialog the test's own waits did not report (it opened after the last one) fails
        // the test, but only after the cleanup, so the next test starts clean.
        String unreported = UiTestSupport.takeClosedErrorDialog();
        // No page: the editor is closed already (a test that closed its project; the text check's
        // wait let that close finish).
        if (editor != null && editor.getSite().getPage() != null) {
            editor.getSite().getPage().closeEditor(editor, false);
        }
        forgetClosedFilesBuffers();
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
        String duringCleanup = UiTestSupport.stopWatchingForErrorDialogs();
        List<String> repaired = stopCapturingRepairs();
        ExpectedLog splits = splitsLog; // null if the setup never started it
        splitsLog = null;
        if (splits != null) {
            splits.close();
        }
        if (unreported != null || duringCleanup != null) {
            fail("An error dialog opened and was closed (not answered): "
                    + (unreported != null ? unreported : duringCleanup)
                    + ". See the test Eclipse's .metadata/.log for the error.");
        }
        if (!repaired.isEmpty()) {
            fail("Eclipse's text and Neovim's drifted apart, and a push repaired it (TextRepairs): " + repaired);
        }
        if (splits != null) {
            splits.assertNothingAtOrAbove(Level.WARNING);
        }
    }

    /**
     * The repairs caught so far, which then no longer fail the test: for a test that makes a drift on
     * purpose and checks its report. The capture goes on for the rest of the test.
     */
    protected List<LogRecord> takeRepairs() {
        if (repairs == null) {
            return List.of();
        }
        List<LogRecord> taken = List.copyOf(repairs.records());
        repairs.close();
        repairs = ExpectedLog.of(TextRepairs.class, Level.FINE);
        return taken;
    }

    /** Ends the capture of repairs and returns what it caught (nothing if the setup never started it). */
    private List<String> stopCapturingRepairs() {
        if (repairs == null) {
            return List.of();
        }
        try {
            List<String> found = new ArrayList<>();
            for (LogRecord logRecord : repairs.records()) {
                found.add(logRecord.getMessage() + (logRecord instanceof TextRepairs.RepairRecord repair
                        ? " (Neovim: \"" + repair.neovimLine() + "\", Eclipse: \"" + repair.documentLine() + "\")"
                        : ""));
            }
            return found;
        } finally {
            repairs.close();
            repairs = null;
        }
    }

    /**
     * Runs the text comparison right after each test method, before any {@code @AfterEach} (which
     * close editors and delete projects): an extension's interception of the test method itself
     * is the one place JUnit offers for that. A test that failed is not compared: its failure says
     * more.
     */
    static final class TextCheckAtEnd implements InvocationInterceptor {
        @Override
        public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                ExtensionContext extensionContext) throws Throwable {
            invocation.proceed();
            Method method = invocationContext.getExecutable();
            if (method.isAnnotationPresent(TextMayDiffer.class)
                    || method.getDeclaringClass().isAnnotationPresent(TextMayDiffer.class)
                    || extensionContext.getRequiredTestClass().isAnnotationPresent(TextMayDiffer.class)) {
                return;
            }
            String differences = TextCheck.differences(TIMEOUT_MS);
            if (differences != null) {
                fail("Eclipse's text and Neovim's differ when the test ends (EndToEndTestBase, the text"
                        + " check; @TextMayDiffer leaves a test out): " + differences);
            }
        }
    }

    /**
     * Wipes the buffers Neovim keeps for closed files (docs/design/BUFFER_NUMBERS.md: a closed file
     * keeps its buffer, as after Vim's {@code :bd}), so that each test starts as with a fresh Neovim.
     * One Neovim serves every test, and test.txt, made again for the next test, would get this test's
     * buffer back, with its cursor on the line this test left it on. Also wiped: the buffers of the
     * test project's files that no editor took over (a {@code gf} onto a file Eclipse would not open).
     */
    private void forgetClosedFilesBuffers() {
        // The running session: a test may have restarted Neovim (RestartNeovimEndToEndTest).
        NeovimSession running = Activator.getDefault().currentSession();
        if (running == null) {
            return;
        }
        // Esc first: a test that failed after the first key of a mapping (Z of ZZ) leaves Neovim
        // waiting for the rest, as long as 'timeoutlen' (waitForTheRestOfAMapping), and until then it
        // holds back the request below. Esc is input, which Neovim reads at once, and ends the wait.
        running.inputAsync("<Esc>");
        UiTestSupport.settle(running, TIMEOUT_MS); // the closes are queued in the session: let them happen first
        String folder = project == null || project.getLocation() == null ? "" : project.getLocation().toOSString();
        SessionProbe.lua(running, """
                local paths = package.loaded['neovim4eclipse.paths'] -- the one rule (LuaScripts.PATHS)
                local folder = FOLDER
                for _, b in ipairs(vim.api.nvim_list_bufs()) do
                  local name = vim.api.nvim_buf_get_name(b)
                  local closed = not vim.api.nvim_buf_is_loaded(b) and vim.b[b].neovim4eclipse_file
                  -- Compared by the paths rule, not as strings: on Windows Neovim's name and the
                  -- project's location differ in case or by an 8.3 short name (C:/Users/RUNNER~1),
                  -- and a string compare missed every stray there, so they piled up from test to
                  -- test and a later :bufdo went through all of them.
                  local stray = folder ~= '' and name ~= '' and vim.bo[b].buftype == ''
                      and paths.inside(name, folder)
                  if closed or stray then
                    pcall(vim.api.nvim_buf_delete, b, { force = true })
                  end
                end
                return 0""".replace("FOLDER", "'" + folder.replace("\\", "\\\\").replace("'", "\\'") + "'"));
    }

    /**
     * Deletes a folder outside the workspace that an editor of the test opened a file in: first
     * {@code contents}, in order, then {@code dir}. Neovim's window may still work there ({@code :lcd}
     * to the file's folder, until another editor attaches), and Windows does not delete a folder a
     * process works in; so Neovim leaves it first, as {@code ProjectFolderExit} has it leave a project
     * before Eclipse deletes one.
     */
    void leaveAndDelete(java.nio.file.Path dir, java.nio.file.Path... contents) throws java.io.IOException {
        session.leaveFolder(dir.toString(), TestWait.duration());
        for (java.nio.file.Path path : contents) {
            java.nio.file.Files.deleteIfExists(path);
        }
        java.nio.file.Files.deleteIfExists(dir);
    }

    /** The active workbench page. */
    static IWorkbenchPage page() {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    /** The open editor whose input is named {@code name}, or {@code null}. */
    static ITextEditor editorNamed(String name) {
        for (IEditorReference reference : page().getEditorReferences()) {
            if (reference.getEditor(false) instanceof ITextEditor text && name.equals(text.getEditorInput().getName())) {
                return text;
            }
        }
        return null;
    }

    /**
     * Closes every editor but test.txt's, unsaved, for a test that opened others. test.txt's editor
     * comes to the front first, then the others close in one call: closing the editor in front
     * would bring another forward, and one with unsaved changes whose file is gone (a test that
     * failed before Eclipse asked) would ask now, a question no watch answers, and hold the run.
     * Closing editors that are not in front brings none forward.
     */
    void closeEditorsButTestTxt() {
        IWorkbenchPage page = page();
        page.activate(editor);
        List<IEditorReference> others = new ArrayList<>();
        for (IEditorReference reference : page.getEditorReferences()) {
            if (reference.getEditor(false) != editor) {
                others.add(reference);
            }
        }
        page.closeEditors(others.toArray(IEditorReference[]::new), false);
        settle();
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

    /** File › New › Untitled Text File, by running its wizard as the menu does, and waits until Neovim is attached. */
    ITextEditor openUntitledFile() throws Exception {
        IWizardDescriptor descriptor = PlatformUI.getWorkbench().getNewWizardRegistry()
                .findWizard("org.eclipse.ui.editors.wizards.UntitledTextFileWizard");
        INewWizard wizard = (INewWizard) descriptor.createWizard();
        wizard.init(PlatformUI.getWorkbench(), StructuredSelection.EMPTY);
        assertTrue(wizard.performFinish());
        ITextEditor untitled = (ITextEditor) page().getActiveEditor();
        assertNull(untitled.getEditorInput().getAdapter(IFile.class), "an untitled file");
        waitForAttach(untitled, "the untitled file");
        return untitled;
    }

    /**
     * Whether the setup may bring the test Eclipse to the front for the editor's keyboard focus:
     * yes for a test, whose focus-dependent steps need it; a subclass whose steps do not need it
     * (the gallery's pictures) says no, so the developer can work in another program meanwhile
     * (see {@link UiTestSupport#focusWithoutTakingTheFront}).
     */
    protected boolean takesTheFront() {
        return true;
    }

    /** Creates {@code name} in the test project, without opening it. */
    IFile createFile(String name, String content) throws Exception {
        IFile file = project.getFile(name);
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }

    /**
     * <em>Restart Neovim</em> ({@code Activator.restartNeovim}, as {@code RestartNeovimEndToEndTest}
     * uses it), waiting until the new Neovim is attached to this test's editor. For a test that
     * loaded a plug-in whose setup acts on the whole Neovim, with no way to undo it (a completion
     * plug-in's autocommands, vim-surround's mappings): the next test gets a Neovim that never
     * loaded it. Call it from a subclass's {@code @AfterEach}, which runs before this class's, so the
     * editor is closed with the new Neovim attached. The text check has run already, on the
     * Neovim the test used.
     *
     * @return the new Neovim's session
     */
    NeovimSession restartNeovimAfterThisTest() {
        NeovimSession used = session;
        Activator.getDefault().restartNeovim();
        NeovimSession fresh = Activator.getDefault().currentSession();
        assertTrue(fresh != null, "Restart Neovim started no new Neovim (see the test Eclipse's log)");
        waitUntil("Neovim attached again, a new one", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed() && fresh != used && fresh.isAlive();
        });
        return fresh;
    }

    /**
     * The text of the status row drawn in editor widget {@code text} for Neovim window
     * {@code window}: the visible one-row grid that lies just above the command line's row;
     * {@code null} when none is shown. Example: {@code " NORMAL  test.txt ... 1:1"} with lualine.
     */
    static String statusRowText(StyledText text, int window) {
        Rectangle area = text.getClientArea();
        int commandLine = StatusLineRow.COMMAND_LINE_ROWS * text.getLineHeight();
        for (Control child : text.getChildren()) {
            if (child instanceof GridCanvas canvas && StatusLineRow.isRow(canvas) && canvas.isVisible() && canvas.grid() != null
                    && canvas.grid().window() == window && canvas.grid().height() == 1
                    && canvas.getBounds().y + canvas.getBounds().height == area.height - commandLine) {
                return canvas.grid().row(0);
            }
        }
        return null;
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

    /**
     * What Neovim shows in ui2's windows (its {@code cmd}, {@code msg}, {@code pager} and
     * {@code dialog} buffers), joined with line breaks; {@code ""} if Neovim does not answer, so it
     * can be a wait's condition.
     *
     * <p>Why from Neovim: ui2 ({@code LuaScripts.UI2}) draws the command line, messages, prompts
     * and the pager itself, as floating windows whose cells the editor copies
     * ({@code editor/FloatingWindows}); no widget of the plug-in holds a message's text any more.
     * The integration tests ask ui2's buffers the same way ({@code core/Ui2Text}). Example: after
     * {@code :bd 999} it contains {@code E516: No buffers were deleted}.
     *
     * <p>ui2's buffers keep older messages until ui2 clears them, so a check looks for a text with
     * {@code contains}, not for the whole of it.
     */
    String shownText() {
        try {
            Object text = SessionProbe.lua(session, UI2_TEXT);
            return text == null ? "" : String.valueOf(text);
        } catch (AssertionError e) {
            return "";
        }
    }

    /** {@link #shownText}'s script: ui2's buffers by kind ({@code require('vim._core.ui2').bufs}). */
    private static final String UI2_TEXT = """
            local ok, ui2 = pcall(require, 'vim._core.ui2')
            if not ok then
              return ''
            end
            local all = {}
            for _, kind in ipairs({ 'cmd', 'msg', 'pager', 'dialog' }) do
              local buf = ui2.bufs[kind]
              if buf and vim.api.nvim_buf_is_valid(buf) then
                all[#all + 1] = table.concat(vim.api.nvim_buf_get_lines(buf, 0, -1, false), '\\n')
              end
            end
            return table.concat(all, '\\n')
            """;

    /** Waits until Neovim shows {@code text} in one of ui2's windows ({@link #shownText}). */
    void expectShown(String text) {
        String[] last = { "" };
        waitUntil("Neovim to show " + text, TIMEOUT_MS, () -> (last[0] = shownText()).contains(text),
                () -> "; ui2's windows hold \"" + last[0].replace("\n", "\\n") + "\"");
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
     * Neovim's command line as it reads now, its type first: {@code ":e ot"} while {@code :e ot} is
     * typed, {@code "/gamma"} for a search; {@code ""} when no line is open. Asked from Neovim
     * ({@code getcmdtype()}, {@code getcmdline()}), whose line ui2 draws.
     */
    String commandLine() {
        try {
            Object line = SessionProbe.lua(session, "local t = vim.fn.getcmdtype(); return t == '' and '' or t .. vim.fn.getcmdline()");
            return line == null ? "" : String.valueOf(line);
        } catch (AssertionError e) {
            return "(Neovim did not answer: " + e.getCause() + ")";
        }
    }

    /** Waits until Neovim's command line reads {@code expected}, its type included ({@link #commandLine}). */
    void expectCommandLine(String expected) {
        String[] last = { "" };
        waitUntil("Neovim's command line to read " + expected, TIMEOUT_MS,
                () -> expected.equals(last[0] = commandLine()),
                () -> "; it reads \"" + last[0] + "\" (mode " + session.getMode() + ")");
    }

    /**
     * Types {@code :} into {@code target} and puts {@code command} into Neovim's command line.
     *
     * <p>The editor's widget keeps the keyboard while the line is open: each key goes to Neovim, and
     * ui2 draws the line over the editor's last row. The command goes in as a paste
     * ({@code session.pasteAsync}, Neovim's {@code nvim_paste}), as Eclipse's Paste puts it there
     * ({@code CommandLineEditHandlers}): one request instead of a key per character. It is sent
     * only once Neovim's line is open: {@code :} goes in as input, which Neovim reads in its own
     * time, while a paste acts at once and would land in the buffer. Then this waits until the line
     * reads {@code :command}: an Enter sent before would run a shorter line.
     */
    void openCommandLine(StyledText target, String command) {
        type(target, ':');
        expectCommandLine(":");
        if (!command.isEmpty()) {
            session.pasteAsync(command);
            expectCommandLine(":" + command);
        }
    }

    /** Runs {@code command} on Neovim's command line, as typed: {@code :}, the command, Enter. */
    void runCommandLine(StyledText target, String command) {
        openCommandLine(target, command);
        pressEnter(target);
        settle();
    }

    /** Sends Enter to {@code target}, as typed; the plug-in passes it on to Neovim ({@code <CR>}). */
    static void pressEnter(StyledText target) {
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        target.notifyListeners(SWT.KeyDown, enter);
    }

    /**
     * Neovim's popup menu as the editor draws it (the {@code :} line's Tab list, insert-mode
     * completion), or {@code null} while none is drawn: the float of window id {@code -1}
     * ({@code NeovimSession.floatingWindows}). Example: after {@code :e ot<Tab>} with two matching
     * files, one row reads {@code other.txt}, the next {@code other2.txt}.
     */
    FloatWindow popupMenu() {
        for (FloatWindow drawn : session.floatingWindows()) {
            if (drawn.grid().window() == -1) {
                return drawn;
            }
        }
        return null;
    }

    /** The text of {@link #popupMenu}'s rows, joined with line breaks; {@code ""} while none is drawn. */
    String popupMenuText() {
        FloatWindow menu = popupMenu();
        if (menu == null) {
            return "";
        }
        List<String> rows = new ArrayList<>();
        for (int r = 0; r < menu.grid().height(); r++) {
            rows.add(menu.grid().row(r).stripTrailing());
        }
        return String.join("\n", rows);
    }

    /**
     * Whether {@code canvas} draws a float that is always there or comes from Neovim itself, not
     * from a test: one of ui2's windows ({@code require('vim._core.ui2').wins}: cmd, msg, pager,
     * dialog), which the editor always draws, or Neovim's popup menu (window id {@code -1}). A
     * test that looks for its own float, or for a panel such as the command-line window's, skips
     * these. Example: ui2's message window still holds {@code :help help-context} from an earlier
     * test while a test waits for its own panel of key hints.
     */
    boolean isNeovimsOwnFloat(GridCanvas canvas) {
        if (canvas.grid() == null) {
            return false;
        }
        int window = canvas.grid().window();
        if (window == -1) {
            return true;
        }
        Object ui2 = SessionProbe.lua(session, """
                local ok, ui2 = pcall(require, 'vim._core.ui2')
                if not ok then
                  return false
                end
                for _, win in pairs(ui2.wins) do
                  if win == WIN then
                    return true
                  end
                end
                return false""".replace("WIN", String.valueOf(window)));
        return Boolean.TRUE.equals(ui2);
    }

    /**
     * The canvases of plug-ins' floats drawn over {@link #widget} ({@link #pluginFloats(StyledText)}).
     */
    List<GridCanvas> pluginFloats() {
        return pluginFloats(widget);
    }

    /**
     * The canvases of plug-ins' floats drawn over {@code text}, an editor's widget: every
     * {@link GridCanvas} child but ui2's windows and the popup menu ({@link #isNeovimsOwnFloat}) and
     * the status line's row. Example: a picker's input, list and preview, three canvases; none
     * while no plug-in has a float open.
     */
    List<GridCanvas> pluginFloats(StyledText text) {
        List<GridCanvas> floats = new ArrayList<>();
        for (Control child : text.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && !StatusLineRow.isRow(canvas)
                    && !isNeovimsOwnFloat(canvas)) {
                floats.add(canvas);
            }
        }
        return floats;
    }

    /**
     * A float's rows as text, each without its trailing blanks and ended by a newline; "" for no
     * grid. Example: oil.nvim's listing of a folder, {@code "a.txt\nb.txt\n~\n"}.
     */
    static String rowsOf(GridSnapshot grid) {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; grid != null && r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
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
        // This watch closes every dialog, error dialogs too, and the test checks the titles itself;
        // UiTestSupport's error-dialog watch stays out of its way until the control is gone.
        Runnable ended = UiTestSupport.dialogWatchStarted();
        control.addDisposeListener(e -> ended.run());
        Runnable check = new Runnable() {
            @Override
            public void run() {
                if (control.isDisposed()) {
                    return;
                }
                for (Shell shell : display.getShells()) {
                    if (shell != owner && shell.isVisible() && UiTestSupport.isModal(shell)) {
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

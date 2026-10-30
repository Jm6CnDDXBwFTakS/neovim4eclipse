// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * The end-to-end checks that need Java tooling: Eclipse's own Java edits (Format, Rename, Organize
 * Imports, a save action, a template) reach Neovim, {@code gd} and {@code K} run Open Declaration
 * and the Javadoc hover, folded imports, and {@code ZQ} in a Java editor.
 *
 * <p>Runs only with {@code ./mvnw verify -PuiHeavyJava}, which adds the Java development tools to
 * the test Eclipse (see the test module's pom.xml); tagged so that {@code -Pui} leaves it out. JDT is
 * used only through IDs (editor, commands, natures, preferences), so the fragment does not depend
 * on it and still compiles and starts without it.
 *
 * <p>The setup turns the test project into a Java project (a source folder and the default JRE)
 * and builds it, so that the Java model can resolve names for Open Declaration and Organize Imports.
 */
@Tag("heavyJava")
class JavaEditorEndToEndTest extends EndToEndTestBase {

    private static final String JAVA_EDITOR = "org.eclipse.jdt.ui.CompilationUnitEditor";
    private static final String FORMAT = "org.eclipse.jdt.ui.edit.text.java.format";
    private static final String ORGANIZE_IMPORTS = "org.eclipse.jdt.ui.edit.text.java.organize.imports";
    private static final String RENAME = "org.eclipse.jdt.ui.edit.text.java.rename.element";
    private static final String CONTENT_ASSIST = "org.eclipse.ui.edit.text.contentAssist.proposals";

    /** Warnings and errors logged meanwhile by any plug-in (the Error Log). */
    private final List<String> errorLog = new CopyOnWriteArrayList<>();
    private final ILogListener logListener = (status, plugin) -> {
        if (status.getSeverity() >= IStatus.WARNING) {
            errorLog.add(status.getPlugin() + ": " + status.getMessage());
        }
    };

    private ITextEditor javaEditor;
    private StyledText javaWidget;

    @BeforeEach
    void makeAJavaProject() throws Exception {
        createFile(".classpath", """
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                  <classpathentry kind="src" path="src"/>
                  <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
                  <classpathentry kind="output" path="bin"/>
                </classpath>
                """);
        project.getFolder("src").create(true, true, null);
        project.getFolder("src/p").create(true, true, null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] { "org.eclipse.jdt.core.javanature" });
        ICommand builder = description.newCommand();
        builder.setBuilderName("org.eclipse.jdt.core.javabuilder");
        description.setBuildSpec(new ICommand[] { builder });
        project.setDescription(description, null);
        Platform.addLogListener(logListener);
    }

    @AfterEach
    void stopListening() {
        Platform.removeLogListener(logListener);
    }

    /** Creates src/p/{@code name}, builds the project, opens it in the Java editor and waits for the attach. */
    private void openJava(String name, String source) throws Exception {
        IFile file = createFile("src/p/" + name, source);
        project.build(IncrementalProjectBuilder.FULL_BUILD, null);
        IEditorPart opened = IDE.openEditor(page(), file, JAVA_EDITOR, true);
        assertTrue(opened instanceof ITextEditor, "a Java editor: " + opened);
        javaEditor = (ITextEditor) opened;
        javaWidget = widgetOf(javaEditor);
        waitForAttach(javaEditor, name);
    }

    @AfterEach
    void closeJavaEditor() {
        if (javaEditor != null && page().findEditor(javaEditor.getEditorInput()) != null) {
            page().closeEditor(javaEditor, false);
        }
    }

    private String javaText() {
        return documentOf(javaEditor).get();
    }

    /** Waits until the Java document reads {@code expected}. */
    private void expectJava(String expected) {
        expectText(javaEditor, expected);
    }

    /**
     * Neovim's copy of the document is the document: what a check like "{@code /newName} finds the
     * new name" makes sure of.
     */
    private void expectNeovimHasTheDocument() {
        List<String> lines = new ArrayList<>(Arrays.asList(javaText().split("\n", -1)));
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // the last line break ends a line, it does not start one
        }
        waitUntil("Neovim to have the document", TIMEOUT_MS, () -> lines.equals(neovimLines()),
                () -> "; Neovim has " + neovimLines() + ", the document " + lines);
    }

    private List<String> neovimLines() {
        try {
            return session.getBufferLines();
        } catch (Exception e) {
            throw new AssertionError("Neovim did not answer", e);
        }
    }

    /** Moves the caret to {@code offset} as a click would; the caret move reaches Neovim. */
    private void caretTo(int offset) {
        javaEditor.selectAndReveal(offset, 0);
        settle();
    }

    /** Runs {@code commandId} and waits until the document has changed. */
    private void runAndWaitForAChange(String commandId) throws Exception {
        String before = javaText();
        runCommand(commandId);
        waitUntil(commandId + " to change the document", TIMEOUT_MS, () -> !before.equals(javaText()));
        settle(); // let the push reach Neovim
    }

    private static final String BADLY_INDENTED = """
            package p;
            class A {
            void f() {
            int x = 1;
            }
            }
            """;

    /** Without the last line: what {@code G dd} leaves. */
    private static String withoutLastLine(String text) {
        String body = text.substring(0, text.length() - 1);
        return body.substring(0, body.lastIndexOf('\n') + 1);
    }

    // ---- Eclipse's own Java edits reach Neovim ----

    /** Format (Ctrl+Shift+F) in normal mode, then {@code G dd}: the real last line goes. */
    @Test
    void formatThenGddDeletesTheRealLastLine() throws Exception {
        openJava("A.java", BADLY_INDENTED);
        runAndWaitForAChange(FORMAT);
        String formatted = javaText();
        assertTrue(formatted.contains("\t\tint x = 1;"), formatted);

        type(javaWidget, "Gdd");
        expectJava(withoutLastLine(formatted));
        type(javaWidget, 'u');
        expectJava(formatted);
    }

    /** After a format, {@code u} undoes the whole format in one step (the push is one Neovim change). */
    @Test
    void uUndoesAFormatInOneStep() throws Exception {
        openJava("A.java", BADLY_INDENTED);
        runAndWaitForAChange(FORMAT);

        type(javaWidget, 'u');
        expectJava(BADLY_INDENTED);
    }

    /**
     * Rename (Alt+Shift+R) on a local variable starts linked editing; {@code ciw} and a new name
     * change every occurrence together, and Neovim has the renamed text.
     */
    @Test
    void ciwInAJavaRenameChangesEveryOccurrenceAndNeovimHasIt() throws Exception {
        String source = """
                package p;
                class A {
                	int f() {
                		int count = 1;
                		return count + count;
                	}
                }
                """;
        openJava("A.java", source);
        caretTo(source.indexOf("count"));
        runCommand(RENAME);
        settle();

        type(javaWidget, "ciw");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(javaWidget, "total");
        escape(javaWidget);
        expectJava(source.replace("count", "total"));
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()),
                () -> "; mode " + session.getMode() + ", the document:\n" + javaText());
        expectNeovimHasTheDocument();
    }

    /**
     * As reported: a rename started in normal mode, {@code ea} and a suffix typed, and Enter, which
     * Eclipse takes to accept the rename. As in Vim, where the insert ends where the change ends,
     * Neovim is back in normal mode; it stayed in insert mode, into the next editor. The cursor is
     * on the name's first letter, where Eclipse puts the caret.
     */
    @Test
    void enterThatAcceptsARenameStartedInNormalModeLeavesInsertMode() throws Exception {
        String source = """
                package p;
                class A {
                	int f() {
                		int count = 1;
                		return count + count;
                	}
                }
                """;
        openJava("A.java", source);
        caretTo(source.indexOf("count"));
        runCommand(RENAME);
        settle();

        type(javaWidget, "ea");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(javaWidget, "Total");
        type(javaWidget, '\r');
        expectJava(source.replace("count", "countTotal"));
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()),
                () -> "; mode " + session.getMode() + ", the document:\n" + javaText());
        expectNeovimHasTheDocument();
        // On the name's first letter, where Enter put the caret: not one before it, where the Esc's
        // step to the left put Neovim's cursor.
        waitUntil("Neovim's cursor on the c of countTotal", TIMEOUT_MS,
                () -> List.of(4L, 6L).equals(SessionProbe.lua(session, "return vim.api.nvim_win_get_cursor(0)")),
                () -> "; it is at " + SessionProbe.lua(session, "return vim.api.nvim_win_get_cursor(0)"));
        settle();
        assertEquals("4:6", caretLineCol(), "the caret");
    }

    /**
     * A template inserted while typing keeps insert mode when Enter leaves its fields: Enter puts
     * the caret in the loop's body, where the typing goes on. Only a linked edit started in normal
     * mode (a rename) ends the insert at its Enter.
     */
    @Test
    void enterThatLeavesATemplatesFieldsKeepsInsertMode() throws Exception {
        String source = """
                package p;
                class A {
                	void f(String[] args) {

                	}
                }
                """;
        openJava("A.java", source);
        type(javaWidget, "4GA");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(javaWidget, "fore");
        List<Shell> before = visibleShells();
        runCommand(CONTENT_ASSIST);
        waitUntil("the proposals", TIMEOUT_MS, () -> proposalCount(before) > 0);
        type(javaWidget, '\r'); // the first proposal
        waitUntil("the template", TIMEOUT_MS, () -> javaText().contains("for (String "),
                () -> "; the document is " + javaText());

        type(javaWidget, '\r'); // leaves the template's fields, into the body
        settle();
        assertEquals("i", session.getMode(), "still in insert mode");
        type(javaWidget, "go();");
        escape(javaWidget);
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        assertTrue(javaText().contains("go();"), javaText());
        expectNeovimHasTheDocument();
    }

    /** Organize Imports (Ctrl+Shift+O) drops an unused import; then {@code gg jj dd} deletes the new third line. */
    @Test
    void organizeImportsReachesNeovim() throws Exception {
        String source = """
                package p;

                import java.util.List;
                import java.util.Map;

                class A {
                	Map<String, String> m;
                }
                """;
        openJava("A.java", source);
        runAndWaitForAChange(ORGANIZE_IMPORTS);
        String organized = javaText();
        assertFalse(organized.contains("List"), organized);

        type(javaWidget, "ggjjdd");
        expectJava(organized.replace("import java.util.Map;\n", ""));
    }

    /** A large file formatted twice in a row, then {@code jjjj}: no freeze, nothing in the Error Log. */
    @Test
    void formattingALargeFileTwiceLogsNothing() throws Exception {
        StringBuilder source = new StringBuilder("package p;\nclass Big {\nvoid f() {\n");
        for (int i = 0; i < 2000; i++) {
            source.append("int v").append(i).append(" =  ").append(i).append(";\n");
        }
        source.append("}\n}\n");
        openJava("Big.java", source.toString());

        runAndWaitForAChange(FORMAT);
        runCommand(FORMAT); // already formatted: may change nothing
        settle();
        type(javaWidget, "ggjjjj");
        settle();
        expectNeovimHasTheDocument();
        assertEquals(List.of(), errorLog);
    }

    /**
     * Save action "Format source code" on: {@code x}, {@code :w}; the file is saved formatted (a
     * change Eclipse makes while saving) and {@code G dd} deletes the real last line.
     */
    @Test
    void aFormatOnSaveReachesNeovim() throws Exception {
        IEclipsePreferences jdtUi = InstanceScope.INSTANCE.getNode("org.eclipse.jdt.ui");
        String participant = "editor_save_participant_org.eclipse.jdt.ui.postsavelistener.cleanup";
        String format = "sp_cleanup.format_source_code";
        jdtUi.putBoolean(participant, true);
        jdtUi.putBoolean(format, true);
        try {
            openJava("A.java", BADLY_INDENTED.replace("x = 1", "xy = 1"));
            caretTo(javaText().indexOf("y = 1"));
            type(javaWidget, 'x');
            expectJava(BADLY_INDENTED);

            runInBox(javaWidget, "w");
            // This timed out once on macOS with no further detail. runInBox now fails at once if the
            // box lost focus before Enter; if the save itself does not finish, say what is left.
            waitUntil("the save", TIMEOUT_MS, () -> !javaEditor.isDirty(),
                    () -> "; the editor is still dirty (" + UiTestSupport.focusReport(javaWidget) + ", : box "
                            + (textChildOf(javaWidget) == null ? "closed" : "still open") + ", mode " + session.getMode()
                            + ", Error Log " + errorLog + "); the document reads:\n" + javaText());
            String formatted = javaText();
            assertTrue(formatted.contains("\t\tint x = 1;"), "saved formatted: " + formatted);
            settle();

            type(javaWidget, "Gdd");
            expectJava(withoutLastLine(formatted));
        } finally {
            jdtUi.remove(participant);
            jdtUi.remove(format);
        }
    }

    /**
     * A template from content assist ({@code fore} for "iterate over array"), typed in insert mode
     * and left with one Esc: Neovim gets the template's text and is back in normal mode. (The real
     * Ctrl+Space is its command here.)
     */
    @Test
    void aTemplateFromContentAssistReachesNeovim() throws Exception {
        String source = """
                package p;
                class A {
                	void f(String[] args) {

                	}
                }
                """;
        openJava("A.java", source);
        type(javaWidget, "4GA");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(javaWidget, "fore");
        List<Shell> before = visibleShells();
        runCommand(CONTENT_ASSIST);
        // The popup opens first and fills its list when the proposals are computed.
        waitUntil("the proposals", TIMEOUT_MS, () -> proposalCount(before) > 0);
        type(javaWidget, '\r'); // the first proposal

        waitUntil("the template", TIMEOUT_MS, () -> javaText().contains("for (String "),
                () -> "; the document is " + javaText());
        escape(javaWidget); // one Esc: Eclipse leaves the template's linked mode, Neovim leaves insert mode
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        expectNeovimHasTheDocument();
    }

    /** The items of the proposal list (a {@link Table}) in a window that was not open {@code before}. */
    private static int proposalCount(List<Shell> before) {
        int count = 0;
        for (Shell shell : visibleShells()) {
            if (!before.contains(shell)) {
                count += tableItems(shell);
            }
        }
        return count;
    }

    private static int tableItems(Control control) {
        if (control instanceof Table table) {
            // While computing, the list shows one placeholder row ("Computing proposals...").
            return (int) Arrays.stream(table.getItems()).filter(item -> !item.getText().startsWith("Computing")).count();
        }
        int count = 0;
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                count += tableItems(child);
            }
        }
        return count;
    }

    private static List<Shell> visibleShells() {
        return Arrays.stream(Display.getCurrent().getShells()).filter(Shell::isVisible).toList();
    }

    // ---- gd and K run Eclipse's commands in a Java editor ----

    private static final String CALLER = """
            package p;
            class A {
            	void target() {
            	}
            	void caller() {
            		target();
            	}
            }
            """;

    /** {@code gd} on a call is Open Declaration (F3): the caret goes to the method's declaration. */
    @Test
    void gdOpensTheDeclaration() throws Exception {
        openJava("A.java", CALLER);
        caretTo(CALLER.indexOf("target();"));

        type(javaWidget, "gd");

        waitUntil("the caret on the declaration", TIMEOUT_MS, () -> selectionLine() == 2,
                () -> "; the selection is on line " + selectionLine());
    }

    /** {@code K} on a call shows the Javadoc hover (F2): a hover window opens. */
    @Test
    void kShowsTheHover() throws Exception {
        openJava("A.java", CALLER);
        caretTo(CALLER.indexOf("target();"));
        List<Shell> before = visibleShells();

        type(javaWidget, 'K');

        waitUntil("a hover window", TIMEOUT_MS, () -> visibleShells().size() > before.size());
        visibleShells().stream().filter(shell -> !before.contains(shell)).forEach(Shell::close);
    }

    /** The editor's selection (Eclipse's caret), as a 0-based document line. */
    private int selectionLine() {
        return ((ITextSelection) javaEditor.getSelectionProvider().getSelection()).getStartLine();
    }

    // ---- Folded code; ZQ in a Java editor ----

    private static final String IMPORTS = """
            package p;

            import java.util.List;
            import java.util.Map;

            class A {
            	List<String> l;
            	Map<String, String> m;
            }
            """;

    /** Waits until the editor's folds have reached Neovim (FoldMirror's first send). */
    private void waitForFoldsInNeovim() {
        waitUntil("the folds in Neovim", TIMEOUT_MS,
                () -> Boolean.TRUE.equals(SessionProbe.lua(session, "return vim.b.neovim4eclipse_folds == true")));
    }

    /** Whether the widget hides lines (some fold is collapsed). */
    private boolean someLinesHidden() {
        return javaWidget.getLineCount() < documentOf(javaEditor).getNumberOfLines();
    }

    /**
     * The imports are folded when the editor opens, and the fold is mirrored closed in Neovim. With
     * Vim's default {@code 'foldopen'} ({@code G} is a "jump", which is not in it) {@code 4G} into
     * them leaves them folded: the caret stands on the fold's caption line, the first import.
     */
    @Test
    void movingIntoFoldedImportsLeavesThemFoldedWithTheCaretOnTheirCaption() throws Exception {
        openJava("A.java", IMPORTS);
        waitForFoldsInNeovim();
        assertTrue(someLinesHidden(), "JDT folds the imports at first");

        type(javaWidget, "4G");
        settle();
        waitUntil("the caret on the imports' caption line", TIMEOUT_MS, () -> selectionLine() == 2,
                () -> "; the selection is on line " + selectionLine());
        assertTrue(someLinesHidden(), "still folded");
    }

    /** With {@code foldopen=all} Neovim opens the fold, Eclipse unfolds it, and {@code x} edits that line. */
    @Test
    void withFoldopenAllMovingIntoFoldedImportsUnfoldsThem() throws Exception {
        String before = (String) SessionProbe.lua(session, "return vim.o.foldopen");
        SessionProbe.lua(session, "vim.o.foldopen = 'all'");
        try {
            openJava("A.java", IMPORTS);
            waitForFoldsInNeovim();

            type(javaWidget, "4G");
            settle();
            waitUntil("the caret on line 4", TIMEOUT_MS, () -> selectionLine() == 3,
                    () -> "; the selection is on line " + selectionLine());
            waitUntil("the imports unfolded", TIMEOUT_MS, () -> !someLinesHidden());
            type(javaWidget, 'x');
            expectJava(IMPORTS.replace("import java.util.Map;", "mport java.util.Map;"));
        } finally {
            SessionProbe.lua(session, "vim.o.foldopen = '" + before + "'");
        }
    }

    /**
     * {@code zc} in a method with Javadoc collapses it in Eclipse, which shows its signature, not
     * the comment above (the caption of JDT's member position), and the caret goes to that line;
     * {@code zo} opens it again with the caret back where Neovim's cursor is.
     */
    @Test
    void zcOnAMethodWithJavadocPutsTheCaretOnItsSignature() throws Exception {
        String source = """
                package p;

                class B {
                	/**
                	 * Docs.
                	 */
                	void m() {
                		int x = 1;
                	}
                }
                """;
        openJava("B.java", source);
        waitForFoldsInNeovim();

        type(javaWidget, "8G");
        settle();
        type(javaWidget, "zc");
        settle();
        waitUntil("the method folded", TIMEOUT_MS, this::someLinesHidden);
        waitUntil("the caret on the signature", TIMEOUT_MS, () -> selectionLine() == 6,
                () -> "; the selection is on line " + selectionLine());

        type(javaWidget, "zo");
        settle();
        waitUntil("the method unfolded", TIMEOUT_MS, () -> !someLinesHidden());
        waitUntil("the caret back on line 8", TIMEOUT_MS, () -> selectionLine() == 7,
                () -> "; the selection is on line " + selectionLine());
    }

    /**
     * As reported on a real file: a method with Javadoc followed by a blank line, the cursor in its
     * body. {@code zc} puts the caret on the signature, Neovim's cursor stays in the fold, and
     * {@code zo} opens it again. Collapsing the fold makes the widget move its caret out of the
     * hidden lines, to the blank line after the method; that move once reached Neovim as the
     * user's, and {@code zo} there found no fold. (The test editor's folds do not show it: there the
     * widget keeps the caret on the caption line.)
     */
    @Test
    void zcInTheBodyOfAMethodWithJavadocAndZoReopensIt() throws Exception {
        String source = """
                package p;

                import java.util.List;
                import java.util.concurrent.ArrayBlockingQueue;

                /**
                 * A class comment.
                 */
                public class D {
                    /**
                     * If you Alt+Enter on this comment, you can turn it into a Markdown comment.
                     */
                    void thisIsAMethodWithAComment() {
                        System.out.println("");
                    }

                }
                """;
        openJava("D.java", source);
        waitForFoldsInNeovim();

        type(javaWidget, "14G");
        settle();
        type(javaWidget, "9|");
        settle();
        type(javaWidget, "zc");
        settle();
        waitUntil("the method folded", TIMEOUT_MS, () -> javaWidget.getLineCount() < documentOf(javaEditor).getNumberOfLines() - 1);
        waitUntil("the caret on the signature", TIMEOUT_MS, () -> selectionLine() == 12,
                () -> "; the selection is on line " + selectionLine() + ", Neovim's cursor on "
                        + SessionProbe.lua(session, "return vim.fn.line('.')"));
        assertEquals(14, ((Number) SessionProbe.lua(session, "return vim.fn.line('.')")).intValue(),
                "Neovim's cursor stays in the fold");

        type(javaWidget, "zo");
        settle();
        waitUntil("the caret back on line 14", TIMEOUT_MS, () -> selectionLine() == 13,
                () -> "; the selection is on line " + selectionLine());
    }

    /**
     * As reported: typing a new method in insert mode, {@code {} and Enter (JDT closes the brace).
     * The reconcile makes a fold for it while Neovim, in insert mode, does not have those lines yet.
     * Two bugs met here: the folds were sent at once, onto the wrong lines, and deleting the old
     * folds ran {@code :normal}, whose pass through normal mode made the plug-in take insert mode as
     * ended, so the typed method went missing. Now the folds wait for {@code Esc}, and the method
     * is in both and is a fold in Neovim.
     */
    @Test
    void aMethodTypedInInsertModeStaysAndBecomesAFold() throws Exception {
        String source = """
                package p;

                public class E {
                    /**
                     * A comment.
                     */
                    void thisIsAMethodWithAComment() {
                        System.out.println("");
                    }

                }
                """;
        openJava("E.java", source);
        waitForFoldsInNeovim();
        type(javaWidget, "10G");
        settle();
        type(javaWidget, "o");
        settle();
        int foldsBefore = foldCount();
        type(javaWidget, "private void aMethod() {");
        type(javaWidget, '\r');
        // Long enough for JDT's reconcile and the folds it makes (about 500 ms); insert mode must hold.
        waitUntil("JDT to fold the new method", TIMEOUT_MS, () -> foldCount() > foldsBefore,
                () -> "; it has " + foldCount() + " folds, as before");
        settle(); // the folds' send, had it not been held
        assertEquals("i", session.getMode(), "still in insert mode");

        escape(javaWidget);
        settle();
        waitUntil("the method in Neovim", TIMEOUT_MS,
                () -> Boolean.TRUE.equals(SessionProbe.lua(session, "return vim.fn.search('aMethod', 'nw') > 0")));
        assertTrue(javaText().contains("private void aMethod() {"), javaText());
        waitUntil("the new method a fold in Neovim", TIMEOUT_MS, () -> {
            Object level = SessionProbe.lua(session, "return vim.fn.foldlevel(vim.fn.search('aMethod', 'nw'))");
            return level instanceof Number n && n.intValue() > 0;
        });
    }

    private static final String CLASS_F = """
            package p;

            public class F {
                void m() {
                    System.out.println("");
                }

            }
            """;

    /** The caret as a 1-based line and a 0-based column, e.g. "6:5". */
    private String caretLineCol() {
        int offset = ((ITextSelection) javaEditor.getSelectionProvider().getSelection()).getOffset();
        org.eclipse.jface.text.IDocument d = documentOf(javaEditor);
        try {
            int line = d.getLineOfOffset(offset);
            return (line + 1) + ":" + (offset - d.getLineOffset(line));
        } catch (org.eclipse.jface.text.BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * As reported: {@code O} on the class's closing brace, then a method typed in insert mode and
     * Enter, which JDT answers with the closing brace and an indented line between. The caret
     * belongs after that line's indentation. It went to column 1: the caret move reached Neovim,
     * whose lines lack what Eclipse typed until Esc, Neovim clamped it to its old line ({@code }})
     * and reported that back.
     */
    @Test
    void enterAfterABraceTypedAboveTheClosingBraceKeepsTheCaretOnTheIndentedLine() throws Exception {
        openJava("F.java", CLASS_F);
        settle();
        type(javaWidget, "8G");
        settle();
        type(javaWidget, "O");
        settle();
        type(javaWidget, "private void aMethod() {");
        type(javaWidget, '\r');
        settle();
        // JDT: "    private void aMethod() {", then "    \t" (the caret after it), then "    }".
        waitUntil("the caret after the new line's indentation", TIMEOUT_MS, () -> caretLineCol().equals("9:5"),
                () -> "; it is at " + caretLineCol() + " in\n" + javaText());
        type(javaWidget, 'x');
        escape(javaWidget);
        settle();
        assertTrue(javaText().contains("    private void aMethod() {\n    \tx\n    }\n"), javaText());
    }

    /** The same with {@code o} below the method's closing brace. */
    @Test
    void enterAfterABraceTypedBelowAMethodKeepsTheCaretOnTheIndentedLine() throws Exception {
        openJava("F.java", CLASS_F);
        settle();
        type(javaWidget, "6G");
        settle();
        type(javaWidget, "o");
        settle();
        type(javaWidget, "void b() {");
        type(javaWidget, '\r');
        settle();
        waitUntil("the caret after the new line's indentation", TIMEOUT_MS, () -> caretLineCol().equals("8:5"),
                () -> "; it is at " + caretLineCol() + " in\n" + javaText());
    }

    /**
     * For a failure message: each fold as offset+length, with c when collapsed, then the widget line
     * of the document's first line (-1: hidden). Example: "1+53c line1->widget 0".
     */
    private String foldPositions() {
        org.eclipse.jface.text.source.projection.ProjectionViewer viewer =
                (org.eclipse.jface.text.source.projection.ProjectionViewer) javaEditor
                        .getAdapter(org.eclipse.jface.text.ITextOperationTarget.class);
        var model = viewer.getProjectionAnnotationModel();
        StringBuilder out = new StringBuilder();
        for (var it = model.getAnnotationIterator(); it.hasNext();) {
            var a = (org.eclipse.jface.text.source.projection.ProjectionAnnotation) it.next();
            var pos = model.getPosition(a);
            out.append(pos.getOffset()).append('+').append(pos.getLength()).append(a.isCollapsed() ? "c " : " ");
        }
        return out + "line1->widget " + viewer.modelLine2WidgetLine(0);
    }

    /** How many fold annotations the Java editor has. */
    private int foldCount() {
        org.eclipse.jface.text.source.projection.ProjectionViewer viewer =
                (org.eclipse.jface.text.source.projection.ProjectionViewer) javaEditor
                        .getAdapter(org.eclipse.jface.text.ITextOperationTarget.class);
        int count = 0;
        for (var it = viewer.getProjectionAnnotationModel().getAnnotationIterator(); it.hasNext(); it.next()) {
            count++;
        }
        return count;
    }

    /**
     * As reported: JDT folds a file's header comment (the copyright) when the editor opens, hiding
     * its {@code /*} line (the caption is the first line with text). {@code O} on line 1 opens a line
     * above the fold, and what is typed then belongs on that new line: {@code //x} on line 1, the
     * header unchanged below it. The new line stayed hidden, and the caret went to the caption line
     * (WidgetOffsets, "Text hidden by no fold").
     *
     * <p>Not tested here, because Eclipse does it without the plug-in too: with a header of
     * {@code //} lines, the {@code //} typed on the new line makes it part of the header comment,
     * and JDT's reconcile grows the collapsed fold over it, hiding the line being typed.
     */
    @Test
    void oAboveAFoldedHeaderCommentTypesOnTheNewLine() throws Exception {
        openJava("H.java", """
                /*
                 * Copyright line one.
                 * Copyright line two.
                 */
                package p;

                class H {
                }
                """);
        waitForFoldsInNeovim();
        assertTrue(someLinesHidden(), "JDT folds the header comment at first");
        type(javaWidget, "gg");
        settle();
        String before = foldPositions();
        type(javaWidget, "O");
        settle();
        waitUntil("the caret on the new line 1", TIMEOUT_MS, () -> caretLineCol().equals("1:0"),
                () -> "; it is at " + caretLineCol() + "; folds before " + before + ", after " + foldPositions()
                        + " in\n" + javaText());
        type(javaWidget, "//x");
        settle();
        assertEquals("1:3", caretLineCol(), "the caret after //x, in\n" + javaText());
        escape(javaWidget);
        settle();
        assertTrue(javaText().startsWith("//x\n/*\n * Copyright line one."), javaText());
        expectNeovimHasTheDocument();
    }

    /** {@code zM} collapses every member; {@code zR} opens them all. */
    @Test
    void zMAndZRFoldAndUnfoldEverything() throws Exception {
        openJava("C.java", CALLER);
        waitForFoldsInNeovim();
        type(javaWidget, "zR");
        settle();
        waitUntil("everything unfolded", TIMEOUT_MS, () -> !someLinesHidden());
        type(javaWidget, "zM");
        settle();
        waitUntil("everything folded", TIMEOUT_MS, this::someLinesHidden);
    }

    /** {@code ZQ} closes a Java editor after its key event: no "Unhandled event loop exception". */
    @Test
    void zqClosesAJavaEditorWithoutAnError() throws Exception {
        openJava("A.java", CALLER);

        type(javaWidget, "ZQ");

        waitUntil("the editor to close", TIMEOUT_MS, () -> page().findEditor(javaEditor.getEditorInput()) == null);
        assertEquals(List.of(), errorLog);
    }

    // ---- Neovim's language keys (gr*) run Java's commands ----

    private static final String CALLS = """
            package p;
            interface I { void g(); }
            class A implements I {
                public void g() { String s = "x"; s.length(); }
                void f(I i) { i.g(); }
            }
            """;

    /** What an IDE key would run with the caret at {@code offset}, without running it. */
    private IdeKeys.Choice choiceAt(String action, int offset) {
        caretTo(offset);
        return IdeActions.choose(action, javaEditor, javaEditor.getAdapter(org.eclipse.jface.text.ITextViewer.class),
                offset);
    }

    private static IdeKeys.Choice command(String id) {
        return new IdeKeys.Choice(IdeKeys.Choice.Kind.COMMAND, id);
    }

    /**
     * In the Java editor each key finds Java's own command: the key Eclipse binds for it where the
     * plug-in's contexts leave that key alone (F3, Alt+Shift+R, Ctrl+Shift+G), else the command's id;
     * {@code grt} finds JDT's "Open Declared Type" link on a variable, and nothing on a keyword.
     */
    @Test
    void theLanguageKeysFindJavasCommands() throws Exception {
        openJava("A.java", CALLS);
        int call = javaText().indexOf("i.g()") + 2;
        assertEquals(command("org.eclipse.jdt.ui.edit.text.java.open.editor"), choiceAt(IdeAction.DEFINITION, call));
        assertEquals(command(IdeKeys.SHOW_INFORMATION), choiceAt(IdeAction.HOVER, call));
        assertEquals(command("org.eclipse.jdt.ui.edit.text.java.rename.element"), choiceAt(IdeAction.RENAME, call));
        assertEquals(command(IdeKeys.QUICK_ASSIST), choiceAt(IdeAction.QUICK_FIX, call));
        assertEquals(command("org.eclipse.jdt.ui.edit.text.java.search.references.in.workspace"),
                choiceAt(IdeAction.REFERENCES, call));
        assertEquals(command("org.eclipse.jdt.ui.edit.text.java.open.implementation"),
                choiceAt(IdeAction.IMPLEMENTATION, call));
        assertEquals(command("org.eclipse.jdt.ui.edit.text.java.show.outline"), choiceAt(IdeAction.OUTLINE, call));
        assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.LINK,
                "org.eclipse.jdt.internal.ui.javaeditor.JavaElementHyperlinkDeclaredTypeDetector"),
                choiceAt(IdeAction.TYPE_DEFINITION, javaText().indexOf("s.length")));
        assertEquals(new IdeKeys.Choice(IdeKeys.Choice.Kind.MESSAGE,
                "grt: Open Declared Type is not available in this editor"),
                choiceAt(IdeAction.TYPE_DEFINITION, javaText().indexOf("class A")));
    }

    /** {@code gri} on a call of an interface's method goes to its one implementation. */
    @Test
    void griGoesToTheImplementation() throws Exception {
        openJava("A.java", CALLS);
        caretTo(javaText().indexOf("i.g()") + 2);

        type(javaWidget, "gri");

        waitUntil("the cursor on A.g's line", TIMEOUT_MS, () -> cursorLine() == 4, () -> "; it is on " + cursorLine());
    }

    /** {@code grt} on a variable opens its type, as Ctrl+click's "Open Declared Type" does. */
    @Test
    void grtOpensTheDeclaredType() throws Exception {
        openJava("A.java", CALLS);
        caretTo(javaText().indexOf("s.length"));

        type(javaWidget, "grt");

        try {
            waitUntil("String's editor", TIMEOUT_MS, () -> page().getActiveEditor() != null
                    && page().getActiveEditor().getTitle().startsWith("String"),
                    () -> "; active: " + (page().getActiveEditor() == null ? null : page().getActiveEditor().getTitle()));
        } finally {
            IEditorPart string = page().getActiveEditor();
            if (string != null && string != javaEditor && string != editor) {
                page().closeEditor(string, false);
            }
        }
    }
}

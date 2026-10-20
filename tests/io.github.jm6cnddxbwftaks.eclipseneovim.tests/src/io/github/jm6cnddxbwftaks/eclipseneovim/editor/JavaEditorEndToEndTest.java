// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
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

/**
 * The review checks that need Java tooling: Eclipse's own Java edits (Format, Rename, Organize
 * Imports, a save action, a template) reach Neovim (Fix 7, REFACTOR), {@code gd} and {@code K} run
 * Open Declaration and the Javadoc hover (Fix 8), folded imports, and {@code ZQ} in a Java editor.
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

    // ---- Fix 7: Eclipse's own Java edits reach Neovim ----

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
     * change every occurrence together, and Neovim has the renamed text (also LATER's check).
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
            waitUntil("the save", TIMEOUT_MS, () -> !javaEditor.isDirty());
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

    // ---- Fix 8: gd and K run Eclipse's commands in a Java editor ----

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

    // ---- REFACTOR: folded code; Fix 10: ZQ in a Java editor ----

    /** With the imports folded, {@code 4G} (inside them) unfolds them; {@code x} edits that line. */
    @Test
    void movingIntoFoldedImportsUnfoldsThem() throws Exception {
        String source = """
                package p;

                import java.util.List;
                import java.util.Map;

                class A {
                	List<String> l;
                	Map<String, String> m;
                }
                """;
        openJava("A.java", source);

        type(javaWidget, "4G");
        settle();
        waitUntil("the caret on line 4", TIMEOUT_MS, () -> selectionLine() == 3,
                () -> "; the selection is on line " + selectionLine());
        type(javaWidget, 'x');
        expectJava(source.replace("import java.util.Map;", "mport java.util.Map;"));
    }

    /** {@code ZQ} closes a Java editor after its key event: no "Unhandled event loop exception". */
    @Test
    void zqClosesAJavaEditorWithoutAnError() throws Exception {
        openJava("A.java", CALLER);

        type(javaWidget, "ZQ");

        waitUntil("the editor to close", TIMEOUT_MS, () -> page().findEditor(javaEditor.getEditorInput()) == null);
        assertEquals(List.of(), errorLog);
    }
}

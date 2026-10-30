// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;

/**
 * Names that reach a workspace file another way, here through a symbolic link to the project's folder
 * (on macOS {@code /var} is one; on Windows a junction or 8.3 short name does the same): {@code :e}
 * opens the workspace file itself, and {@code :wq!} of the editor's own file under that name saves it.
 * Both follow the one rule for paths ({@code util/FilePaths}, {@code NewFiles.workspaceFileAt});
 * its cases are {@code FilePathsTest} and {@code paths_spec.lua}.
 */
class FilePathsEndToEndTest extends EndToEndTestBase {

    /** A link to the project's folder, outside the workspace. */
    private Path link;

    @BeforeEach
    void linkToTheProject() throws IOException {
        Path dir = Files.createTempDirectory("n4e-link");
        link = dir.resolve("proj");
        try {
            Files.createSymbolicLink(link, Path.of(project.getLocation().toOSString()));
        } catch (IOException | UnsupportedOperationException e) {
            Files.delete(dir);
            link = null;
        }
        assumeTrue(link != null, "no right to make a symbolic link here (a Windows runner)");
    }

    @AfterEach
    void removeTheLink() throws IOException {
        for (IEditorReference ref : page().getEditorReferences()) {
            IEditorPart part = ref.getEditor(false);
            if (part != null && part != editor) {
                page().closeEditor(part, false);
            }
        }
        if (link != null) {
            Files.deleteIfExists(link);
            Files.deleteIfExists(link.getParent());
        }
    }

    /**
     * {@code :e} of a project file through the link opens the workspace file, not a file outside it.
     * A guard on Linux and macOS, where Eclipse's lookup resolves symbolic links itself (found
     * 2026-10-29: this passes without {@code NewFiles.workspaceFileAt}'s retry); the retry is for
     * Windows junctions, which this test cannot make there without rights.
     */
    @Test
    void eThroughALinkOpensTheWorkspaceFile() throws Exception {
        IFile other = createFile("other.txt", "other\n");

        session.inputAsync(":e " + link.resolve("other.txt").toString().replace("\\", "\\\\") + "<CR>");

        waitUntil("other.txt's editor, on the workspace file", TIMEOUT_MS, () -> {
            IEditorPart active = page().getActiveEditor();
            return active != null && active.getEditorInput() instanceof IFileEditorInput input
                    && input.getFile().equals(other);
        }, () -> "; active: " + (page().getActiveEditor() == null ? null : page().getActiveEditor().getEditorInput()));
        assertEquals(1, List.of(page().getEditorReferences()).stream()
                .filter(r -> r.getTitle().equals("other.txt")).count(), "one editor on the file");
    }

    /**
     * {@code :wq!} of the editor's own file under the link's name saves the editor and closes it; a
     * copy onto its own file would leave it with unsaved changes and ask before closing.
     */
    @Test
    void wqBangOfTheOwnFileThroughALinkSavesAndCloses() throws Exception {
        ITextEditor own = openFile("own.txt", "one\n");
        IFile file = ((IFileEditorInput) own.getEditorInput()).getFile();
        assertEquals(true, FilePaths.same(link.resolve("own.txt"), Path.of(file.getLocation().toOSString())));
        typeSlowly(widgetOf(own), "Atwo");
        UiTestSupport.escape(widgetOf(own));
        settle();

        session.inputAsync(":wq! " + link.resolve("own.txt").toString().replace("\\", "\\\\") + "<CR>");

        waitUntil("the editor closed", TIMEOUT_MS, () -> page().findEditor(own.getEditorInput()) == null);
        assertEquals("onetwo\n", Files.readString(Path.of(file.getLocation().toOSString()), StandardCharsets.UTF_8));
    }
}

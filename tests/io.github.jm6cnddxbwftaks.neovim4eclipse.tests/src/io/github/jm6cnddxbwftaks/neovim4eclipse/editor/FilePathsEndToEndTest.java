// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;

/**
 * Names that reach a workspace file another way, here through a symbolic link to the project's folder
 * (on macOS {@code /var} is one; on Windows a junction, made when a symbolic link may not be, or an
 * 8.3 short name does the same): {@code :e} opens the workspace file itself, and {@code :wq!} of the
 * editor's own file under that name saves it.
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
        if (!makeLink(link, Path.of(project.getLocation().toOSString()))) {
            Files.delete(dir);
            link = null;
        }
        assumeTrue(link != null, "could make neither a symbolic link nor a junction here");
    }

    /** Makes {@code link} a symbolic link to {@code target}, else a junction; whether either worked. */
    private static boolean makeLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return junction(link, target);
        }
    }

    /**
     * Makes {@code link} a Windows junction to the folder {@code target}, for when Java may not make a
     * symbolic link (that needs developer mode or admin rights; a junction needs none, and Java has no
     * API for one). As Neovim's {@code fs_symlink(..., { junction = true })} does in the Lua specs.
     * Example: {@code cmd /c mklink /J C:\Temp\n4e-link1\proj C:\ws\proj}. {@code false} off Windows
     * or when it fails.
     */
    private static boolean junction(Path link, Path target) throws IOException {
        if (!FilePaths.Platform.current().windows()) {
            return false;
        }
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        try {
            mklink.getInputStream().transferTo(OutputStream.nullOutputStream());
            return mklink.waitFor() == 0 && Files.isDirectory(link);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @AfterEach
    void removeTheLink() throws IOException {
        closeOtherEditors();
        if (link != null) {
            Files.deleteIfExists(link);
            Files.deleteIfExists(link.getParent());
        }
    }

    /** Closes every editor but the base class's. */
    private void closeOtherEditors() {
        for (IEditorReference ref : page().getEditorReferences()) {
            IEditorPart part = ref.getEditor(false);
            if (part != null && part != editor) {
                page().closeEditor(part, false);
            }
        }
    }

    /**
     * {@code :e} of a project file through the link opens the workspace file, not a file outside it.
     * A guard on Linux and macOS, where Eclipse's lookup resolves symbolic links itself (found
     * 2026-10-29: this passes without {@code NewFiles.workspaceFileAt}'s retry); the retry is for
     * Windows junctions, which this test makes there when it may not make a symbolic link.
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
     * A linked folder (<i>New → Folder → Link to alternate location</i>) whose target was given
     * through a link, reached by its real path: {@code :e} opens the workspace file in the linked
     * folder. The lookup resolves the path, which is real already, so it needs the folder's recorded
     * spelling compared by the one rule ({@code NewFiles.workspaceFileAt}, its last step). Found
     * 2026-10-31 on Windows, where {@code ~\src} was a junction to the profile's {@code src}: the
     * link was made through the junction, {@code gf} gave the real path, and no editor opened.
     * Example: {@code e2e/ext} linked to {@code /tmp/n4e-viaN/via}, a link to
     * {@code /tmp/n4e-realN}; {@code :e /tmp/n4e-realN/ext.txt} opens {@code /e2e/ext/ext.txt}.
     */
    @Test
    void eByTheRealPathOpensAFileInAFolderLinkedThroughALink() throws Exception {
        Path real = Files.createTempDirectory("n4e-real");
        Path ext = real.resolve("ext.txt");
        Files.writeString(ext, "outside\n", StandardCharsets.UTF_8);
        Path via = Files.createTempDirectory("n4e-via").resolve("via");
        IFolder folder = project.getFolder("ext");
        try {
            assumeTrue(makeLink(via, real), "could make neither a symbolic link nor a junction here");
            folder.createLink(IPath.fromOSString(via.toString()), IResource.NONE, null);
            IFile linked = folder.getFile("ext.txt");
            String inside = "return require('neovim4eclipse.project_files').inside('"
                    + ext.toString().replace("\\", "\\\\") + "')";
            waitUntil("Neovim to know the linked folder", TIMEOUT_MS,
                    () -> Boolean.TRUE.equals(SessionProbe.lua(session, inside)));

            session.inputAsync(":e " + ext.toString().replace("\\", "\\\\") + "<CR>");

            waitUntil("ext.txt's editor, on the linked workspace file", TIMEOUT_MS, () -> {
                IEditorPart active = page().getActiveEditor();
                return active != null && active.getEditorInput() instanceof IFileEditorInput input
                        && input.getFile().equals(linked);
            }, () -> "; active: " + (page().getActiveEditor() == null ? null : page().getActiveEditor().getEditorInput()));
        } finally {
            closeOtherEditors();
            if (folder.exists()) {
                folder.delete(true, null);
            }
            Files.deleteIfExists(via);
            Files.deleteIfExists(via.getParent());
            Files.deleteIfExists(ext);
            Files.deleteIfExists(real);
        }
    }

    /**
     * A project whose location Eclipse records through a link, reached by both spellings: {@code gf}
     * onto the link's spelling opens the file's editor, and {@code :e} by the real path then finds
     * that same editor and that same Neovim buffer, not a second of either. The manual test "A
     * workspace or project under a link" did this by hand. Common in practice: on macOS every folder
     * under {@code /tmp} or {@code /var} is really under {@code /private}; on Windows a profile folder
     * may be a junction.
     * Example: project {@code e2e-via} at {@code /tmp/n4e-viaN/p}, a link to {@code /tmp/n4e-realN};
     * {@code gf} onto {@code /tmp/n4e-viaN/p/b.txt}, then {@code :e /tmp/n4e-realN/b.txt}.
     */
    @Test
    void aProjectUnderALinkHasOneEditorAndOneBufferForBothPaths() throws Exception {
        Path real = Files.createTempDirectory("n4e-real");
        Path via = Files.createTempDirectory("n4e-via").resolve("p");
        Path a = real.resolve("a.txt");
        Path b = real.resolve("b.txt");
        IProject linked = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e-via");
        try {
            assumeTrue(makeLink(via, real), "could make neither a symbolic link nor a junction here");
            Files.writeString(a, via.resolve("b.txt") + "\n", StandardCharsets.UTF_8); // the link's spelling, for gf
            Files.writeString(b, "bee\n", StandardCharsets.UTF_8);
            IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(linked.getName());
            description.setLocation(IPath.fromOSString(via.toString()));
            linked.create(description, null);
            linked.open(null);
            IFile bFile = linked.getFile("b.txt");
            String inside = "return require('neovim4eclipse.project_files').inside(" + lua(b.toString()) + ")";
            waitUntil("Neovim to know the project under the link", TIMEOUT_MS,
                    () -> Boolean.TRUE.equals(SessionProbe.lua(session, inside)));
            ITextEditor aEditor = (ITextEditor) IDE.openEditor(page(), linked.getFile("a.txt"), TEXT_EDITOR, true);
            waitForAttach(aEditor, "a.txt of the project under the link");

            session.inputAsync("gg0gf");
            waitForActive(bFile, "b.txt's editor, by the link's spelling");

            session.inputAsync(":e " + b.toString().replace("\\", "\\\\") + "<CR>");
            UiTestSupport.settle(session, TIMEOUT_MS);
            waitForActive(bFile, "b.txt's editor still, by the real path");
            assertEquals(1, List.of(page().getEditorReferences()).stream()
                    .filter(r -> r.getTitle().equals("b.txt")).count(), "one editor on the file");
            assertEquals(1, ((Number) SessionProbe.lua(session, """
                    local want, n = vim.uv.fs_realpath(FILE), 0
                    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                      local name = vim.api.nvim_buf_get_name(buf)
                      if name ~= '' and vim.uv.fs_realpath(name) == want then n = n + 1 end
                    end
                    return n""".replace("FILE", lua(b.toString())))).intValue(), "one Neovim buffer for the file");
        } finally {
            closeOtherEditors();
            // Wipe the closed files' buffers, as the base class does for its own project's: one Neovim
            // serves every test.
            SessionProbe.lua(session, """
                    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                      local name = vim.uv.fs_realpath(vim.api.nvim_buf_get_name(buf))
                      if name and (name == vim.uv.fs_realpath(A) or name == vim.uv.fs_realpath(B)) then
                        pcall(vim.api.nvim_buf_delete, buf, { force = true })
                      end
                    end
                    return 0""".replace("A", lua(a.toString())).replace("B", lua(b.toString())));
            if (linked.exists()) {
                linked.delete(false, true, null); // keeps the files; deleted below
            }
            session.leaveFolder(via.toString(), TestWait.duration());
            Files.deleteIfExists(via);
            Files.deleteIfExists(via.getParent());
            leaveAndDelete(real, a, b, real.resolve(".project"));
        }
    }

    /** Waits until the active editor is the one on {@code file}. */
    private static void waitForActive(IFile file, String what) {
        waitUntil(what, TIMEOUT_MS, () -> {
            IEditorPart active = page().getActiveEditor();
            return active != null && active.getEditorInput() instanceof IFileEditorInput input
                    && input.getFile().equals(file);
        }, () -> "; active: " + (page().getActiveEditor() == null ? null : page().getActiveEditor().getEditorInput()));
    }

    /** {@code s} as a Lua string literal. */
    private static String lua(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
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

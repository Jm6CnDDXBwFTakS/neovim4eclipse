// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IURIEditorInput;
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
 * editor's own file under that name saves it. Also folder names with a space and a non-ASCII letter,
 * and Windows' other spellings of one file (lower-case drive, {@code /}, 8.3 short name).
 * All follow the one rule for paths ({@code util/FilePaths}, {@code NewFiles.workspaceFileAt});
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
     * {@code :e} of a file whose Eclipse editor is the system's program opens it in a text editor
     * inside Eclipse instead: running the program on it, as Eclipse's default lookup would, runs a
     * cloned repository's {@code build.bat} on Windows (0.2.1 security review). The file is set to
     * open with the system editor as a file's "Open With" choice would set it.
     */
    @Test
    void eNeverOpensAFileWithAProgramOutsideEclipse() throws Exception {
        IFile script = createFile("build.zzz", "echo hi\n");
        IDE.setDefaultEditor(script, IEditorRegistry.SYSTEM_EXTERNAL_EDITOR_ID);
        assertEquals(IEditorRegistry.SYSTEM_EXTERNAL_EDITOR_ID, IDE.getEditorDescriptor(script, true, false).getId(),
                "Eclipse itself would run the system's program on it");

        session.inputAsync(":e build.zzz<CR>");

        waitUntil("build.zzz in an editor inside Eclipse", TIMEOUT_MS, () -> {
            IEditorPart active = page().getActiveEditor();
            return active != null && active.getEditorInput() instanceof IFileEditorInput input
                    && input.getFile().equals(script) && EditorAttachManager.textEditorOf(active) != null;
        }, () -> "; active: " + (page().getActiveEditor() == null ? null : page().getActiveEditor().getEditorInput()));
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
            assertOneEditorAndOneBuffer("b.txt", b, "by the real path");
        } finally {
            closeOtherEditors();
            wipeBuffers(a, b);
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
        }, () -> "; active: " + describe(page().getActiveEditor()));
    }

    /** The editor's input, with its location when it is a file outside the workspace. */
    private static String describe(IEditorPart part) {
        if (part == null) {
            return "none";
        }
        return part.getEditorInput() instanceof IURIEditorInput uri && !(part.getEditorInput() instanceof IFileEditorInput)
                ? "a file outside the workspace, " + uri.getURI() : String.valueOf(part.getEditorInput());
    }

    /**
     * Waits until the active editor is the one on {@code file} and Neovim has attached to it. An editor
     * that {@code :e} opened gets its Neovim buffer only on that attach, a moment after it is active.
     */
    private void waitForActiveAndAttached(IFile file, String what) {
        waitForActive(file, what);
        waitForAttach((ITextEditor) page().getActiveEditor(), what);
    }

    /**
     * {@code file}'s 8.3 short name, as {@code dir /x} shows it, or {@code null} where the volume
     * makes none. Asked of {@code cmd}'s {@code %~sI}, with the path passed in an environment variable
     * so that a non-ASCII letter reaches {@code cmd} intact; Java has no API for it, and Neovim's
     * {@code fnamemodify(name, ':8')} gave the name back unchanged (Neovim 0.12.6, 2026-10-31).
     * Example: {@code C:\Users\me\AppData\Local\Temp\n4e path ü1\c.txt} gives
     * {@code C:\Users\me\AppData\Local\Temp\N4EPAT~1\c.txt}.
     */
    private static String shortName(Path file) throws IOException, InterruptedException {
        // Separate arguments with no spaces, so Java quotes none of them and cmd sees ("%N4E_PATH%").
        ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "for", "%I", "in", "(\"%N4E_PATH%\")", "do", "@echo", "%~sI");
        builder.environment().put("N4E_PATH", file.toString());
        Process cmd = builder.redirectErrorStream(true).start();
        String out = new String(cmd.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
        if (cmd.waitFor() != 0 || out.equals(file.toString()) || !out.chars().allMatch(ch -> ch > 32 && ch < 127 || ch == ' ')) {
            return null; // none, or a long name cmd could not print in ASCII
        }
        Path shortPath = Path.of(out);
        return Files.exists(shortPath) && Files.isSameFile(shortPath, file) ? out : null;
    }

    /** {@code s} as a Lua string literal. */
    private static String lua(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * {@code path} as typed after {@code :e}: each {@code \} doubled, as the other tests here do, and
     * each space escaped, since {@code :e} reads a space as the end of the name.
     * Example: {@code C:\Temp\n4e path ü1\c.txt} is typed {@code C:\\Temp\\n4e\ path\ ü1\\c.txt}.
     */
    private static String typed(String path) {
        return path.replace("\\", "\\\\").replace(" ", "\\ ");
    }

    /**
     * Asserts one editor titled {@code title} and one Neovim buffer for {@code file}: the buffers are
     * counted by the one rule for paths ({@code neovim4eclipse.paths}: links, case where the system
     * ignores it, separators), so a second buffer under another spelling of the same file counts too.
     */
    private void assertOneEditorAndOneBuffer(String title, Path file, String spelling) {
        assertEquals(1, List.of(page().getEditorReferences()).stream()
                .filter(r -> r.getTitle().equals(title)).count(), "one editor on the file, " + spelling);
        assertEquals(1, ((Number) SessionProbe.lua(session, """
                local paths = require('neovim4eclipse.paths')
                local rules, n = paths.rules(paths.current()), 0
                for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                  local name = vim.api.nvim_buf_get_name(buf)
                  if name ~= '' and rules.same(name, FILE) then n = n + 1 end
                end
                return n""".replace("FILE", lua(file.toString())))).intValue(),
                () -> "one Neovim buffer for the file, " + spelling + "; " + SessionProbe.lua(session, """
                        local out = { 'want ' .. tostring(vim.uv.fs_realpath(FILE)) .. ' (' .. FILE .. ')' }
                        for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                          local name = vim.api.nvim_buf_get_name(buf)
                          out[#out + 1] = buf .. ' ' .. name .. ' -> ' .. tostring(vim.uv.fs_realpath(name))
                        end
                        return table.concat(out, '; ')""".replace("FILE", lua(file.toString()))));
    }

    /**
     * Wipes the buffers of {@code files} (closed editors' files), as the base class does for its own
     * project's: one Neovim serves every test.
     */
    private void wipeBuffers(Path... files) {
        StringBuilder wanted = new StringBuilder();
        for (Path file : files) {
            wanted.append(lua(file.toString())).append(", ");
        }
        SessionProbe.lua(session, """
                local paths = require('neovim4eclipse.paths')
                local rules = paths.rules(paths.current())
                for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                  local name = vim.api.nvim_buf_get_name(buf)
                  for _, file in ipairs({ WANTED }) do
                    if name ~= '' and rules.same(name, file) then
                      pcall(vim.api.nvim_buf_delete, buf, { force = true })
                      break
                    end
                  end
                end
                return 0""".replace("WANTED", wanted));
    }

    /**
     * A project at {@code dir}, outside the workspace folder ({@code File → New → Project}, with
     * <i>Use default location</i> off), opened, once Neovim knows it, so that {@code :e} into it is
     * sent to Eclipse.
     */
    private IProject projectAt(String name, Path dir, Path someFile) throws Exception {
        IProject created = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(name);
        description.setLocation(IPath.fromOSString(dir.toString()));
        created.create(description, null);
        created.open(null);
        String inside = "return require('neovim4eclipse.project_files').inside(" + lua(someFile.toString()) + ")";
        waitUntil("Neovim to know the project " + name, TIMEOUT_MS,
                () -> Boolean.TRUE.equals(SessionProbe.lua(session, inside)));
        return created;
    }

    /** Undoes {@link #projectAt}: closes the editors, wipes the buffers, deletes project and files. */
    private void removeProject(IProject created, Path dir, Path... files) throws Exception {
        closeOtherEditors();
        wipeBuffers(files);
        if (created.exists()) {
            created.delete(false, true, null); // keeps the files; deleted below
        }
        Path[] contents = Arrays.copyOf(files, files.length + 1);
        contents[files.length] = dir.resolve(".project");
        leaveAndDelete(dir, contents);
    }

    /**
     * A project in a folder whose name has a space and a non-ASCII letter: {@code :e} of a file's full
     * path opens its workspace editor, once, with one Neovim buffer. The manual test "Windows path
     * spellings" did this by hand; it runs on every system, since each spells such names its own way:
     * on macOS APFS and HFS+ may give back {@code ü} decomposed (u and a combining mark, NFD) where
     * Java and Neovim spelled it composed (NFC).
     * Example: project {@code e2e-spelled} at {@code C:\Users\me\AppData\Local\Temp\n4e path ü123};
     * {@code :e C:\...\n4e path ü123\c.txt}.
     */
    @Test
    void aFolderNameWithASpaceAndANonAsciiLetterOpensTheWorkspaceFile() throws Exception {
        Path dir = Files.createTempDirectory("n4e path \u00fc");
        Path c = dir.resolve("c.txt");
        Files.writeString(c, "sea\n", StandardCharsets.UTF_8);
        IProject spelled = null;
        try {
            spelled = projectAt("e2e-spelled", dir, c);
            IFile cFile = spelled.getFile("c.txt");

            session.inputAsync(":e " + typed(c.toString()) + "<CR>");

            waitForActiveAndAttached(cFile, "c.txt's editor, on the workspace file");
            assertOneEditorAndOneBuffer("c.txt", c, "by its full path");
        } finally {
            if (spelled != null) {
                removeProject(spelled, dir, c);
            } else {
                leaveAndDelete(dir, c);
            }
        }
    }

    /**
     * The other ways Windows spells one file, each reaching the same editor and the same Neovim
     * buffer: the path as given, its drive letter in lower case, {@code /} for {@code \}, and its 8.3
     * short name ({@code dir /x} shows them). Before each, the base class's editor is made active, so
     * every spelling must find the file's editor again rather than leave it active. Windows only
     * (skipped elsewhere, by assumption: the fragment imports no {@code junit.jupiter.api.condition}
     * for {@code @EnabledOnOs}). Where the volume makes no 8.3 names, that spelling is left out with a
     * note on standard output. The rule these follow has its cases in {@code path-cases.tsv}; this
     * test runs them on a real disk and through Eclipse's lookup ({@code NewFiles.workspaceFileAt}).
     * Example spellings: {@code C:\Users\me\...\n4e path ü123\c.txt}, {@code c:\Users\...},
     * {@code C:/Users/me/...}, {@code C:\Users\me\AppData\Local\Temp\N4EPAT~1\c.txt}.
     */
    @Test
    void eachWindowsSpellingOfAFileFindsItsOneEditorAndBuffer() throws Exception {
        assumeTrue(FilePaths.Platform.current().windows(), "Windows spellings");
        Path dir = Files.createTempDirectory("n4e path \u00fc");
        Path c = dir.resolve("c.txt");
        Files.writeString(c, "sea\n", StandardCharsets.UTF_8);
        IProject spelled = null;
        try {
            spelled = projectAt("e2e-spelled-win", dir, c);
            IFile cFile = spelled.getFile("c.txt");
            String given = c.toString();
            assertEquals(true, given.matches("^[A-Z]:\\\\.*"), "a path with an upper-case drive: " + given);
            String shortName = shortName(c);
            List<String[]> spellings = new ArrayList<>(List.of(
                    new String[] { "as given", given },
                    new String[] { "lower-case drive", Character.toLowerCase(given.charAt(0)) + given.substring(1) },
                    new String[] { "/ separators", given.replace('\\', '/') }));
            if (shortName == null) {
                System.out.println("FilePathsEndToEndTest: no 8.3 short name for " + given + ", that spelling left out");
            } else {
                spellings.add(new String[] { "8.3 short name", shortName });
            }

            for (String[] spelling : spellings) {
                page().activate(editor);
                waitForAttach(editor, "the base editor, before the " + spelling[0] + " spelling");

                session.inputAsync(":e " + typed(spelling[1]) + "<CR>");

                waitForActiveAndAttached(cFile, "c.txt's editor, " + spelling[0] + ": " + spelling[1]);
                assertOneEditorAndOneBuffer("c.txt", c, spelling[0] + ": " + spelling[1]);
            }
        } finally {
            if (spelled != null) {
                removeProject(spelled, dir, c);
            } else {
                leaveAndDelete(dir, c);
            }
        }
    }

    /**
     * Where file names ignore case (macOS, Windows), another case of an open file's name reaches its
     * editor: {@code :e B.TXT} goes to b.txt's editor, with no second editor or buffer, and after a
     * change {@code :wq! B.TXT} saves the editor and closes it, as {@code :wq} would (a copy onto its
     * own file would leave it unsaved). Skipped where names keep case (Linux), as the file system
     * tells. The manual tests "An open file spelled another way" and ":wq! under another spelling of
     * the editor's own file".
     */
    @Test
    void anotherCaseOfAnOpenFilesNameReachesItsEditorWhereNamesIgnoreCase() throws Exception {
        ITextEditor b = openFile("b.txt", "one\n");
        IFile bFile = ((IFileEditorInput) b.getEditorInput()).getFile();
        Path real = Path.of(bFile.getLocation().toOSString());
        assumeTrue(Files.exists(real.resolveSibling("B.TXT")), "file names keep their case here");
        page().activate(editor);
        waitForAttach(editor, "test.txt");

        session.inputAsync(":e B.TXT<CR>");

        waitForActiveAndAttached(bFile, "b.txt's editor, by B.TXT");
        assertOneEditorAndOneBuffer("b.txt", real, "as B.TXT");

        typeSlowly(widgetOf(b), "Atwo");
        UiTestSupport.escape(widgetOf(b));
        settle();
        session.inputAsync(":wq! B.TXT<CR>");

        waitUntil("the editor closed", TIMEOUT_MS, () -> page().findEditor(b.getEditorInput()) == null);
        assertEquals("onetwo\n", Files.readString(real, StandardCharsets.UTF_8));
    }

    /**
     * A new file under folders that do not exist yet, in a project whose location goes through a
     * link: {@code :e new/deep/c.txt} opens an empty editor, and {@code :w ++p} makes the folders
     * and the file, which the editor then shows as the project's file (one file on disk, reached by
     * both paths). The manual test "A name under folders that do not exist yet".
     * Example: project at {@code /tmp/n4e-viaN/p}, a link to {@code /tmp/n4e-realN}; the file is
     * written at {@code /tmp/n4e-realN/new/deep/c.txt}.
     */
    @Test
    void aNewFileUnderMissingFoldersInAProjectUnderALink() throws Exception {
        Path real = Files.createTempDirectory("n4e-real");
        Path via = Files.createTempDirectory("n4e-via").resolve("p");
        Path a = real.resolve("a.txt");
        Path c = real.resolve("new").resolve("deep").resolve("c.txt");
        IProject linked = null;
        try {
            assumeTrue(makeLink(via, real), "could make neither a symbolic link nor a junction here");
            Files.writeString(a, "a\n", StandardCharsets.UTF_8);
            linked = projectAt("e2e-via-new", via, a);
            ITextEditor aEditor = (ITextEditor) IDE.openEditor(page(), linked.getFile("a.txt"), TEXT_EDITOR, true);
            waitForAttach(aEditor, "a.txt of the project under the link");

            session.inputAsync(":e new/deep/c.txt<CR>");

            // A file not there yet opens by its location, and its first write makes it the
            // workspace file's editor (NewFiles): the project is found then, through the link.
            waitUntil("the new c.txt's editor", TIMEOUT_MS, () -> page().getActiveEditor() != null
                    && "c.txt".equals(page().getActiveEditor().getTitle()), () -> "; active: " + describe(page().getActiveEditor()));
            ITextEditor cEditor = (ITextEditor) page().getActiveEditor();
            waitForAttach(cEditor, "the new c.txt");
            assertEquals("", documentOf(cEditor).get(), "a new, empty editor");
            typeSlowly(widgetOf(cEditor), "isea");
            UiTestSupport.escape(widgetOf(cEditor));
            settle();
            session.inputAsync(":w ++p<CR>");

            IFile cFile = linked.getFile("new/deep/c.txt");
            waitForActive(cFile, "c.txt's editor on the project's file, after the write");
            assertEquals("sea", Files.readString(c, StandardCharsets.UTF_8));
            assertTrue(cFile.exists(), "the workspace knows the file, in the project");
            assertFalse(((ITextEditor) page().getActiveEditor()).isDirty(), "saved");
        } finally {
            if (linked != null) {
                removeProject(linked, real, c, c.getParent(), c.getParent().getParent(), a);
            } else {
                Files.deleteIfExists(real);
            }
            session.leaveFolder(via.toString(), TestWait.duration());
            Files.deleteIfExists(via);
            Files.deleteIfExists(via.getParent());
        }
    }

    /**
     * Deleting a project whose location is a junction, contents and all, while one of its files is
     * open (Windows): Neovim's window works in the project's folder, and Windows deletes no folder a
     * process works in, so Neovim first leaves it ({@code ProjectFolderExit}), recognizing the
     * folder through the junction. The delete then finishes, and Neovim goes on in the other
     * editors. Windows only (junctions are Windows'; the Lua side, links included, is
     * folders_spec.lua). The manual test "Deleting a project reached through a junction".
     */
    @Test
    void aProjectReachedThroughAJunctionIsDeletedWithItsFileOpen() throws Exception {
        assumeTrue(FilePaths.Platform.current().windows(), "a Windows junction");
        Path real = Files.createTempDirectory("n4e-real");
        Path via = Files.createTempDirectory("n4e-via").resolve("p");
        Path d = real.resolve("d.txt");
        Files.writeString(d, "dee\n", StandardCharsets.UTF_8);
        IProject linked = null;
        try {
            assumeTrue(junction(via, real), "could not make a junction");
            linked = projectAt("e2e-junction", via, d);
            ITextEditor dEditor = (ITextEditor) IDE.openEditor(page(), linked.getFile("d.txt"), TEXT_EDITOR, true);
            waitForAttach(dEditor, "d.txt through the junction");

            linked.delete(true, true, null); // contents too: through the junction, real's files

            assertFalse(linked.exists(), "the project is gone");
            assertFalse(Files.exists(d), "its file deleted");
            waitUntil("test.txt active again", TIMEOUT_MS, () -> page().getActiveEditor() == editor);
            waitForAttach(editor, "test.txt after the delete");
            assertTrue(session.isAlive(), "Neovim goes on");
        } finally {
            closeOtherEditors();
            wipeBuffers(d);
            if (linked != null && linked.exists()) {
                linked.delete(false, true, null);
            }
            session.leaveFolder(via.toString(), TestWait.duration());
            Files.deleteIfExists(via);
            Files.deleteIfExists(via.getParent());
            leaveAndDelete(real, d, real.resolve(".project"));
        }
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

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * {@link FileRename}'s rules for {@code :file name}, in the order the class lists them, with the
 * paths of a project {@code /ws/app} holding {@code notes.txt}.
 */
class FileRenameTest {

    /** Linux's rule: case counts, nothing to resolve. */
    private static final FilePaths.Platform LINUX = new FilePaths.Platform(false, false, p -> p);
    /** macOS's rule for the case-only rename: case ignored. */
    private static final FilePaths.Platform MAC = new FilePaths.Platform(false, true, p -> p);

    private static final Path PROJECT = Path.of("/ws/app");
    private static final Path FILE = Path.of("/ws/app/notes.txt");
    /** Where the target's folder and the project end once links are resolved: here, where they are written. */
    private static final Path REAL_FOLDER = Path.of("/ws/app/docs");
    private static final Path REAL_PROJECT = PROJECT;

    /** Renaming the clean project file to {@code target}, which resolves into the same project. */
    private static FileRename to(String target, boolean exists, boolean folderExists) {
        return FileRename.decide(new FileRename.Facts(FILE, PROJECT, false, Path.of(target), PROJECT, exists, folderExists, REAL_FOLDER, REAL_PROJECT),
                LINUX);
    }

    @Test
    void aNameInTheSameFolderIsARename() {
        assertEquals(new FileRename(FileRename.Action.RENAME, "todo.txt", null, null), to("/ws/app/todo.txt", false, true));
    }

    @Test
    void aNameInAnotherFolderOfTheProjectIsAMove() {
        assertEquals(new FileRename(FileRename.Action.MOVE, "todo.txt", Path.of("/ws/app/docs"), null),
                to("/ws/app/docs/todo.txt", false, true));
        assertEquals(new FileRename(FileRename.Action.MOVE, "notes.txt", Path.of("/ws/app/docs"), null),
                to("/ws/app/docs/notes.txt", false, true), "the same name, another folder");
    }

    @Test
    void theFilesOwnNameDoesNothing() {
        assertEquals(FileRename.Action.NOTHING, to("/ws/app/notes.txt", true, true).action());
    }

    /** On a system that ignores case, only the case changes: a rename, the one way to change the spelling. */
    @Test
    void onlyTheCaseIsARenameWhereCaseIsIgnored() {
        FileRename rename = FileRename.decide(new FileRename.Facts(FILE, PROJECT, false, Path.of("/ws/app/Notes.txt"),
                PROJECT, true, true, REAL_FOLDER, REAL_PROJECT), MAC);
        assertEquals(new FileRename(FileRename.Action.RENAME, "Notes.txt", null, null), rename);
    }

    @Test
    void withoutAProjectFileThereIsNothingToRename() {
        assertEquals(FileRename.NOT_A_PROJECT_FILE, FileRename.decide(new FileRename.Facts(null, null, false,
                Path.of("/ws/app/x.txt"), PROJECT, false, true, REAL_FOLDER, REAL_PROJECT), LINUX).message(), "an untitled editor");
        assertEquals(FileRename.NOT_A_PROJECT_FILE, FileRename.decide(new FileRename.Facts(Path.of("/tmp/a.txt"), null,
                false, Path.of("/tmp/b.txt"), null, false, true, REAL_FOLDER, REAL_PROJECT), LINUX).message(), "a file outside the workspace");
    }

    @Test
    void aJavaFileIsLeftToEclipsesJavaRename() {
        for (String name : new String[] {"Foo.java", "Foo.JAVA"}) {
            Path java = Path.of("/ws/app/src/p/" + name);
            FileRename rename = FileRename.decide(new FileRename.Facts(java, PROJECT, false, Path.of("/ws/app/src/p/Bar.java"),
                    PROJECT, false, true, REAL_FOLDER, REAL_PROJECT), LINUX);
            assertEquals(new FileRename(FileRename.Action.REFUSE, null, null, FileRename.JAVA), rename, name);
        }
    }

    @Test
    void unsavedChangesAreSavedFirst() {
        assertEquals(FileRename.UNSAVED, FileRename.decide(new FileRename.Facts(FILE, PROJECT, true,
                Path.of("/ws/app/todo.txt"), PROJECT, false, true, REAL_FOLDER, REAL_PROJECT), LINUX).message());
    }

    @Test
    void theNewNameStaysInTheProject() {
        assertEquals(FileRename.OTHER_PROJECT, FileRename.decide(new FileRename.Facts(FILE, PROJECT, false,
                Path.of("/ws/other/todo.txt"), Path.of("/ws/other"), false, true, REAL_FOLDER, REAL_PROJECT), LINUX).message(), "another project");
        assertEquals(FileRename.OTHER_PROJECT, FileRename.decide(new FileRename.Facts(FILE, PROJECT, false,
                Path.of("/tmp/todo.txt"), null, false, true, REAL_FOLDER, REAL_PROJECT), LINUX).message(), "outside the workspace");
    }

    /**
     * Where the path ends decides: a folder of the project that is a link to another place (a
     * cloned repository's {@code docs -> ../../elsewhere}) resolves outside the project, and the
     * move is refused, whatever the path looked like.
     */
    @Test
    void aFolderThatResolvesOutsideTheProjectIsRefused() {
        FileRename rename = FileRename.decide(new FileRename.Facts(FILE, PROJECT, false, Path.of("/ws/app/docs/todo.txt"),
                PROJECT, false, true, Path.of("/home/you/.ssh"), REAL_PROJECT), LINUX);
        assertEquals(FileRename.OTHER_PROJECT, rename.message());
    }

    /** A name that only differs in case is a rename only in the same folder; anywhere else an existing name is refused. */
    @Test
    void theCaseExceptionIsOnlyForTheSameFolder() {
        FileRename elsewhere = FileRename.decide(new FileRename.Facts(FILE, PROJECT, false, Path.of("/ws/app/docs/NOTES.txt"),
                PROJECT, true, true, REAL_FOLDER, REAL_PROJECT), MAC);
        assertEquals("file: \"NOTES.txt\" exists already", elsewhere.message());
    }

    @Test
    void nothingIsOverwrittenAndNoFolderIsMade() {
        assertEquals("file: \"todo.txt\" exists already", to("/ws/app/todo.txt", true, true).message());
        assertEquals(FileRename.NO_FOLDER, to("/ws/app/missing/todo.txt", false, false).message());
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;

/**
 * What {@code :file name} does to an editor's file, decided from facts the editor side gathers
 * ({@code editor/FileRenames}), so that every rule is checked here without Eclipse
 * ({@code FileRenameTest}).
 *
 * <p>{@code :file name} renames the file the editor shows, or moves it to another folder of the
 * same project, with Eclipse's refactoring. Not Vim's meaning, which only renames the buffer and
 * leaves the file where it is: in an IDE, a file is renamed in place. Example: in
 * {@code /ws/app/notes.txt}, {@code :file todo.txt} renames it to {@code /ws/app/todo.txt};
 * {@code :file docs/notes.txt} moves it into {@code /ws/app/docs}.
 *
 * <h2>The rules, in order</h2>
 * <ol>
 *   <li>Only a file of an open project: an untitled editor, a file outside the workspace, a page
 *       has no project file for Eclipse to rename ({@code :saveas} or {@code :w name} write one
 *       under a new name). Not a linked file either, nor one in a linked folder ({@link #LINKED},
 *       checked by the editor side): Eclipse would rename the link, or write in the folder the
 *       link points to.</li>
 *   <li>The same name: nothing to do, as Vim's {@code :file} of the buffer's own name. The same name
 *       in another case, in the same folder, on a system that ignores case: a rename, the one way
 *       to change the spelling.</li>
 *   <li>Not a Java file: its class and every reference to it would keep the old name. Eclipse's
 *       Java Rename does it right, which {@code grn} on the class name runs.</li>
 *   <li>Not while the editor has unsaved changes: the refactoring works on the files, and the
 *       changes would have to be saved or carried along on the way.</li>
 *   <li>Where the path ends decides, by an allow-list rather than a list of bad cases: the new
 *       file's folder, with every link resolved ({@code FilePaths.resolved}), must be the project's
 *       own folder (resolved too) or inside it, so no link, junction, {@code ..} or linked folder
 *       can take the file out of it (a move into a linked folder is refused, the cost); it must
 *       also be a workspace file of that project, for Eclipse to move it there; nothing at all may
 *       exist under the new name, a link included, even one that points nowhere; and the folder
 *       must exist.</li>
 *   <li>In the file's own folder it is a rename, in another folder a move with that name.</li>
 * </ol>
 *
 * @param action  what to do
 * @param name    the new file name (no folder) for {@link Action#RENAME} and {@link Action#MOVE}
 * @param folder  the folder to move into for {@link Action#MOVE}
 * @param message why not, for {@link Action#REFUSE}; shown as Neovim shows an error
 */
public record FileRename(Action action, String name, Path folder, String message) {

    /** What {@code :file name} does. */
    public enum Action {
        /** Nothing: the file already has that name. */
        NOTHING,
        /** Rename the file in its folder. */
        RENAME,
        /** Move the file to another folder of its project, under {@link FileRename#name}. */
        MOVE,
        /** Do nothing, and say why ({@link FileRename#message}). */
        REFUSE
    }

    /** No project file to rename: an untitled editor, a file outside the workspace. */
    public static final String NOT_A_PROJECT_FILE =
            "file: only a project's file can be renamed; :saveas writes this text under another name";
    /** A linked file: Eclipse would rename or move the link, not the file it points to. */
    public static final String LINKED = "file: this file is linked into the project (itself or a folder above it);"
            + " rename it where it is stored";
    /** A Java file. */
    public static final String JAVA =
            "file: rename a Java file with grn on its class name, which renames the class and its references too";
    /** Unsaved changes. */
    public static final String UNSAVED = "file: save the changes first (:w)";
    /** Another project, or outside the workspace. */
    public static final String OTHER_PROJECT = "file: the new name must be in this file's project";
    /** The folder is missing. */
    public static final String NO_FOLDER = "file: the folder does not exist";

    /**
     * The facts {@link #decide} needs. Paths are full; {@code null} where there is none.
     *
     * @param file          the editor's file, or {@code null} if it shows none on disk
     * @param fileProject   the folder of the project holding {@code file}, or {@code null} if it is in none
     * @param dirty         whether the editor has unsaved changes
     * @param target        the new name, a full path (Neovim expanded it as for {@code :e})
     * @param targetProject the folder of the open project the target resolves into, or {@code null}
     * @param targetExists  whether anything exists at the target already, a link included (not followed)
     * @param folderExists  whether the target's folder exists
     * @param realFolder    the target's folder with every link resolved, or {@code null} if unknown
     * @param realProject   the project's folder with every link resolved, or {@code null} if unknown
     */
    public record Facts(Path file, Path fileProject, boolean dirty, Path target, Path targetProject,
            boolean targetExists, boolean folderExists, Path realFolder, Path realProject) {
    }

    /** What {@code :file name} does with {@code facts}, under this machine's path rule; see the class comment. */
    public static FileRename decide(Facts facts) {
        return decide(facts, FilePaths.Platform.current());
    }

    /**
     * {@link #decide(Facts)} under {@code platform}'s path rule ({@link FilePaths}), so that a test
     * on any system checks the case-only rename of a system that ignores case.
     */
    static FileRename decide(Facts facts, FilePaths.Platform platform) {
        Path file = facts.file();
        Path target = facts.target();
        if (file == null || facts.fileProject() == null || target == null || target.getFileName() == null) {
            return refuse(NOT_A_PROJECT_FILE);
        }
        String newName = target.getFileName().toString();
        String oldName = file.getFileName() == null ? "" : file.getFileName().toString();
        boolean sameFolder = target.getParent() != null && file.getParent() != null
                && same(target.getParent(), file.getParent(), platform);
        if (sameFolder && oldName.equals(newName)) {
            return new FileRename(Action.NOTHING, null, null, null);
        }
        // Only the case differs, where case is ignored: the file itself is "there" under the new name.
        boolean caseOnly = sameFolder && platform.ignoreCase() && oldName.equalsIgnoreCase(newName);
        if (isJava(file)) {
            return refuse(JAVA);
        }
        if (facts.dirty()) {
            return refuse(UNSAVED);
        }
        if (facts.targetProject() == null || !same(facts.targetProject(), facts.fileProject(), platform)
                || facts.realFolder() == null || facts.realProject() == null
                || !FilePaths.inside(facts.realFolder().toString(), facts.realProject().toString(), platform)) {
            return refuse(OTHER_PROJECT);
        }
        if (facts.targetExists() && !caseOnly) {
            return refuse("file: \"" + newName + "\" exists already");
        }
        if (!facts.folderExists()) {
            return refuse(NO_FOLDER);
        }
        if (sameFolder) {
            return new FileRename(Action.RENAME, newName, null, null);
        }
        return new FileRename(Action.MOVE, newName, target.getParent(), null);
    }

    /** A Java source file, by its extension in any case ({@code Foo.java}, {@code Foo.JAVA}). */
    static boolean isJava(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }

    private static boolean same(Path a, Path b, FilePaths.Platform platform) {
        return FilePaths.same(a.toString(), b.toString(), platform);
    }

    private static FileRename refuse(String message) {
        return new FileRename(Action.REFUSE, null, null, message);
    }
}

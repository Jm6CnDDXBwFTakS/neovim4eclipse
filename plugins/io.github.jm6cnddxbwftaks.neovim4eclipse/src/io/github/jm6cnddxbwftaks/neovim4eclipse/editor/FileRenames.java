// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.CreateChangeOperation;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.MoveRenameResourceDescriptor;
import org.eclipse.ltk.core.refactoring.resource.RenameResourceDescriptor;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.IProgressService;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EditPath;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FileRename;

/**
 * {@code :file name}: renames the editor's file, or moves it to another folder of its project,
 * with Eclipse's resource refactoring (the Rename and Move of the Project Explorer, without their
 * dialogs). What is allowed is {@link FileRename}'s decision; this class gathers its facts and
 * carries it out. Example: {@code :file todo.txt} in {@code app/notes.txt} renames it to
 * {@code app/todo.txt}; {@code :file docs/notes.txt} moves it into {@code app/docs}.
 *
 * <p><b>The editor follows by itself.</b> Eclipse moves an open editor along with its file, and the
 * plug-in keeps its Neovim buffer, with its undo history and marks, under the new name
 * ({@code EditorAttachManager.onInputChanged}). Nothing is saved: a rename needs no unsaved changes,
 * which {@link FileRename} refuses.
 *
 * <p><b>Undo.</b> The refactoring goes on Eclipse's refactoring undo stack
 * ({@link PerformChangeOperation#setUndoManager} records it), so <i>Edit &gt; Undo</i> in the Project Explorer
 * puts the file back. In the editor, {@code u} and Ctrl+Z undo text, as before.
 *
 * <p><b>Why only the resource refactoring.</b> It renames and moves files, nothing inside them: a
 * Java file keeps its class name and package, and references to it break (seen with JDT
 * installed). So {@link FileRename} refuses Java files and points to Eclipse's Java Rename
 * ({@code grn}), and the plug-in needs no Java tooling. UI thread: the refactoring works on the
 * workspace's files only, and Neovim is not asked anything; it runs through the progress service,
 * as Eclipse's own Rename does, so a build holding the workspace shows a wait, not a freeze.
 */
final class FileRenames {

    private static final Logger LOG = Logger.getLogger(FileRenames.class.getName());

    private FileRenames() {
    }

    /** Renames or moves {@code editor}'s file to {@code argument} (a full path, from Neovim), or says why not. */
    static void rename(ITextEditor editor, String argument) {
        Path home = Path.of(System.getProperty("user.home"));
        Path folder = EclipseFileCommands.windowFolder(editor);
        Path target = EditPath.resolve(argument, home, folder);
        if (target == null) {
            HostCommands.showError(editor, "file: no such name \"" + argument + "\"");
            return;
        }
        IFile file = editor.getEditorInput() == null ? null : editor.getEditorInput().getAdapter(IFile.class);
        if (file != null && file.isLinked(IResource.CHECK_ANCESTORS)) {
            // Eclipse would rename or move the link in the project, not the file it points to;
            // in a linked folder, it would work in the folder the link points to.
            HostCommands.showError(editor, FileRename.LINKED);
            return;
        }
        IPath fileLocation = file == null ? null : file.getLocation();
        IPath projectLocation = file == null ? null : file.getProject().getLocation();
        IFile targetFile = NewFiles.workspaceFileAt(target);
        IPath targetProject = targetFile == null ? null : targetFile.getProject().getLocation();
        boolean dirty = EditorAttachManager.getInstance().workbenchPartOf(editor).isDirty();
        boolean folderExists = targetFile != null ? targetFile.getParent().exists()
                : target.getParent() != null && Files.isDirectory(target.getParent());
        // Anything under the new name counts, a link too, even one pointing nowhere (not followed).
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS) || targetFile != null && targetFile.exists();
        FileRename decision = FileRename.decide(new FileRename.Facts(
                fileLocation == null ? null : fileLocation.toPath(),
                projectLocation == null ? null : projectLocation.toPath(),
                dirty, target,
                targetProject == null ? null : targetProject.toPath(),
                exists, folderExists,
                // Where Eclipse will write: the folder's own location, which for a linked folder is
                // its target, not the path as written (null for a virtual folder: refused).
                realFolderOf(targetFile),
                projectLocation == null ? null : FilePaths.resolved(projectLocation.toPath())));
        switch (decision.action()) {
            case NOTHING -> {
                // As Vim's :file of the buffer's own name: nothing changes, nothing is said.
            }
            case REFUSE -> HostCommands.showError(editor, decision.message());
            case RENAME -> run(editor, renaming(file, decision.name()), EditPath.shown(target, home, folder), "renamed");
            case MOVE -> run(editor, moving(file, targetFile, decision.name()), EditPath.shown(target, home, folder), "moved");
        }
    }

    /** {@code target}'s folder as Eclipse writes there, links resolved; {@code null} if it has no location. */
    private static Path realFolderOf(IFile target) {
        if (target == null || target.getParent().getLocation() == null) {
            return null;
        }
        return FilePaths.resolved(target.getParent().getLocation().toPath());
    }

    /** Eclipse's Rename of {@code file} to {@code name}, in its folder. */
    private static RefactoringDescriptor renaming(IFile file, String name) {
        RenameResourceDescriptor descriptor = (RenameResourceDescriptor) contribution(RenameResourceDescriptor.ID).createDescriptor();
        descriptor.setProject(file.getProject().getName());
        descriptor.setResourcePath(file.getFullPath());
        descriptor.setNewName(name);
        return descriptor;
    }

    /** Eclipse's Move of {@code file} into {@code target}'s folder, renamed to {@code name} on the way. */
    private static RefactoringDescriptor moving(IFile file, IFile target, String name) {
        MoveRenameResourceDescriptor descriptor =
                (MoveRenameResourceDescriptor) contribution(MoveRenameResourceDescriptor.ID).createDescriptor();
        descriptor.setProject(file.getProject().getName());
        descriptor.setResourcePath(file.getFullPath());
        descriptor.setDestinationPath(target.getParent().getFullPath());
        descriptor.setNewName(name);
        return descriptor;
    }

    private static RefactoringContribution contribution(String id) {
        RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(id);
        if (contribution == null) {
            throw new IllegalStateException("Eclipse has no refactoring " + id);
        }
        return contribution;
    }

    /**
     * Runs the refactoring in the workspace (one operation: the editor moves once), and shows the
     * result as Neovim shows a message: the new name, or Eclipse's reason as an error.
     */
    private static void run(ITextEditor editor, RefactoringDescriptor descriptor, String shown, String done) {
        try {
            RefactoringStatus created = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(created);
            if (refactoring == null || created.hasFatalError()) {
                HostCommands.showError(editor, "file: " + reason(created, "Eclipse cannot rename this file"));
                return;
            }
            // The conditions checked first, and no change made unless they pass below ERROR:
            // Eclipse's PerformRefactoringOperation stops only at FATAL, so a check that says ERROR
            // (a read-only file, a participant's objection) would still move the file. The change
            // is recorded for Edit > Undo.
            CreateChangeOperation create = new CreateChangeOperation(
                    new CheckConditionsOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS), RefactoringStatus.ERROR);
            PerformChangeOperation perform = new PerformChangeOperation(create);
            perform.setUndoManager(RefactoringCore.getUndoManager(), refactoring.getName());
            // As Eclipse's own Rename runs it: through the progress service, with the workspace's
            // rule, so that while a build holds the workspace Eclipse shows that it waits (and lets
            // it be cancelled) instead of freezing.
            IProgressService progress = PlatformUI.getWorkbench().getProgressService();
            progress.runInUI(progress, monitor -> {
                try {
                    ResourcesPlugin.getWorkspace().run(perform, monitor);
                } catch (CoreException e) {
                    throw new InvocationTargetException(e);
                }
            }, ResourcesPlugin.getWorkspace().getRoot());
            // What happened first: once the change ran, the file moved, whatever a status says.
            if (!perform.changeExecuted()) {
                RefactoringStatus checked = create.getConditionCheckingStatus();
                RefactoringStatus validated = perform.getValidationStatus();
                RefactoringStatus why = checked != null && checked.getSeverity() >= RefactoringStatus.ERROR ? checked
                        : validated != null && validated.getSeverity() >= RefactoringStatus.ERROR ? validated : null;
                HostCommands.showError(editor, "file: " + (why == null ? "Eclipse did not carry it out" : reason(why, "Eclipse refused it")));
                return;
            }
            HostCommands.showMessage(editor, "\"" + shown + "\" (" + done + ")", false);
        } catch (InterruptedException e) {
            HostCommands.showError(editor, "file: cancelled");
        } catch (CoreException | InvocationTargetException | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            LOG.log(Level.WARNING, "Renaming " + editor.getTitle() + " to " + shown + " failed", cause);
            String why = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            HostCommands.showError(editor, "file: it failed: " + why + " (see the Error Log)");
        }
    }

    /** The first message of {@code status} at its own severity, or {@code fallback}. */
    private static String reason(RefactoringStatus status, String fallback) {
        String message = status.getMessageMatchingSeverity(status.getSeverity());
        return message == null || message.isBlank() || status.getSeverity() < RefactoringStatus.WARNING ? fallback : message;
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The folders Neovim is told about ({@link ProjectFolders}), walked again only for the projects a
 * workspace change touched: after each change the folders are the same as a walk of every project
 * would give, and a change to one project walks at most that one. No Neovim: the folders are asked
 * for directly. That Neovim gets them is {@code ProjectFilesEndToEndTest}.
 */
class ProjectFoldersTest {

    private static final int PROJECTS = 4;

    private final List<IProject> projects = new ArrayList<>();
    private ProjectFolders listener;
    private Path outside;

    @BeforeEach
    void create() throws Exception {
        // A listener of the test's own, so that changes are marked whether the plug-in's runs or not.
        listener = ProjectFolders.install(() -> null);
        for (int i = 0; i < PROJECTS; i++) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("project-folders-" + i);
            project.create(null);
            project.open(null);
            project.getFolder("deep").create(true, true, null);
            projects.add(project);
        }
        outside = Files.createTempDirectory("project-folders-link").toRealPath();
        ProjectFolders.currentAfterFullWalk();
    }

    @AfterEach
    void delete() throws Exception {
        ProjectFolders.uninstall(listener);
        for (IProject project : projects) {
            project.delete(true, true, null);
        }
        Files.deleteIfExists(outside);
        ProjectFolders.currentAfterFullWalk(); // the next test starts from the workspace as it is
    }

    /** The folders now, made the new way, after checking they are what a walk of everything gives. */
    private List<String> currentWalkingAtMost(int walks) {
        int before = ProjectFolders.walks();
        List<String> folders = ProjectFolders.current();
        int made = ProjectFolders.walks() - before;
        assertTrue(made <= walks, made + " projects walked, " + walks + " expected at most");
        assertEquals(ProjectFolders.currentAfterFullWalk(), folders, "the same as a walk of every project");
        return folders;
    }

    private static String location(IProject project) {
        return project.getLocation().toOSString();
    }

    @Test
    void nothingChangedWalksNothing() {
        List<String> folders = currentWalkingAtMost(0);
        for (IProject project : projects) {
            assertTrue(folders.contains(location(project)), folders.toString());
        }
    }

    @Test
    void aLinkedFolderMadeAndRemovedIsSeenWalkingOnlyItsProject() throws CoreException {
        IFolder link = projects.get(1).getFolder("deep").getFolder("ext");
        link.createLink(IPath.fromOSString(outside.toString()), IResource.NONE, null);
        assertTrue(currentWalkingAtMost(1).contains(outside.toString()), "the link's target is sent");

        link.delete(true, null);
        assertFalse(currentWalkingAtMost(1).contains(outside.toString()), "and no longer once removed");
    }

    @Test
    void aProjectClosedAndOpenedIsSeenWalkingOnlyIt() throws CoreException {
        IProject project = projects.get(2);
        project.close(null);
        assertFalse(currentWalkingAtMost(0).contains(location(project)), "a closed project is left out");

        project.open(null);
        assertTrue(currentWalkingAtMost(1).contains(location(project)));
    }

    @Test
    void aRenamedProjectIsSeenUnderItsNewLocation() throws CoreException {
        IProject project = projects.get(3);
        String old = location(project);
        project.move(IPath.fromOSString("project-folders-renamed"), true, null);
        IProject renamed = ResourcesPlugin.getWorkspace().getRoot().getProject("project-folders-renamed");
        projects.set(3, renamed);
        List<String> folders = currentWalkingAtMost(1);
        assertFalse(folders.contains(old), folders.toString());
        assertTrue(folders.contains(location(renamed)), folders.toString());
    }
}

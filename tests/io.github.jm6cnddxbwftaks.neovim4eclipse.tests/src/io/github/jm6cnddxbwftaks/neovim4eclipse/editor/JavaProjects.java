// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Java projects for the pictures ({@code DemoRecordingTest}, {@code ScreenshotCaptureTest}): the
 * Java editor needs one for content assist (Java's completion asks the project's build path, and in
 * a plain project it shows a dialog instead) and for the compiler's problems.
 *
 * <p>Made with the resources API only: the Java nature by its id, the build path as the
 * {@code .classpath} file JDT reads. So the test fragment needs no JDT bundle at compile time; the
 * {@code screenshots} profile adds JDT at run time. The JRE container is the Java the test Eclipse
 * runs on. Example: {@code create("shop", "shop")} gives {@code shop/src/shop/}, where
 * {@link #addClass} puts {@code shop/src/shop/Invoice.java}.
 */
final class JavaProjects {

    private JavaProjects() {
    }

    /** A new Java project {@code name} (an old one of that name is deleted first) with the source package {@code pkg}. */
    static IProject create(String name, String pkg) throws CoreException {
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (p.exists()) {
            p.delete(true, true, null);
        }
        p.create(null);
        p.open(null);
        IFolder src = p.getFolder("src");
        src.create(true, true, null);
        src.getFolder(pkg).create(true, true, null);
        p.getFile(".classpath").create(new ByteArrayInputStream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                    <classpathentry kind="src" path="src"/>
                    <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
                    <classpathentry kind="output" path="bin"/>
                </classpath>
                """.getBytes(StandardCharsets.UTF_8)), true, null);
        IProjectDescription description = p.getDescription();
        description.setNatureIds(new String[] {"org.eclipse.jdt.core.javanature"});
        p.setDescription(description, null);
        return p;
    }

    /** Adds {@code src/<pkg>/<name>} with {@code source} to {@code project}. */
    static IFile addClass(IProject project, String pkg, String name, String source) throws CoreException {
        IFile file = project.getFile("src/" + pkg + "/" + name);
        file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }

    /** Whether Eclipse's workspace build is done: no build job queued or running. */
    static boolean buildDone() {
        return Job.getJobManager().find(ResourcesPlugin.FAMILY_AUTO_BUILD).length == 0
                && Job.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length == 0;
    }
}

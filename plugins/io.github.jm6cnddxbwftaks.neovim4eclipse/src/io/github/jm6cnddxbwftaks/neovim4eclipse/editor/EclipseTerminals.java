// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EclipseTerminal;

/**
 * {@code :terminal} in an editor: runs Eclipse's <i>Open local terminal</i> command by its id
 * ({@link EclipseTerminal#commandId}), so the Terminal view opens a shell in the file's folder, with
 * no dependency on the Terminal's bundles (an optional part of Eclipse, renamed in 2025-09). The
 * decisions (which id, what the messages say) are in {@link EclipseTerminal}; this class only asks
 * Eclipse and shows the result. UI thread ({@link HostCommands} puts it there); no Neovim call.
 *
 * <p>Example: {@code :terminal} in {@code ~/src/app/Main.java} opens a shell in {@code ~/src/app};
 * {@code :terminal make} does the same and says {@code "make" was not run, type it there}. Without
 * the Terminal: {@link EclipseTerminal#NO_TERMINAL}, and nothing runs.
 */
final class EclipseTerminals {

    private static final Logger LOG = Logger.getLogger(EclipseTerminals.class.getName());

    private EclipseTerminals() {
    }

    /**
     * Opens Eclipse's Terminal for {@code editor}, or says why not.
     *
     * @param command what was typed after {@code :terminal}, which is not run; "" for a shell alone
     */
    static void open(ITextEditor editor, String command) {
        ICommandService commands = editor.getSite().getService(ICommandService.class);
        IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
        Optional<String> id = commands == null || handlers == null ? Optional.empty()
                : EclipseTerminal.commandId(candidate -> command(commands, candidate).isDefined(),
                        candidate -> command(commands, candidate).isHandled());
        if (id.isEmpty()) {
            HostCommands.showError(editor, EclipseTerminal.NO_TERMINAL);
            return;
        }
        // The workbench part: a multi-page editor (a plugin.xml editor) holds the text editor as a page.
        if (editor.getSite().getPage().getActivePart() != EditorAttachManager.getInstance().workbenchPartOf(editor)) {
            // The command reads the active part's selection: in another part (a click elsewhere
            // between Enter and now), the shell would start in that part's folder, not this file's.
            HostCommands.showError(editor, "terminal: not opened, as another part of Eclipse became active");
            return;
        }
        try {
            // Through the editor's site, while this editor is the active part: the command's handler
            // reads the active editor's file (its selection is a text selection, not a resource) and
            // starts the shell in that file's folder.
            handlers.executeCommand(id.get(), null);
        } catch (NotDefinedException | NotHandledException | NotEnabledException e) {
            LOG.fine(() -> id.get() + " is not available here: " + e.getMessage());
            HostCommands.showError(editor, "terminal: Eclipse's Terminal cannot be opened here (" + e.getMessage() + ")");
            return;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "opening Eclipse's Terminal failed", e);
            HostCommands.showError(editor, "terminal: opening Eclipse's Terminal failed: " + e.getMessage());
            return;
        }
        String message = EclipseTerminal.notRun(command, fileFolder(editor.getEditorInput()),
                Path.of(System.getProperty("user.home", "")));
        if (message != null) {
            HostCommands.showMessage(editor, message, false);
        }
    }

    /** The command of {@code id}; Eclipse makes an undefined one on request, so never {@code null}. */
    private static Command command(ICommandService commands, String id) {
        return commands.getCommand(id);
    }

    /**
     * The folder the Terminal's handler starts the shell in: the editor file's folder, as that
     * handler finds it ({@code IPathEditorInput}, a file's parent, a readable folder), or
     * {@code null} when the editor has none (an untitled editor), where the Terminal picks its own.
     */
    static Path fileFolder(IEditorInput input) {
        if (!(input instanceof IPathEditorInput pathInput)) {
            return null;
        }
        IPath path;
        try {
            path = pathInput.getPath();
        } catch (RuntimeException e) { // an input whose file is gone (a deleted project) throws
            return null;
        }
        if (path == null) {
            return null;
        }
        File file = path.toFile();
        File folder = file.isFile() ? file.getParentFile() : file;
        return folder != null && folder.isDirectory() && folder.canRead() ? folder.toPath() : null;
    }
}

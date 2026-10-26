// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.function.Consumer;

/**
 * Decides what a host {@code :} command does in the IDE: which files to save or close, in which order,
 * and whether a failed save stops the close. {@link HostCommand} says <em>which</em> command Neovim
 * parsed; this class turns it into calls on the small {@link Ide} interface. The editor's
 * {@code HostCommands} implements {@link Ide} with Eclipse's save, close, open and tab switching,
 * so the decisions here are tested without a workbench ({@code HostCommandRunnerTest}).
 *
 * <p>Examples, as Vim does them:
 * <ul>
 *   <li>{@code :wq}: save; close only if the save worked (a failed save keeps the file open).</li>
 *   <li>{@code :wq!}: save; close even if the save failed.</li>
 *   <li>{@code :q!}: close and throw the changes away.</li>
 *   <li>{@code :tabn 3}: go three tabs to the right.</li>
 * </ul>
 */
public final class HostCommandRunner {

    /** What a host command can ask of the IDE, for the editor it was typed in. */
    public interface Ide {
        /**
         * Saves the current editor; {@code false} if it could not be saved.
         *
         * @param createFolders   {@code :w ++p}: a new file's missing folders may be created
         * @param evenIfUnchanged {@code :w}, {@code :wq}: a new file is created even when nothing was
         *                        typed (an empty file); {@code :x} and {@code :update} create none
         */
        boolean save(boolean createFolders, boolean evenIfUnchanged);

        /** Saves every modified editor; {@code false} if one could not be saved. */
        boolean saveAll();

        /** Closes the current editor; {@code force} discards its changes instead of asking. */
        void close(boolean force);

        /** Closes every editor; {@code force} discards changes instead of asking. */
        void closeAll(boolean force);

        /**
         * {@code :bd 3 other.txt}: closes the editors of the buffers named by number or by (part of
         * a) name, as typed; {@code force} discards their changes instead of asking. Finding the
         * buffer needs Neovim, so the editors may close after this returns; a buffer with no editor
         * gets Vim's error message.
         */
        void closeBuffers(List<String> buffers, boolean force);

        /**
         * {@code :e [file]}: opens {@code file} (relative to the current one), or reloads the current
         * file when {@code file} is empty. Without {@code force}, refuses when there are changes (E37).
         */
        void edit(String file, boolean force);

        /** Switches editor tabs; {@code count} is the {@code :tabn N} number, 0 when none. */
        void goToTab(TabNavigation.Action action, int count);
    }

    private final Ide ide;
    /** Runs the IDE work on the UI thread (at once when already there). */
    private final Consumer<Runnable> onUi;

    /**
     * @param onUi runs work on the UI thread; tests pass {@code Runnable::run}
     */
    public HostCommandRunner(Ide ide, Consumer<Runnable> onUi) {
        this.ide = ide;
        this.onUi = onUi;
    }

    /**
     * Runs {@code cmd} in the IDE. Any thread: the work goes through {@code onUi}, and this does not
     * wait for it.
     */
    public void run(HostCommand cmd) {
        boolean force = cmd.force();
        switch (cmd.kind()) {
            case WRITE_ALL -> onUi.accept(ide::saveAll);
            case QUIT -> onUi.accept(() -> ide.close(force));
            case QUIT_ALL -> onUi.accept(() -> ide.closeAll(force));
            case BUFFER_DELETE -> onUi.accept(() -> ide.closeBuffers(cmd.args(), force));
            // Closed with force = true once saved: the save already dealt with the changes.
            case WRITE_QUIT -> onUi.accept(() -> {
                if (ide.save(cmd.createFolders(), cmd.writesUnchanged()) || force) {
                    ide.close(true);
                }
            });
            case WRITE_QUIT_ALL -> onUi.accept(() -> {
                if (ide.saveAll() || force) {
                    ide.closeAll(true);
                }
            });
            case EDIT -> onUi.accept(() -> ide.edit(cmd.file(), force));
            case TAB_NEXT -> onUi.accept(() -> ide.goToTab(TabNavigation.Action.NEXT, cmd.count()));
            case TAB_PREVIOUS -> onUi.accept(() -> ide.goToTab(TabNavigation.Action.PREVIOUS, cmd.count()));
            case TAB_FIRST -> onUi.accept(() -> ide.goToTab(TabNavigation.Action.FIRST, 0));
            case TAB_LAST -> onUi.accept(() -> ide.goToTab(TabNavigation.Action.LAST, 0));
        }
    }
}

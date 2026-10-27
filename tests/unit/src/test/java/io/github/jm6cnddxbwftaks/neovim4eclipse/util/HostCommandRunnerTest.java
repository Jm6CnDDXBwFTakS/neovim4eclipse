// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What each host {@code :} command asks of the IDE, without Eclipse: a fake {@link HostCommandRunner.Ide}
 * records the calls, so each test reads as "{@code :wq} saves, then closes". Commands are written as
 * Neovim hands them over, by full name ({@code :Eclipse! quit} is {@code "quit!"}).
 * That Eclipse's save, close and tab switching work is tested in the UI tests ({@code HostCommandsTest}).
 */
class HostCommandRunnerTest {

    /** Records calls; {@link #saveWorks} decides whether saving succeeds. */
    private static final class FakeIde implements HostCommandRunner.Ide {
        final List<String> calls = new ArrayList<>();
        boolean saveWorks = true;

        @Override
        public boolean save(boolean createFolders, boolean evenIfUnchanged) {
            calls.add((evenIfUnchanged ? "save" : "update") + (createFolders ? " ++p" : ""));
            return saveWorks;
        }

        @Override
        public boolean writeCopy(String file, boolean force, boolean onlyIfChanged, boolean createFolders) {
            calls.add("copy" + (force ? "!" : "") + (onlyIfChanged ? " if changed" : "") + (createFolders ? " ++p" : "")
                    + " " + file);
            return saveWorks;
        }

        @Override
        public boolean saveAll() {
            calls.add("saveAll");
            return saveWorks;
        }

        @Override
        public void close(boolean force) {
            calls.add(force ? "close!" : "close");
        }

        @Override
        public void closeAll(boolean force) {
            calls.add(force ? "closeAll!" : "closeAll");
        }

        @Override
        public void closeBuffers(List<String> buffers, boolean force) {
            calls.add((force ? "closeBuffers! " : "closeBuffers ") + buffers);
        }

        @Override
        public void edit(String file, boolean force) {
            calls.add((force ? "edit! " : "edit ") + "[" + file + "]");
        }

        @Override
        public void goToTab(TabNavigation.Action action, int count) {
            calls.add("tab " + action + " " + count);
        }
    }

    private final FakeIde ide = new FakeIde();
    private final HostCommandRunner runner = new HostCommandRunner(ide, Runnable::run);

    /** {@code "bwipeout! 3"}: the command, as {@code :Eclipse} sends it, by full name. */
    static HostCommand command(String nameAndArgs) {
        List<String> words = List.of(nameAndArgs.split(" "));
        String name = words.get(0);
        boolean bang = name.endsWith("!");
        HostCommand cmd = HostCommand.of(bang ? name.substring(0, name.length() - 1) : name, bang,
                words.subList(1, words.size()));
        assertNotNull(cmd, nameAndArgs + " is a host command");
        return cmd;
    }

    private List<String> run(String nameAndArgs) {
        runner.run(command(nameAndArgs));
        return ide.calls;
    }

    // ---- writing and quitting ----

    @Test
    void writeAllSavesEveryEditor() {
        assertEquals(List.of("saveAll"), run("wall"));
    }

    @Test
    void quitClosesAndAsksAboutChanges() {
        assertEquals(List.of("close"), run("quit"));
    }

    @Test
    void quitBangDiscardsTheChanges() {
        assertEquals(List.of("close!"), run("quit!"));
    }

    @Test
    void bufferDeleteWithBuffersClosesThoseEditors() {
        // :%bd and :3,5bd arrive as the buffers they cover (Neovim's side turns the range into them).
        run("bdelete 3 other.txt");
        run("bwipeout! 3");
        run("bdelete");
        // run() returns every call so far
        assertEquals(List.of("closeBuffers [3, other.txt]", "closeBuffers! [3]", "close", "closeBuffers [1, 2]"), run("bdelete 1 2"));
    }

    @Test
    void quitAllBangClosesEveryEditorWithoutAsking() {
        assertEquals(List.of("closeAll!"), run("qall!"));
    }

    @Test
    void writeQuitSavesThenCloses() {
        // Closed with force: the save already took care of the changes.
        assertEquals(List.of("save", "close!"), run("wq"));
    }

    @Test
    void writeQuitWithANameWritesACopyThenClosesAskingAboutTheEditorsChanges() {
        // As Vim's :wq other.txt: the copy, then :q, which asks (Vim: E37) since the editor is not saved.
        assertEquals(List.of("copy other.txt", "close"), run("wq other.txt"));
    }

    @Test
    void xitWithANameCopiesOnlyChangesAndBangDiscardsThem() {
        assertEquals(List.of("copy! if changed ++p sub/a.txt", "close!"), run("xit! ++p sub/a.txt"));
    }

    @Test
    void writeQuitWithANameStaysOpenWhenTheCopyFails() {
        ide.saveWorks = false; // E13, a missing folder, unsaved changes in the target's editor
        assertEquals(List.of("copy! other.txt"), run("wq! other.txt"));
    }

    @Test
    void writeQuitKeepsTheFileOpenWhenTheSaveFails() {
        ide.saveWorks = false; // a read-only file, a save participant that failed
        assertEquals(List.of("save"), run("wq"));
    }

    @Test
    void writeQuitBangClosesEvenWhenTheSaveFails() {
        ide.saveWorks = false;
        assertEquals(List.of("save", "close!"), run("wq!"));
    }

    @Test
    void writeQuitAllSavesEverythingThenClosesEverything() {
        assertEquals(List.of("saveAll", "closeAll!"), run("xall"));
    }

    @Test
    void writeQuitAllKeepsTheEditorsOpenWhenASaveFails() {
        ide.saveWorks = false;
        assertEquals(List.of("saveAll"), run("wqall"));
    }

    @Test
    void writeQuitAllBangClosesEverythingEvenWhenASaveFails() {
        ide.saveWorks = false;
        assertEquals(List.of("saveAll", "closeAll!"), run("wqall!"));
    }

    @Test
    void writeQuitPlusPlusPClosesOnlyAfterTheSaveWorked() {
        assertEquals(List.of("save ++p", "close!"), run("wq ++p"));
        ide.calls.clear();
        ide.saveWorks = false; // the folder could not be created
        assertEquals(List.of("update ++p"), run("xit ++p"));
    }

    /** For a new file's editor: :wq creates it even untouched, :x only with changes. */
    @Test
    void wqWritesUnchangedFilesWhileXDoesNot() {
        run("wq");
        run("xit");
        run("exit");
        assertEquals(List.of("save", "close!", "update", "close!", "update", "close!"), ide.calls);
    }

    // ---- :e ----

    @Test
    void editOpensTheNamedFile() {
        assertEquals(List.of("edit [three.txt]"), run("edit three.txt"));
    }

    @Test
    void editBangWithoutAFileReloadsTheCurrentOne() {
        assertEquals(List.of("edit! []"), run("edit!"));
    }

    // ---- tabs ----

    @Test
    void tabCommandsPassTheirDirectionAndCount() {
        run("tabnext 3");
        run("tabprevious");
        run("tabfirst");
        run("tablast");
        assertEquals(List.of("tab NEXT 3", "tab PREVIOUS 0", "tab FIRST 0", "tab LAST 0"), ide.calls);
    }

    // ---- what is not a host command, and threads ----

    @Test
    void otherCommandsAndArgumentsAreNotEclipses() {
        assertNull(HostCommand.of("substitute", false, List.of()));
        assertNull(HostCommand.of("quit", false, List.of("foo")), ":q foo is Neovim's to report");
        assertNull(HostCommand.of("tabnext", false, List.of("x")));
        assertNull(HostCommand.of(null, false, List.of()));
    }

    @Test
    void theWorkRunsThroughTheUiExecutorAndTheAnswerDoesNotWaitForIt() {
        List<Runnable> queued = new ArrayList<>();
        HostCommandRunner later = new HostCommandRunner(ide, queued::add); // a UI thread that is busy

        // Every kind of command: none may touch the IDE from the calling thread.
        for (String command : List.of("wall", "quit", "qall", "wq", "xall", "edit x.txt", "tabnext",
                "tabprevious", "tabfirst", "tablast")) {
            later.run(command(command));
        }
        assertEquals(List.of(), ide.calls, "nothing happens until the UI thread runs the work");

        queued.forEach(Runnable::run);
        assertEquals(List.of("saveAll", "close", "closeAll", "save", "close!", "saveAll", "closeAll!",
                "edit [x.txt]", "tab NEXT 0", "tab PREVIOUS 0", "tab FIRST 0", "tab LAST 0"), ide.calls,
                "in order, and a save and its close in one piece of work");
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What each host {@code :} command asks of the IDE, without Eclipse: a fake {@link HostCommandRunner.Ide}
 * records the calls, so each test reads as "typing {@code :wq} saves, then closes".
 * That Eclipse's save, close and tab switching work is tested in the UI tests ({@code HostCommandsTest}).
 */
class HostCommandRunnerTest {

    /** Records calls; {@link #saveWorks} decides whether saving succeeds. */
    private static final class FakeIde implements HostCommandRunner.Ide {
        final List<String> calls = new ArrayList<>();
        boolean saveWorks = true;

        @Override
        public boolean save() {
            calls.add("save");
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

    private List<String> run(String command) {
        assertTrue(runner.run(command), command + " is a host command");
        return ide.calls;
    }

    // ---- writing and quitting ----

    @Test
    void writeSavesTheCurrentEditor() {
        assertEquals(List.of("save"), run("w"));
    }

    @Test
    void writeAllSavesEveryEditor() {
        assertEquals(List.of("saveAll"), run(":wa"));
    }

    @Test
    void quitClosesAndAsksAboutChanges() {
        assertEquals(List.of("close"), run("q"));
    }

    @Test
    void quitBangDiscardsTheChanges() {
        assertEquals(List.of("close!"), run("q!"));
    }

    @Test
    void quitAllBangClosesEveryEditorWithoutAsking() {
        assertEquals(List.of("closeAll!"), run("qa!"));
    }

    @Test
    void writeQuitSavesThenCloses() {
        // Closed with force: the save already took care of the changes.
        assertEquals(List.of("save", "close!"), run("wq"));
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
        assertEquals(List.of("saveAll", "closeAll!"), run("xa"));
    }

    @Test
    void writeQuitAllKeepsTheEditorsOpenWhenASaveFails() {
        ide.saveWorks = false;
        assertEquals(List.of("saveAll"), run("wqa"));
    }

    @Test
    void writeQuitAllBangClosesEverythingEvenWhenASaveFails() {
        ide.saveWorks = false;
        assertEquals(List.of("saveAll", "closeAll!"), run("wqa!"));
    }

    // ---- :e ----

    @Test
    void editOpensTheNamedFile() {
        assertEquals(List.of("edit [three.txt]"), run("e three.txt"));
    }

    @Test
    void editBangWithoutAFileReloadsTheCurrentOne() {
        assertEquals(List.of("edit! []"), run("e!"));
    }

    // ---- tabs ----

    @Test
    void tabCommandsPassTheirDirectionAndCount() {
        run("tabn 3");
        run("tabp");
        run("tabfirst");
        run("tablast");
        assertEquals(List.of("tab NEXT 3", "tab PREVIOUS 0", "tab FIRST 0", "tab LAST 0"), ide.calls);
    }

    // ---- what is not a host command, and threads ----

    @Test
    void otherCommandsAreLeftToNeovim() {
        assertFalse(runner.run("s/a/b/"));
        assertFalse(runner.run("set ts=4"));
        assertEquals(List.of(), ide.calls);
    }

    @Test
    void theWorkRunsThroughTheUiExecutorAndTheAnswerDoesNotWaitForIt() {
        List<Runnable> queued = new ArrayList<>();
        HostCommandRunner later = new HostCommandRunner(ide, queued::add); // a UI thread that is busy

        // Every kind of command: none may touch the IDE from the calling thread.
        for (String command : List.of("w", "wa", "q", "qa", "wq", "xa", "e x.txt", "tabn", "tabp", "tabfirst",
                "tablast")) {
            assertTrue(later.run(command), command);
        }
        assertEquals(List.of(), ide.calls, "nothing happens until the UI thread runs the work");

        queued.forEach(Runnable::run);
        assertEquals(List.of("save", "saveAll", "close", "closeAll", "save", "close!", "saveAll", "closeAll!",
                "edit [x.txt]", "tab NEXT 0", "tab PREVIOUS 0", "tab FIRST 0", "tab LAST 0"), ide.calls,
                "in order, and a save and its close in one piece of work");
    }
}

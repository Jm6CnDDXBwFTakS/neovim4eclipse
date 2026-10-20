// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link UiExecutor#IMMEDIATE}, the executor the text-model tests use in place of the SWT UI
 * thread: it must run work at once, on the calling thread, or those tests would check the state
 * before the work ran.
 */
class UiExecutorTest {

    private final UiExecutor ui = UiExecutor.IMMEDIATE;

    @Test
    void isAlwaysAvailableAndTheCallingThreadIsTheUiThread() {
        assertTrue(ui.isAvailable());
        assertTrue(ui.isUiThread());
    }

    @Test
    void asyncExecRunsTheWorkBeforeItReturnsOnTheCallingThread() {
        List<Thread> ran = new ArrayList<>();
        ui.asyncExec(() -> ran.add(Thread.currentThread()));
        assertEquals(List.of(Thread.currentThread()), ran);
    }

    @Test
    void syncExecRunsTheWorkOnTheCallingThread() {
        List<Thread> ran = new ArrayList<>();
        ui.syncExec(() -> ran.add(Thread.currentThread()));
        assertEquals(1, ran.size());
        assertSame(Thread.currentThread(), ran.get(0));
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** What the Ctrl-V check reports ({@link CtrlVResolution}): a warning only for a binding that is wrong. */
class CtrlVResolutionTest {

    @Test
    void ctrlVReachingNeovimIsFine() {
        assertEquals(CtrlVResolution.NEOVIM, CtrlVResolution.of(true, true, true));
    }

    @Test
    void ctrlVMissingNeovimWhenItShouldReachItIsWrong() {
        assertEquals(CtrlVResolution.WRONG, CtrlVResolution.of(true, true, false));
    }

    /** Linux and Windows, insert mode, defaults: Ctrl-V stays Eclipse's paste. */
    @Test
    void ctrlVGivenToEclipseIsExpected() {
        assertFalse(VimCtrlKeys.inInsertByDefault('v', false), "the case this is about");
        assertEquals(CtrlVResolution.EXPECTED_ECLIPSE, CtrlVResolution.of(true, false, false));
    }

    /** The editor lost focus before the posted check ran: whatever wins now is not a fault. */
    @Test
    void aContextTurnedOffAgainIsNotReported() {
        assertEquals(CtrlVResolution.NOT_ACTIVE, CtrlVResolution.of(false, true, false));
        assertEquals(CtrlVResolution.NOT_ACTIVE, CtrlVResolution.of(false, false, false));
    }
}

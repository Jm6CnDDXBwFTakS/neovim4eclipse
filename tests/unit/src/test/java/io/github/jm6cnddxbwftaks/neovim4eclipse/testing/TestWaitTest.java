// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** How {@link TestWait} reads {@code -Dtest.wait.seconds}: unset means 15, a typo stops the run. */
class TestWaitTest {

    @Test
    void unsetMeansTheDefault() {
        assertEquals(TestWait.DEFAULT_SECONDS, TestWait.seconds(null));
        assertEquals(TestWait.DEFAULT_SECONDS, TestWait.seconds(""));
        assertEquals(TestWait.DEFAULT_SECONDS, TestWait.seconds("null"), "how some Maven plug-ins pass an unset property");
        assertEquals(TestWait.DEFAULT_SECONDS, TestWait.seconds("${test.wait.seconds}"), "an undefined ${...} passed on as text");
    }

    @Test
    void aNumberIsSeconds() {
        assertEquals(60, TestWait.seconds("60"));
        assertEquals(5, TestWait.seconds(" 5 "));
    }

    @Test
    void anythingElseIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> TestWait.seconds("30s"));
        assertThrows(IllegalArgumentException.class, () -> TestWait.seconds("0"));
        assertThrows(IllegalArgumentException.class, () -> TestWait.seconds("-1"));
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote.Kind;

/** When the welcome and the update note are shown, and what they say. */
class WelcomeNoteTest {

    // ---- decide: once, and again for each new minor version ----

    @Test
    void theFirstRunGetsTheWelcome() {
        assertEquals(Kind.WELCOME, WelcomeNote.decide(null, "0.1.5.qualifier"));
        assertEquals(Kind.WELCOME, WelcomeNote.decide("", "0.1.5.qualifier"));
    }

    @Test
    void aPatchReleaseIsQuiet() {
        assertEquals(Kind.NONE, WelcomeNote.decide("0.1", "0.1.5.qualifier"));
        assertEquals(Kind.NONE, WelcomeNote.decide("0.1", "0.1.6.v20261101-1200"));
    }

    @Test
    void aNewMinorOrMajorVersionGetsTheUpdateNote() {
        assertEquals(Kind.UPDATED, WelcomeNote.decide("0.1", "0.2.0.v20261101-1200"));
        assertEquals(Kind.UPDATED, WelcomeNote.decide("0.9", "0.10.0")); // numbers, not text: 10 > 9
        assertEquals(Kind.UPDATED, WelcomeNote.decide("0.9", "1.0.0"));
    }

    @Test
    void aDowngradeIsQuiet() {
        assertEquals(Kind.NONE, WelcomeNote.decide("0.2", "0.1.6"));
        assertEquals(Kind.NONE, WelcomeNote.decide("1.0", "0.9.0"));
    }

    @Test
    void anUnreadableRecordCountsAsNone() {
        assertEquals(Kind.WELCOME, WelcomeNote.decide("garbage", "0.1.5"));
        assertEquals(Kind.WELCOME, WelcomeNote.decide("1", "0.1.5"));
        assertEquals(Kind.WELCOME, WelcomeNote.decide("-1.0", "0.1.5"));
    }

    @Test
    void anUnreadableRunningVersionShowsNothing() {
        assertEquals(Kind.NONE, WelcomeNote.decide(null, null));
        assertEquals(Kind.NONE, WelcomeNote.decide(null, "dev"));
    }

    // ---- featureLine: what is recorded ----

    @Test
    void theFeatureLineIsMajorDotMinor() {
        assertEquals("0.1", WelcomeNote.featureLine("0.1.5.qualifier"));
        assertEquals("0.10", WelcomeNote.featureLine("0.10.0"));
        assertEquals("2.0", WelcomeNote.featureLine("2.0"));
        assertNull(WelcomeNote.featureLine("dev"));
        assertNull(WelcomeNote.featureLine(null));
    }

    @Test
    void recordingTheLineSilencesTheSameVersion() {
        String version = "0.2.0.v20261101-1200";
        assertEquals(Kind.NONE, WelcomeNote.decide(WelcomeNote.featureLine(version), version));
    }

    // ---- the texts ----

    @Test
    void theWelcomeNamesTheToggleKeyAndThePreferences() {
        String message = WelcomeNote.welcomeMessage("Ctrl+Shift+M");
        assertTrue(message.contains("Ctrl+Shift+M, the NVIM toolbar button"), message);
        assertTrue(message.contains("init.lua"), message);
        assertTrue(message.contains("Preferences > Neovim"), message);
        assertTrue(message.contains("Ctrl Keys"), message);
        assertTrue(message.contains("Welcome"), message);
    }

    @Test
    void withoutAKeyBindingTheWelcomeLeavesItOut() {
        for (String none : new String[] { null, "", " " }) {
            String message = WelcomeNote.welcomeMessage(none);
            assertTrue(message.contains("Turn it off and on with the NVIM toolbar button"), message);
            assertFalse(message.contains("null"), message);
        }
    }

    @Test
    void theUpdateNoteShowsTheReleaseVersionWithoutTheQualifier() {
        String message = WelcomeNote.updatedMessage("0.2.0.v20261101-1200");
        assertTrue(message.startsWith("Neovim for Eclipse was updated to 0.2.0."), message);
        assertFalse(message.contains("v2026"), message);
    }

    @Test
    void releaseVersionKeepsShortVersionsAsTheyAre() {
        assertEquals("0.2.0", WelcomeNote.releaseVersion("0.2.0"));
        assertEquals("0.2", WelcomeNote.releaseVersion("0.2"));
        assertEquals("", WelcomeNote.releaseVersion(null));
    }
}

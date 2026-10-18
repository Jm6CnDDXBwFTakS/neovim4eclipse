// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The plug-in's JUL warnings reach Eclipse's log (the Error Log view); INFO does not. */
class ErrorLogHandlerTest {

    private final List<IStatus> logged = new CopyOnWriteArrayList<>();
    private final ILogListener listener = (status, plugin) -> {
        if (Activator.PLUGIN_ID.equals(status.getPlugin())) {
            logged.add(status);
        }
    };

    @BeforeEach
    void listen() {
        assertNotNull(Activator.getDefault(), "the plug-in must be started");
        Platform.addLogListener(listener);
    }

    @AfterEach
    void stopListening() {
        Platform.removeLogListener(listener);
    }

    @Test
    void warningsAndErrorsGoToTheErrorLogButInfoDoesNot() {
        Logger log = Logger.getLogger(Activator.PLUGIN_ID + ".editor.SomeClass");
        log.info("routine");
        log.warning("probe warning");
        log.log(java.util.logging.Level.SEVERE, "probe error", new IllegalStateException("cause"));

        assertEquals(2, logged.size(), "only the warning and the error: " + logged);
        assertEquals(IStatus.WARNING, logged.get(0).getSeverity());
        assertEquals("probe warning (SomeClass)", logged.get(0).getMessage());
        assertEquals(IStatus.ERROR, logged.get(1).getSeverity());
        assertTrue(logged.get(1).getException() instanceof IllegalStateException);
    }
}

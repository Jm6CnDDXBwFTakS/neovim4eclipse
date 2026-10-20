// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.util.Util;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LogLevelSwitch;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * With {@code -Dneovim4eclipse.logLevel=FINE}, turning Neovim mode on prints
 * "Neovim key context activated" and the Ctrl-V resolution, each once, and neither reaches the
 * Error Log. The switch is applied here the way the activator applies it, with the output going to
 * a buffer instead of {@code System.err}. The real Ctrl+V key press is left out (synthetic keys miss
 * the workbench's key bindings); the binding lookup that decides it is checked directly.
 */
class KeyContextLogEndToEndTest extends EndToEndTestBase {

    private static final String TOGGLE = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.toggleMode";

    @Test
    void atFineTurningNeovimModeOnPrintsTheKeyContextLinesOnceAndNotToTheErrorLog() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        List<String> errorLog = new CopyOnWriteArrayList<>();
        ILogListener listener = (status, plugin) -> {
            if (Activator.PLUGIN_ID.equals(status.getPlugin()) && status.getSeverity() != IStatus.OK) {
                errorLog.add(status.getMessage());
            }
        };
        LogLevelSwitch logLevel = new LogLevelSwitch();
        // Neovim mode off first, so that turning it on below activates the key context afresh.
        runCommand(TOGGLE);
        settle();
        logLevel.apply(Logger.getLogger(Activator.PLUGIN_ID), Level.FINE, out);
        Platform.addLogListener(listener);
        try {
            runCommand(TOGGLE);
            widget.setFocus();
            settle();
            String resolves = "FINE KeyContextDiagnostics: Ctrl-V (" + (Util.isMac() ? "M4+V" : "M1+V")
                    + ") resolves to the Neovim Ctrl-key command";
            waitUntil("the Ctrl-V resolution line", TIMEOUT_MS, () -> printed(bytes).contains(resolves),
                    () -> "; printed:\n" + printed(bytes));

            assertEquals(1, count(printed(bytes), "FINE EditorKeyContext: Neovim key context activated"), printed(bytes));
            assertEquals(1, count(printed(bytes), resolves), printed(bytes));
            assertTrue(errorLog.stream().noneMatch(m -> m.contains("key context") || m.contains("Ctrl-V")),
                    "FINE lines never reach the Error Log: " + errorLog);
            assertTrue(VimCtrlKeys.isCtrlKeyCommand(KeyBindings.commandIdFor(Util.isMac() ? "M4+V" : "M1+V")),
                    "Ctrl+V is bound to the plug-in's command");
        } finally {
            Platform.removeLogListener(listener);
            logLevel.remove();
        }
    }

    private static String printed(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}

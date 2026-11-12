// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.commands.ICommandService;
import org.junit.jupiter.api.Test;

/**
 * {@link OtherCommandWatch} with a command service of the test's own: watching starts and stops
 * on it, and stopping does not fail when the service is gone, as the window's is at Eclipse's quit
 * (a detach queued while the workbench closes ran after it: Eclipse's {@code SlaveCommandService}
 * threw a {@code NullPointerException} from {@code removeExecutionListener}). It also stops by
 * itself when its widget is disposed, and stopping twice removes the listener once.
 */
class OtherCommandWatchTest {

    /** A command service that records the calls on it, and fails a removal when {@code gone}. */
    private static ICommandService service(List<String> calls, boolean gone) {
        return (ICommandService) Proxy.newProxyInstance(OtherCommandWatchTest.class.getClassLoader(),
                new Class<?>[] {ICommandService.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (gone && method.getName().equals("removeExecutionListener")) {
                        throw new NullPointerException("this.fParentService is null (as Eclipse's disposed service)");
                    }
                    return null;
                });
    }

    @Test
    void watchingStartsAndStopsOnTheService() {
        List<String> calls = new ArrayList<>();
        new OtherCommandWatch(service(calls, false), null, () -> { }).dispose();
        assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
    }

    @Test
    void stoppingDoesNotFailWhenTheServiceIsGone() {
        List<String> calls = new ArrayList<>();
        OtherCommandWatch watch = new OtherCommandWatch(service(calls, true), null, () -> { });
        watch.dispose(); // a throw fails the test
        assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
    }

    @Test
    void withNoServiceThereIsNothingToWatch() {
        new OtherCommandWatch(null, null, () -> { }).dispose(); // a throw fails the test
    }

    @Test
    void watchingStopsWhenTheWidgetIsDisposedAndOnlyOnce() {
        List<String> calls = new ArrayList<>();
        Shell shell = new Shell(Display.getDefault()); // stands in for the editor's text widget
        OtherCommandWatch watch = new OtherCommandWatch(service(calls, false), shell, () -> { });
        shell.dispose(); // the editor closed without its controller's close()
        assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
        watch.dispose(); // close() after all: nothing more to remove
        assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
    }

    @Test
    void stoppingFirstLeavesTheWidgetAlone() {
        List<String> calls = new ArrayList<>();
        Shell shell = new Shell(Display.getDefault());
        try {
            new OtherCommandWatch(service(calls, false), shell, () -> { }).dispose();
            shell.dispose(); // the watch no longer listens: no second removal
            assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
        } finally {
            if (!shell.isDisposed()) {
                shell.dispose();
            }
        }
    }

    @Test
    void aWidgetDisposedAlreadyGivesNoWatch() {
        List<String> calls = new ArrayList<>();
        Shell shell = new Shell(Display.getDefault());
        shell.dispose(); // the editor closed before its controller was built
        new OtherCommandWatch(service(calls, false), shell, () -> { }).dispose();
        assertEquals(List.of(), calls, "nothing would end a watch added now");
    }
}

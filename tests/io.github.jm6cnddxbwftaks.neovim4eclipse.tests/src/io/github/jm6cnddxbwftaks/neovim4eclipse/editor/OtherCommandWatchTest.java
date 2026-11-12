// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.ui.commands.ICommandService;
import org.junit.jupiter.api.Test;

/**
 * {@link OtherCommandWatch} with a command service of the test's own: watching starts and stops
 * on it, and stopping does not fail when the service is gone, as the window's is at Eclipse's quit
 * (a detach queued while the workbench closes ran after it: Eclipse's {@code SlaveCommandService}
 * threw a {@code NullPointerException} from {@code removeExecutionListener}).
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
        new OtherCommandWatch(service(calls, false), () -> { }).dispose();
        assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
    }

    @Test
    void stoppingDoesNotFailWhenTheServiceIsGone() {
        List<String> calls = new ArrayList<>();
        OtherCommandWatch watch = new OtherCommandWatch(service(calls, true), () -> { });
        watch.dispose(); // a throw fails the test
        assertEquals(List.of("addExecutionListener", "removeExecutionListener"), calls);
    }

    @Test
    void withNoServiceThereIsNothingToWatch() {
        new OtherCommandWatch(null, () -> { }).dispose(); // a throw fails the test
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Closing a connection to a process that never reads its input, as a hung Neovim does, while
 * another thread is stuck writing a large request into the full pipe. Closing must neither wait
 * for that writer's lock nor flush into the pipe before the process is gone ({@link BoundedSend}).
 *
 * <p>The process is a second JVM that only sleeps ({@link Sleeper}): portable, and certain never
 * to read. No Neovim is needed.
 */
class NeovimConnectionCloseTest {

    /** The child process: sleeps and never reads its stdin. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }

    private Process process;

    @AfterEach
    void endTheProcess() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private static String javaProgram() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    @Test
    void closingWhileAWriteIsStuckInTheFullPipeReturnsAndEndsTheProcess() throws Exception {
        process = new ProcessBuilder(javaProgram(), "-cp", System.getProperty("java.class.path"),
                Sleeper.class.getName()).start();
        NeovimConnection connection = NeovimConnection.over(process, "sleeper");

        // Far more than a pipe holds (64 KiB on Linux): the write blocks, holding the write lock.
        CompletableFuture<Object> stuck = BoundedSend.start("test-stuck-writer",
                () -> connection.api().execLua("return 0", List.of("x".repeat(8 * 1024 * 1024))));
        Thread writer = waitForWriterInRpcWrite();

        try (ExpectedLog log = ExpectedLog.of(NeovimConnection.class)) {
            assertTimeoutPreemptively(TestWait.duration(), () -> {
                connection.requestQuit(); // cannot write :qa!: gives up after its bound
                connection.close(); // ends the process first, which breaks the pipe
            });
            log.assertLogged(Level.WARNING, "Could not send :qa! within");
        }

        assertFalse(process.isAlive());
        // The stuck write failed once the pipe broke, and its thread ended.
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> stuck.get(TestWait.millis(), TimeUnit.MILLISECONDS));
        assertInstanceOf(RuntimeException.class, failed.getCause());
        writer.join(TestWait.millis());
        assertFalse(writer.isAlive(), "the writer ended");
    }

    /** The stuck writer's thread, once it is inside the channel's write (where it then stays). */
    private static Thread waitForWriterInRpcWrite() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TestWait.millis());
        while (System.nanoTime() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("test-stuck-writer") && Arrays.stream(t.getStackTrace())
                        .anyMatch(f -> f.getClassName().endsWith("RpcChannel") && f.getMethodName().equals("write"))) {
                    return t;
                }
            }
            Thread.sleep(10);
        }
        throw new TimeoutException("the writer never reached RpcChannel.write");
    }
}

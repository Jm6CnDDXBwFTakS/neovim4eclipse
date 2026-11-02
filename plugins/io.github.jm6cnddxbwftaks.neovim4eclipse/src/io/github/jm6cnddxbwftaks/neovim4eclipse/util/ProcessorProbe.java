// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The machine's real processor, which can differ from the one Java reports.
 *
 * <h2>Why</h2>
 * Java's {@code os.arch} is the processor its own process runs as. An x86_64 Eclipse (or JDK) on an
 * Apple silicon Mac or an arm64 Windows machine runs under emulation (Rosetta 2, Windows' x64
 * emulation) and reports {@code amd64}. A Neovim for x86_64 would run there too, emulated and slower;
 * the native arm64 Neovim is the better download. So <i>Get Neovim</i> asks the operating system:
 * <ul>
 *   <li><b>macOS</b>: {@code sysctl -n hw.optional.arm64} prints {@code 1} on Apple silicon, also
 *       from an emulated process.</li>
 *   <li><b>Windows</b>: the system-wide {@code PROCESSOR_ARCHITECTURE} in the registry
 *       ({@code reg query}, about 50 ms), {@code ARM64} or {@code AMD64}. A process's own variable
 *       of that name says what the process runs as, so the registry's is the one to read. Whether the
 *       registry still says {@code ARM64} when read from an emulated x64 process was not verified (no
 *       x64 JDK was at hand).</li>
 *   <li><b>Linux</b>: {@code os.arch} as it is.</li>
 * </ul>
 *
 * <h2>When</h2>
 * Starting a program takes some milliseconds, so the answer is asked once per Eclipse session, on a
 * background thread, and kept. {@link #known()} never waits: until the answer is in, or if asking
 * fails, it gives Java's own platform. {@link #await()} waits a few seconds, for the install Job.
 * Example on an arm64 Mac running an x86_64 Eclipse: {@code known()} is {@code mx} at first, then
 * {@code ma}.
 */
public final class ProcessorProbe {

    /** How long one command may take. */
    private static final long COMMAND_TIMEOUT_MS = 3000;

    private static volatile CompletableFuture<Optional<NeovimPlatform>> answer;

    private ProcessorProbe() {
    }

    /** The real platform if it is known yet, else Java's own; starts asking if nobody has. Never waits. */
    public static Optional<NeovimPlatform> known() {
        CompletableFuture<Optional<NeovimPlatform>> a = start();
        return a.isDone() ? a.join() : NeovimPlatform.current();
    }

    /** The real platform, waiting up to a few seconds for the answer; Java's own if none comes. */
    public static Optional<NeovimPlatform> await() {
        try {
            return start().get(2 * COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NeovimPlatform.current();
        } catch (ExecutionException | TimeoutException e) {
            return NeovimPlatform.current();
        }
    }

    private static CompletableFuture<Optional<NeovimPlatform>> start() {
        CompletableFuture<Optional<NeovimPlatform>> a = answer;
        if (a == null) {
            synchronized (ProcessorProbe.class) {
                a = answer;
                if (a == null) {
                    String osName = System.getProperty("os.name", "");
                    String osArch = System.getProperty("os.arch", "");
                    a = CompletableFuture.supplyAsync(() -> probe(osName, osArch, ProcessorProbe::run));
                    answer = a;
                }
            }
        }
        return a;
    }

    /**
     * Testable core: the platform for {@code osName} with the processor the system reports.
     *
     * @param run runs a command and gives its output, or {@code null} if it failed
     */
    static Optional<NeovimPlatform> probe(String osName, String osArch, Function<List<String>, String> run) {
        String os = osName.toLowerCase(Locale.ROOT);
        String real = null;
        if (os.startsWith("mac")) {
            String out = run.apply(List.of("sysctl", "-n", "hw.optional.arm64"));
            if (out != null) {
                real = switch (out.strip()) {
                    case "1" -> "arm64";
                    case "0" -> "x86_64";
                    default -> null;
                };
            }
        } else if (os.startsWith("windows")) {
            String out = run.apply(List.of("reg", "query",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "/v",
                    "PROCESSOR_ARCHITECTURE"));
            real = windowsArchitecture(out);
        }
        Optional<NeovimPlatform> platform = real == null ? Optional.empty() : NeovimPlatform.of(osName, real);
        return platform.isPresent() ? platform : NeovimPlatform.of(osName, osArch);
    }

    /**
     * The processor in {@code reg query}'s answer, whose last line reads
     * {@code     PROCESSOR_ARCHITECTURE    REG_SZ    ARM64}; {@code null} if it says something else.
     */
    static String windowsArchitecture(String regOutput) {
        if (regOutput == null) {
            return null;
        }
        for (String line : regOutput.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 3 && parts[0].equalsIgnoreCase("PROCESSOR_ARCHITECTURE")) {
                return switch (parts[2].toUpperCase(Locale.ROOT)) {
                    case "ARM64" -> "arm64";
                    case "AMD64" -> "x86_64";
                    default -> null;
                };
            }
        }
        return null;
    }

    /** Runs {@code command}; its standard output, or {@code null} if it failed or took too long. */
    private static String run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return null;
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}

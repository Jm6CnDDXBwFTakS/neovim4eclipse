// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Incoming;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenGrid;

/**
 * A second Neovim, beside the editing one, whose whole screen Eclipse draws: the Neovim view
 * (first for {@code :checkhealth} and {@code :messages}, later for {@code :help}). It knows
 * nothing of what the view shows; that is the view's <em>profile</em>, which passes its own start
 * arguments and Lua. This class holds what every profile shares: the process, the screen, keys,
 * mouse, size, yanks and the end.
 *
 * <h2>The one rule: the second Neovim never writes a file</h2>
 * Two Neovims and Eclipse's documents editing the same file would drift apart (lost edits, swap
 * prompts, reloads), so the second Neovim writes nothing at all. Every profile starts with
 * {@link #FIXED_ARGS}: {@code --clean} (no user config, no plug-ins, no shada file), {@code -n}
 * (no swap file) and {@code 'write'} and {@code 'modeline'} off. And it is checked where it ends,
 * not trusted on the way: once started, Neovim is asked for those options as it has them
 * ({@link LuaScripts#SCREEN_SETUP}), and the start is refused unless every one is off
 * ({@link #unsafeOptions}). One check covers every way of writing a buffer ({@code :w},
 * {@code :saveas}, {@code :update}, {@code ZZ}, an autocmd's write). A profile may also forbid
 * changes to the text ({@code -M}: {@code nomodifiable}). What the user types in the view
 * ({@code :set write}, {@code :!}) stays their own, as in the editing Neovim.
 *
 * <h2>The screen</h2>
 * Attached with {@link NeovimConnection#SCREEN_UI} (no {@code ext_multigrid}, no
 * {@code ext_cmdline}, no {@code ext_messages}): Neovim draws everything into one grid, as on a
 * terminal, which {@link ScreenGrid} keeps; at each {@code flush} a changed screen goes to the
 * listener as a {@link GridSnapshot}.
 *
 * <h2>Threads</h2>
 * {@link #start} blocks while Neovim starts (a few milliseconds with {@code --clean}, at most the
 * connection's timeouts): call it off the SWT UI thread. Every other call returns at once: the
 * writes to Neovim run on this session's own {@link InputThread}, in the order they were made,
 * so the UI thread never waits for Neovim. {@link Listener#screen} and {@link Listener#yank} are
 * called on the RPC reader thread, {@link Listener#ended} on a thread of the JDK's: none of them
 * on the UI thread, and none may wait for an answer from this Neovim.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * ScreenSession screen = ScreenSession.start(nvimPath, List.of("-M"), version, 80, 24, listener);
 * screen.execLuaAsync(profileScript, List.of(lines));   // the profile fills the screen
 * screen.inputAsync("/OK<CR>");                          // a search, typed in the view
 * screen.resizeAsync(100, 30);                           // the view grew
 * screen.close();                                        // or :q in the view: listener.ended()
 * }</pre>
 */
public final class ScreenSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ScreenSession.class.getName());

    /**
     * The arguments every second Neovim starts with, before the profile's own: see the class
     * comment, "The one rule". {@code --cmd} runs before anything else Neovim loads.
     */
    static final List<String> FIXED_ARGS = List.of("--clean", "-n", "--cmd", "set nowrite nomodeline");

    /** What the session reports; see the class comment, "Threads", for the threads each runs on. */
    public interface Listener {

        /** The screen changed: the whole of it, as it stood at a {@code flush}. */
        void screen(GridSnapshot screen);

        /**
         * Text was yanked in the view. Example: {@code "ayy} on a line {@code OK} gives
         * {@code ("a", "V", ["OK"])}; a plain {@code yy} gives the register {@code ""}.
         *
         * @param register the register's name, {@code ""} for the unnamed one
         * @param type     Neovim's register type: {@code v}, {@code V}, or Ctrl-V and a width
         * @param lines    the yanked lines
         */
        void yank(String register, String type, List<String> lines);

        /**
         * {@code Ctrl-W w}, {@code p} or {@code k} in the view ({@link LuaScripts#SCREEN_SETUP}):
         * the keyboard goes back to the editor the user came from.
         */
        void toEditor();

        /** The Neovim process ended: {@link #close}, {@code :q} in the view, or a crash. Called once. */
        void ended();
    }

    private final NeovimConnection connection;
    private final NeovimApi api;
    private final Listener listener;
    private final InputThread input = new InputThread("neovim4eclipse-screen-input");
    private final ScreenGrid screen = new ScreenGrid();
    private final AtomicBoolean ended = new AtomicBoolean();

    private ScreenSession(NeovimConnection connection, Listener listener) {
        this.connection = connection;
        this.api = connection.api();
        this.listener = listener;
    }

    /**
     * Starts a second Neovim with {@link #FIXED_ARGS} and the profile's arguments, attached at
     * {@code width} by {@code height} cells, and checks the one rule. Blocks: call it off the SWT
     * UI thread.
     *
     * @param nvimPath      the {@code -Dneovim4eclipse.nvim} value, as for the editing Neovim: the
     *                      same program, found and checked the same way ({@code util/NvimExecutable})
     * @param profileArgs   the profile's own arguments, after {@link #FIXED_ARGS}; example {@code -M}
     * @param clientVersion this plug-in's version, reported to Neovim
     * @throws IllegalStateException if Neovim started with a way to write files still open
     *                               ({@link #unsafeOptions}); the process is ended then
     * @throws Exception             if Neovim cannot start or does not answer in time
     */
    public static ScreenSession start(String nvimPath, List<String> profileArgs, String clientVersion, int width,
            int height, Listener listener) throws Exception {
        Objects.requireNonNull(listener, "listener");
        List<String> args = new ArrayList<>(FIXED_ARGS);
        args.addAll(profileArgs);
        NeovimConnection connection = NeovimConnection.open(nvimPath, args, clientVersion,
                new NeovimConnection.Ui(Math.max(1, width), Math.max(1, height), NeovimConnection.SCREEN_UI));
        ScreenSession session = new ScreenSession(connection, listener);
        try {
            // The first screen came before anyone listened (only highlight definitions are kept for
            // a late listener, NeovimConnection.Forwarder): the size is the one attached with, and
            // the cells come again with the full redraw below.
            session.screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, Math.max(1, width), Math.max(1, height)));
            connection.setIncoming(session.new Events());
            int channel = Rpc.await(session.api.getChannelId());
            Object options = Rpc.await(session.api.execLua(LuaScripts.SCREEN_SETUP, List.of(String.valueOf(channel))));
            List<String> unsafe = unsafeOptions(options);
            if (!unsafe.isEmpty()) {
                throw new IllegalStateException("The second Neovim could write files (" + String.join(", ", unsafe)
                        + "); it was not started");
            }
            if (!(options instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ui2")))) {
                LOG.info("The second Neovim has no ui2 (vim._core.ui2): long messages stop at a Press ENTER prompt"
                        + " there, and hold back new content until a key is pressed in the view");
            }
            Rpc.await(session.api.command("redraw!"));
        } catch (Exception e) {
            session.close();
            throw e;
        }
        connection.ended().thenRun(session::onEnded);
        return session;
    }

    /**
     * The options of {@link LuaScripts#SCREEN_SETUP}'s answer that leave a way to write a file
     * open, by name; empty when the one rule holds. An answer that is not a table of those
     * options counts as all of them open. Example: {@code {write = true, modeline = false,
     * updatecount = 0, undofile = false, shadafile = "NONE"}} gives {@code ["write"]}.
     */
    static List<String> unsafeOptions(Object answer) {
        Map<?, ?> options = answer instanceof Map<?, ?> m ? m : Map.of();
        List<String> unsafe = new ArrayList<>();
        for (String flag : List.of("write", "modeline", "undofile")) {
            if (!Boolean.FALSE.equals(options.get(flag))) {
                unsafe.add(flag);
            }
        }
        if (!(options.get("updatecount") instanceof Number n && n.longValue() == 0)) {
            unsafe.add("updatecount");
        }
        if (!"NONE".equals(options.get("shadafile"))) {
            unsafe.add("shadafile");
        }
        return unsafe;
    }

    /** Types {@code keys}, in Neovim's key notation ({@code "/OK<CR>"}), as {@code nvim_input} does. */
    public void inputAsync(String keys) {
        input.submit("screen input", Level.FINE, () -> api.input(keys));
    }

    /**
     * A mouse event at a cell of the screen, as {@code nvim_input_mouse} takes it. Example:
     * {@code ("left", "press", "", 3, 10)}; the wheel is {@code ("wheel", "down", "", row, col)}.
     */
    public void mouseAsync(String button, String action, String modifier, int row, int col) {
        // Grid 0: without ext_multigrid, Neovim places the mouse on its screen itself.
        input.submit("screen mouse", Level.FINE, () -> api.inputMouse(button, action, modifier, 0, row, col));
    }

    /** Asks Neovim to make its screen {@code width} by {@code height} cells ({@code nvim_ui_try_resize}). */
    public void resizeAsync(int width, int height) {
        input.submit("screen resize", Level.FINE, () -> api.uiTryResize(Math.max(1, width), Math.max(1, height)));
    }

    /**
     * Runs a profile's Lua in this Neovim, as {@code nvim_exec_lua} does, after everything asked
     * before. The future completes with Lua's answer on the RPC reader thread; it fails if the
     * session has ended.
     */
    public CompletableFuture<Object> execLuaAsync(String code, List<?> args) {
        CompletableFuture<Object> answer = new CompletableFuture<>();
        boolean queued = input.submit("screen Lua", Level.FINE, () -> api.execLua(code, args).whenComplete((value, failure) -> {
            if (failure != null) {
                answer.completeExceptionally(failure);
            } else {
                answer.complete(value);
            }
        }));
        if (!queued) {
            answer.completeExceptionally(new IllegalStateException("the second Neovim has ended"));
        }
        return answer;
    }

    /** Whether the Neovim process is still running. */
    public boolean isAlive() {
        return connection.isAlive();
    }

    /**
     * Ends the second Neovim ({@code :qa!}, then the process if it lingers) and the session's
     * thread; {@link Listener#ended} follows. Waits for the process at most a second or two
     * ({@link NeovimConnection#requestQuit}): call it off the UI thread where possible. Safe to call
     * more than once.
     */
    @Override
    public void close() {
        input.shutdown();
        connection.requestQuit();
        connection.close();
    }

    /** The process ended, for whatever reason: frees the rest, once, and tells the listener. */
    private void onEnded() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        input.shutdown();
        connection.close();
        LOG.fine("The second Neovim ended");
        listener.ended();
    }

    /** What the second Neovim sends on its own; on the RPC reader thread. */
    private final class Events implements Incoming {

        @Override
        public void notification(String method, List<?> params) {
            switch (method) {
                case "redraw" -> redraw(params);
                case "eclipse_screen_yank" -> yank(params);
                case "eclipse_screen_to_editor" -> listener.toEditor();
                default -> {
                    // nothing else is expected from a Neovim that runs no plug-ins
                }
            }
        }

        @Override
        public Object request(String method, List<?> params) {
            if ("eclipse_screen_leaving".equals(method)) {
                connection.expectEnd(); // the end of its output that follows is no crash
                return null;
            }
            throw new IllegalArgumentException("Eclipse does not answer " + method + " from the second Neovim");
        }

        private void redraw(List<?> batches) {
            for (GridEvent event : GridEvent.fromRedraw(batches, raw -> -1)) {
                screen.apply(event);
                if (event instanceof GridEvent.Flush) {
                    screen.snapshotIfChanged().ifPresent(listener::screen);
                }
            }
        }

        private void yank(List<?> params) {
            if (params.size() < 3 || !(params.get(2) instanceof List<?> raw)) {
                return;
            }
            List<String> lines = new ArrayList<>();
            for (Object line : raw) {
                lines.add(String.valueOf(line));
            }
            listener.yank(String.valueOf(params.get(0)), String.valueOf(params.get(1)), List.copyOf(lines));
        }
    }
}

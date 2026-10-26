// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Incoming;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent;

/**
 * Receives raw Neovim RPC notifications (and two requests) and turns them into typed {@link Sink} calls.
 *
 * <p>The RPC layer hands every message to {@link #notification} or {@link #request} (see
 * {@link Incoming}), which pick the handler by the message's name with a {@code switch}. Example:
 * {@code notification("eclipse_mode", ["i"])} becomes {@code sink.onMode("i")}. (These used to be
 * annotated methods that neovim-java found by reflection; a {@code switch} shows every name in one
 * place and needs no {@code public} methods.)
 *
 * <p>The notifications come from two places: Neovim's built-in buffer-attach events
 * ({@code nvim_buf_lines_event}) and custom {@code vim.rpcnotify} calls made by the Lua autocmds
 * in {@link LuaScripts#SETUP}. Malformed payloads are ignored rather than thrown, because an
 * exception here would be logged by the RPC library as an error on every keystroke.
 *
 * <p><b>Notification or request.</b> A notification ({@code vim.rpcnotify}) is fire-and-forget:
 * Neovim goes on at once. A request ({@code vim.rpcrequest}) makes Neovim wait for the answer, so
 * it is used only where Neovim needs a value back: {@code eclipse_clipboard_paste}, asked when
 * Neovim pastes from the system clipboard, and {@code eclipse_write}, asked when Neovim writes a
 * document buffer (Neovim must know whether the save worked). The return value of {@link #request}
 * is the answer.
 */
final class NotificationBridge implements Incoming {

    private static final Logger LOG = Logger.getLogger(NotificationBridge.class.getName());

    /** Buffer number used when a notification does not say which buffer it is about. */
    static final int UNKNOWN_BUFFER = -1;

    /** Typed destination for notifications. */
    interface Sink {
        void onMode(String mode);

        /**
         * @param line1 1-based line; @param col0 0-based UTF-8 byte column (Neovim's convention)
         * @param bufnr the buffer the cursor is in, or {@link #UNKNOWN_BUFFER}
         */
        void onCursor(int line1, int col0, int bufnr);

        /**
         * Lines {@code [firstLine, lastLine)} (0-based) of buffer {@code bufnr} were replaced by
         * {@code newLines}; {@code lastLine == -1} means whole buffer.
         */
        void onBufferLines(int bufnr, int firstLine, int lastLine, List<String> newLines);

        /** Neovim made {@code bufnr} its current buffer (a jump, {@code :b}, or a switch by Eclipse). */
        void onBufferEnter(int bufnr);

        /**
         * Text was yanked or deleted into a register other than {@code "+} and {@code "*}.
         *
         * @param regtype {@code v} (characters), {@code V} (lines) or Ctrl-V and a width (a block)
         */
        void onYank(List<String> lines, String regtype);

        /**
         * The clipboard provider's copy: Neovim wrote register {@code register} ({@code +} or {@code *}).
         *
         * @param lines with a final empty line for linewise and blockwise text (see {@link ClipboardRegisters})
         */
        void onClipboardCopy(String register, List<String> lines, String regtype);

        /**
         * The clipboard provider's paste: Neovim waits for the contents of register {@code register}.
         *
         * @return the answer sent to Neovim: a list of lines, or {@code [lines, regtype]}
         */
        Object onClipboardPaste(String register);

        /**
         * Neovim writes buffer {@code bufnr} ({@code :w}, {@code :update}, {@code :wall}; see
         * {@link LuaScripts#DOCUMENT_BUFFER}) and waits until Eclipse has saved it.
         *
         * @return {@code null} if the document was saved, else the error message Neovim shows
         */
        String onWrite(int bufnr);

        /** A key mapped in Neovim asks the IDE to act ({@code gd}, {@code gt}, ...). */
        void onIdeAction(IdeAction action);

        /**
         * The command-line, completion-menu and view events of one {@code redraw} notification, in
         * order (the UI is attached with {@code ext_cmdline} and {@code ext_popupmenu}; see
         * {@link CmdlineEvent}). Passed together so that the box is redrawn once for them.
         */
        void onCmdline(List<CmdlineEvent> events);

        /**
         * Neovim showed a message, the pending command changed, or a redraw ended (the UI is
         * attached with {@code ext_messages}; see {@link MessageEvent}).
         */
        void onMessageEvent(MessageEvent event);
    }

    private final Sink sink;

    NotificationBridge(Sink sink) {
        this.sink = sink;
    }

    /**
     * Dispatches a notification by name. Unknown names are ignored (see {@link Incoming}). Of the
     * {@code redraw} notifications, which Neovim sends several times per key, only the command-line
     * and message events are read; the rest is Neovim's screen, which the plug-in does not draw.
     * The command-line events go first: a command's output is titled with its command line
     * ({@code :ls}), which the same notification may carry.
     */
    @Override
    public void notification(String method, List<?> args) {
        switch (method) {
            case "eclipse_mode" -> onMode(args);
            case "eclipse_cursor" -> onCursor(args);
            case "eclipse_buf_enter" -> onBufEnter(args);
            case "eclipse_yank" -> onYank(args);
            case "eclipse_clipboard_copy" -> onClipboardCopy(args);
            case "eclipse_action" -> onAction(args);
            case "nvim_buf_lines_event" -> onBufLines(args);
            case "redraw" -> {
                List<CmdlineEvent> cmdline = CmdlineEvent.fromRedraw(args);
                if (!cmdline.isEmpty()) {
                    sink.onCmdline(cmdline);
                }
                MessageEvent.fromRedraw(args).forEach(sink::onMessageEvent);
            }
            default -> LOG.finest(() -> "Ignored notification " + method);
        }
    }

    private void onMode(List<?> args) {
        if (!args.isEmpty()) {
            sink.onMode(String.valueOf(args.get(0)));
        }
    }

    private void onCursor(List<?> args) {
        if (args.size() >= 2) {
            Integer line = toInt(args.get(0));
            Integer col = toInt(args.get(1));
            Integer buf = args.size() >= 3 ? toInt(args.get(2)) : null;
            if (line != null && col != null) {
                sink.onCursor(line, col, buf == null ? UNKNOWN_BUFFER : buf);
            }
        }
    }

    private void onBufEnter(List<?> args) {
        Integer buf = args.isEmpty() ? null : toInt(args.get(0));
        if (buf != null) {
            sink.onBufferEnter(buf);
        }
    }

    /** Payload: {@code [regcontents, regtype]}; a missing type counts as characters. */
    private void onYank(List<?> args) {
        if (!args.isEmpty() && args.get(0) != null) {
            String regtype = args.size() >= 2 && args.get(1) != null ? String.valueOf(args.get(1)) : "v";
            sink.onYank(toStringList(args.get(0)), regtype);
        }
    }

    /** Payload: {@code [register, lines, regtype]}, from the copy function of the clipboard provider. */
    private void onClipboardCopy(List<?> args) {
        if (args.size() >= 3 && args.get(0) != null && args.get(1) != null) {
            sink.onClipboardCopy(String.valueOf(args.get(0)), toStringList(args.get(1)),
                    String.valueOf(args.get(2)));
        }
    }

    /**
     * Answers a request from Neovim; see the class comment.
     *
     * @throws UnsupportedOperationException for a request this bridge does not know (answered as an error)
     */
    @Override
    public Object request(String method, List<?> args) {
        return switch (method) {
            case "eclipse_clipboard_paste" -> onClipboardPaste(args);
            case "eclipse_write" -> onWrite(args);
            default -> throw new UnsupportedOperationException("Unknown request: " + method);
        };
    }

    /**
     * Payload: {@code [register]}, from the paste function of the clipboard provider. Neovim is
     * blocked until this returns. Never throws: an exception would reach Neovim as an RPC error, and
     * an empty list (nothing to paste) is the friendlier answer.
     */
    private Object onClipboardPaste(List<?> args) {
        String register = !args.isEmpty() && args.get(0) != null ? String.valueOf(args.get(0)) : "+";
        try {
            return sink.onClipboardPaste(register);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Reading the system clipboard for Neovim failed", e);
            return List.of();
        }
    }

    /**
     * Payload: {@code [bufnr]}. Answers {@code ""} when the document was saved, else the error
     * message. Never throws, for the same reason as {@link #onClipboardPaste}: the message is what
     * Neovim shows the user, an RPC error would only show a stack trace.
     */
    private Object onWrite(List<?> args) {
        Integer buf = args.isEmpty() ? null : toInt(args.get(0));
        if (buf == null) {
            return "Eclipse got a write request without a buffer number";
        }
        try {
            String error = sink.onWrite(buf);
            return error == null ? "" : error;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Saving buffer " + buf + " for Neovim failed", e);
            return "Eclipse could not save the file: " + e.getMessage();
        }
    }

    /** Payload: {@code [name, count]} (see {@link IdeAction}). */
    private void onAction(List<?> args) {
        if (!args.isEmpty() && args.get(0) != null) {
            Integer count = args.size() >= 2 ? toInt(args.get(1)) : null;
            sink.onIdeAction(new IdeAction(String.valueOf(args.get(0)), count == null ? 0 : Math.max(0, count)));
        }
    }

    /** Payload: {@code [bufnr, changedtick, firstline, lastline, linedata, more]}. */
    private void onBufLines(List<?> args) {
        if (args.size() < 5) {
            return;
        }
        Integer first = toInt(args.get(2));
        Integer last = toInt(args.get(3));
        if (first == null || last == null) {
            return;
        }
        Integer buf = bufferNumber(args.get(0));
        sink.onBufferLines(buf == null ? UNKNOWN_BUFFER : buf, first, last, toStringList(args.get(4)));
    }

    /**
     * The buffer number in an event: Neovim sends the buffer as a handle (a MessagePack extension
     * value, decoded into a {@link Handle}); a plain number is accepted too.
     */
    static Integer bufferNumber(Object raw) {
        if (raw instanceof Handle handle && handle.kind() == Handle.Kind.BUFFER) {
            return handle.intId();
        }
        return toInt(raw);
    }

    private static List<String> toStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        } else if (raw != null) {
            out.add(String.valueOf(raw));
        }
        return out;
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

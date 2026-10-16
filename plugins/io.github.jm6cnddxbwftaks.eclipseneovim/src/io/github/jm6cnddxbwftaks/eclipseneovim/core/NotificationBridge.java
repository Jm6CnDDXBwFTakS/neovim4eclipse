// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.ArrayList;
import java.util.List;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.jackson.dataformat.MessagePackExtensionType;

import com.ensarsarajcic.neovim.java.api.types.msgpack.BaseCustomIdType;
import com.ensarsarajcic.neovim.java.corerpc.message.NotificationMessage;
import com.ensarsarajcic.neovim.java.handler.annotations.NeovimNotificationHandler;

/**
 * Receives raw Neovim RPC notifications and turns them into typed {@link Sink} calls.
 *
 * <p>Registered with neovim-java's {@code NeovimHandlerManager}, which finds the annotated
 * methods by reflection; hence this class and its handler methods must stay {@code public}.
 *
 * <p>The notifications come from two places: Neovim's built-in buffer-attach events
 * ({@code nvim_buf_lines_event}) and custom {@code vim.rpcnotify} calls made by the Lua autocmds
 * in {@link LuaScripts#SETUP}. Malformed payloads are ignored rather than thrown, because an
 * exception here would be logged by the RPC library as an error on every keystroke.
 */
public final class NotificationBridge {

    /** Buffer number used when a notification does not say which buffer it is about. */
    public static final int UNKNOWN_BUFFER = -1;

    /** Typed destination for notifications. */
    public interface Sink {
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

        /** @param linewise {@code true} when the register type is {@code V} */
        void onYank(List<String> lines, boolean linewise);
    }

    private final Sink sink;

    public NotificationBridge(Sink sink) {
        this.sink = sink;
    }

    @NeovimNotificationHandler("eclipse_mode")
    public void onMode(NotificationMessage message) {
        List<?> args = message.getArguments();
        if (args != null && !args.isEmpty()) {
            sink.onMode(String.valueOf(args.get(0)));
        }
    }

    @NeovimNotificationHandler("eclipse_cursor")
    public void onCursor(NotificationMessage message) {
        List<?> args = message.getArguments();
        if (args != null && args.size() >= 2) {
            Integer line = toInt(args.get(0));
            Integer col = toInt(args.get(1));
            Integer buf = args.size() >= 3 ? toInt(args.get(2)) : null;
            if (line != null && col != null) {
                sink.onCursor(line, col, buf == null ? UNKNOWN_BUFFER : buf);
            }
        }
    }

    @NeovimNotificationHandler("eclipse_buf_enter")
    public void onBufEnter(NotificationMessage message) {
        List<?> args = message.getArguments();
        Integer buf = args == null || args.isEmpty() ? null : toInt(args.get(0));
        if (buf != null) {
            sink.onBufferEnter(buf);
        }
    }

    @NeovimNotificationHandler("eclipse_yank")
    public void onYank(NotificationMessage message) {
        List<?> args = message.getArguments();
        if (args != null && !args.isEmpty() && args.get(0) != null) {
            boolean linewise = args.size() >= 2 && "V".equals(String.valueOf(args.get(1)));
            sink.onYank(toStringList(args.get(0)), linewise);
        }
    }

    /** Payload: {@code [bufnr, changedtick, firstline, lastline, linedata, more]}. */
    @NeovimNotificationHandler("nvim_buf_lines_event")
    public void onBufLines(NotificationMessage message) {
        List<?> args = message.getArguments();
        if (args == null || args.size() < 5) {
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
     * Buffer handles arrive as msgpack extension values: decoded by neovim-java into a
     * {@code Buffer}, or left as a raw extension whose payload is the msgpack-encoded number.
     */
    static Integer bufferNumber(Object raw) {
        if (raw instanceof BaseCustomIdType id) {
            return (int) id.getId();
        }
        if (raw instanceof MessagePackExtensionType ext) {
            try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(ext.getData())) {
                return (int) unpacker.unpackLong();
            } catch (Exception e) {
                return null;
            }
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

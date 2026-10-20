// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.msgpack.core.ExtensionTypeHeader;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

/**
 * Converts between Java values and <a href="https://msgpack.org">MessagePack</a>, for the values
 * Neovim's API uses. Built on msgpack-core, which reads and writes the format's primitives; this
 * class decides which Java type stands for which MessagePack type.
 *
 * <h2>The mapping</h2>
 * <table>
 *   <caption>Neovim API type, MessagePack type and Java type</caption>
 *   <tr><th>Neovim</th><th>MessagePack</th><th>Java (decoded)</th><th>Java (accepted when encoding)</th></tr>
 *   <tr><td>Nil</td><td>nil</td><td>{@code null}</td><td>{@code null}</td></tr>
 *   <tr><td>Boolean</td><td>bool</td><td>{@code Boolean}</td><td>{@code Boolean}</td></tr>
 *   <tr><td>Integer</td><td>int / uint, any width</td><td>{@code Long}, always</td><td>{@code Long}, {@code Integer}, {@code Short}, {@code Byte}</td></tr>
 *   <tr><td>Float</td><td>float 32 / 64</td><td>{@code Double}</td><td>{@code Double}, {@code Float}</td></tr>
 *   <tr><td>String</td><td>str</td><td>{@code String}</td><td>{@code String}</td></tr>
 *   <tr><td>(Vim Blob)</td><td>bin</td><td>{@code byte[]}</td><td>{@code byte[]}</td></tr>
 *   <tr><td>Array</td><td>array</td><td>{@code ArrayList}</td><td>any {@code List}</td></tr>
 *   <tr><td>Dictionary</td><td>map</td><td>{@code LinkedHashMap} (keeps the order)</td><td>any {@code Map}</td></tr>
 *   <tr><td>Buffer, Window, Tabpage</td><td>ext, types 0, 1, 2</td><td>{@link Handle}</td><td>{@link Handle}</td></tr>
 * </table>
 *
 * <p><b>Integers are always {@code Long}</b>, whatever their size, so a value's Java type never
 * depends on how large it happens to be. Neovim's Integer is a signed 64-bit number, so it always
 * fits; an unsigned 64-bit value above {@code Long.MAX_VALUE} makes msgpack-core throw
 * {@code MessageIntegerOverflowException}, which fails that message as a protocol error. Read
 * numbers through {@code Number} ({@code ((Number) x).intValue()}): {@code Long.valueOf(1)} does
 * not {@code equals} {@code Integer.valueOf(1)}.
 *
 * <p><b>Strings</b> that are not valid UTF-8 (a buffer with Latin-1 bytes, say) are decoded with
 * U+FFFD, the replacement character, for each bad sequence: msgpack-core's default.
 *
 * <p><b>Handles</b> are MessagePack <em>extension</em> values: a type byte and a payload, which for
 * Neovim is the handle's number, itself MessagePack-encoded. Example: buffer 9 is the three bytes
 * {@code d4 00 09}: {@code d4} "fixext 1" (one byte of payload follows the type), {@code 00} the
 * type (Buffer), {@code 09} the number 9 as a fixint. The type codes come from
 * {@code nvim_get_api_info()} and have been 0, 1, 2 since Neovim 0.1, so they are fixed here.
 *
 * <p>Nesting is decoded recursively. Input nested thousands of levels deep would overflow the
 * stack; the only input is our own Neovim child process, whose answers nest a few levels.
 */
public final class MsgpackValues {

    /** Extension type codes from {@code nvim_get_api_info().types}. */
    static final byte BUFFER_TYPE = 0;
    static final byte WINDOW_TYPE = 1;
    static final byte TABPAGE_TYPE = 2;

    private MsgpackValues() {
    }

    /**
     * Writes {@code value} (see the class comment for the accepted types).
     *
     * @throws IllegalArgumentException for a Java type with no MessagePack form here. Elements of
     *         a list or map are checked as they are written, so the output may then hold half a
     *         value: pack a message into a buffer first and send it only when it is complete.
     * @throws IOException if writing fails
     */
    public static void pack(MessagePacker packer, Object value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else if (value instanceof Boolean b) {
            packer.packBoolean(b);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            packer.packLong(((Number) value).longValue());
        } else if (value instanceof Double || value instanceof Float) {
            packer.packDouble(((Number) value).doubleValue());
        } else if (value instanceof String s) {
            packer.packString(s);
        } else if (value instanceof byte[] bytes) {
            packer.packBinaryHeader(bytes.length);
            packer.writePayload(bytes);
        } else if (value instanceof Handle handle) {
            byte[] payload = idPayload(handle.id());
            packer.packExtensionTypeHeader(typeCode(handle.kind()), payload.length);
            packer.writePayload(payload);
        } else if (value instanceof List<?> list) {
            packer.packArrayHeader(list.size());
            for (Object element : list) {
                pack(packer, element);
            }
        } else if (value instanceof Map<?, ?> map) {
            packer.packMapHeader(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pack(packer, entry.getKey());
                pack(packer, entry.getValue());
            }
        } else {
            throw new IllegalArgumentException("No MessagePack form for " + value.getClass().getName()
                    + ": " + value);
        }
    }

    /**
     * Reads one complete value (an array or map with everything in it).
     *
     * <p>Mind the two kinds of failure. msgpack-core reports bad or incomplete input with its own
     * <em>unchecked</em> {@code MessagePackException}s: {@code MessageInsufficientBufferException}
     * when the input ends in the middle of a value (also at the end of a stream: Neovim exited),
     * {@code MessageIntegerOverflowException} for an integer above {@code Long.MAX_VALUE}. A reader
     * loop must catch those as well as {@link IOException}.
     *
     * @throws IOException if reading the input fails, or for an extension type that is not a Neovim
     *         handle
     * @throws org.msgpack.core.MessagePackException if the input is not (complete) MessagePack
     */
    public static Object unpack(MessageUnpacker unpacker) throws IOException {
        switch (unpacker.getNextFormat().getValueType()) {
            case NIL:
                unpacker.unpackNil();
                return null;
            case BOOLEAN:
                return unpacker.unpackBoolean();
            case INTEGER:
                return unpacker.unpackLong();
            case FLOAT:
                return unpacker.unpackDouble();
            case STRING:
                return unpacker.unpackString();
            case BINARY:
                return unpacker.readPayload(unpacker.unpackBinaryHeader());
            case ARRAY: {
                int size = unpacker.unpackArrayHeader();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(unpack(unpacker));
                }
                return list;
            }
            case MAP: {
                int size = unpacker.unpackMapHeader();
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    Object key = unpack(unpacker);
                    map.put(key, unpack(unpacker));
                }
                return map;
            }
            case EXTENSION: {
                ExtensionTypeHeader header = unpacker.unpackExtensionTypeHeader();
                byte[] payload = unpacker.readPayload(header.getLength());
                return new Handle(kind(header.getType()), idFromPayload(payload));
            }
            default:
                throw new IOException("Unexpected MessagePack value: " + unpacker.getNextFormat());
        }
    }

    private static byte typeCode(Handle.Kind kind) {
        return switch (kind) {
            case BUFFER -> BUFFER_TYPE;
            case WINDOW -> WINDOW_TYPE;
            case TABPAGE -> TABPAGE_TYPE;
        };
    }

    private static Handle.Kind kind(byte typeCode) throws IOException {
        return switch (typeCode) {
            case BUFFER_TYPE -> Handle.Kind.BUFFER;
            case WINDOW_TYPE -> Handle.Kind.WINDOW;
            case TABPAGE_TYPE -> Handle.Kind.TABPAGE;
            default -> throw new IOException("Unknown MessagePack extension type " + typeCode
                    + " (Neovim uses 0, 1 and 2 for buffers, windows and tab pages)");
        };
    }

    /** The handle's number, MessagePack-encoded: the extension's payload. */
    private static byte[] idPayload(long id) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packLong(id);
            return packer.toByteArray();
        }
    }

    private static long idFromPayload(byte[] payload) throws IOException {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload)) {
            return unpacker.unpackLong();
        }
    }
}

// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessageInsufficientBufferException;
import org.msgpack.core.MessageIntegerOverflowException;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * {@link MsgpackValues}: round trips of every type, and decoding the exact bytes Neovim sends
 * (written out in hex, so a test shows what is on the wire).
 */
class MsgpackValuesTest {

    private static byte[] encode(Object value) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            MsgpackValues.pack(packer, value);
            return packer.toByteArray();
        }
    }

    private static Object decode(byte[] bytes) throws IOException {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            return MsgpackValues.unpack(unpacker);
        }
    }

    private static Object roundTrip(Object value) throws IOException {
        return decode(encode(value));
    }

    /** {@code "d4 00 09"} as bytes. */
    private static byte[] hex(String text) {
        String[] parts = text.split(" ");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    @Test
    void scalarsRoundTrip() throws IOException {
        assertNull(roundTrip(null));
        assertEquals(true, roundTrip(true));
        assertEquals(false, roundTrip(false));
        assertEquals(1.5, roundTrip(1.5));
        assertEquals(0.1, roundTrip(0.1), "written as float 64: float 32 would round 0.1");
        assertEquals("héllo 日本", roundTrip("héllo 日本"));
        assertEquals("", roundTrip(""));
    }

    @Test
    void integersOfEveryWidthDecodeAsLong() throws IOException {
        // Small values travel as one byte, large ones as nine; the Java type is the same.
        for (long n : new long[] { 0, 1, -1, 127, 128, -33, 65535, Integer.MAX_VALUE,
                Integer.MAX_VALUE + 1L, Long.MIN_VALUE, Long.MAX_VALUE }) {
            assertEquals(Long.valueOf(n), roundTrip(n), "value " + n);
        }
        assertEquals(Long.valueOf(7), roundTrip(7), "an Integer is written as an integer too");
        assertEquals(Long.valueOf(7), roundTrip((short) 7));
        assertEquals(Long.valueOf(7), roundTrip((byte) 7));
    }

    @Test
    void anUnsignedValueAboveLongMaxIsAProtocolError() {
        // cf ff ff ff ff ff ff ff ff: uint 64 with all bits set, 2^64 - 1. Neovim never sends it.
        assertThrows(MessageIntegerOverflowException.class,
                () -> decode(hex("cf ff ff ff ff ff ff ff ff")));
    }

    @Test
    void floatsDecodeAsDouble() throws IOException {
        assertEquals(2.5, roundTrip(2.5f));
        // ca 40 20 00 00: float 32, 2.5
        assertEquals(2.5, decode(hex("ca 40 20 00 00")));
    }

    @Test
    void invalidUtf8IsDecodedWithTheReplacementCharacter() throws IOException {
        // a3 61 e9 62: str of 3 bytes, "a", Latin-1 "é" (not UTF-8), "b"
        assertEquals("a�b", decode(hex("a3 61 e9 62")));
    }

    @Test
    void binaryDecodesAsBytes() throws IOException {
        Object decoded = roundTrip(new byte[] { 1, 2, (byte) 0xff });
        assertArrayEquals(new byte[] { 1, 2, (byte) 0xff }, (byte[]) decoded);
    }

    @Test
    void listsAndMapsRoundTripWithTheirOrder() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", "n");
        map.put("blocking", false);
        map.put("lines", List.of("a", "b"));
        Object decoded = roundTrip(List.of(1, map, List.of()));
        assertEquals(List.of(1L, map, List.of()), decoded);
        assertInstanceOf(ArrayList.class, decoded);
        Object decodedMap = ((List<?>) decoded).get(1);
        assertInstanceOf(LinkedHashMap.class, decodedMap);
        assertEquals(List.of("mode", "blocking", "lines"), new ArrayList<>(((Map<?, ?>) decodedMap).keySet()));
    }

    @Test
    void nullInsideAListSurvives() throws IOException {
        assertEquals(Arrays.asList("a", null), roundTrip(Arrays.asList("a", null)));
    }

    @Test
    void handlesRoundTrip() throws IOException {
        assertEquals(Handle.buffer(3), roundTrip(Handle.buffer(3)));
        assertEquals(Handle.window(1000), roundTrip(Handle.window(1000)));
        assertEquals(new Handle(Handle.Kind.TABPAGE, 1), roundTrip(new Handle(Handle.Kind.TABPAGE, 1)));
    }

    @Test
    void handlesUseNeovimsBytes() throws IOException {
        // fixext 1, type 0 (buffer), payload fixint 9
        assertArrayEquals(hex("d4 00 09"), encode(Handle.buffer(9)));
        assertEquals(Handle.buffer(9), decode(hex("d4 00 09")));
        // ext 8 of length 3, type 1 (window), payload uint 16 1000 (cd 03 e8)
        assertEquals(Handle.window(1000), decode(hex("c7 03 01 cd 03 e8")));
    }

    @Test
    void anUnknownExtensionTypeIsAnError() {
        // fixext 1, type 5: not a Neovim handle
        IOException e = assertThrows(IOException.class, () -> decode(hex("d4 05 01")));
        assertEquals(true, e.getMessage().contains("type 5"), e.getMessage());
    }

    @Test
    void aTypeWithNoMessagePackFormIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> encode(new Object()));
        assertEquals(true, e.getMessage().contains("java.lang.Object"), e.getMessage());
    }

    @Test
    void truncatedInputIsMsgpackCoresUncheckedException() {
        // 92 01: an array of two elements, but only one follows. Not an IOException: a reader loop
        // must catch this too (see MsgpackValues.unpack).
        assertThrows(MessageInsufficientBufferException.class, () -> decode(hex("92 01")));
    }
}

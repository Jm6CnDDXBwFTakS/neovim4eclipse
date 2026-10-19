// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

import com.ensarsarajcic.neovim.java.corerpc.client.RpcConnection;

/**
 * How the end of Neovim's output looks to neovim-java's reader, before and after the session is
 * closed on purpose. The reader logs every {@code IOException} as an error; {@link QuittingConnection.Quit}
 * is unchecked, so it ends the reader without a log line.
 */
class QuittingConnectionTest {

    /** A connection whose output from Neovim is {@code incoming}. */
    private static final class FakeConnection implements RpcConnection {
        final InputStream incoming;
        final OutputStream outgoing = new ByteArrayOutputStream();
        boolean closed;

        FakeConnection(InputStream incoming) {
            this.incoming = incoming;
        }

        @Override
        public InputStream getIncomingStream() {
            return incoming;
        }

        @Override
        public OutputStream getOutgoingStream() {
            return outgoing;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Neovim's output pipe after the process died: every read fails. */
    private static final class BrokenPipe extends InputStream {
        final IOException failure = new IOException("Stream closed");

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw failure;
        }
    }

    @Test
    void duringASessionTheBytesAndTheEndOfTheStreamPassThrough() throws IOException {
        QuittingConnection connection = new QuittingConnection(new FakeConnection(new ByteArrayInputStream(new byte[] {7, 8})));
        InputStream in = connection.getIncomingStream();

        assertEquals(7, in.read());
        byte[] buffer = new byte[4];
        assertEquals(1, in.read(buffer, 0, 4));
        assertEquals(8, buffer[0]);
        assertEquals(-1, in.read(), "an unexpected end is reported as usual");
    }

    @Test
    void duringASessionAReadFailureIsStillAnIoExceptionSoItIsLogged() {
        BrokenPipe pipe = new BrokenPipe();
        InputStream in = new QuittingConnection(new FakeConnection(pipe)).getIncomingStream();

        IOException e = assertThrows(IOException.class, () -> in.read(new byte[4], 0, 4));
        assertSame(pipe.failure, e);
    }

    @Test
    void whenQuittingTheEndOfTheStreamEndsTheReaderQuietly() throws IOException {
        QuittingConnection connection = new QuittingConnection(new FakeConnection(new ByteArrayInputStream(new byte[] {7})));
        InputStream in = connection.getIncomingStream();
        connection.quitting();

        assertEquals(7, in.read(), "what Neovim sent before exiting is still read");
        assertThrows(QuittingConnection.Quit.class, in::read);
        assertThrows(QuittingConnection.Quit.class, () -> in.read(new byte[4], 0, 4));
    }

    @Test
    void whenQuittingAReadFailureEndsTheReaderQuietly() {
        QuittingConnection connection = new QuittingConnection(new FakeConnection(new BrokenPipe()));
        connection.quitting();

        assertThrows(QuittingConnection.Quit.class, () -> connection.getIncomingStream().read());
    }

    @Test
    void closingAlsoCountsAsQuittingAndClosesTheConnection() throws IOException {
        FakeConnection fake = new FakeConnection(new BrokenPipe());
        QuittingConnection connection = new QuittingConnection(fake);

        connection.close();

        assertTrue(fake.closed);
        assertThrows(QuittingConnection.Quit.class, () -> connection.getIncomingStream().read());
    }

    @Test
    void theOutgoingStreamIsNeovimsOwn() {
        FakeConnection fake = new FakeConnection(new ByteArrayInputStream(new byte[0]));

        assertSame(fake.outgoing, new QuittingConnection(fake).getOutgoingStream());
    }
}

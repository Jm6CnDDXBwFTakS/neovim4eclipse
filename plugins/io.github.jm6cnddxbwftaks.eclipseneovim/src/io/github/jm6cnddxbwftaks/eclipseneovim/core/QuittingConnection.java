// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.ensarsarajcic.neovim.java.corerpc.client.RpcConnection;

/**
 * The connection to Neovim as neovim-java sees it, with one change: once we are shutting the
 * session down on purpose ({@link #quitting()}), the end of Neovim's output is not reported as an
 * error.
 *
 * <p>Why: neovim-java reads Neovim's output on a background thread and logs any
 * {@link IOException} of that read at ERROR, with a stack trace ("Listening to messages
 * failed!"). When Neovim exits, which is what {@code :qa!} asks it to do, the read ends with an
 * {@code IOException} too (the MessagePack parser reports "Unexpected EOF"), so every normal
 * shutdown printed an error. The library catches only {@code IOException}: after
 * {@link #quitting()} this stream turns the end of the stream, and any read failure, into
 * {@link Quit}, an unchecked exception that ends the reader thread without a log line. Before it,
 * the stream behaves exactly like Neovim's own, so a failure during a session (Neovim crashing) is
 * still logged.
 */
final class QuittingConnection implements RpcConnection {

    /** Ends neovim-java's reader thread quietly when the session is being closed on purpose. */
    static final class Quit extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Quit() {
            super("Neovim connection closed on purpose", null, false, false);
        }
    }

    private final RpcConnection delegate;
    private final InputStream incoming;
    private volatile boolean quitting;

    QuittingConnection(RpcConnection delegate) {
        this.delegate = delegate;
        this.incoming = new FilterInputStream(delegate.getIncomingStream()) {
            @Override
            public int read() throws IOException {
                return endUnlessQuitting(readChecked(() -> super.read()));
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return endUnlessQuitting(readChecked(() -> super.read(buffer, offset, length)));
            }
        };
    }

    /** From now on the end of Neovim's output is expected: call before asking Neovim to exit. */
    void quitting() {
        quitting = true;
    }

    @Override
    public InputStream getIncomingStream() {
        return incoming;
    }

    @Override
    public OutputStream getOutgoingStream() {
        return delegate.getOutgoingStream();
    }

    @Override
    public void close() throws IOException {
        quitting = true;
        delegate.close();
    }

    private interface Read {
        int run() throws IOException;
    }

    private int readChecked(Read read) throws IOException {
        try {
            return read.run();
        } catch (IOException e) {
            if (quitting) {
                throw new Quit();
            }
            throw e;
        }
    }

    private int endUnlessQuitting(int result) {
        if (result < 0 && quitting) {
            throw new Quit();
        }
        return result;
    }
}

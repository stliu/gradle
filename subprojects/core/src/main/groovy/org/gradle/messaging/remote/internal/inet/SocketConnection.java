/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.messaging.remote.internal.inet;

import com.google.common.base.Objects;
import org.gradle.api.UncheckedIOException;
import org.gradle.internal.CompositeStoppable;
import org.gradle.messaging.remote.Address;
import org.gradle.messaging.remote.internal.Connection;
import org.gradle.messaging.remote.internal.MessageIOException;
import org.gradle.messaging.remote.internal.MessageSerializer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

//TODO SF this implementation is not thread safe and apparently this is the contract of Connection
//It is certainly a problem for the dispatch method.
public class SocketConnection<T> implements Connection<T> {
    private final SocketChannel socket;
    private final Address localAddress;
    private final Address remoteAddress;
    private final MessageSerializer<T> serializer;
    private final DataInputStream instr;
    private final DataOutputStream outstr;

    public SocketConnection(SocketChannel socket, MessageSerializer<T> serializer) {
        this.socket = socket;
        this.serializer = serializer;
        try {
            // NOTE: we use non-blocking IO as there is no reliable way when using blocking IO to shutdown reads while
            // keeping writes active. For example, Socket.shutdownInput() does not work on Windows.
            socket.configureBlocking(false);
            outstr = new DataOutputStream(new SocketOutputStream(socket));
            instr = new DataInputStream(new SocketInputStream(socket));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        InetSocketAddress localSocketAddress = (InetSocketAddress) socket.socket().getLocalSocketAddress();
        localAddress = new SocketInetAddress(localSocketAddress.getAddress(), localSocketAddress.getPort());
        InetSocketAddress remoteSocketAddress = (InetSocketAddress) socket.socket().getRemoteSocketAddress();
        remoteAddress = new SocketInetAddress(remoteSocketAddress.getAddress(), remoteSocketAddress.getPort());
    }

    @Override
    public String toString() {
        return String.format("socket connection at %s with %s", localAddress, remoteAddress);
    }

    public Address getLocalAddress() {
        return localAddress;
    }

    public Address getRemoteAddress() {
        return remoteAddress;
    }

    public T receive() {
        try {
            return serializer.read(instr, localAddress, remoteAddress);
        } catch (Exception e) {
            if (isEndOfStream(e)) {
                return null;
            }
            throw new MessageIOException(String.format("Could not read message from '%s'.", remoteAddress), e);
        }
    }

    private boolean isEndOfStream(Exception e) {
        if (e instanceof EOFException) {
            return true;
        }
        if (e instanceof IOException) {
            if (Objects.equal(e.getMessage(), "An existing connection was forcibly closed by the remote host")) {
                return true;
            }
            if (Objects.equal(e.getMessage(), "An established connection was aborted by the software in your host machine")) {
                return true;
            }
            if (Objects.equal(e.getMessage(), "Connection reset by peer")) {
                return true;
            }
        }
        return false;
    }

    public void dispatch(T message) {
        try {
            serializer.write(message, outstr);
            outstr.flush();
        } catch (Exception e) {
            throw new MessageIOException(String.format("Could not write message %s to '%s'.", message, remoteAddress), e);
        }
    }

    public void requestStop() {
        //TODO SF - why doesn't it stop the outstr and socket?
        new CompositeStoppable(instr).stop();
    }

    public void stop() {
        new CompositeStoppable(instr, outstr, socket).stop();
    }

    private static class SocketInputStream extends InputStream {
        private final Selector selector;
        private final ByteBuffer buffer;
        private final SocketChannel socket;
        private final byte[] readBuffer = new byte[1];

        public SocketInputStream(SocketChannel socket) throws IOException {
            this.socket = socket;
            selector = Selector.open();
            socket.register(selector, SelectionKey.OP_READ);
            buffer = ByteBuffer.allocateDirect(4096);
            buffer.limit(0);
        }

        @Override
        public int read() throws IOException {
            int nread = read(readBuffer, 0, 1);
            if (nread <= 0) {
                return nread;
            }
            return readBuffer[0];
        }

        @Override
        public int read(byte[] dest, int offset, int max) throws IOException {
            if (max == 0) {
                return 0;
            }

            if (buffer.remaining() == 0) {
                try {
                    selector.select();
                } catch (ClosedSelectorException e) {
                    return -1;
                }
                if (!selector.isOpen()) {
                    return -1;
                }

                buffer.clear();
                int nread = socket.read(buffer);
                buffer.flip();

                if (nread < 0) {
                    return -1;
                }
            }

            int count = Math.min(buffer.remaining(), max);
            buffer.get(dest, offset, count);
            return count;
        }

        @Override
        public void close() throws IOException {
            selector.close();
        }
    }

    private static class SocketOutputStream extends OutputStream {
        private final Selector selector;
        private final SocketChannel socket;
        private final ByteBuffer buffer;
        private final byte[] writeBuffer = new byte[1];

        public SocketOutputStream(SocketChannel socket) throws IOException {
            this.socket = socket;
            selector = Selector.open();
            socket.register(selector, SelectionKey.OP_WRITE);
            buffer = ByteBuffer.allocateDirect(4096);
        }

        @Override
        public void write(int b) throws IOException {
            writeBuffer[0] = (byte) b;
            write(writeBuffer);
        }

        @Override
        public void write(byte[] src, int offset, int max) throws IOException {
            int remaining = max;
            int currentPos = offset;
            while (remaining > 0) {
                int count = Math.min(remaining, buffer.remaining());
                if (count > 0) {
                    buffer.put(src, currentPos, count);
                    remaining -= count;
                    currentPos += count;
                }
                if (buffer.remaining() == 0) {
                    flush();
                }
            }
        }

        @Override
        public void flush() throws IOException {
            buffer.flip();
            while (buffer.remaining() > 0) {
                selector.select();
                if (!selector.isOpen()) {
                    throw new EOFException();
                }
                socket.write(buffer);
            }
            buffer.clear();
        }

        @Override
        public void close() throws IOException {
            selector.close();
        }
    }
}

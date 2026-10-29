package cc.hrva.urlshortener.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

record PlainHttpSocket(ServerSocket socket, Thread acceptor) implements AutoCloseable {

    private static final byte[] PLAIN_ANSWER =
            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    static PlainHttpSocket start() throws IOException {
        final var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        final var acceptor = Thread.ofVirtual().start(() -> answerEveryConnection(socket));
        return new PlainHttpSocket(socket, acceptor);
    }

    String httpsUrl() {
        return "https://127.0.0.1:" + socket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        socket.close();
        acceptor.interrupt();
    }

    private static void answerEveryConnection(final ServerSocket socket) {
        while (!socket.isClosed()) {
            try (final Socket connection = socket.accept()) {
                connection.getInputStream().read(new byte[512]);
                connection.getOutputStream().write(PLAIN_ANSWER);
                connection.getOutputStream().flush();
            } catch (final IOException exception) {
                if (socket.isClosed()) {
                    return;
                }
            }
        }
    }

}

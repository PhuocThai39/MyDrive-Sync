package vn.edu.pbl4sync.server;

import vn.edu.pbl4sync.common.MessageTypes;
import vn.edu.pbl4sync.common.ProtocolIO;
import vn.edu.pbl4sync.common.ReceivedPacket;

import java.net.Socket;
import javax.net.ssl.SSLSocket;

public class ClientHandler implements Runnable {
    private final ServerCore server;
    private final Socket socket;

    public ClientHandler(ServerCore server, Socket socket) {
        this.server = server;
        this.socket = socket;
    }

    @Override
    public void run() {
        ClientSession session = new ClientSession(socket);
        try {
            if (!(socket instanceof SSLSocket tlsSocket)) {
                throw new IllegalStateException("TLS is required for client connections");
            }
            tlsSocket.setSoTimeout(10_000);
            tlsSocket.startHandshake();
            tlsSocket.setSoTimeout(0);
            while (!socket.isClosed()) {
                try (ReceivedPacket received = ProtocolIO.receive(socket.getInputStream())) {
                    if (received == null) break;
                    try {
                        server.handle(session, received);
                    } catch (SecurityException | IllegalArgumentException e) {
                        session.send(received.packet().response(MessageTypes.ERROR).with("message", e.getMessage()));
                    } catch (Exception e) {
                        server.log("Request error " + received.packet().type() + ": " + e.getMessage());
                        session.send(received.packet().response(MessageTypes.ERROR).with("message", "Server error: " + e.getMessage()));
                    }
                }
            }
        } catch (Exception e) {
            if (!socket.isClosed()) server.log("Client connection closed: " + e.getMessage());
        } finally {
            server.onDisconnected(session);
            session.close();
        }
    }
}

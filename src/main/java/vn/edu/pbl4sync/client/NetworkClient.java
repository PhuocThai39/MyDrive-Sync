package vn.edu.pbl4sync.client;

import vn.edu.pbl4sync.common.*;

import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.SSLSocket;

public class NetworkClient implements AutoCloseable {
    private Socket socket;
    private final Object sendLock = new Object();
    private final Map<String, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<ReceivedPacket> eventHandler = rp -> {};
    private volatile Consumer<String> disconnectHandler = m -> {};
    private volatile boolean running;

    public void connect(String host, int port) throws Exception {
        SSLSocket tlsSocket = (SSLSocket) TlsSupport.clientContext()
                .getSocketFactory().createSocket(host, port);
        try {
            TlsSupport.configureClientSocket(tlsSocket);
            tlsSocket.setSoTimeout(10_000);
            tlsSocket.startHandshake();
            tlsSocket.setSoTimeout(0);
            tlsSocket.setKeepAlive(true);
            tlsSocket.setTcpNoDelay(true);
        } catch (Exception e) {
            try { tlsSocket.close(); } catch (Exception closeError) { e.addSuppressed(closeError); }
            throw e;
        }
        socket = tlsSocket;
        running = true;
        Thread reader = new Thread(this::readLoop, "agent-network-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public Packet request(Packet packet, Duration timeout) throws Exception { return request(packet, null, timeout); }
    public Packet request(Packet packet, Path payload, Duration timeout) throws Exception {
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pending.put(packet.requestId(), future);
        try {
            send(packet, payload);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(packet.requestId());
        }
    }

    public void send(Packet packet) throws Exception { send(packet, null); }
    public void send(Packet packet, Path payload) throws Exception {
        synchronized (sendLock) {
            if (socket == null || socket.isClosed()) throw new IllegalStateException("Not connected");
            ProtocolIO.send(socket.getOutputStream(), packet, payload);
        }
    }

    private void readLoop() {
        try {
            while (running && !socket.isClosed()) {
                try (ReceivedPacket rp = ProtocolIO.receive(socket.getInputStream())) {
                    if (rp == null) break;
                    Packet p = rp.packet();
                    CompletableFuture<Packet> future = p.requestId().isBlank() ? null : pending.get(p.requestId());
                    if (future != null) future.complete(p);
                    else eventHandler.accept(rp);
                }
            }
        } catch (Exception e) {
            if (running) disconnectHandler.accept(e.getMessage());
        } finally {
            running = false;
            for (var f : pending.values()) f.completeExceptionally(new IllegalStateException("Disconnected"));
        }
    }

    public void setEventHandler(Consumer<ReceivedPacket> handler) { this.eventHandler = handler; }
    public void setDisconnectHandler(Consumer<String> handler) { this.disconnectHandler = handler; }
    public boolean isConnected() { return running && socket != null && !socket.isClosed(); }

    @Override
    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }
}

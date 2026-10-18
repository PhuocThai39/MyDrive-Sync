package vn.edu.pbl4sync.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProtocolIO {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_HEADER_SIZE = 4 * 1024 * 1024;

    private ProtocolIO() {}

    public static void send(OutputStream rawOut, Packet packet, Path payload) throws IOException {
        byte[] header = encode(packet);
        if (header.length > MAX_HEADER_SIZE) throw new IOException("Header too large");

        long payloadLength = payload == null ? 0L : Files.size(payload);
        DataOutputStream out = new DataOutputStream(rawOut);
        out.writeInt(header.length);
        out.write(header);
        out.writeLong(payloadLength);

        if (payloadLength > 0) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(payload))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }
        }
        out.flush();
    }

    public static ReceivedPacket receive(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        final int headerLength;
        try {
            headerLength = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (headerLength <= 0 || headerLength > MAX_HEADER_SIZE) {
            throw new IOException("Invalid header length: " + headerLength);
        }

        byte[] header = in.readNBytes(headerLength);
        if (header.length != headerLength) throw new EOFException("Unexpected EOF in header");
        Packet packet = decode(header);

        long payloadLength = in.readLong();
        if (payloadLength < 0) throw new IOException("Invalid payload length");

        Path temp = null;
        if (payloadLength > 0) {
            temp = Files.createTempFile("pbl4sync-payload-", ".tmp");
            try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(temp,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long remaining = payloadLength;
                while (remaining > 0) {
                    int wanted = (int) Math.min(buffer.length, remaining);
                    int n = in.read(buffer, 0, wanted);
                    if (n == -1) throw new EOFException("Unexpected EOF in payload");
                    fileOut.write(buffer, 0, n);
                    remaining -= n;
                }
            } catch (Exception e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        }
        return new ReceivedPacket(packet, temp, payloadLength);
    }

    private static byte[] encode(Packet packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeString(out, packet.type());
            writeString(out, packet.requestId());
            out.writeInt(packet.data().size());
            for (Map.Entry<String, String> e : packet.data().entrySet()) {
                writeString(out, e.getKey());
                writeString(out, e.getValue());
            }
        }
        return baos.toByteArray();
    }

    private static Packet decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String type = readString(in);
            String requestId = readString(in);
            int count = in.readInt();
            if (count < 0 || count > 100_000) throw new IOException("Invalid map size");
            Map<String, String> data = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                data.put(readString(in), readString(in));
            }
            return new Packet(type, requestId, data);
        }
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Invalid string length: " + len);
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) throw new EOFException("Unexpected EOF in string");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

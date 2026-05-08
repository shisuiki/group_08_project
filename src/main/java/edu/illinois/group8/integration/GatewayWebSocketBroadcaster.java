package edu.illinois.group8.integration;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayWebSocketBroadcaster {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong messagesSent = new AtomicLong();

    public void handle(HttpExchange exchange) throws IOException {
        String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
        String key = exchange.getRequestHeaders().getFirst("Sec-WebSocket-Key");
        if (key == null || !"websocket".equalsIgnoreCase(upgrade)) {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Upgrade", "websocket");
        exchange.getResponseHeaders().set("Connection", "Upgrade");
        exchange.getResponseHeaders().set("Sec-WebSocket-Accept", acceptKey(key));
        exchange.sendResponseHeaders(101, -1);

        Client client = new Client(exchange.getResponseBody());
        clients.add(client);
        try {
            client.send("{\"type\":\"ready\",\"message\":\"frontend adapter storage stream connected\"}");
            drainUntilClosed(exchange.getRequestBody(), client.output);
        } finally {
            clients.remove(client);
            exchange.close();
        }
    }

    public void broadcast(String payload) {
        for (Client client : clients) {
            try {
                client.send(payload);
                messagesSent.incrementAndGet();
            } catch (IOException e) {
                clients.remove(client);
                try {
                    client.output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public long clientCount() {
        return clients.size();
    }

    public long messagesSent() {
        return messagesSent.get();
    }

    private static String acceptKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest((key.trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute WebSocket accept key", e);
        }
    }

    private static void drainUntilClosed(InputStream input, OutputStream output) throws IOException {
        while (true) {
            int first = input.read();
            if (first < 0) {
                return;
            }
            int second = input.read();
            if (second < 0) {
                return;
            }
            int opcode = first & 0x0F;
            long length = second & 0x7F;
            if (length == 126L) {
                length = ByteBuffer.wrap(input.readNBytes(2)).getShort() & 0xFFFFL;
            } else if (length == 127L) {
                length = ByteBuffer.wrap(input.readNBytes(8)).getLong();
            }
            byte[] mask = (second & 0x80) != 0 ? input.readNBytes(4) : new byte[0];
            byte[] payload = input.readNBytes((int) length);
            if (mask.length == 4) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            if (opcode == 0x8) {
                return;
            }
            if (opcode == 0x9) {
                synchronized (output) {
                    output.write(frame((byte) 0x8A, payload));
                    output.flush();
                }
            }
        }
    }

    private static byte[] textFrame(String message) throws IOException {
        return frame((byte) 0x81, message.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] frame(byte firstByte, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(firstByte);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(126);
            frame.write((payload.length >>> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(127);
            ByteBuffer length = ByteBuffer.allocate(8).putLong(payload.length);
            frame.write(length.array());
        }
        frame.write(payload);
        return frame.toByteArray();
    }

    private static final class Client {
        private final OutputStream output;

        private Client(OutputStream output) {
            this.output = output;
        }

        private void send(String message) throws IOException {
            synchronized (output) {
                output.write(textFrame(message));
                output.flush();
            }
        }
    }
}

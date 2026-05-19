package edu.illinois.group8.utils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public abstract class WebSocketClient {
    private SSLSocket socket;
    private BufferedWriter writer;
    private OutputStream outputStream;
    private BufferedReader reader;
    private Thread listenerThread;
    private final AtomicBoolean closeCallbackSent = new AtomicBoolean(false);
    private final Random maskRandom = new Random();
    private volatile boolean open;
    private volatile boolean closed;
    private volatile int closeCode = -1;
    private volatile String closeReason = "";

    public WebSocketClient(String url) {
        try {
            URI uri = new URI(url);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            this.socket = (SSLSocket) factory.createSocket(uri.getHost(), 443);
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.outputStream = socket.getOutputStream();
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (Exception e) {
            onError(e);
        }
    }

    private String generateSecWebSocketKey() {
        byte[] keyBytes = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    protected void connect(String path, Map<String, String> headers) {
        try {
            writer.write("GET " + path + " HTTP/1.1\r\n");
            writer.write("Host: " + socket.getInetAddress().getHostName() + "\r\n");
            writer.write("Connection: Upgrade\r\n");
            writer.write("Upgrade: websocket\r\n");
            writer.write("Sec-Websocket-Key:" + generateSecWebSocketKey() + "\r\n");
            writer.write("Sec-WebSocket-Version: 13\r\n");

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String header = entry.getKey() + ": " + entry.getValue() + "\r\n";
                writer.write(header);
            }

            writer.write("\r\n");
            writer.flush();

            boolean accepted = false;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.contains("101 Switching Protocols")) {
                    accepted = true;
                }
            }
            if (!accepted) {
                throw new IOException("WebSocket handshake did not return 101 Switching Protocols");
            }

            open = true;
            closed = false;
            onOpen();
            startListening();
        } catch (IOException e) {
            markClosedIfOpen(-1, e.getMessage());
            try {
                socket.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            onError(e);
            notifyClosed();
        }
    }

    // Starts a new thread to listen for incoming messages
    private void startListening() {
        listenerThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192]; // Buffer for reading chunks of data
                WebSocketFrameParser parser = new WebSocketFrameParser();
                WebSocketFrameParser.Handler handler = new WebSocketFrameParser.Handler() {
                    @Override
                    public void onText(String message) {
                        onMessage(message);
                    }

                    @Override
                    public void onPing(byte[] payload) throws IOException {
                        sendControlFrame(0xA, payload);
                    }

                    @Override
                    public void onPong() {
                        // No-op. Receiving the frame is enough to keep the connection healthy.
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        markClosedIfOpen(code, reason);
                    }
                };

                while (true) {
                    int bytesRead = socket.getInputStream().read(buffer);
                    if (bytesRead == -1) {
                        markClosedIfOpen(-1, "input stream ended");
                        break;
                    }

                    if (parser.feed(buffer, 0, bytesRead, handler)) {
                        break;
                    }
                }
            } catch (IOException e) {
                markClosedIfOpen(-1, e.getMessage());
                onError(e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    onError(e);
                }
                notifyClosed();
            }
        });
        listenerThread.start();
    }

    public synchronized boolean sendMessage(String message) {
        if (!isOpen()) {
            return false;
        }
        try {
            System.out.println("Sending " + summarizeMessage(message));
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            this.outputStream.write(maskedFrame(0x1, messageBytes, maskRandom));
            this.outputStream.flush();
            return true;
        } catch (IOException e) {
            markClosedIfOpen(-1, e.getMessage());
            onError(e);
            return false;
        }
    }

    private String summarizeMessage(String message) {
        int maxLoggedChars = 1000;
        if (message.length() <= maxLoggedChars) {
            return message;
        }
        return message.substring(0, maxLoggedChars) + "... (" + message.length() + " chars)";
    }

    private synchronized void sendControlFrame(int opcode, byte[] payload) throws IOException {
        this.outputStream.write(maskedFrame(opcode, payload, maskRandom));
        this.outputStream.flush();
    }

    static byte[] maskedFrame(int opcode, byte[] payload, Random random) throws IOException {
        int length = payload.length;
        if ((opcode & 0x08) != 0 && length > 125) {
            throw new IOException("WebSocket control frame payload too large: " + length);
        }

        int headerLength = 2 + payloadLengthBytes(length) + 4;
        byte[] frame = new byte[headerLength + length];
        int offset = 0;
        frame[offset++] = (byte) (0x80 | (opcode & 0x0F));
        if (length <= 125) {
            frame[offset++] = (byte) (0x80 | length);
        } else if (length <= 65535) {
            frame[offset++] = (byte) (0x80 | 126);
            frame[offset++] = (byte) ((length >> 8) & 0xFF);
            frame[offset++] = (byte) (length & 0xFF);
        } else {
            frame[offset++] = (byte) (0x80 | 127);
            long lengthLong = length;
            for (int i = 7; i >= 0; i--) {
                frame[offset++] = (byte) ((lengthLong >> (i * 8)) & 0xFF);
            }
        }

        byte[] maskKey = new byte[4];
        random.nextBytes(maskKey);
        System.arraycopy(maskKey, 0, frame, offset, maskKey.length);
        offset += maskKey.length;
        for (int i = 0; i < length; i++) {
            frame[offset + i] = (byte) (payload[i] ^ maskKey[i % 4]);
        }
        return frame;
    }

    private static int payloadLengthBytes(int length) {
        if (length <= 125) {
            return 0;
        }
        if (length <= 65535) {
            return 2;
        }
        return 8;
    }

    public void close() {
        try {
            markClosedIfOpen(1000, "client close");
            socket.close();
            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            notifyClosed();
        } catch (IOException e) {
            onError(e);
        }
    }

    public boolean isOpen() {
        return open && !closed && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public boolean isClosed() {
        return closed || (socket != null && socket.isClosed());
    }

    public String closeDescription() {
        if (closeCode >= 0 && closeReason != null && !closeReason.isBlank()) {
            return closeCode + " " + closeReason;
        }
        if (closeCode >= 0) {
            return Integer.toString(closeCode);
        }
        if (closeReason != null && !closeReason.isBlank()) {
            return closeReason;
        }
        return "not closed";
    }

    private void markClosedIfOpen(int code, String reason) {
        if (closed) {
            return;
        }
        open = false;
        closed = true;
        closeCode = code;
        closeReason = reason == null ? "" : reason;
    }

    private void notifyClosed() {
        if (closeCallbackSent.compareAndSet(false, true)) {
            onClose();
        }
    }

    public abstract void onOpen();
    public abstract void onMessage(String message);
    public abstract void onError(Exception e);
    public abstract void onClose();
}

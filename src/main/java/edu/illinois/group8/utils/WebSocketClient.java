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
            onError(e);
        }
    }

    // Starts a new thread to listen for incoming messages
    private void startListening() {
        listenerThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192]; // Buffer for reading chunks of data
                ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream();
                ByteArrayOutputStream fragmentedPayload = new ByteArrayOutputStream();
                int fragmentedOpcode = -1;

                while (true) {
                    int bytesRead = socket.getInputStream().read(buffer);
                    if (bytesRead == -1) {
                        markClosedIfOpen(-1, "input stream ended");
                        break;
                    }

                    messageBuffer.write(buffer, 0, bytesRead);

                    // Frame parsing logic
                    while (true) {
                        byte[] messageBytes = messageBuffer.toByteArray();
                        if (messageBytes.length < 2) break;

                        int b1 = messageBytes[0] & 0xFF;
                        boolean fin = (b1 & 0x80) != 0;
                        int opcode = b1 & 0x0F;

                        if (opcode != 0 && opcode != 1 && opcode != 8 && opcode != 9 && opcode != 10) {
                            throw new IOException("Unsupported WebSocket opcode: " + opcode);
                        }

                        int b2 = messageBytes[1] & 0xFF;
                        boolean masked = (b2 & 0x80) != 0;
                        int payloadLengthCode = b2 & 0x7F;
                        int offset = 2;
                        long payloadLengthLong = payloadLengthCode;

                        if (payloadLengthCode == 126) {
                            if (messageBytes.length < 4) break;
                            payloadLengthLong = ((messageBytes[2] & 0xFF) << 8) | (messageBytes[3] & 0xFF);
                            offset += 2;
                        } else if (payloadLengthCode == 127) {
                            if (messageBytes.length < 10) break;
                            payloadLengthLong = 0;
                            for (int i = 0; i < 8; i++) {
                                payloadLengthLong = (payloadLengthLong << 8) | (messageBytes[offset++] & 0xFF);
                            }
                        }

                        if (payloadLengthLong > Integer.MAX_VALUE) {
                            throw new IOException("WebSocket frame too large: " + payloadLengthLong);
                        }

                        byte[] maskingKey = new byte[4];
                        if (masked) {
                            if (messageBytes.length < offset + 4) break;
                            System.arraycopy(messageBytes, offset, maskingKey, 0, 4);
                            offset += 4;
                        }

                        int payloadLength = (int) payloadLengthLong;
                        if (messageBytes.length < offset + payloadLength) break;

                        byte[] payloadData = new byte[payloadLength];
                        System.arraycopy(messageBytes, offset, payloadData, 0, payloadLength);
                        if (masked) {
                            for (int i = 0; i < payloadData.length; i++) {
                                payloadData[i] ^= maskingKey[i % 4];
                            }
                        }

                        if (opcode == 8) { // Close frame
                            markClosedFromFrame(payloadData);
                            return;
                        } else if (opcode == 9) { // Ping frame
                            sendControlFrame(0xA, payloadData);
                        } else if (opcode == 10) { // Pong frame
                            // No-op. Receiving the frame is enough to keep the connection healthy.
                        } else if (opcode == 1) {
                            if (fin) {
                                String message = new String(payloadData, StandardCharsets.UTF_8);
                                onMessage(message);
                            } else {
                                fragmentedOpcode = opcode;
                                fragmentedPayload.reset();
                                fragmentedPayload.write(payloadData);
                            }
                        } else if (opcode == 0) {
                            if (fragmentedOpcode == -1) {
                                throw new IOException("Received WebSocket continuation frame without an initial frame");
                            }
                            fragmentedPayload.write(payloadData);
                            if (fin) {
                                if (fragmentedOpcode == 1) {
                                    String message = fragmentedPayload.toString(StandardCharsets.UTF_8);
                                    onMessage(message);
                                }
                                fragmentedPayload.reset();
                                fragmentedOpcode = -1;
                            }
                        }

                        messageBuffer.reset();
                        int frameLength = offset + payloadLength;
                        messageBuffer.write(messageBytes, frameLength, messageBytes.length - frameLength);
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
            int messageLength = messageBytes.length;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            // First byte: FIN bit set and text frame (0x81)
            outputStream.write(0x81);

            // Determine payload length and masking
            if (messageLength <= 125) {
                outputStream.write(0x80 | messageLength); // Set mask bit with length
            } else if (messageLength <= 65535) {
                outputStream.write(0x80 | 126); // 126 indicates 2 additional length bytes
                outputStream.write((messageLength >> 8) & 0xFF); // Higher byte
                outputStream.write(messageLength & 0xFF); // Lower byte
            } else {
                outputStream.write(0x80 | 127); // 127 indicates 8 additional length bytes
                for (int i = 7; i >= 0; i--) {
                    outputStream.write((messageLength >> (i * 8)) & 0xFF);
                }
            }

            // Generate a random masking key
            byte[] maskKey = new byte[4];
            new Random().nextBytes(maskKey);
            outputStream.write(maskKey);

            // Mask the message
            for (int i = 0; i < messageBytes.length; i++) {
                messageBytes[i] ^= maskKey[i % 4];
            }
            outputStream.write(messageBytes);

            // Write the entire frame to the socket output stream
            this.outputStream.write(outputStream.toByteArray());
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

    private void sendControlFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | (opcode & 0x0F));
        int length = payload.length;
        if (length > 125) {
            throw new IOException("WebSocket control frame payload too large: " + length);
        }
        frame.write(0x80 | length);
        byte[] maskKey = new byte[4];
        new Random().nextBytes(maskKey);
        frame.write(maskKey);
        byte[] maskedPayload = payload.clone();
        for (int i = 0; i < maskedPayload.length; i++) {
            maskedPayload[i] ^= maskKey[i % 4];
        }
        frame.write(maskedPayload);
        this.outputStream.write(frame.toByteArray());
        this.outputStream.flush();
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

    private void markClosedFromFrame(byte[] payloadData) {
        int code = -1;
        String reason = "";
        if (payloadData.length >= 2) {
            code = ((payloadData[0] & 0xFF) << 8) | (payloadData[1] & 0xFF);
            if (payloadData.length > 2) {
                reason = new String(payloadData, 2, payloadData.length - 2, StandardCharsets.UTF_8);
            }
        }
        markClosedIfOpen(code, reason);
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

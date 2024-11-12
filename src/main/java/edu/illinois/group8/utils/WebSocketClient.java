package edu.illinois.group8.utils;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public abstract class WebSocketClient {
    private SSLSocket socket;
    private BufferedWriter writer;
    private OutputStream outputStream;
    private BufferedReader reader;
    private Thread listenerThread;

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

            String line;
            while (!(line = reader.readLine()).isEmpty()) {
                if (line.contains("101 Switching Protocols")) {
                    onOpen();
                }
            }

            startListening();
        } catch (IOException e) {
            onError(e);
        }
    }

    // Starts a new thread to listen for incoming messages
    private void startListening() {
        listenerThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192]; // Buffer for reading chunks of data
                ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream();

                while (true) {
                    int bytesRead = socket.getInputStream().read(buffer);
                    if (bytesRead == -1) break; // End of stream

                    messageBuffer.write(buffer, 0, bytesRead);

                    // Frame parsing logic
                    while (true) {
                        byte[] messageBytes = messageBuffer.toByteArray();
                        if (messageBytes.length < 2) break;

                        int b1 = messageBytes[0] & 0xFF;
                        boolean fin = (b1 & 0x80) != 0;
                        int opcode = b1 & 0x0F;

                        if (opcode == 8) { // Close frame
                            onClose();
                            return;
                        } else if (opcode != 1) { // Only handle text frames
                            messageBuffer.reset();
                            break;
                        }

                        int b2 = messageBytes[1] & 0xFF;
                        boolean masked = (b2 & 0x80) != 0;
                        int payloadLength = b2 & 0x7F;

                        int headerSize = 2;
                        if (payloadLength == 126) headerSize += 2;
                        else if (payloadLength == 127) headerSize += 8;
                        if (masked) headerSize += 4;

                        if (messageBytes.length < headerSize + payloadLength) break;

                        int offset = 2;
                        if (payloadLength == 126) {
                            payloadLength = ((messageBytes[2] & 0xFF) << 8) | (messageBytes[3] & 0xFF);
                            offset += 2;
                        } else if (payloadLength == 127) {
                            payloadLength = 0;
                            for (int i = 0; i < 8; i++) {
                                payloadLength = (payloadLength << 8) | (messageBytes[offset++] & 0xFF);
                            }
                        }

                        byte[] maskingKey = new byte[4];
                        if (masked) {
                            System.arraycopy(messageBytes, offset, maskingKey, 0, 4);
                            offset += 4;
                        }

                        byte[] payloadData = new byte[payloadLength];
                        System.arraycopy(messageBytes, offset, payloadData, 0, payloadLength);
                        if (masked) {
                            for (int i = 0; i < payloadData.length; i++) {
                                payloadData[i] ^= maskingKey[i % 4];
                            }
                        }

                        String message = new String(payloadData, "UTF-8");
                        onMessage(message);

                        messageBuffer.reset();
                        messageBuffer.write(messageBytes, headerSize + payloadLength, messageBytes.length - headerSize - payloadLength);
                    }
                }
            } catch (IOException e) {
                onError(e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    onError(e);
                }
                onClose();
            }
        });
        listenerThread.start();
    }

    public void sendMessage(String message) {
        try {
            System.out.println("Sending " + message);
            byte[] messageBytes = message.getBytes("UTF-8");
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
        } catch (IOException e) {
            onError(e);
        }
    }

    public void close() {
        try {
            socket.close();
            listenerThread.interrupt();
            onClose();
        } catch (IOException e) {
            onError(e);
        }
    }

    public abstract void onOpen();
    public abstract void onMessage(String message);
    public abstract void onError(Exception e);
    public abstract void onClose();
}


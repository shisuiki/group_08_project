package edu.illinois.group8.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class WebSocketFrameParser {
    private static final int INITIAL_CAPACITY = 8192;

    private byte[] pending = new byte[INITIAL_CAPACITY];
    private int readIndex;
    private int writeIndex;
    private final ByteArrayOutputStream fragmentedPayload = new ByteArrayOutputStream();
    private int fragmentedOpcode = -1;

    boolean feed(byte[] source, int offset, int length, Handler handler) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(handler, "handler");
        if (length == 0) {
            return drain(handler);
        }
        append(source, offset, length);
        return drain(handler);
    }

    private void append(byte[] source, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > source.length) {
            throw new IndexOutOfBoundsException("Invalid source slice");
        }
        ensureCapacity(length);
        System.arraycopy(source, offset, pending, writeIndex, length);
        writeIndex += length;
    }

    private boolean drain(Handler handler) throws IOException {
        boolean closeFrame = false;
        try {
            while (true) {
                int frameStart = readIndex;
                if (writeIndex - frameStart < 2) {
                    break;
                }

                int b1 = pending[frameStart] & 0xFF;
                boolean fin = (b1 & 0x80) != 0;
                int opcode = b1 & 0x0F;
                if (opcode != 0 && opcode != 1 && opcode != 8 && opcode != 9 && opcode != 10) {
                    throw new IOException("Unsupported WebSocket opcode: " + opcode);
                }

                int b2 = pending[frameStart + 1] & 0xFF;
                boolean masked = (b2 & 0x80) != 0;
                int payloadLengthCode = b2 & 0x7F;
                int offset = frameStart + 2;
                long payloadLengthLong = payloadLengthCode;

                if (payloadLengthCode == 126) {
                    if (writeIndex - offset < 2) {
                        break;
                    }
                    payloadLengthLong = ((pending[offset] & 0xFF) << 8) | (pending[offset + 1] & 0xFF);
                    offset += 2;
                } else if (payloadLengthCode == 127) {
                    if (writeIndex - offset < 8) {
                        break;
                    }
                    payloadLengthLong = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLengthLong = (payloadLengthLong << 8) | (pending[offset++] & 0xFF);
                    }
                }

                if (payloadLengthLong > Integer.MAX_VALUE) {
                    throw new IOException("WebSocket frame too large: " + payloadLengthLong);
                }

                int maskOffset = -1;
                if (masked) {
                    if (writeIndex - offset < 4) {
                        break;
                    }
                    maskOffset = offset;
                    offset += 4;
                }

                int payloadLength = (int) payloadLengthLong;
                if (writeIndex - offset < payloadLength) {
                    break;
                }

                handleFrame(handler, fin, opcode, masked, maskOffset, offset, payloadLength);
                readIndex = offset + payloadLength;
                if (opcode == 8) {
                    closeFrame = true;
                    break;
                }
            }
            return closeFrame;
        } finally {
            compact();
        }
    }

    private void handleFrame(
        Handler handler,
        boolean fin,
        int opcode,
        boolean masked,
        int maskOffset,
        int payloadOffset,
        int payloadLength
    ) throws IOException {
        if (opcode == 8) {
            ClosePayload closePayload = closePayload(masked, maskOffset, payloadOffset, payloadLength);
            handler.onClose(closePayload.code(), closePayload.reason());
        } else if (opcode == 9) {
            handler.onPing(decodedPayload(masked, maskOffset, payloadOffset, payloadLength));
        } else if (opcode == 10) {
            handler.onPong();
        } else if (opcode == 1) {
            if (fin) {
                handler.onText(textPayload(masked, maskOffset, payloadOffset, payloadLength));
            } else {
                fragmentedOpcode = opcode;
                fragmentedPayload.reset();
                writePayload(masked, maskOffset, payloadOffset, payloadLength, fragmentedPayload);
            }
        } else if (opcode == 0) {
            if (fragmentedOpcode == -1) {
                throw new IOException("Received WebSocket continuation frame without an initial frame");
            }
            writePayload(masked, maskOffset, payloadOffset, payloadLength, fragmentedPayload);
            if (fin) {
                if (fragmentedOpcode == 1) {
                    handler.onText(fragmentedPayload.toString(StandardCharsets.UTF_8));
                }
                fragmentedPayload.reset();
                fragmentedOpcode = -1;
            }
        }
    }

    private String textPayload(boolean masked, int maskOffset, int payloadOffset, int payloadLength) {
        if (!masked) {
            return new String(pending, payloadOffset, payloadLength, StandardCharsets.UTF_8);
        }
        byte[] payload = decodedPayload(masked, maskOffset, payloadOffset, payloadLength);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private ClosePayload closePayload(boolean masked, int maskOffset, int payloadOffset, int payloadLength) {
        if (payloadLength < 2) {
            return new ClosePayload(-1, "");
        }
        if (!masked) {
            int code = ((pending[payloadOffset] & 0xFF) << 8) | (pending[payloadOffset + 1] & 0xFF);
            String reason = payloadLength > 2
                ? new String(pending, payloadOffset + 2, payloadLength - 2, StandardCharsets.UTF_8)
                : "";
            return new ClosePayload(code, reason);
        }
        byte[] payload = decodedPayload(true, maskOffset, payloadOffset, payloadLength);
        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        String reason = payloadLength > 2
            ? new String(payload, 2, payloadLength - 2, StandardCharsets.UTF_8)
            : "";
        return new ClosePayload(code, reason);
    }

    private byte[] decodedPayload(boolean masked, int maskOffset, int payloadOffset, int payloadLength) {
        byte[] payload = new byte[payloadLength];
        if (masked) {
            for (int i = 0; i < payloadLength; i++) {
                payload[i] = (byte) (pending[payloadOffset + i] ^ pending[maskOffset + (i % 4)]);
            }
        } else {
            System.arraycopy(pending, payloadOffset, payload, 0, payloadLength);
        }
        return payload;
    }

    private void writePayload(
        boolean masked,
        int maskOffset,
        int payloadOffset,
        int payloadLength,
        ByteArrayOutputStream target
    ) {
        if (masked) {
            for (int i = 0; i < payloadLength; i++) {
                target.write(pending[payloadOffset + i] ^ pending[maskOffset + (i % 4)]);
            }
        } else {
            target.write(pending, payloadOffset, payloadLength);
        }
    }

    private void ensureCapacity(int additionalBytes) {
        if (pending.length - writeIndex >= additionalBytes) {
            return;
        }
        compact();
        if (pending.length - writeIndex >= additionalBytes) {
            return;
        }
        int needed = writeIndex + additionalBytes;
        int newCapacity = pending.length;
        while (newCapacity < needed) {
            newCapacity *= 2;
        }
        byte[] resized = new byte[newCapacity];
        System.arraycopy(pending, 0, resized, 0, writeIndex);
        pending = resized;
    }

    private void compact() {
        if (readIndex == 0) {
            return;
        }
        int remaining = writeIndex - readIndex;
        if (remaining > 0) {
            System.arraycopy(pending, readIndex, pending, 0, remaining);
        }
        readIndex = 0;
        writeIndex = remaining;
    }

    interface Handler {
        void onText(String message) throws IOException;

        void onPing(byte[] payload) throws IOException;

        void onPong() throws IOException;

        void onClose(int code, String reason) throws IOException;
    }

    private record ClosePayload(int code, String reason) {
    }
}

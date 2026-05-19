package edu.illinois.group8.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameParserTest {
    private static final byte[] MASK_KEY = new byte[] {0x37, (byte) 0xFA, 0x21, 0x3D};

    @Test
    void unmaskedTextFrameEmitsMessage() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        assertEquals(false, feed(parser, handler, text("hello")));

        assertEquals(List.of("hello"), handler.messages);
    }

    @Test
    void multipleTextFramesInOneChunkEmitOrderedMessages() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        feed(parser, handler, concat(text("one"), text("two")));

        assertEquals(List.of("one", "two"), handler.messages);
    }

    @Test
    void maskedTextFrameEmitsDecodedMessage() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        feed(parser, handler, frame(true, 0x1, true, bytes("masked")));

        assertEquals(List.of("masked"), handler.messages);
    }

    @Test
    void partialFrameSplitAcrossFeedsWaitsUntilComplete() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();
        byte[] frame = text("split");

        parser.feed(frame, 0, 2, handler);
        assertEquals(List.of(), handler.messages);

        parser.feed(frame, 2, frame.length - 2, handler);

        assertEquals(List.of("split"), handler.messages);
    }

    @Test
    void completeFramePlusPartialNextFramePreservesPartial() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();
        byte[] first = text("one");
        byte[] second = text("two");

        feed(parser, handler, concat(first, Arrays.copyOf(second, 3)));
        assertEquals(List.of("one"), handler.messages);

        parser.feed(second, 3, second.length - 3, handler);

        assertEquals(List.of("one", "two"), handler.messages);
    }

    @Test
    void maskedPingEmitsMaskedPongWithSamePayload() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();
        byte[] payload = new byte[] {1, 2, 3};

        feed(parser, handler, ping(payload, true));

        assertEquals(List.of(), handler.messages);
        assertEquals(1, handler.outboundFrames.size());
        byte[] pongFrame = handler.outboundFrames.get(0);
        assertEquals(0x8A, pongFrame[0] & 0xFF);
        assertTrue((pongFrame[1] & 0x80) != 0, "pong frame should be masked");
        assertArrayEquals(payload, decodePayload(pongFrame));
    }

    @Test
    void pongFrameIsNoOp() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        feed(parser, handler, pong("ignored".getBytes(StandardCharsets.UTF_8), false));

        assertEquals(List.of(), handler.messages);
        assertEquals(0, handler.outboundFrames.size());
    }

    @Test
    void fragmentedTextAssemblesMessage() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        feed(parser, handler, frame(false, 0x1, false, bytes("hel")));
        feed(parser, handler, continuation(true, "lo"));

        assertEquals(List.of("hello"), handler.messages);
    }

    @Test
    void pingBetweenTextFragmentsEmitsPongAndFinalMessage() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        feed(parser, handler, frame(false, 0x1, false, bytes("hel")));
        feed(parser, handler, ping(bytes("p"), true));
        feed(parser, handler, continuation(true, "lo"));

        assertEquals(List.of("hello"), handler.messages);
        assertEquals(1, handler.outboundFrames.size());
        assertArrayEquals(bytes("p"), decodePayload(handler.outboundFrames.get(0)));
    }

    @Test
    void continuationWithoutInitialFrameThrows() {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        assertThrows(IOException.class, () -> feed(parser, handler, continuation(true, "orphan")));
    }

    @Test
    void extended126PayloadLengthDecodes() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();
        String payload = repeat('x', 126);

        feed(parser, handler, text(payload));

        assertEquals(List.of(payload), handler.messages);
    }

    @Test
    void closeFrameReturnsTrueAndReportsCodeAndReason() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WebSocketFrameParser parser = new WebSocketFrameParser();

        assertEquals(true, feed(parser, handler, close(1001, "going away")));

        assertEquals(1, handler.closeCount);
        assertEquals(1001, handler.closeCode);
        assertEquals("going away", handler.closeReason);
    }

    private static boolean feed(
        WebSocketFrameParser parser,
        RecordingHandler handler,
        byte[] chunk
    ) throws IOException {
        return parser.feed(chunk, 0, chunk.length, handler);
    }

    private static byte[] text(String payload) {
        return frame(true, 0x1, false, bytes(payload));
    }

    private static byte[] continuation(boolean fin, String payload) {
        return frame(fin, 0x0, false, bytes(payload));
    }

    private static byte[] ping(byte[] payload, boolean masked) {
        return frame(true, 0x9, masked, payload);
    }

    private static byte[] pong(byte[] payload, boolean masked) {
        return frame(true, 0xA, masked, payload);
    }

    private static byte[] close(int code, String reason) {
        byte[] reasonBytes = bytes(reason);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return frame(true, 0x8, false, payload);
    }

    private static byte[] frame(boolean fin, int opcode, boolean masked, byte[] payload) {
        int length = payload.length;
        int headerLength = 2 + (length > 125 ? 2 : 0) + (masked ? 4 : 0);
        byte[] frame = new byte[headerLength + length];
        int offset = 0;
        frame[offset++] = (byte) ((fin ? 0x80 : 0) | (opcode & 0x0F));
        if (length <= 125) {
            frame[offset++] = (byte) ((masked ? 0x80 : 0) | length);
        } else {
            frame[offset++] = (byte) ((masked ? 0x80 : 0) | 126);
            frame[offset++] = (byte) ((length >> 8) & 0xFF);
            frame[offset++] = (byte) (length & 0xFF);
        }
        if (masked) {
            System.arraycopy(MASK_KEY, 0, frame, offset, MASK_KEY.length);
            offset += MASK_KEY.length;
            for (int i = 0; i < length; i++) {
                frame[offset + i] = (byte) (payload[i] ^ MASK_KEY[i % 4]);
            }
        } else {
            System.arraycopy(payload, 0, frame, offset, length);
        }
        return frame;
    }

    private static byte[] decodePayload(byte[] frame) {
        int offset = 2;
        int length = frame[1] & 0x7F;
        if (length == 126) {
            length = ((frame[offset] & 0xFF) << 8) | (frame[offset + 1] & 0xFF);
            offset += 2;
        }
        byte[] maskKey = Arrays.copyOfRange(frame, offset, offset + 4);
        offset += 4;
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (frame[offset + i] ^ maskKey[i % 4]);
        }
        return payload;
    }

    private static byte[] concat(byte[]... chunks) {
        int length = 0;
        for (byte[] chunk : chunks) {
            length += chunk.length;
        }
        byte[] concatenated = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, concatenated, offset, chunk.length);
            offset += chunk.length;
        }
        return concatenated;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static final class RecordingHandler implements WebSocketFrameParser.Handler {
        private final List<String> messages = new ArrayList<>();
        private final List<byte[]> outboundFrames = new ArrayList<>();
        private int closeCount;
        private int closeCode = -1;
        private String closeReason = "";

        @Override
        public void onText(String message) {
            messages.add(message);
        }

        @Override
        public void onPing(byte[] payload) throws IOException {
            outboundFrames.add(WebSocketClient.maskedFrame(0xA, payload, new Random(123L)));
        }

        @Override
        public void onPong() {
        }

        @Override
        public void onClose(int code, String reason) {
            closeCount++;
            closeCode = code;
            closeReason = reason;
        }
    }
}

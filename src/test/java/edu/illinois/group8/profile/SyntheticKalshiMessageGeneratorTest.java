package edu.illinois.group8.profile;

import edu.illinois.group8.parser.KalshiCanonicalParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SyntheticKalshiMessageGeneratorTest {
    @Test
    void generatedMessagesParseIntoCanonicalEvents() {
        SyntheticKalshiMessageGenerator generator = new SyntheticKalshiMessageGenerator(2, 1_700_000_000_000L);
        KalshiCanonicalParser parser = new KalshiCanonicalParser();

        for (int i = 0; i < 20; i++) {
            assertFalse(parser.parseWebSocketMessage(generator.next(i)).canonicalEvents().isEmpty());
        }
    }
}

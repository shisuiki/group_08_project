package edu.illinois.group8;

import edu.illinois.group8.config.BackendConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiSystemTest {
    @Test
    void parsesPaginatedMarketDiscoveryResponse() {
        String response = """
            {
              "cursor": "next-page",
              "markets": [
                {"ticker": "KXONE-26MAY03-Y"},
                {"ticker": "KXTWO-26MAY03-N"},
                {"ticker": ""}
              ]
            }
            """;

        assertEquals(
            java.util.List.of("KXONE-26MAY03-Y", "KXTWO-26MAY03-N"),
            KalshiSystem.parseMarketTickers(response)
        );
        assertEquals("next-page", KalshiSystem.parseCursor(response));
    }

    @Test
    void openMarketModeDoesNotRequireSeriesOrExplicitTickers() {
        BackendConfig config = BackendConfig.from(Map.of(
            "KALSHI_KEY_ID", "key",
            "KALSHI_KEY_PATH", "/tmp/key.pem",
            "KALSHI_MARKET_SELECTION_MODE", "open_markets"
        ));

        assertTrue(config.openMarketSelectionEnabled());
        assertFalse(config.sourceSequenceMonitorEnabled());
        assertTrue(config.orderBookDerivedEnabled());
        assertDoesNotThrow(config::validateForLiveIngestion);
    }
}

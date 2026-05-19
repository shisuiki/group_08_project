package edu.illinois.group8.wrapper;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KalshiWebSocketClientTest {
    @Test
    void updateCommandBuildsAddAndDeleteMarketRequests() {
        assertUpdateCommand("add_markets");
        assertUpdateCommand("delete_markets");
    }

    @Test
    void updateCommandBuildsGetSnapshotWithMarketTickersArray() {
        JSONObject command = KalshiWebSocketClient.buildUpdateCommand(
            42L,
            "get_snapshot",
            new String[] {"MARKET-1", "MARKET-2"},
            1001L
        );

        JSONObject params = params(command);
        assertEquals("update_subscription", command.get("cmd"));
        assertEquals(1001L, ((Number) command.get("id")).longValue());
        assertEquals("get_snapshot", params.get("action"));
        assertEquals(42L, ((Number) ((JSONArray) params.get("sids")).get(0)).longValue());
        assertMarketTickers(params, "MARKET-1", "MARKET-2");
        assertFalse(params.containsKey("market_ticker"));
    }

    @Test
    void unsupportedUpdateActionIsRejected() {
        assertFalse(KalshiWebSocketClient.isSupportedUpdateAction("resubscribe"));
        assertFalse(KalshiWebSocketClient.isSupportedUpdateAction(null));
        assertThrows(IllegalArgumentException.class, () ->
            KalshiWebSocketClient.buildUpdateCommand(42L, "resubscribe", new String[] {"MARKET-1"}, 1001L));
    }

    private static void assertUpdateCommand(String action) {
        JSONObject command = KalshiWebSocketClient.buildUpdateCommand(
            42L,
            action,
            new String[] {"MARKET-1", "MARKET-2"},
            1001L
        );

        JSONObject params = params(command);
        assertEquals("update_subscription", command.get("cmd"));
        assertEquals(1001L, ((Number) command.get("id")).longValue());
        assertEquals(action, params.get("action"));
        assertEquals(42L, ((Number) ((JSONArray) params.get("sids")).get(0)).longValue());
        assertMarketTickers(params, "MARKET-1", "MARKET-2");
    }

    private static JSONObject params(JSONObject command) {
        return (JSONObject) command.get("params");
    }

    private static void assertMarketTickers(JSONObject params, String... expectedTickers) {
        JSONArray marketTickers = (JSONArray) params.get("market_tickers");
        assertEquals(expectedTickers.length, marketTickers.size());
        for (int i = 0; i < expectedTickers.length; i++) {
            assertEquals(expectedTickers[i], marketTickers.get(i));
        }
    }
}

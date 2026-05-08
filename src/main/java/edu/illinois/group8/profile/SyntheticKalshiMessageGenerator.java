package edu.illinois.group8.profile;

import java.util.Locale;

public final class SyntheticKalshiMessageGenerator {
    private final String[] markets;
    private final long[] bookSequences;
    private long tradeId;
    private long timestampMs;

    public SyntheticKalshiMessageGenerator(int marketCount, long startTimestampMs) {
        if (marketCount <= 0) {
            throw new IllegalArgumentException("marketCount must be positive");
        }
        this.markets = new String[marketCount];
        this.bookSequences = new long[marketCount];
        for (int i = 0; i < marketCount; i++) {
            markets[i] = "PROFILE-MARKET-" + i;
        }
        this.timestampMs = startTimestampMs;
    }

    public String next(int index) {
        int marketIndex = index % markets.length;
        int messageKind = index % 10;
        timestampMs += 10;
        if (bookSequences[marketIndex] == 0L || messageKind == 0) {
            bookSequences[marketIndex] = 1L;
            return snapshot(marketIndex);
        }
        if (messageKind <= 5) {
            bookSequences[marketIndex]++;
            return delta(marketIndex, index);
        }
        if (messageKind <= 7) {
            return trade(marketIndex, index);
        }
        return ticker(marketIndex, index);
    }

    private String snapshot(int marketIndex) {
        return String.format(
            Locale.ROOT,
            "{\"type\":\"orderbook_snapshot\",\"sid\":2,\"seq\":%d,\"msg\":{\"market_ticker\":\"%s\",\"market_id\":\"m-%d\",\"yes_dollars_fp\":[[\"0.4100\",\"100.00\"],[\"0.4300\",\"50.00\"]],\"no_dollars_fp\":[[\"0.5100\",\"75.00\"],[\"0.5500\",\"40.00\"]],\"ts_ms\":%d}}",
            bookSequences[marketIndex],
            markets[marketIndex],
            marketIndex,
            timestampMs
        );
    }

    private String delta(int marketIndex, int index) {
        boolean yesSide = (index & 1) == 0;
        String side = yesSide ? "yes" : "no";
        String price = yesSide ? "0.4400" : "0.5200";
        String delta = ((index / 10) & 1) == 0 ? "1.00" : "-1.00";
        return String.format(
            Locale.ROOT,
            "{\"type\":\"orderbook_delta\",\"sid\":2,\"seq\":%d,\"msg\":{\"market_ticker\":\"%s\",\"market_id\":\"m-%d\",\"price_dollars\":\"%s\",\"delta_fp\":\"%s\",\"side\":\"%s\",\"ts_ms\":%d}}",
            bookSequences[marketIndex],
            markets[marketIndex],
            marketIndex,
            price,
            delta,
            side,
            timestampMs
        );
    }

    private String trade(int marketIndex, int index) {
        tradeId++;
        String takerSide = (index & 1) == 0 ? "yes" : "no";
        return String.format(
            Locale.ROOT,
            "{\"type\":\"trade\",\"sid\":11,\"msg\":{\"trade_id\":\"profile-trade-%d\",\"market_ticker\":\"%s\",\"market_id\":\"m-%d\",\"yes_price_dollars\":\"0.4400\",\"no_price_dollars\":\"0.5600\",\"count_fp\":\"3.00\",\"taker_side\":\"%s\",\"ts_ms\":%d}}",
            tradeId,
            markets[marketIndex],
            marketIndex,
            takerSide,
            timestampMs
        );
    }

    private String ticker(int marketIndex, int index) {
        return String.format(
            Locale.ROOT,
            "{\"type\":\"ticker\",\"sid\":12,\"msg\":{\"market_ticker\":\"%s\",\"market_id\":\"m-%d\",\"price_dollars\":\"0.4400\",\"yes_bid_dollars\":\"0.4300\",\"yes_ask_dollars\":\"0.4900\",\"yes_bid_size_fp\":\"50.00\",\"yes_ask_size_fp\":\"40.00\",\"last_trade_size_fp\":\"3.00\",\"volume_fp\":\"%d.00\",\"open_interest_fp\":\"200.00\",\"dollar_volume\":1000,\"dollar_open_interest\":200,\"ts_ms\":%d}}",
            markets[marketIndex],
            marketIndex,
            1000 + index,
            timestampMs
        );
    }
}

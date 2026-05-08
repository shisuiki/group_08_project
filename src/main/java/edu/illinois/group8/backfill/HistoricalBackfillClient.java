package edu.illinois.group8.backfill;

import edu.illinois.group8.wrapper.RequestParameters;

public interface HistoricalBackfillClient {
    String getMarkets(RequestParameters params);

    String getTrades(RequestParameters params);

    String getMarketOrderbook(String ticker, RequestParameters params);

    String getMarketCandlesticks(String ticker, String seriesTicker, Integer startTs, Integer endTs, Integer periodInterval);
}

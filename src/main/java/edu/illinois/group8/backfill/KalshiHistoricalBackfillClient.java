package edu.illinois.group8.backfill;

import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;

public final class KalshiHistoricalBackfillClient implements HistoricalBackfillClient {
    private final KalshiWrapper wrapper;

    public KalshiHistoricalBackfillClient(KalshiWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public String getMarkets(RequestParameters params) {
        return wrapper.getMarkets(params);
    }

    @Override
    public String getTrades(RequestParameters params) {
        return wrapper.getTrades(params);
    }

    @Override
    public String getMarketOrderbook(String ticker, RequestParameters params) {
        return wrapper.getMarketOrderbook(ticker, params);
    }

    @Override
    public String getMarketCandlesticks(String ticker, String seriesTicker, Integer startTs, Integer endTs, Integer periodInterval) {
        return wrapper.getMarketCandlesticks(ticker, seriesTicker, startTs, endTs, periodInterval);
    }
}

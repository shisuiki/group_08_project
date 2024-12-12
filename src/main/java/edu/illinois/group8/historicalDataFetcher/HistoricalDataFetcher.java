package edu.illinois.group8.fetcher;

import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;

public class HistoricalDataFetcher {

    private KalshiWrapper kalshiWrapper;

    public HistoricalDataFetcher(KalshiWrapper wrapper) {
        this.kalshiWrapper = wrapper;
    }

    public String fetchTradeData(long startTime, long endTime, String cursor) {
        try {
            RequestParameters params = new RequestParameters();
            params.addParam("min_ts", startTime);
            params.addParam("max_ts", endTime);
            if (cursor != null && !cursor.isEmpty()) {
                params.addParam("cursor", cursor);
            }
            return kalshiWrapper.getTrades(params);
        } catch (Exception e) {
            System.err.println("Error fetching trade data: " + e.getMessage());
            return null;
        }
    }

}

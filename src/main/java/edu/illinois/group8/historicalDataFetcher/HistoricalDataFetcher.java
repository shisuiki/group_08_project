package edu.illinois.group8.historicalDataFetcher;

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
            System.out.println("start time: " + startTime);
            System.out.println("end time: " + endTime);
            System.out.println("cursor: " + cursor);
            String output = kalshiWrapper.getTrades(params);
            System.out.println("request output: ");
            System.out.println(output);
            return output;
        } catch (Exception e) {
            System.err.println("Error fetching trade data: " + e.getMessage());
            return null;
        }
    }

    public String fetchEvents(String cursor) {
        try {
            RequestParameters params = new RequestParameters();
            params.addParam("status", "open,unopened");
            params.addParam("with_nested_markets", true);
            if (cursor != null && !cursor.isEmpty()) {
                params.addParam("cursor", cursor);
            }
            return kalshiWrapper.getEvents(params);
        } catch (Exception e) {
            System.err.println("Error fetching events: " + e.getMessage());
            return null;
        }
    }

    public String fetchSeries(String seriesTicker) {
        try {
            return kalshiWrapper.getSeries(seriesTicker);
        } catch (Exception e) {
            System.err.println("Error fetching events: " + e.getMessage());
            return null;
        }
    }

}

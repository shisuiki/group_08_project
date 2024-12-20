package edu.illinois.group8.historicalDataFetcher;

import edu.illinois.group8.wrapper.KalshiWrapper;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.HashSet;
import java.util.Set;

public class HistoricalFetcherRunner {

    private final HistoricalDataFetcher fetcher;
    private final DataProcessor processor;
    private final ThrottlingManager throttler;

    public HistoricalFetcherRunner(HistoricalDataFetcher fetcher, DataProcessor processor, ThrottlingManager throttler) {
        this.fetcher = fetcher;
        this.processor = processor;
        this.throttler = throttler;
    }

    public void fetchTradesRunner(long startTime, long endTime, String tableName) {
        String cursor = "null"; // Initialize cursor as null for the first request
        boolean moreData = true; // Flag to indicate if more data is available

        while (moreData) {
            try {
                throttler.throttleIfNecessary(); // Ensure API rate limits are respected

                // Fetch trade data with the current cursor
                System.out.println("trying to fetch trades");
                String jsonData = fetcher.fetchTradeData(startTime, endTime, cursor);
                System.out.println("successfully fetched trade data");
                if (jsonData == null || jsonData.isEmpty()) {
                    System.out.println("No more data to fetch or an error occurred.");
                    break;
                }

                // Parse JSON response
                JSONObject responseObject = new JSONObject(jsonData);

                // Check for the cursor in the response for pagination
                cursor = responseObject.optString("cursor", null);

                // Process and store data if available
                if (responseObject.has("trades")) {
                    JSONArray tradesData = responseObject.getJSONArray("trades");
                    System.out.println("going to process data");
                    processor.processAndStoreData(tradesData.toString(), tableName);
                }

                // Determine if more data is available
                moreData = (cursor != null);

            } catch (Exception e) {
                System.err.println("Error in fetchTradesRunner: " + e.getMessage());
                moreData = false; // Stop fetching on error
            }
        }

        System.out.println("Trade data fetching complete.");
    }

    public void fetchSymbols() {
        String cursor = null;

        Set<String> seriesTickers = new HashSet<>();
        Set<JSONObject> seriesSet = new HashSet<>();
        Set<JSONObject> marketSet = new HashSet<>();
        Set<JSONObject> eventSet = new HashSet<>();

        while (cursor == null || !cursor.isEmpty()) {
            try {
                throttler.throttleIfNecessary();

                String jsonData = fetcher.fetchEvents(cursor);
                if (jsonData == null || jsonData.isEmpty()) {
                    System.out.println("No more data to fetch or an error occurred.");
                    break;
                }
                JSONObject responseObject = new JSONObject(jsonData);
                cursor = responseObject.getString("cursor");

                if (responseObject.has("events")) {
                    JSONArray eventsArray = responseObject.getJSONArray("events");

                    for (Object eventObject : eventsArray) {
                        JSONObject event = (JSONObject) eventObject;
                        String seriesTicker = event.getString("series_ticker");
                        if (!seriesTicker.isEmpty()) seriesTickers.add(seriesTicker);
                        if (event.has("markets")) {
                            JSONArray marketsArray = event.getJSONArray("markets");
                            for (Object marketObject : marketsArray) {
                                JSONObject market = (JSONObject) marketObject;
                                market.put("series_ticker", seriesTicker);
                                marketSet.add(market);
                            }
                        }
                        eventSet.add(event);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error while fetching events/markets: " + e.getMessage());
            }
        }

        for (String seriesTicker : seriesTickers) {
            try {
                throttler.throttleIfNecessary();

                String jsonData = fetcher.fetchSeries(seriesTicker);
                if (jsonData == null || jsonData.isEmpty()) {
                    System.out.println("No more data to fetch or an error occurred.");
                    break;
                }
                JSONObject responseObject = new JSONObject(jsonData);
                JSONObject seriesObject = responseObject.getJSONObject("series");
                seriesSet.add(seriesObject);
            } catch (Exception e) {
                System.err.println("Error while fetching series: " + e.getMessage());
            }
        }

        JSONArray seriesArray = new JSONArray();
        seriesSet.forEach(seriesArray::put);
        JSONArray eventsArray = new JSONArray();
        eventSet.forEach(eventsArray::put);
        JSONArray marketsArray = new JSONArray();
        marketSet.forEach(marketsArray::put);

        System.out.println(seriesArray.length() + " series");
        System.out.println(eventsArray.length() + " events");
        System.out.println(marketsArray.length() + " markets");

        processor.processAndStoreData(seriesArray.toString(), "SeriesMaster");
        processor.processAndStoreData(eventsArray.toString(), "EventsMaster");
        processor.processAndStoreData(marketsArray.toString(), "SymbolMaster");
    }

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        // Initialize the Kalshi API wrapper
        KalshiWrapper wrapper = new KalshiWrapper("https://api.elections.kalshi.com", dotenv.get("KALSHI_KEY_ID"), "keys/brian.key");

        // Set up the data fetcher, processor, and throttler
        HistoricalDataFetcher fetcher = new HistoricalDataFetcher(wrapper);
        String redshift_url = "jdbc:redshift://kalshi-cluster.cqnzqxki7plp.us-east-2.redshift.amazonaws.com:5439/processed_data";
        DataProcessor processor = new DataProcessor(redshift_url, dotenv.get("DB_USER"), dotenv.get("DB_PASSWORD"));
        ThrottlingManager throttler = new ThrottlingManager(10, 1000); // 10 requests per second

        // Configure fetch parameters
        long startTime = 1640995200; // Example: Jan 1, 2022, 00:00:00 UTC
        long endTime = 1641081600;   // Example: Jan 2, 2022, 00:00:00 UTC
        String tableName = "Trades";

        // Run the fetch and store process
        HistoricalFetcherRunner runner = new HistoricalFetcherRunner(fetcher, processor, throttler);
        runner.fetchTradesRunner(startTime, endTime, tableName);

//        HistoricalFetcherRunner runner = new HistoricalFetcherRunner(fetcher, processor, throttler);
//        runner.fetchSymbols();
    }
}

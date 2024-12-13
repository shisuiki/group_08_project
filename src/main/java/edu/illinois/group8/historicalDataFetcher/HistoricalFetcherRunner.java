package edu.illinois.group8.historicalDataFetcher;

import edu.illinois.group8.historicalDataFetcher.HistoricalDataFetcher;
import edu.illinois.group8.historicalDataFetcher.DataProcessor;
import edu.illinois.group8.historicalDataFetcher.ThrottlingManager;
import edu.illinois.group8.wrapper.KalshiWrapper;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.cdimascio.dotenv.Dotenv;

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
        String cursor = null; // Initialize cursor as null for the first request
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
                moreData = (cursor != null && responseObject.has("data") && responseObject.getJSONArray("data").length() > 0);

            } catch (Exception e) {
                System.err.println("Error in fetchTradesRunner: " + e.getMessage());
                moreData = false; // Stop fetching on error
            }
        }

        System.out.println("Trade data fetching complete.");
    }

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        // Initialize the Kalshi API wrapper
        KalshiWrapper wrapper = new KalshiWrapper("https://api.elections.kalshi.com", dotenv.get("KALSHI_KEY_ID"), "keys/anushree.txt");

        // Set up the data fetcher, processor, and throttler
        HistoricalDataFetcher fetcher = new HistoricalDataFetcher(wrapper);
        String redshift_url = "jdbc:redshift://kalshi-cluster.cqnzqxki7plp.us-east-2.redshift.amazonaws.com:5439/processed_data";
        DataProcessor processor = new DataProcessor(redshift_url, dotenv.get("DB_USER"), dotenv.get("DB_PASSWORD"));
        ThrottlingManager throttler = new ThrottlingManager(10, 1000); // 100 requests per minute

        // Configure fetch parameters
        long startTime = 1640995200; // Example: Jan 1, 2022, 00:00:00 UTC
        long endTime = 1641081600;   // Example: Jan 2, 2022, 00:00:00 UTC
        String tableName = "Trades";

        // Run the fetch and store process
        HistoricalFetcherRunner runner = new HistoricalFetcherRunner(fetcher, processor, throttler);
        runner.fetchTradesRunner(startTime, endTime, tableName);
    }
}

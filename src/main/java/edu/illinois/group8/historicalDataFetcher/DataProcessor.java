package edu.illinois.group8.historicalDataFetcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.Timestamp;
import java.sql.Date;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class DataProcessor {

    private String redshiftUrl;
    private String username;
    private String password;

    public DataProcessor(String redshiftUrl, String username, String password) {
        this.redshiftUrl = redshiftUrl;
        this.username = username;
        this.password = password;
    }

    public void processAndStoreData(String jsonData, String tableName) {
        try {
            // Parse JSON data
            JSONArray dataArray = new JSONArray(jsonData);

            // Connect to Redshift
            try (Connection connection = DriverManager.getConnection(redshiftUrl, username, password)) {
                System.out.println("Connecting to Redshift...");
                getPreparedStatement(connection, tableName, dataArray); // Execute insertion logic
            }
        } catch (Exception e) {
            System.err.println("Error processing or storing data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void getPreparedStatement(Connection connection, String tableName, JSONArray dataArray) throws Exception {
        String insertQuery;
        PreparedStatement statement = null;

        switch (tableName) {
            case "Trades":
                insertQuery = "INSERT INTO Trades (TradeDate, TradeTimestamp, Symbol, Price, Size, Side) VALUES (?, ?, ?, ?, ?, ?)";
                statement = connection.prepareStatement(insertQuery);
                
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject trade = dataArray.getJSONObject(i);
                    
                    try {
                        // Print each trade
                        // System.out.println("Processing trade: " + trade.toString());

                        String createdTime = trade.getString("created_time");
                        Date tradeDate = Date.valueOf(createdTime.substring(0, 10)); // Extract date
                        Timestamp tradeTimestamp = Timestamp.valueOf(createdTime.replace("Z", "").replace("T", " ")); // Convert to timestamp

                        String symbol = trade.getString("ticker");
                        int size = trade.getInt("count");
                        String side = trade.getString("taker_side");

                        // Determine price based on side
                        double price = side.equalsIgnoreCase("yes") ? trade.getDouble("yes_price") : trade.getDouble("no_price");

                        // Set parameters for insertion
                        statement.setDate(1, tradeDate);
                        statement.setTimestamp(2, tradeTimestamp);
                        statement.setString(3, symbol);
                        statement.setDouble(4, price);
                        statement.setInt(5, size);
                        statement.setString(6, side);

                        statement.addBatch(); // Add to batch
                    } catch (Exception ex) {
                        System.err.println("Error processing trade at index " + i + ": " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                // Execute the batch
                try {
                    statement.executeBatch();
                    System.out.println("Insert successful for Trades table.");
                } catch (Exception e) {
                    System.err.println("Batch execution error: " + e.getMessage());
                    e.printStackTrace();
                }
                break;
            case "SymbolMaster":
                insertQuery = "INSERT INTO SymbolMaster (MarketTicker, SeriesTicker, EventTicker, Title, Subtitle, OpenTime, CloseTime, ExpirationTime, Status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                statement = connection.prepareStatement(insertQuery);

                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject market = dataArray.getJSONObject(i);

                    try {
                        String symbol = market.getString("ticker");
                        if (symbol.length() >= 50) {
                            System.out.println("Symbol \"" + symbol + "\" has length " + symbol.length() + ". Skipping");
                            continue;
                        }
                        String seriesTicker = market.getString("series_ticker");
                        String eventTicker = market.getString("event_ticker");
                        String title = market.getString("title");
                        String subtitle = market.getString("yes_sub_title");
                        String status = market.getString("status");

                        Timestamp openTimestamp = Timestamp.valueOf(market.getString("open_time").replace("Z", "").replace("T", " "));
                        Timestamp closeTimestamp = Timestamp.valueOf(market.getString("close_time").replace("Z", "").replace("T", " "));
                        Timestamp expTimestamp = Timestamp.valueOf(market.getString("expiration_time").replace("Z", "").replace("T", " "));

                        statement.setString(1, symbol);
                        statement.setString(2, seriesTicker);
                        statement.setString(3, eventTicker);
                        statement.setString(4, title);
                        statement.setString(5, subtitle);
                        statement.setTimestamp(6, openTimestamp);
                        statement.setTimestamp(7, closeTimestamp);
                        statement.setTimestamp(8, expTimestamp);
                        statement.setString(9, status);

                        statement.addBatch();
                    } catch (Exception ex) {
                        System.err.println("Error processing market at index " + i + ": " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                try {
                    statement.executeBatch();
                    System.out.println("Insert successful for SymbolMaster table.");
                } catch (Exception e) {
                    System.err.println("Batch execution error: " + e.getMessage());
                    e.printStackTrace();
                }
                break;
            case "EventsMaster":
                insertQuery = "INSERT INTO EventsMaster (EventTicker, SeriesTicker, Title, Subtitle, MutuallyExclusive) VALUES (?, ?, ?, ?, ?)";
                statement = connection.prepareStatement(insertQuery);

                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject event = dataArray.getJSONObject(i);

                    try {
                        String eventTicker = event.getString("event_ticker");
                        if (eventTicker.length() >= 50) {
                            System.out.println("Event \"" + eventTicker + "\" has length " + eventTicker.length() + ". Skipping");
                            continue;
                        }
                        String seriesTicker = event.getString("series_ticker");
                        String title = event.getString("title");
                        String subtitle = event.getString("sub_title");
                        boolean mutuallyExclusive = event.getBoolean("mutually_exclusive");

                        statement.setString(1, seriesTicker);
                        statement.setString(2, eventTicker);
                        statement.setString(3, title);
                        statement.setString(4, subtitle);
                        statement.setBoolean(5, mutuallyExclusive);

                        statement.addBatch();
                    } catch (Exception ex) {
                        System.err.println("Error processing event at index " + i + ": " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                try {
                    statement.executeBatch();
                    System.out.println("Insert successful for EventsMaster table.");
                } catch (Exception e) {
                    System.err.println("Batch execution error: " + e.getMessage());
                    e.printStackTrace();
                }
                break;
            case "SeriesMaster":
                insertQuery = "INSERT INTO SeriesMaster (SeriesTicker, Title, Category, Frequency, ContractURL) VALUES (?, ?, ?, ?, ?)";
                statement = connection.prepareStatement(insertQuery);

                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject series = dataArray.getJSONObject(i);

                    try {
                        String seriesTicker = series.getString("ticker");
                        String title = series.getString("title");
                        String category = series.getString("category");
                        String frequency = series.getString("frequency");
                        String contractURL = series.getString("contract_url");

                        statement.setString(1, seriesTicker);
                        statement.setString(2, title);
                        statement.setString(3, category);
                        statement.setString(4, frequency);
                        statement.setString(5, contractURL);

                        statement.addBatch();
                    } catch (Exception ex) {
                        System.err.println("Error processing series at index " + i + ": " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                try {
                    statement.executeBatch();
                    System.out.println("Insert successful for SeriesMaster table.");
                } catch (Exception e) {
                    System.err.println("Batch execution error: " + e.getMessage());
                    e.printStackTrace();
                }

                break;
            default:
                System.err.println("Unsupported table: " + tableName);
        }
    }
}

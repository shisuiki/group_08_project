// package edu.illinois.group8.historicalDataFetcher;

// import org.json.JSONArray;
// import org.json.JSONObject;
// import java.sql.Timestamp;
// import java.sql.Date;

// import java.sql.Connection;
// import java.sql.DriverManager;
// import java.sql.PreparedStatement;

// public class DataProcessor {

//     private String redshiftUrl;
//     private String username;
//     private String password;

//     public DataProcessor(String redshiftUrl, String username, String password) {
//         this.redshiftUrl = redshiftUrl;
//         this.username = username;
//         this.password = password;
//     }

//     public void processAndStoreData(String jsonData, String tableName) {
//         try {
//             // Parse JSON data
//             JSONArray dataArray = new JSONArray(jsonData);

//             // Connect to Redshift
//             try (Connection connection = DriverManager.getConnection(redshiftUrl, username, password)) {
//                 System.out.println("connecting to redshift");
//                 try (PreparedStatement statement = getPreparedStatement(connection, tableName, dataArray)) {
//                     if (statement != null) {
//                         statement.executeBatch();
//                     } else {
//                         System.err.println("No processing logic available for table: " + tableName);
//                     }
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("Error processing or storing data: " + e.getMessage());
//         }
//     }

//     private PreparedStatement getPreparedStatement(Connection connection, String tableName, JSONArray dataArray) throws Exception {
//         String insertQuery;
//         PreparedStatement statement = null;

//         switch (tableName) {
//             case "Trades":
//                 insertQuery = "INSERT INTO Trades (TradeDate, TradeTimestamp, Symbol, Price, Size, Side) VALUES (?, ?, ?, ?, ?, ?)";
//                 statement = connection.prepareStatement(insertQuery);
        
//                 for (int i = 0; i < dataArray.length(); i++) {
//                     JSONObject trade = dataArray.getJSONObject(i);
        
//                     String createdTime = trade.getString("created_time");
//                     Date tradeDate = Date.valueOf(createdTime.substring(0, 10)); // Extract date: YYYY-MM-DD
//                     Timestamp tradeTimestamp = Timestamp.valueOf(createdTime.replace("Z", "").replace("T", " ")); // Convert to timestamp

//                     String symbol = trade.getString("ticker");
//                     int size = trade.getInt("count");
//                     String side = trade.getString("taker_side");

//                     // Choose the price based on side
//                     double price = side.equalsIgnoreCase("yes") ? trade.getDouble("yes_price") : trade.getDouble("no_price");

//                     // Set parameters for insertion
//                     statement.setDate(1, tradeDate);
//                     statement.setTimestamp(2, tradeTimestamp);
//                     statement.setString(3, symbol);
//                     statement.setDouble(4, price);
//                     statement.setInt(5, size);
//                     statement.setString(6, side);

//                     statement.addBatch();
//                 }
//                 try {
//                     statement.executeBatch(); // Execute the batch
//                     System.out.println("Insert successful for Trades table.");
//                 } catch (Exception e) {
//                     System.err.println("Batch execution error: " + e.getMessage());
//                 }
                
//                 break;
        
//             default:
//                 System.err.println("Unsupported table: " + tableName);
//         }
        

//         return statement;
//     }
// }


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

            default:
                System.err.println("Unsupported table: " + tableName);
        }
    }
}

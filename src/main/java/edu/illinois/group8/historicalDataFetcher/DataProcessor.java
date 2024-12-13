package edu.illinois.group8.historicalDataFetcher;

import org.json.JSONArray;
import org.json.JSONObject;

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
                System.out.println("connecting to redshift");
                try (PreparedStatement statement = getPreparedStatement(connection, tableName, dataArray)) {
                    if (statement != null) {
                        statement.executeBatch();
                    } else {
                        System.err.println("No processing logic available for table: " + tableName);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing or storing data: " + e.getMessage());
        }
    }

    private PreparedStatement getPreparedStatement(Connection connection, String tableName, JSONArray dataArray) throws Exception {
        String insertQuery;
        PreparedStatement statement = null;

        switch (tableName) {
            case "Trades":
                insertQuery = "INSERT INTO Trades (trade_date, trade_timestamp, symbol, price, size, side) VALUES (?, ?, ?, ?, ?, ?)";
                statement = connection.prepareStatement(insertQuery);
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject trade = dataArray.getJSONObject(i);
                    statement.setString(1, trade.getString("trade_date"));
                    statement.setLong(2, trade.getLong("trade_timestamp"));
                    statement.setString(3, trade.getString("symbol"));
                    statement.setDouble(4, trade.getDouble("price"));
                    statement.setInt(5, trade.getInt("size"));
                    statement.setString(6, trade.getString("side"));
                    statement.addBatch();
                }
                break;

            default:
                System.err.println("Unsupported table: " + tableName);
        }

        return statement;
    }
}

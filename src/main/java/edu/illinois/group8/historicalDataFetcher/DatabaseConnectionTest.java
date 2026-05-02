package edu.illinois.group8.historicalDataFetcher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import edu.illinois.group8.config.BackendConfig;

public class DatabaseConnectionTest {
    public static void main(String[] args) {
        BackendConfig config = BackendConfig.fromEnvironment();
        if (config.databaseUrl().isBlank()) {
            throw new IllegalStateException("BACKEND_DATABASE_URL is required for database connection tests.");
        }
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;

        try {
            // Load JDBC driver
            Class.forName("com.amazon.redshift.jdbc42.Driver");

            System.out.println("Attempting to connect to the database...");
            // Establish connection
            connection = DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUser(),
                config.databasePassword()
            );

            if (connection != null) {
                System.out.println("Connection successful!");

                // Create a statement to execute SQL
                statement = connection.createStatement();

                // Example query
                String query = "SELECT * FROM Trades ORDER BY TradeTimestamp DESC LIMIT 50";

                System.out.println("Executing query: " + query);
                resultSet = statement.executeQuery(query);

                // Print results
                while (resultSet.next()) {
                    System.out.println(
                        "TradeDate: " + resultSet.getDate("TradeDate") +
                        ", TradeTimestamp: " + resultSet.getTimestamp("TradeTimestamp") +
                        ", Symbol: " + resultSet.getString("Symbol") +
                        ", Price: " + resultSet.getBigDecimal("Price") +
                        ", Size: " + resultSet.getInt("Size") +
                        ", Side: " + resultSet.getString("Side")
                    );
                }


                query = "SELECT * FROM Trades WHERE Symbol LIKE 'KXBTCD%' ORDER BY TradeTimestamp DESC LIMIT 50";

                System.out.println("Executing query: " + query);
                resultSet = statement.executeQuery(query);

                // Print results
                while (resultSet.next()) {
                    System.out.println(
                        "TradeDate: " + resultSet.getDate("TradeDate") +
                        ", TradeTimestamp: " + resultSet.getTimestamp("TradeTimestamp") +
                        ", Symbol: " + resultSet.getString("Symbol") +
                        ", Price: " + resultSet.getBigDecimal("Price") +
                        ", Size: " + resultSet.getInt("Size") +
                        ", Side: " + resultSet.getString("Side")
                    );
                }

                // commented example queries
                // String countQuery = "SELECT COUNT(*) AS row_count FROM Trades;";
                // String dropColumn = "ALTER TABLE SymbolMaster DROP COLUMN Category";
                // resultSet = statement.executeQuery(dropColumn);

                // if (resultSet.next()) {
                //     int rowCount = resultSet.getInt("row_count");
                //     System.out.println("Total rows in Trades table: " + rowCount);
                // }

                // String describeQuery = "DESCRIBE SymbolMaster;";
                // resultSet = statement.executeQuery(describeQuery);
                
                // // Output the schema of the SymbolMaster table
                // System.out.println("Schema of SymbolMaster Table:");
                // while (resultSet.next()) {
                //     String field = resultSet.getString("Field");
                //     String type = resultSet.getString("Type");
                //     String nullStatus = resultSet.getString("Null");
                //     String key = resultSet.getString("Key");
                //     String defaultValue = resultSet.getString("Default");
                //     String extra = resultSet.getString("Extra");

                //     System.out.println("Field: " + field + ", Type: " + type + ", Null: " + nullStatus +
                //             ", Key: " + key + ", Default: " + defaultValue + ", Extra: " + extra);
                // }

            } else {
                System.out.println("Connection failed!");
            }
        } catch (ClassNotFoundException e) {
            System.err.println("JDBC Driver not found: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("Database connection error: " + e.getMessage());
        } finally {
            try {
                if (resultSet != null) resultSet.close();
                if (statement != null) statement.close();
                if (connection != null) {
                    connection.close();
                    System.out.println("Connection closed.");
                }
            } catch (SQLException e) {
                System.err.println("Error closing the resources: " + e.getMessage());
            }
        }
    }
}

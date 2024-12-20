package edu.illinois.group8.historicalDataFetcher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.github.cdimascio.dotenv.Dotenv;

public class DatabaseConnectionTest {
    // Redshift connection details
    static Dotenv dotenv = Dotenv.load();

    private static final String REDSHIFT_URL = "jdbc:redshift://kalshi-cluster.cqnzqxki7plp.us-east-2.redshift.amazonaws.com:5439/processed_data";
    private static final String DB_USER = dotenv.get("DB_USER");
    private static final String DB_PASSWORD = dotenv.get("DB_PASSWORD");

    public static void main(String[] args) {
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;

        try {
            // Load JDBC driver
            Class.forName("com.amazon.redshift.jdbc42.Driver");

            System.out.println("Attempting to connect to the database...");
            // Establish connection
            connection = DriverManager.getConnection(REDSHIFT_URL, DB_USER, DB_PASSWORD);

            if (connection != null) {
                System.out.println("Connection successful!");

                // Create a statement to execute SQL
                statement = connection.createStatement();

                // // Example query
                // String query = "SELECT * FROM Trades LIMIT 10;"; // Replace with your query

                // System.out.println("Executing query: " + query);
                // resultSet = statement.executeQuery(query);

                // // Print results
                // while (resultSet.next()) {
                //     // Assuming the table has columns "column1", "column2", etc.
                //     System.out.println("Price: " + resultSet.getString("Price") +
                //                        ", Size: " + resultSet.getString("Size"));
                // }
                String countQuery = "SELECT COUNT(*) AS row_count FROM Trades;";
                String dropColumn = "ALTER TABLE SymbolMaster DROP COLUMN Category";
                resultSet = statement.executeQuery(dropColumn);

                if (resultSet.next()) {
                    int rowCount = resultSet.getInt("row_count");
                    System.out.println("Total rows in Trades table: " + rowCount);
                }

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

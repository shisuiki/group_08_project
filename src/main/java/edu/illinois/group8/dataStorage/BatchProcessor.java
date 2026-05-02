package edu.illinois.group8.dataStorage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import edu.illinois.group8.config.BackendConfig;

public class BatchProcessor {
    private static final BackendConfig CONFIG = BackendConfig.fromEnvironment();

    // A map for table-specific SQL INSERT queries
    private static final String INSERT_TRADE_SQL = "INSERT INTO Trades (raw_data) VALUES (?)";

    
    private static final List<String> batch = new ArrayList<>();
    private static final int BATCH_SIZE = 100;


    public static synchronized void addToBatch(String streamName, String message) {
        String tableInsertSQL = getInsertSQLForStream(streamName);

        if (tableInsertSQL == null) {
            System.out.println("Unknown stream: " + streamName);
            return;
        }

        batch.add(tableInsertSQL + "|" + message); // You can store it as a string like this or directly in SQL query format

        if (batch.size() >= BATCH_SIZE) {
            flushBatch();
        }
    }

    private static String getInsertSQLForStream(String streamName) {
        if ("canonical.trade".equals(streamName)) {
            return INSERT_TRADE_SQL;
        }
        return null;
    }

    public static synchronized void flushBatch() {
        if (batch.isEmpty()) return;

        if (CONFIG.databaseUrl().isBlank()) {
            throw new IllegalStateException("BACKEND_DATABASE_URL is required to flush the batch.");
        }
        try (Connection conn = DriverManager.getConnection(
            CONFIG.databaseUrl(),
            CONFIG.databaseUser(),
            CONFIG.databasePassword()
        )) {
            conn.setAutoCommit(false);

            for (String data : batch) {
                String[] parts = data.split("\\|", 2); // Split SQL and message
                String insertSQL = parts[0];
                String message = parts[1];

                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    pstmt.setString(1, message); // Bind the message to the SQL query
                    pstmt.addBatch();
                }
            }

            conn.prepareStatement("COMMIT").execute();
            conn.commit();
            batch.clear();
            System.out.println("Batch successfully written to the database.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void shutdown() {
        // scheduler.shutdown();
        flushBatch();
    }
}

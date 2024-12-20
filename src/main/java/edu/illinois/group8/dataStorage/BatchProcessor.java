package edu.illinois.group8.dataStorage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BatchProcessor {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:postgresql://kalshi-raw-data.cluuu2aq0l4j.us-east-2.rds.amazonaws.com:5432/<database-name>"; // TODO: add database name
    private static final String DB_USER = dotenv.get("DB_USER");
    private static final String DB_PASSWORD = dotenv.get("DB_PASSWORD");

    // A map for table-specific SQL INSERT queries
    private static final String INSERT_TRADE_SQL = "INSERT INTO Trades (raw_data) VALUES (?)";

    
    private static final List<String> batch = new ArrayList<>();
    private static final int BATCH_SIZE = 100;


    public static synchronized void addToBatch(char messageType, String message) {
        String tableInsertSQL = getInsertSQLForMessageType(messageType);

        if (tableInsertSQL == null) {
            System.out.println("Unknown message type: " + messageType);
            return;
        }

        batch.add(tableInsertSQL + "|" + message); // You can store it as a string like this or directly in SQL query format

        if (batch.size() >= BATCH_SIZE) {
            flushBatch();
        }
    }

    private static String getInsertSQLForMessageType(char messageType) {
        switch (messageType) {
            case 'T': return INSERT_TRADE_SQL;
            default: return null;
        }
    }

    public static synchronized void flushBatch() {
        if (batch.isEmpty()) return;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
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
        scheduler.shutdown();
        flushBatch();
    }
}

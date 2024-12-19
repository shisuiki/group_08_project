package edu.illinois.group8.datastorage;

import edu.illinois.group8.cluster.StreamIDs;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;

import org.json.JSONArray;
import org.json.JSONObject;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import com.fasterxml.jackson.databind.ObjectMapper;


public class TradeDataStorage implements Runnable {
    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription tradeSubscription;
    private ObjectMapper objectMapper;

    private final String redshiftUrl = "jdbc:redshift://kalshi-cluster.cqnzqxki7plp.us-east-2.redshift.amazonaws.com:5439/processed_data";
    Dotenv dotenv = Dotenv.load();

    private String dbUser = dotenv.get("DB_USER");
    private String dbPassword = dotenv.get("DB_PASSWORD");

    public TradeDataStorage(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.tradeSubscription = communicationOrchestrator.getTradesSubscription();
        objectMapper = new ObjectMapper();
    }

    @Override
    public void run() {
        System.out.println("Trade listener running...");

        while (true) {
            tradeSubscription.poll((buffer, offset, length, header) -> {
                String message = buffer.getStringWithoutLengthUtf8(offset, length);
                System.out.println("Received trade message: " + message);
                processAndStoreTrades(message);
            }, 1);
        }
    }

    private void processAndStoreTrades(String jsonData) {
        try {
            JSONArray trades = new JSONArray(jsonData);

            try (Connection connection = DriverManager.getConnection(redshiftUrl, dbUser, dbPassword)) {
                System.out.println("Connected to Redshift...");
                String insertQuery = "INSERT INTO Trades (TradeDate, TradeTimestamp, Symbol, Price, Size, Side) VALUES (?, ?, ?, ?, ?, ?)";

                try (PreparedStatement statement = connection.prepareStatement(insertQuery)) {
                    for (int i = 0; i < trades.length(); i++) {
                        JSONObject trade = trades.getJSONObject(i);

                        String createdTime = trade.getString("created_time");
                        Date tradeDate = Date.valueOf(createdTime.substring(0, 10)); // Extract date
                        Timestamp tradeTimestamp = Timestamp.valueOf(createdTime.replace("Z", "").replace("T", " ")); // Convert to timestamp

                        String symbol = trade.getString("ticker");
                        int size = trade.getInt("count");
                        String side = trade.getString("taker_side");

                        double price = side.equalsIgnoreCase("yes") ? trade.getDouble("yes_price") : trade.getDouble("no_price");

                        statement.setDate(1, tradeDate);
                        statement.setTimestamp(2, tradeTimestamp);
                        statement.setString(3, symbol);
                        statement.setDouble(4, price);
                        statement.setInt(5, size);
                        statement.setString(6, side);

                        statement.addBatch();
                    }

                    statement.executeBatch();
                    System.out.println("Trades successfully inserted into Redshift.");
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing or storing trades: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Replace with actual values or load from environment/config
        String redshiftUrl = "jdbc:redshift://your-redshift-cluster:5439/your-database";
        String dbUser = "your-username";
        String dbPassword = "your-password";

        // Initialize communication orchestrator (mocked or real implementation)
        ESBClusterCommunicationOrchestrator orchestrator = new ESBClusterCommunicationOrchestrator();

        // Start the trade listener
        TradeListener listener = new TradeListener(orchestrator, redshiftUrl, dbUser, dbPassword);
        new Thread(listener).start();
    }
}

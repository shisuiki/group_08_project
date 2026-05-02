package edu.illinois.group8.dataStorage;

import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.config.BackendConfig;
import io.aeron.Subscription;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TradeDataStorage implements Runnable {
    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription tradeSubscription;
    private final ObjectMapper objectMapper;

    private Connection connection;

    public TradeDataStorage(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.tradeSubscription = communicationOrchestrator.getTradesSubscription();
        this.objectMapper = new ObjectMapper();
        BackendConfig config = BackendConfig.fromEnvironment();
        if (config.databaseUrl().isBlank()) {
            throw new IllegalStateException("BACKEND_DATABASE_URL is required for TradeDataStorage.");
        }
        try {
            this.connection = DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword());
            System.out.println("Database connection established.");
        } catch (Exception e) {
            System.err.println("Failed to establish database connection: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Database connection error.");
        }
    }

    @Override
    public void run() {
        System.out.println("Trade listener running...");

        while (true) {
            
                System.out.println("Database connection established.");
                tradeSubscription.poll((buffer, offset, length, header) -> {
                    String message = buffer.getStringWithoutLengthUtf8(offset, length);
                    System.out.println("Received trade message: " + message);
                    processTrades(message);
                }, 1);

    }
    }

    private void processTrades(String message) {

        try {
            JsonNode rootNode = objectMapper.readTree(message);

            String symbol = rootNode.get("metadata").get("market_ticker").asText();
            String takerSide = rootNode.get("taker_side").asText();
            double price = takerSide.equals("no")
                ? rootNode.get("no_price_micros").asDouble() / 1_000_000.0
                : rootNode.get("yes_price_micros").asDouble() / 1_000_000.0;
            int size = (int) Math.round(rootNode.get("quantity_micros").asDouble() / 1_000_000.0);
            long timestamp = rootNode.get("metadata").get("event_ts_ms").asLong();

            System.out.printf("Parsed trade data: Symbol=%s, Price=%.2f, Size=%d, Side=%s, Timestamp=%d%n",
                    symbol, price, size, takerSide, timestamp);

            String insertQuery = "INSERT INTO Trades (TradeTimestamp, Symbol, Price, Size, Side) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(insertQuery)) {
                statement.setTimestamp(1, new Timestamp(timestamp));
                statement.setString(2, symbol);
                statement.setDouble(3, price);
                statement.setInt(4, size);
                statement.setString(5, takerSide.equals("no") ? "ask" : "bid");

                int rowsInserted = statement.executeUpdate();
                System.out.println("Rows inserted: " + rowsInserted);
            }
            catch (Exception e) {
                System.err.println("Error processing trade: " + e.getMessage());
                e.printStackTrace();
            }
        }
        catch (Exception e) {
            System.err.println("Error processing trade: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
}

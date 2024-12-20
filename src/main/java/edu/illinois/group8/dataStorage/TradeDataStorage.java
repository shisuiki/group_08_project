package edu.illinois.group8.dataStorage;

import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import io.aeron.Subscription;
import io.github.cdimascio.dotenv.Dotenv;
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

    private final String redshiftUrl = "jdbc:redshift://kalshi-cluster.cqnzqxki7plp.us-east-2.redshift.amazonaws.com:5439/processed_data";
    private final Dotenv dotenv = Dotenv.load();
    private final String dbUser = dotenv.get("DB_USER");
    private final String dbPassword = dotenv.get("DB_PASSWORD");

    private Connection connection;

    public TradeDataStorage(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.tradeSubscription = communicationOrchestrator.getTradesSubscription();
        this.objectMapper = new ObjectMapper();
        try {
            this.connection = DriverManager.getConnection(redshiftUrl, dbUser, dbPassword);
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

            String symbol = rootNode.get("msg").get("market_ticker").asText();
            String takerSide = rootNode.get("msg").get("taker_side").asText();
            double price = takerSide.equals("no") 
                ? rootNode.get("msg").get("no_price").asDouble() 
                : rootNode.get("msg").get("yes_price").asDouble();
            int size = rootNode.get("msg").get("count").asInt();
            long timestamp = rootNode.get("msg").get("ts").asLong() * 1000;

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

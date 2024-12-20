package edu.illinois.group8.dataStorage;

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


import com.fasterxml.jackson.databind.JsonNode;
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
                processTrades(message);
            }, 1);
        }
    }

    private void processTrades(String message) {
        try (Connection connection = DriverManager.getConnection(redshiftUrl, dbUser, dbPassword)) {
            JsonNode rootNode = objectMapper.readTree(message);
            String symbol = rootNode.get("msg").get("market_ticker").asText();
            double price = rootNode.get("msg").get("yes_price").asDouble();
            int size = rootNode.get("msg").get("count").asInt();
            String side = rootNode.get("msg").get("taker_side").asText().equals("no") ? "ask" : "bid";
            long timestamp = rootNode.get("msg").get("ts").asLong() * 1000;

            String insertQuery = "INSERT INTO Trades (TradeTimestamp, Symbol, Price, Size, Side) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(insertQuery)) {
                statement.setTimestamp(1, new Timestamp(timestamp));
                statement.setString(2, symbol);
                statement.setDouble(3, price);
                statement.setInt(4, size);
                statement.setString(5, side);
                statement.executeUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

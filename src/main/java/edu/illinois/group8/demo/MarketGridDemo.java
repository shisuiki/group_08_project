package edu.illinois.group8.demo;

import edu.illinois.group8.cluster.StreamIDs;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MarketGridDemo implements Runnable {
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription topOfBookSubscription;

    private ObjectMapper objectMapper;
    private DefaultTableModel tableModel;

    public MarketGridDemo(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.topOfBookSubscription = communicationOrchestrator.getTopOfBookSubscription();
        objectMapper = new ObjectMapper();
    }

    @Override
    public void run() {
        System.out.println("market grid running...");
        SwingUtilities.invokeLater(this::initializeGUI);

        while (true) {
            topOfBookSubscription.poll((buffer, offset, length, header) -> {
                String message = buffer.getStringWithoutLengthUtf8(offset, length);
                System.out.println("market grid received: " + message);
                processMessage(message);
            }, 1);
        }
    }

    private void initializeGUI() {
        JFrame frame = new JFrame("Market Grid Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 400);
        frame.setLayout(new BorderLayout());

        String[] columnNames = {"Symbol", "Bid Price", "Bid Size", "Ask Price", "Ask Size"};
        tableModel = new DefaultTableModel(columnNames, 0);
        JTable table = new JTable(tableModel);

        JScrollPane scrollPane = new JScrollPane(table);
        frame.add(scrollPane, BorderLayout.CENTER);

        frame.setVisible(true);
    }

    private void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            SwingUtilities.invokeLater(() -> updateTable(
                                                rootNode.get("symbol").asText(),
                                                rootNode.get("bidPrice").asText(),
                                                rootNode.get("bidSize").asText(),
                                                rootNode.get("askPrice").asText(),
                                                rootNode.get("askSize").asText()
                                            ));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateTable(String symbol, String bidPrice, String bidSize, String askPrice, String askSize) {
        boolean updated = false;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (tableModel.getValueAt(i, 0).equals(symbol)) {
                tableModel.setValueAt(bidPrice, i, 1);
                tableModel.setValueAt(bidSize, i, 2);
                tableModel.setValueAt(askPrice, i, 3);
                tableModel.setValueAt(askSize, i, 4);
                updated = true;
                break;
            }
        }

        if (!updated) {
            tableModel.addRow(new Object[]{symbol, bidPrice, bidSize, askPrice, askSize});
        }
    }
}

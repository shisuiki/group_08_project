package edu.illinois.group8.demo;

import edu.illinois.group8.cluster.StreamIDs;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MarketGridDemo implements Runnable {
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final Subscription topOfBookSubscription;

    private ObjectMapper objectMapper;

    private int rowCount = 0;
    private final String[][] tableData = new String[100][5];

    public MarketGridDemo(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.topOfBookSubscription = communicationOrchestrator.getTopOfBookSubscription();
        objectMapper = new ObjectMapper();
    }

    @Override
    public void run() {
        while (true) {
            topOfBookSubscription.poll((buffer, offset, length, header) -> {
                String message = buffer.getStringWithoutLengthUtf8(offset, length);
                processMessage(message);
            }, 1);
        }
    }

    private void processMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            updateTable(
                rootNode.get("symbol").asText(),
                rootNode.get("bidPrice").asText(),
                rootNode.get("bidSize").asText(),
                rootNode.get("askPrice").asText(),
                rootNode.get("askSize").asText()
            );
            printTable();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateTable(String symbol, String bidPrice, String bidSize, String askPrice, String askSize) {
        boolean updated = false;
        for (int i = 0; i < rowCount; i++) {
            if (tableData[i][0].equals(symbol)) {
                tableData[i][1] = bidPrice;
                tableData[i][2] = bidSize;
                tableData[i][3] = askPrice;
                tableData[i][4] = askSize;
                updated = true;
                break;
            }
        }

        if (!updated && rowCount < tableData.length) {
            tableData[rowCount][0] = symbol;
            tableData[rowCount][1] = bidPrice;
            tableData[rowCount][2] = bidSize;
            tableData[rowCount][3] = askPrice;
            tableData[rowCount][4] = askSize;
            rowCount++;
        }
    }

    private void printTable() {
        clearConsole();
        printTableHeader();
        if (rowCount == 0) System.out.println("No data.");
        else {
            for (int i = 0; i < rowCount; i++) {
                System.out.printf("| %-15s | %-9s | %-7s | %-9s | %-7s |\n", 
                        tableData[i][0], tableData[i][1], tableData[i][2], tableData[i][3], tableData[i][4]);
            }
        }
        System.out.println("+-----------------+-----------+---------+-----------+---------+");
    }

    private void printTableHeader() {
        System.out.println("+-----------------+-----------+---------+-----------+---------+");
        System.out.printf("| %-15s | %-9s | %-7s | %-9s | %-7s |\n", "Symbol", "Bid Price", "Bid Size", "Ask Price", "Ask Size");
        System.out.println("+-----------------+-----------+---------+-----------+---------+");
    }

    private void clearConsole() {
        // try {
        //     Runtime.getRuntime().exec("clear");
        // } catch (Exception e) {
        //     e.printStackTrace();
        // }

        System.out.printf("\n\n\n\n\n\n\n\n\n\n\n\n");
    }
}

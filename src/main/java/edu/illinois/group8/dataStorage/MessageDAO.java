package edu.illinois.group8.database;

import edu.illinois.group8.messages.OrderBookDeltaMessage;
import edu.illinois.group8.messages.OrderBookSnapshotMessage;
import edu.illinois.group8.messages.TickerMessage;
import edu.illinois.group8.messages.TradeMessage;

public class MessageDAO {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MessageDAO(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public void addOrderBookDeltaMessage(OrderBookDeltaMessage message) {

    }

    public void addOrderBookSnapshotMessage(OrderBookSnapshotMessage message) {

    }

    public void addTickerMessage(TickerMessage message) {

    }

    public void addTradeMessage(TradeMessage message) {
        
    }

}

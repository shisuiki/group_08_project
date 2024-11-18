package edu.illinois.group8.cluster;

import java.util.List;

// Subclass for orderbook_snapshot
class OrderBookSnapshot {
    private String type;
    private int sid;
    private int seq;
    private OrderBookMsg msg;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getSid() { return sid; }
    public void setSid(int sid) { this.sid = sid; }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public OrderBookMsg getMsg() { return msg; }
    public void setMsg(OrderBookMsg msg) { this.msg = msg; }

    public static class OrderBookMsg {
        private String market_ticker;
        private List<List<Integer>> yes;
        private List<List<Integer>> no;

        // Getters and Setters
        public String getMarket_ticker() { return market_ticker; }
        public void setMarket_ticker(String market_ticker) { this.market_ticker = market_ticker; }

        public List<List<Integer>> getYes() { return yes; }
        public void setYes(List<List<Integer>> yes) { this.yes = yes; }

        public List<List<Integer>> getNo() { return no; }
        public void setNo(List<List<Integer>> no) { this.no = no; }
    }
}

// Subclass for orderbook_delta
class OrderBookDelta {
    private OrderBookDeltaMsg msg;

    public OrderBookDeltaMsg getMsg() { return msg; }
    public void setMsg(OrderBookDeltaMsg msg) { this.msg = msg; }

    public static class OrderBookDeltaMsg {
        private String market_ticker;
        private int price;
        private int delta;
        private String side;

        // Getters and Setters
        public String getMarket_ticker() { return market_ticker; }
        public void setMarket_ticker(String market_ticker) { this.market_ticker = market_ticker; }

        public int getPrice() { return price; }
        public void setPrice(int price) { this.price = price; }

        public int getDelta() { return delta; }
        public void setDelta(int delta) { this.delta = delta; }

        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }
    }
}

// // Subclass for ticker
// class Ticker extends Message {
//     private TickerMsg msg;

//     public TickerMsg getMsg() { return msg; }
//     public void setMsg(TickerMsg msg) { this.msg = msg; }

//     public static class TickerMsg {
//         private String market_ticker;
//         private int price;
//         private int yes_bid;
//         private int yes_ask;
//         private int volume;
//         private int open_interest;
//         private int dollar_volume;
//         private int dollar_open_interest;
//         private long ts;

//         // Getters and Setters
//         public String getMarket_ticker() { return market_ticker; }
//         public void setMarket_ticker(String market_ticker) { this.market_ticker = market_ticker; }

//         public int getPrice() { return price; }
//         public void setPrice(int price) { this.price = price; }

//         public int getYes_bid() { return yes_bid; }
//         public void setYes_bid(int yes_bid) { this.yes_bid = yes_bid; }

//         public int getYes_ask() { return yes_ask; }
//         public void setYes_ask(int yes_ask) { this.yes_ask = yes_ask; }

//         public int getVolume() { return volume; }
//         public void setVolume(int volume) { this.volume = volume; }

//         public int getOpen_interest() { return open_interest; }
//         public void setOpen_interest(int open_interest) { this.open_interest = open_interest; }

//         public int getDollar_volume() { return dollar_volume; }
//         public void setDollar_volume(int dollar_volume) { this.dollar_volume = dollar_volume; }

//         public int getDollar_open_interest() { return dollar_open_interest; }
//         public void setDollar_open_interest(int dollar_open_interest) { this.dollar_open_interest = dollar_open_interest; }

//         public long getTs() { return ts; }
//         public void setTs(long ts) { this.ts = ts; }
//     }
// }

// // Subclass for trade
// class Trade extends Message {
//     private TradeMsg msg;

//     public TradeMsg getMsg() { return msg; }
//     public void setMsg(TradeMsg msg) { this.msg = msg; }

//     public static class TradeMsg {
//         private String market_ticker;
//         private int yes_price;
//         private int no_price;
//         private int count;
//         private String taker_side;
//         private long ts;

//         // Getters and Setters
//         public String getMarket_ticker() { return market_ticker; }
//         public void setMarket_ticker(String market_ticker) { this.market_ticker = market_ticker; }

//         public int getYes_price() { return yes_price; }
//         public void setYes_price(int yes_price) { this.yes_price = yes_price; }

//         public int getNo_price() { return no_price; }
//         public void setNo_price(int no_price) { this.no_price = no_price; }

//         public int getCount() { return count; }
//         public void setCount(int count) { this.count = count; }

//         public String getTaker_side() { return taker_side; }
//         public void setTaker_side(String taker_side) { this.taker_side = taker_side; }

//         public long getTs() { return ts; }
//         public void setTs(long ts) { this.ts = ts; }
//     }
// }

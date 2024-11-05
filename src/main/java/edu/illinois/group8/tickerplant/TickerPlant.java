package edu.illinois.group8.tickerplant;

public class TickerPlant {
    private final TPClientHandler clientHandler;
    private final Thread wsServerThread;
    private final Thread kafkaListenerThread;

    public TickerPlant() {
        this.clientHandler = new TPClientHandler();
        wsServerThread = new Thread(new TPWebSocketServer(8080, this.clientHandler));
        kafkaListenerThread = new Thread(new TPKafkaListener(9092, this.clientHandler));
    }

    public void run() {
        wsServerThread.start();
        kafkaListenerThread.start();
    }
}

package edu.illinois.group8.historicalDataFetcher;

public class ThrottlingManager {

    private int requestLimit; // API request limit per interval
    private long intervalMillis; // Interval in milliseconds
    private long lastRequestTime;

    public ThrottlingManager(int requestLimit, long intervalMillis) {
        this.requestLimit = requestLimit;
        this.intervalMillis = intervalMillis;
        this.lastRequestTime = 0;
    }

    public void throttleIfNecessary() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRequestTime < intervalMillis / requestLimit) {
            try {
                Thread.sleep(intervalMillis / requestLimit);
            } catch (InterruptedException e) {
                System.err.println("Throttling interrupted: " + e.getMessage());
            }
        }
        lastRequestTime = currentTime;
    }
}

package edu.illinois.group8.rawdatastorage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BatchScheduler {
    public static void main(String[] args) {
        AeronListener listener = new AeronListener();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(subscriber::receiveData, 0, 1, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(BatchProcessor::flushBatch, 0, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            subscriber.close();
            scheduler.shutdown();
            BatchProcessor.flushBatch();
            System.out.println("Application shutting down...");
        }));
    }
}

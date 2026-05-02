package edu.illinois.group8.dataStorage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeron.Aeron;
import io.aeron.Subscription;

public class AeronListener implements Runnable {
    private static final String CHANNEL = System.getenv().getOrDefault("AERON_STORAGE_CHANNEL", "aeron:udp?endpoint=localhost:40123");
    private static final int STREAM_ID = Integer.parseInt(System.getenv().getOrDefault("AERON_STORAGE_STREAM_ID", "11"));

    private final Aeron aeron;
    private final Subscription subscription;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean running = true;

    public AeronListener() {
        Aeron.Context ctx = new Aeron.Context();
        this.aeron = Aeron.connect(ctx);
        this.subscription = aeron.addSubscription(CHANNEL, STREAM_ID);
    }

    @Override
    public void run() {
        while (true) {
            subscription.poll((buffer, offset, length, header) -> {
                if (length <= 0) return; // Skip empty messages
                byte[] data = new byte[length];
                buffer.getBytes(offset, data);
                String message = new String(data); // Convert data to string
                try {
                    JsonNode root = objectMapper.readTree(message);
                    BatchProcessor.addToBatch(root.path("stream_name").asText(), message);
                } catch (Exception e) {
                    System.err.println("Skipping malformed canonical event: " + e.getMessage());
                }
            }, 1);
        }
    }
    
    public void stop() {
        running = false;
    }

    public void close() {
        subscription.close();
        aeron.close();
    }

    public static void main(String[] args) {
        AeronListener listener = new AeronListener();
        Thread listenerThread = new Thread(listener);

        // Add a shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            listener.stop();
            listener.close();
            BatchProcessor.shutdown();
            System.out.println("Application shutting down...");
        }));

        // Start the listener in a separate thread
        listenerThread.start();
    }
}

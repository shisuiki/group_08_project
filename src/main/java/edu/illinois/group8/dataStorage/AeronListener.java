package edu.illinois.group8.dataStorage;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
// import io.aeron.logbuffer.FragmentAssembler;
import io.aeron.logbuffer.Header;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import edu.illinois.group8.dataStorage.BatchProcessor;

public class AeronListener implements Runnable {
    private static final String CHANNEL = "aeron:udp?endpoint=localhost:40123"; // TODO: change to your Aeron channel
    private static final int STREAM_ID = 1001; // TODO: change to your Aeron stream ID

    private final Aeron aeron;
    private final Subscription subscription;

    private volatile boolean running = true;

    private final int messageTypeOffset = 17;

    private BatchProcessor processor = new BatchProcessor();

    private int MESSAGE_TYPE_OFFSET = 4;

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
                char messageType = (char) buffer.getByte(offset + MESSAGE_TYPE_OFFSET); // Get message type
                byte[] data = new byte[length - 1]; // Extract data after the message type
                buffer.getBytes(offset + 1, data);
                String message = new String(data); // Convert data to string
                processor.addToBatch(messageType, message);
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

package edu.illinois.group8.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.agrona.concurrent.UnsafeBuffer;

import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;
import edu.illinois.group8.canonical.EventType;
import edu.illinois.group8.config.BackendConfig;
import io.aeron.ConcurrentPublication;

public class TestPublisher {
    private final ESBClusterCommunicationOrchestrator communicationOrchestrator;

    public TestPublisher() {
        BackendConfig config = BackendConfig.fromEnvironment();
        this.communicationOrchestrator = new ESBClusterCommunicationOrchestrator(config.hostIp(), false, "dirName");
    }

    public void sendMessage(String message) {
        System.out.println("sending message");
        ConcurrentPublication pub = this.communicationOrchestrator.getPublication(EventType.ORDER_BOOK_DELTA.streamName());
        if (pub == null) {
            System.err.println("Trades publication not found.");
            return;
        }

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.wrap(messageBytes));

        long result = pub.offer(buffer, 0, messageBytes.length);
        if (result == 0L) {
            System.out.println("Failed to send message: "+message);
        }

        System.out.println("Sent message: "+message);
    }
    public static void main(String[] args) {
        if(args.length < 1){
            System.err.println("Usage: TestPublisher <message>");
            System.exit(1);
        }

        String message = args[0];

        try {
            Thread.sleep(2000);
        } catch (Exception e) {
            System.out.println("failed sleeping");
        }
        

        TestPublisher publisher = new TestPublisher();
        publisher.sendMessage(message);;
    }
}

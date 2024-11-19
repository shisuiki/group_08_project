package edu.illinois.group8.tickerplant;

import io.aeron.Aeron;
import io.aeron.Publication;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.BufferUtil;

public class TPAeronServer implements Runnable {
    private final String channel;

    private Aeron aeron;
    private Publication topOfBookPublication;
    private Publication tradePublication;
    private Publication bookEventsPublication;
    private final BlockingQueue<String> messageQueue;
    private final UnsafeBuffer buffer;

    private boolean running = true;

    public TPAeronServer(String channel, BlockingQueue<String> queue) {
        this.channel = channel;

        aeron = Aeron.connect(new Aeron.Context());
        topOfBookPublication = aeron.addPublication(channel, 10);
        tradePublication = aeron.addPublication(channel, 11);
        bookEventsPublication = aeron.addPublication(channel, 12);

        this.messageQueue = queue;
        this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(256, 64));
    }

    @Override
    public void run() {
        while (running) {
            try {
                String message = messageQueue.take();
                sendMessageToClients(message);
            } catch (InterruptedException e) {
                e.printStackTrace();
                running = false;
            }
        }
    }

    private void sendMessageToClients(String message) {
        Publication stream = selectStream(message);

        if (stream != null) {
            final int length = buffer.putStringWithoutLengthAscii(0, message);
            final long position = stream.offer(buffer, 0, length);
            
            if (position > 0) {
                System.out.println("Message sent: " + message);
            } else {
                System.out.println("Message could not be sent: " + message);
            }
        } else {
            System.out.println("Publication stream not detected for message: " + message);
        }
    }

    private Publication selectStream(String message) {
        switch (message.charAt(0)) { // replace with message type determiner once format is finalized
            case 'B':
                return topOfBookPublication;
            case 'T':
                return tradePublication;
            case 'E':
                return bookEventsPublication;
            default:
                return null;
        }
    }

    public void stop() {
        running = false;
    }
}

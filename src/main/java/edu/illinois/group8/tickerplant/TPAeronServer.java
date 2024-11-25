package edu.illinois.group8.tickerplant;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;

public class TPAeronServer implements Runnable {
    private final String externalChannel;
    private final String internalChannel;

    private final Aeron aeron;
    private final Publication topOfBookPublication;
    private final Publication tradePublication;
    private final Publication bookEventsPublication;
    private final Subscription internalChannelSubscription;

    private final int messageTypeOffset = 17;

    public TPAeronServer(String externalChannel, String internalChannel, int internalStreamId) {
        this.externalChannel = externalChannel;
        this.internalChannel = internalChannel;

        aeron = Aeron.connect(new Aeron.Context());
        topOfBookPublication = aeron.addPublication(externalChannel, 10);
        tradePublication = aeron.addPublication(externalChannel, 11);
        bookEventsPublication = aeron.addPublication(externalChannel, 12);
        internalChannelSubscription = aeron.addSubscription(internalChannel, internalStreamId);
    }

    @Override
    public void run() {
        while (true) {
            internalChannelSubscription.poll((buffer, offset, length, header) -> {
                char messageType = (char) buffer.getByte(messageTypeOffset);

                switch (messageType) {
                    case 'T':
                        System.out.println("publishing trade message");
                        tradePublication.offer(buffer, offset, length);
                        break;
                    case 'K':
                        System.out.println("publishing ticker message");
                        topOfBookPublication.offer(buffer, offset, length);
                        break;
                    case 'D':
                        System.out.println("publishing orderbook delta message");
                        bookEventsPublication.offer(buffer, offset, length);
                        break;
                    case 'S':
                        System.out.println("publishing orderbook snapshot message");
                        bookEventsPublication.offer(buffer, offset, length);
                        break;
                    default:
                        System.out.println("Unknown message type: " + messageType);
                }
            }, 1);
        }
    }
}

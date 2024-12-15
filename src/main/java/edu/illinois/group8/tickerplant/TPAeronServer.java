package edu.illinois.group8.tickerplant;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Publication;
import io.aeron.Subscription;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;

import java.nio.charset.StandardCharsets;
import java.nio.file.WatchEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TPAeronServer implements Runnable {
    private final Aeron aeron;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final int messageTypeOffset = 17;
    private final Publication topOfBookPublication;
    private final Publication tradePublication;
    private final Publication bookEventsPublication;
    private final Subscription internalSubscription;

    private static final Logger logger = LoggerFactory.getLogger(TPAeronServer.class);

    public TPAeronServer() {
        aeron = Aeron.connect(new Aeron.Context());
        String ip = System.getenv("IP_ADDRESS");
        if (ip == "") {
            System.out.println("Unable to get system IP");
            System.exit(1);
        }
        this.communicationOrchestrator = new ESBClusterCommunicationOrchestrator(ip);
        internalSubscription = communicationOrchestrator.getInternalSubscription();
        topOfBookPublication = communicationOrchestrator.getTopOfBookPublication();
        tradePublication = communicationOrchestrator.getTradesPublication();
        bookEventsPublication = communicationOrchestrator.getBookEventsPublication();
    }

    @Override
    public void run() {
        while (true) {
            internalSubscription.poll((buffer, offset, length, header) -> {
                    char messageType = (char) buffer.getByte(messageTypeOffset);
    
                    switch (messageType) {
                        case 'T':
                            System.out.println("tickerplant: publishing trade message");
                            tradePublication.offer(buffer, offset, length);
                            break;
                        case 'K':
                            System.out.println("tickerplant: publishing ticker message");
                            topOfBookPublication.offer(buffer, offset, length);
                            break;
                        case 'D':
                            System.out.println("tickerplant: publishing book events message");
                            bookEventsPublication.offer(buffer, offset, length);
                            break;
                        case 'S':
                            System.out.println("tickerplant: publishing book events message");
                            bookEventsPublication.offer(buffer, offset, length);
                            break;
                        default:
                            logger.warn("Unknown message type: " + messageType);
                    }
                }, 1);
        }
    }
}

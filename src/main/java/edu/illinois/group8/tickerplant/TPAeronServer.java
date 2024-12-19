package edu.illinois.group8.tickerplant;

import io.aeron.Publication;
import io.aeron.Subscription;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TPAeronServer implements Runnable {
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final int messageTypeOffset = 18;
    private final Publication topOfBookPublication;
    private final Publication tradePublication;
    private final Publication bookEventsPublication;
    private final Publication tickerPublication;
    private final Publication openInterestPublication;
    private final Subscription internalSubscription;

    private static final Logger logger = LoggerFactory.getLogger(TPAeronServer.class);

    public TPAeronServer(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
        this.communicationOrchestrator = communicationOrchestrator;
        this.internalSubscription = communicationOrchestrator.getInternalSubscription();
        this.topOfBookPublication = communicationOrchestrator.getTopOfBookPublication();
        this.tradePublication = communicationOrchestrator.getTradesPublication();
        this.bookEventsPublication = communicationOrchestrator.getBookEventsPublication();
        this.tickerPublication = communicationOrchestrator.getTickerPublication();
        this.openInterestPublication = communicationOrchestrator.getOpenInterestPublication();
    }

    @Override
    public void run() {
        while (true) {
            internalSubscription.poll((buffer, offset, length, header) -> {
                    byte msgType = buffer.getByte(offset + messageTypeOffset);
    
                    switch (msgType) {
                        case 'T':
                            System.out.println("tickerplant: publishing trade message");
                            tradePublication.offer(buffer, offset, length);
                            break;
                        case 'K':
                            System.out.println("tickerplant: publishing top of book message");
                            topOfBookPublication.offer(buffer, offset, length);
                            break;
                        case 'D':
                        case 'S':
                            System.out.println("tickerplant: publishing book events message");
                            bookEventsPublication.offer(buffer, offset, length);
                            break;
                        case 'R':
                            System.out.println("tickerplant: publishing ticker event message");
                            tickerPublication.offer(buffer, offset, length);
                            break;
                        case 'O':
                            System.out.println("tickerplant: publishing open interest message");
                            openInterestPublication.offer(buffer, offset, length);
                            break;
                        default:
                            System.out.println("Unknown message type: " + msgType);
                    }
                }, 1);
        }
    }
}

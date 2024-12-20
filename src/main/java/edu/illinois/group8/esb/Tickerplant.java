package edu.illinois.group8.esb;

import io.aeron.Publication;
import io.aeron.Subscription;
import edu.illinois.group8.cluster.ESBClusterCommunicationOrchestrator;

import java.nio.charset.StandardCharsets;

public class Tickerplant implements Runnable {
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private final int messageTypeOffset = 8;
    private final Publication topOfBookPublication;
    private final Publication tradePublication;
    private final Publication bookEventsPublication;
    private final Publication tickerPublication;
    private final Publication openInterestPublication;
    private final Subscription internalSubscription;

    public Tickerplant(ESBClusterCommunicationOrchestrator communicationOrchestrator) {
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

                    byte[] data = new byte[length];
                    buffer.getBytes(offset, data);
                    String msg = new String(data, StandardCharsets.UTF_8);

                    char msgType = extractMessageType(msg);
    
                    switch (msgType) {
                        case 'T':
                            // System.out.println("tickerplant: publishing trade message");
                            tradePublication.offer(buffer, offset, length);
                            break;
                        case 'K':
                            // System.out.println("tickerplant: publishing top of book message");
                            topOfBookPublication.offer(buffer, offset, length);
                            break;
                        case 'S':
                        case 'D':
                            // System.out.println("tickerplant: publishing book events message");
                            bookEventsPublication.offer(buffer, offset, length);
                            break;
                        case 'R':
                            // System.out.println("tickerplant: publishing ticker event message");
                            tickerPublication.offer(buffer, offset, length);
                            break;
                        case 'O':
                            // System.out.println("tickerplant: publishing open interest message");
                            openInterestPublication.offer(buffer, offset, length);
                            break;
                        default:
                            System.out.println("Unknown message type: " + msgType);
                    }
                }, 1);
        }
    }

    private char extractMessageType(String json) {
        int idx = json.indexOf("\"type\":");
        return idx == -1 ? '?' : json.charAt(idx + messageTypeOffset);
    }

}

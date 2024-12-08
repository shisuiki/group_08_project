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
    // private final int messageTypeOffset = 17;

    private static final Logger logger = LoggerFactory.getLogger(TPAeronServer.class);

    public TPAeronServer() {
        aeron = Aeron.connect(new Aeron.Context());
        this.communicationOrchestrator = new ESBClusterCommunicationOrchestrator("");
    }

    @Override
    public void run() {
        ConcurrentPublication a = communicationOrchestrator.getBookEventsPublication();
        ConcurrentPublication b = communicationOrchestrator.getTopOfBookPublication();
        ConcurrentPublication c = communicationOrchestrator.getTradesPublication();

        // TODO: write 3 different threads to listen and then offer processed data to external channels
        while (true) {
            // String currentLeaderIp = leaderIp.get();
            // if (!currentLeaderIp.isEmpty()) {
            //     internalChannels.get(currentLeaderIp).poll((buffer, offset, length, header) -> {
            //             char messageType = (char) buffer.getByte(messageTypeOffset);
        
            //             switch (messageType) {
            //                 case 'T':
            //                     externalChannels.get(currentLeaderIp).get(tradeIdx).offer(buffer, offset, length);
            //                     break;
            //                 case 'K':
            //                     externalChannels.get(currentLeaderIp).get(topOfBookIdx).offer(buffer, offset, length);
            //                     break;
            //                 case 'D':
            //                     externalChannels.get(currentLeaderIp).get(bookEventsIdx).offer(buffer, offset, length);
            //                     break;
            //                 case 'S':
            //                     externalChannels.get(currentLeaderIp).get(bookEventsIdx).offer(buffer, offset, length);
            //                     break;
            //                 default:
            //                     logger.warn("Unknown message type: " + messageType);
            //             }
            //         }, 1);
            // }
        }
    }
}

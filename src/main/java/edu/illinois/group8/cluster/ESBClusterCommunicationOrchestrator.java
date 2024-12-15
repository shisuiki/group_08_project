package edu.illinois.group8.cluster;

import java.util.concurrent.ConcurrentHashMap;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

public class ESBClusterCommunicationOrchestrator {
    private final MediaDriver mediaDriver;
    private ConcurrentPublication internalChannelPublication;
    private Subscription internalChannelSubscription;
    private ConcurrentHashMap<Character, ConcurrentPublication> externalChannelPublications = new ConcurrentHashMap<>();
    private String[] ClusterNodes = {"172.20.0.2","172.20.0.3","172.20.0.4"};
    private int currentNodeId = 0;
    private final Aeron aeron;

    /**
     * Constructor for communication orchestrator.
     * @param ip IP of your system.
     */
    public ESBClusterCommunicationOrchestrator(String ip) {
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .termBufferSparseFile(true));
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        addInternalChannelPublication(ip);
        addInternalChannelSubscription(ip);
        addExternalChannelPublications(ip);
    }

    public void addExternalChannelPublications(String ip) {
        String endpoint = "aeron:udp?endpoint="+ip+":40456|control=224.0.1.1:40457|control-mode=dynamic";
        externalChannelPublications.put('T', aeron.addPublication(endpoint, StreamIDs.TRADE_IDX.getValue()));
        externalChannelPublications.put('K', aeron.addPublication(endpoint, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        ConcurrentPublication bookEventsPublication = aeron.addPublication(endpoint, StreamIDs.BOOK_EVENTS_IDX.getValue());
        externalChannelPublications.put('D', bookEventsPublication);
        externalChannelPublications.put('S', bookEventsPublication);
        externalChannelPublications.put('R', aeron.addPublication(endpoint, StreamIDs.TICKER_IDX.getValue()));
        externalChannelPublications.put('O', aeron.addPublication(endpoint, StreamIDs.OPEN_INTEREST_IDX.getValue()));
    }

    public void addInternalChannelPublication(String ip) {
        String endpoint = "aeron:udp?endpoint="+ip+":40456|control=224.0.1.1:40457|control-mode=dynamic";
        internalChannelPublication = aeron.addPublication(endpoint, StreamIDs.INTERNAL_IDX.getValue());
    }

    private void addInternalChannelSubscription(String ip) {
        String endpoint = "aeron:udp?endpoint="+ip+":40456|control=224.0.1.1:40457|control-mode=dynamic";
        internalChannelSubscription = aeron.addSubscription(endpoint, StreamIDs.INTERNAL_IDX.getValue());
    }

    /**
     * Gets ConcurrentPublication object for trades channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTradesPublication() {
        return externalChannelPublications.get('T');
    }

    /**
     * Gets ConcurrentPublication object for top of book channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTopOfBookPublication() {
        return externalChannelPublications.get('K');
    }

    /**
     * Gets ConcurrentPublication object for book events channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getBookEventsPublication() {
        return externalChannelPublications.get('D');
    }

    public ConcurrentPublication getTickerPublication() {
        return externalChannelPublications.get('R');
    }

    public ConcurrentPublication getOpenInterestPublication() {
        return externalChannelPublications.get('O');
    }

    /**
     * Gets Subscription object for internal data channel channel. Use .poll on the object to poll for new messages with the subscription object.
     * @return Subscription object
     */
    public Subscription getInternalSubscription() {
        return internalChannelSubscription;
    }

    /**
     * Gets ConcurrentPublication object for internal data channel.
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getInternalPublication() {
        return internalChannelPublication;
    }
}

package edu.illinois.group8.cluster;

import java.util.concurrent.ConcurrentHashMap;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

public class ESBClusterCommunicationOrchestrator {
    private final MediaDriver mediaDriver;
    private ConcurrentHashMap<Character, ConcurrentPublication> internalChannelPublications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Character, Subscription> internalChannelSubscriptions = new ConcurrentHashMap<>();
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
        addInternalChannelPublications(ip);
        addInternalChannelSubscriptions(ip);
    }

    public void addInternalChannelPublications(String ip) {
        String endpoint = "aeron:udp?endpoint="+ip+":40456|control=224.0.1.1:40457|control-mode=dynamic";
        internalChannelPublications.put('T', aeron.addPublication(endpoint, StreamIDs.TRADE_IDX.getValue()));
        internalChannelPublications.put('K', aeron.addPublication(endpoint, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        ConcurrentPublication bookEventsPublication = aeron.addPublication(endpoint, StreamIDs.BOOK_EVENTS_IDX.getValue());
        internalChannelPublications.put('D', bookEventsPublication);
        internalChannelPublications.put('S', bookEventsPublication);
        internalChannelPublications.put('R', aeron.addPublication(endpoint, StreamIDs.TICKER_IDX.getValue()));
        internalChannelPublications.put('O', aeron.addPublication(endpoint, StreamIDs.OPEN_INTEREST_IDX.getValue()));
    }

    private void addInternalChannelSubscriptions(String ip) {
        String endpoint = "aeron:udp?endpoint="+ip+":40456|control=224.0.1.1:40457|control-mode=dynamic";
        internalChannelSubscriptions.put('T', aeron.addSubscription(endpoint, StreamIDs.TRADE_IDX.getValue()));
        internalChannelSubscriptions.put('K', aeron.addSubscription(endpoint, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        Subscription bookEventsSubscription = aeron.addSubscription(endpoint, StreamIDs.BOOK_EVENTS_IDX.getValue());
        internalChannelSubscriptions.put('D', bookEventsSubscription);
        internalChannelSubscriptions.put('S', bookEventsSubscription);
        internalChannelSubscriptions.put('R', aeron.addSubscription(endpoint, StreamIDs.TICKER_IDX.getValue()));
        internalChannelSubscriptions.put('O', aeron.addSubscription(endpoint, StreamIDs.OPEN_INTEREST_IDX.getValue()));
    }

    /**
     * Gets ConcurrentPublication object for trades channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTradesPublication() {
        return internalChannelPublications.get('T');
    }

    /**
     * Gets ConcurrentPublication object for top of book channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTopOfBookPublication() {
        return internalChannelPublications.get('K');
    }

    /**
     * Gets ConcurrentPublication object for book events channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getBookEventsPublication() {
        return internalChannelPublications.get('D');
    }

    public ConcurrentPublication getTickerPublication() {
        return internalChannelPublications.get('R');
    }

    public ConcurrentPublication getOpenInterestPublication() {
        return internalChannelPublications.get('O');
    }

    /**
     * Gets Subscription object for trades channel. Use .poll on the object to poll for new messages with the subscription object.
     * @return ConcurrentPublication object
     */
    public Subscription getTradesSubscription() {
        return internalChannelSubscriptions.get('T');
    }

    /**
     * Gets Subscription object for top of book channel. Use .poll on the object to poll for new messages with the subscription object.
     * @return ConcurrentPublication object
     */
    public Subscription getTopOfBookSubscription() {
        return internalChannelSubscriptions.get('K');
    }

    /**
     * Gets Subscription object for book events channel. Use .poll on the object to poll for new messages with the subscription object.
     * @return ConcurrentPublication object
     */
    public Subscription getBookEventsSubscription() {
        return internalChannelSubscriptions.get('D');
    }

    public Subscription getTickerSubscription() {
        return internalChannelSubscriptions.get('R');
    }

    public Subscription getOpenInterestSubscription() {
        return internalChannelSubscriptions.get('O');
    }

}

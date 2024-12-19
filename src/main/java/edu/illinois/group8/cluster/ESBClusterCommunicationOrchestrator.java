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
    private ConcurrentHashMap<Character, Subscription> externalChannelSubscriptions = new ConcurrentHashMap<>();
    private String[] ClusterNodes = {"172.20.0.2","172.20.0.3","172.20.0.4"};
    private int currentNodeId = 0;
    private final Aeron aeron;

    public ESBClusterCommunicationOrchestrator(String ip, boolean isCluster, String aeronDirName) {
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .termBufferSparseFile(true)
                .aeronDirectoryName(aeronDirName));

        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));

        String channel;
        // channel = "aeron:udp?endpoint=" + ip + ":40456";
        if (isCluster) {
            channel = "aeron:udp?endpoint=0.0.0.0:40456";
        } else {
            channel = "aeron:udp?endpoint=0.0.0.0:40456";
        }
        
        addInternalChannelPublication(channel);
        addInternalChannelSubscription(channel);
        addExternalChannelPublications(channel);
        addExternalChannelSubscriptions(channel);
    }

    private void addInternalChannelPublication(String channel) {
        internalChannelPublication = aeron.addPublication(channel, StreamIDs.INTERNAL_IDX.getValue());
    }

    private void addInternalChannelSubscription(String channel) {
        internalChannelSubscription = aeron.addSubscription(channel, StreamIDs.INTERNAL_IDX.getValue());
    }

    private void addExternalChannelPublications(String channel) {
        externalChannelPublications.put('T', aeron.addPublication(channel, StreamIDs.TRADE_IDX.getValue()));
        externalChannelPublications.put('K', aeron.addPublication(channel, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        externalChannelPublications.put('D', aeron.addPublication(channel, StreamIDs.BOOK_EVENTS_IDX.getValue()));
        externalChannelPublications.put('R', aeron.addPublication(channel, StreamIDs.TICKER_IDX.getValue()));
        externalChannelPublications.put('O', aeron.addPublication(channel, StreamIDs.OPEN_INTEREST_IDX.getValue()));
    }

    private void addExternalChannelSubscriptions(String channel) {
        externalChannelSubscriptions.put('T', aeron.addSubscription(channel, StreamIDs.TRADE_IDX.getValue()));
        externalChannelSubscriptions.put('K', aeron.addSubscription(channel, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        externalChannelSubscriptions.put('D', aeron.addSubscription(channel, StreamIDs.BOOK_EVENTS_IDX.getValue()));
        externalChannelSubscriptions.put('R', aeron.addSubscription(channel, StreamIDs.TICKER_IDX.getValue()));
        externalChannelSubscriptions.put('O', aeron.addSubscription(channel, StreamIDs.OPEN_INTEREST_IDX.getValue()));
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

    /**
     * Gets ConcurrentPublication object for ticker channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTickerPublication() {
        return externalChannelPublications.get('R');
    }

    /**
     * Gets ConcurrentPublication object for open interest channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getOpenInterestPublication() {
        return externalChannelPublications.get('O');
    }

    /**
     * Gets ConcurrentPublication object for trades channel
     * @return ConcurrentPublication object
     */
    public Subscription getTradesSubscription() {
        return externalChannelSubscriptions.get('T');
    }

    /**
     * Gets ConcurrentPublication object for top of book channel
     * @return ConcurrentPublication object
     */
    public Subscription getTopOfBookSubscription() {
        return externalChannelSubscriptions.get('K');
    }

    /**
     * Gets ConcurrentPublication object for book events channel
     * @return ConcurrentPublication object
     */
    public Subscription getBookEventsSubscription() {
        return externalChannelSubscriptions.get('D');
    }

    /**
     * Gets ConcurrentPublication object for ticker channel
     * @return ConcurrentPublication object
     */
    public Subscription getTickerSubscription() {
        return externalChannelSubscriptions.get('R');
    }

    /**
     * Gets ConcurrentPublication object for open interest channel
     * @return ConcurrentPublication object
     */
    public Subscription getOpenInterestSubscription() {
        return externalChannelSubscriptions.get('O');
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

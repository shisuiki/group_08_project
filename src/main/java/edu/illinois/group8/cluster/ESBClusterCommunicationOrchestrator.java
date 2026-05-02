package edu.illinois.group8.cluster;

import java.util.concurrent.ConcurrentHashMap;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.canonical.StreamRegistry;
import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

public class ESBClusterCommunicationOrchestrator {
    private final MediaDriver mediaDriver;
    private ConcurrentPublication internalChannelPublication;
    private Subscription internalChannelSubscription;
    private final ConcurrentHashMap<String, ConcurrentPublication> externalChannelPublications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Subscription> externalChannelSubscriptions = new ConcurrentHashMap<>();
    private final Aeron aeron;

    public ESBClusterCommunicationOrchestrator(String ip, boolean isCluster, String aeronDirName) {
        if (isCluster) {
            mediaDriver = null;
            aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDirName));
        } else {
            mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                    .dirDeleteOnStart(true)
                    .termBufferSparseFile(true)
                    .aeronDirectoryName(aeronDirName));
            aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        }

        String internalChannel = System.getenv().getOrDefault("AERON_INTERNAL_CHANNEL", "aeron:ipc");
        String externalChannel = System.getenv().getOrDefault(
            "AERON_EXTERNAL_CHANNEL",
            System.getenv().getOrDefault("AERON_CHANNEL", "aeron:udp?endpoint=224.0.1.1:40456")
        );
        
        addInternalChannelPublication(internalChannel);
        addInternalChannelSubscription(internalChannel);
        addExternalChannelPublications(externalChannel);
        addExternalChannelSubscriptions(externalChannel);
    }

    private void addInternalChannelPublication(String channel) {
        internalChannelPublication = aeron.addPublication(channel, StreamIDs.INTERNAL_IDX.getValue());
    }

    private void addInternalChannelSubscription(String channel) {
        internalChannelSubscription = aeron.addSubscription(channel, StreamIDs.INTERNAL_IDX.getValue());
    }

    private void addExternalChannelPublications(String channel) {
        for (StreamContract stream : StreamRegistry.all()) {
            externalChannelPublications.put(stream.streamName(), aeron.addPublication(channel, stream.streamId()));
        }
    }

    private void addExternalChannelSubscriptions(String channel) {
        for (StreamContract stream : StreamRegistry.all()) {
            externalChannelSubscriptions.put(stream.streamName(), aeron.addSubscription(channel, stream.streamId()));
        }
    }

    public ConcurrentPublication getPublication(String streamName) {
        return externalChannelPublications.get(streamName);
    }

    public Subscription getSubscription(String streamName) {
        return externalChannelSubscriptions.get(streamName);
    }

    /**
     * Gets ConcurrentPublication object for trades channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTradesPublication() {
        return externalChannelPublications.get("canonical.trade");
    }

    /**
     * Gets ConcurrentPublication object for top of book channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTopOfBookPublication() {
        return externalChannelPublications.get("derived.top_of_book");
    }

    /**
     * Gets ConcurrentPublication object for book events channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getBookEventsPublication() {
        return externalChannelPublications.get("canonical.orderbook.delta");
    }

    /**
     * Gets ConcurrentPublication object for ticker channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getTickerPublication() {
        return externalChannelPublications.get("canonical.ticker");
    }

    /**
     * Gets ConcurrentPublication object for open interest channel
     * @return ConcurrentPublication object
     */
    public ConcurrentPublication getOpenInterestPublication() {
        return externalChannelPublications.get("canonical.open_interest");
    }

    /**
     * Gets ConcurrentPublication object for trades channel
     * @return ConcurrentPublication object
     */
    public Subscription getTradesSubscription() {
        return externalChannelSubscriptions.get("canonical.trade");
    }

    /**
     * Gets ConcurrentPublication object for top of book channel
     * @return ConcurrentPublication object
     */
    public Subscription getTopOfBookSubscription() {
        return externalChannelSubscriptions.get("derived.top_of_book");
    }

    /**
     * Gets ConcurrentPublication object for book events channel
     * @return ConcurrentPublication object
     */
    public Subscription getBookEventsSubscription() {
        return externalChannelSubscriptions.get("canonical.orderbook.delta");
    }

    /**
     * Gets ConcurrentPublication object for ticker channel
     * @return ConcurrentPublication object
     */
    public Subscription getTickerSubscription() {
        return externalChannelSubscriptions.get("canonical.ticker");
    }

    /**
     * Gets ConcurrentPublication object for open interest channel
     * @return ConcurrentPublication object
     */
    public Subscription getOpenInterestSubscription() {
        return externalChannelSubscriptions.get("canonical.open_interest");
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

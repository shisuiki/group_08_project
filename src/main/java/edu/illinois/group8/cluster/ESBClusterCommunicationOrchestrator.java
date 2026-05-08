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
            mediaDriver = launchEmbeddedDriver(aeronDirName + "-esb");
        } else {
            mediaDriver = launchEmbeddedDriver(aeronDirName);
        }
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));

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

    private MediaDriver launchEmbeddedDriver(String directoryName) {
        return MediaDriver.launchEmbedded(new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .termBufferSparseFile(true)
                .aeronDirectoryName(directoryName));
    }

    private void addInternalChannelPublication(String channel) {
        internalChannelPublication = aeron.addPublication(channel, StreamRegistry.INTERNAL_CANONICAL);
    }

    private void addInternalChannelSubscription(String channel) {
        internalChannelSubscription = aeron.addSubscription(channel, StreamRegistry.INTERNAL_CANONICAL);
    }

    private void addExternalChannelPublications(String channel) {
        for (StreamContract stream : StreamRegistry.externalStreams()) {
            externalChannelPublications.put(stream.streamName(), aeron.addPublication(channel, stream.streamId()));
        }
    }

    private void addExternalChannelSubscriptions(String channel) {
        for (StreamContract stream : StreamRegistry.externalStreams()) {
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

package edu.illinois.group8.cluster;

import java.util.concurrent.ConcurrentHashMap;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.Subscription;

public class ESBClusterCommunicationOrchestrator {
    private ConcurrentHashMap<Character, ConcurrentPublication> internalChannelPublications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Character, Subscription> internalChannelSubscriptions = new ConcurrentHashMap<>();
    private String[] ClusterNodes = {"172.20.0.2","172.20.0.3","172.20.0.4"};
    private int currentNodeId = 0;
    private final Aeron aeron;

    public ESBClusterCommunicationOrchestrator() {
        String ip = ClusterNodes[currentNodeId];
        aeron = Aeron.connect(new Aeron.Context());
        addInternalChannelPublications(ip);
        addInternalChannelSubscriptions(ip);
    }

    public void addInternalChannelPublications(String ip) {
        internalChannelPublications.put('T', aeron.addPublication("aeron:udp?endpoint=224.0.1.1:40456|interface="+ip, StreamIDs.TRADE_IDX.getValue()));
        internalChannelPublications.put('K', aeron.addPublication("aeron:udp?endpoint=224.0.1.1:40456|interface="+ip, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        ConcurrentPublication bookEventsPublication = aeron.addPublication("aeron:udp?endpoint=224.0.1.1:40456|interface="+ip, StreamIDs.BOOK_EVENTS_IDX.getValue());
        internalChannelPublications.put('D', bookEventsPublication);
        internalChannelPublications.put('S', bookEventsPublication);
    }

    private void addInternalChannelSubscriptions(String ip) {
        internalChannelSubscriptions.put('T', aeron.addSubscription("aeron:udp?endpoint=224.0.1.1:40456|interface=" + ip, StreamIDs.TRADE_IDX.getValue()));
        internalChannelSubscriptions.put('K', aeron.addSubscription("aeron:udp?endpoint=224.0.1.1:40456|interface=" + ip, StreamIDs.TOP_OF_BOOK_IDX.getValue()));
        Subscription bookEventsSubscription = aeron.addSubscription("aeron:udp?endpoint=224.0.1.1:40456|interface=" + ip, StreamIDs.BOOK_EVENTS_IDX.getValue());
        internalChannelSubscriptions.put('D', bookEventsSubscription);
        internalChannelSubscriptions.put('S', bookEventsSubscription);
    }

    public ConcurrentPublication getTradesPublication() {
        return internalChannelPublications.get('T');
    }

    public ConcurrentPublication getTopOfBookPublication() {
        return internalChannelPublications.get('K');
    }

    public ConcurrentPublication getBookEventsPublication() {
        return internalChannelPublications.get('D');
    }

    public Subscription getTradesSubscription() {
        return internalChannelSubscriptions.get('T');
    }

    public Subscription getTopOfBookSubscription() {
        return internalChannelSubscriptions.get('K');
    }

    public Subscription getBookEventsSubscription() {
        return internalChannelSubscriptions.get('D');
    }
}

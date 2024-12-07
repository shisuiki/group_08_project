package edu.illinois.group8.tickerplant;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;

import edu.illinois.group8.etcd.EtcdManager;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.options.WatchOption;

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
    private final EtcdManager etcdManager;
    private final Client etcdClient;

    private final int tradeIdx = 0;
    private final int topOfBookIdx = 1;
    private final int bookEventsIdx = 2;
    private final int messageTypeOffset = 17;

    private AtomicReference<String> leaderIp;
    private final ByteSequence leaderKey = ByteSequence.from("leader", StandardCharsets.UTF_8);
    private final Thread watchThread;

    private ConcurrentHashMap<String, List<Publication>> externalChannels;
    private ConcurrentHashMap<String, Subscription> internalChannels;

    private static final Logger logger = LoggerFactory.getLogger(TPAeronServer.class);

    public TPAeronServer(EtcdManager etcdManager) {
        externalChannels = new ConcurrentHashMap<>();
        internalChannels = new ConcurrentHashMap<>();
        leaderIp = new AtomicReference<>("");
        aeron = Aeron.connect(new Aeron.Context());

        this.etcdManager = etcdManager;
        this.etcdClient = etcdManager.getClient();
        etcdClient.getKVClient().get(leaderKey)
            .thenAccept(response -> {
                if (response.getKvs().isEmpty()) {
                    leaderIp.set("");
                    logger.info("Leader key not found.");
                } else {
                    String newLeaderIp = response.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
                    leaderIp.set(newLeaderIp);
                    createChannelsForLeader(newLeaderIp);
                    logger.info("Current leader IP: " + newLeaderIp);
                }
            })
            .exceptionally(ex -> {
                logger.error("Error fetching leader key", ex);
                return null;
            });

        watchThread = new Thread(() -> { watchForLeaderChanges(); });
        watchThread.start();
    }

    private void createChannelsForLeader(String ipString) {
        if (!ipString.isEmpty()) {
            // TODO: make publications multicast with interface to leader node
            externalChannels.put(ipString, new ArrayList<>(List.of(
                aeron.addPublication("aeron:udp?endpoint=" + ipString, 10),
                aeron.addPublication("aeron:udp?endpoint=" + ipString, 11),
                aeron.addPublication("aeron:udp?endpoint=" + ipString, 12)
            )));
            internalChannels.put(ipString, aeron.addSubscription("aeron:udp?endpoint=" + ipString, 1));
        }
    }

    public void watchForLeaderChanges() {
        Watch watchClient = etcdClient.getWatchClient();
        watchClient.watch(leaderKey, WatchOption.DEFAULT,
            watchResponse -> watchResponse.getEvents().forEach(event -> {
                switch (event.getEventType()) {
                    case PUT:
                        String newLeaderIp = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);
                        leaderIp.set(newLeaderIp);
                        if (!externalChannels.containsKey(newLeaderIp)) {
                            createChannelsForLeader(newLeaderIp);
                        }
                        logger.info("Leader updated to: " + newLeaderIp);
                        break;
                    case DELETE:
                        leaderIp.set("");
                        logger.info("Leader key deleted.");
                        break;
                    default:
                    logger.warn("Unhandled event: " + event.getEventType());
                }
            }),
            throwable -> {
                logger.error("Error watching for leader changes", throwable);
            });
    }

    @Override
    public void run() {
        while (true) {
            String currentLeaderIp = leaderIp.get();
            if (!currentLeaderIp.isEmpty()) {
                internalChannels.get(currentLeaderIp).poll((buffer, offset, length, header) -> {
                        char messageType = (char) buffer.getByte(messageTypeOffset);
        
                        switch (messageType) {
                            case 'T':
                                externalChannels.get(currentLeaderIp).get(tradeIdx).offer(buffer, offset, length);
                                break;
                            case 'K':
                                externalChannels.get(currentLeaderIp).get(topOfBookIdx).offer(buffer, offset, length);
                                break;
                            case 'D':
                                externalChannels.get(currentLeaderIp).get(bookEventsIdx).offer(buffer, offset, length);
                                break;
                            case 'S':
                                externalChannels.get(currentLeaderIp).get(bookEventsIdx).offer(buffer, offset, length);
                                break;
                            default:
                                logger.warn("Unknown message type: " + messageType);
                        }
                    }, 1);
            }
        }
    }
}

package edu.illinois.group8.etcd;

import java.nio.charset.StandardCharsets;
import java.nio.file.WatchEvent;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.options.WatchOption;

public class EtcdManager {
    private Client client;
    private KV kvClient;

    public EtcdManager(String... endpoints) {
        this.client = Client.builder().endpoints(endpoints).build();
        this.kvClient = this.client.getKVClient();
    }

    public void setLeader(String ip) throws Exception {
        kvClient.put(ByteSequence.from("leader", StandardCharsets.UTF_8), ByteSequence.from(ip, StandardCharsets.UTF_8)).get();
    }

    public void setNodeIP(String nodeID, String ip) throws Exception {
        kvClient.put(ByteSequence.from(nodeID, StandardCharsets.UTF_8), ByteSequence.from(ip, StandardCharsets.UTF_8)).get();
        // kvClient.put(io.etcd.jetcd.ByteSequence.from(nodeID.getBytes()), io.etcd.jetcd.ByteSequence.from(ip.getBytes())).get();
    }

    public void watchForLeaderChanges() {
        final String key = "leader";
        ByteSequence keyBytes = ByteSequence.from(key.getBytes(StandardCharsets.UTF_8));

        Watch watchClient = client.getWatchClient();
        watchClient.watch(keyBytes, WatchOption.DEFAULT,
            watchResponse -> watchResponse.getEvents().forEach(event -> {
                switch (event.getEventType()) {
                    case PUT:
                        System.out.println("Leader updated to: " +
                                event.getKeyValue().getValue().toString(StandardCharsets.UTF_8));
                        break;
                    case DELETE:
                        System.out.println("Leader key deleted.");
                        break;
                    default:
                        System.out.println("Unhandled event: " + event.getEventType());
                }
            }),
            throwable -> {
                System.err.println("Error watching for leader changes: " + throwable.getMessage());
                throwable.printStackTrace();
            }
        );
    }
}

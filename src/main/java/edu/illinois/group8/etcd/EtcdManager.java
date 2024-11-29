package edu.illinois.group8.etcd;

import java.nio.charset.StandardCharsets;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;

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
}

package edu.illinois.group8.cluster;

import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import io.aeron.logbuffer.Header;

import edu.illinois.group8.esb.DataProcessor;
import edu.illinois.group8.esb.Tickerplant;

import edu.illinois.group8.demo.MarketGridDemo;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.AsyncDbWriter;
import edu.illinois.group8.storage.db.AsyncDbWriterFactory;
import edu.illinois.group8.storage.db.CanonicalDbSink;
import edu.illinois.group8.storage.db.DbWriterConfig;

public class ESBClusteredService implements ClusteredService {
    static final int SNAPSHOT_OFFER_MAX_ATTEMPTS = 1024;

    @FunctionalInterface
    interface SnapshotOffer {
        long offer(DirectBuffer buffer, int offset, int length);
    }

    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private Role currentRole = Role.FOLLOWER;
    private Aeron aeron;
    private String hostname;
    private String aeronDirName;
    private ESBClusterCommunicationOrchestrator communicationOrchestrator;
    private DataProcessor processor;
    private CanonicalDbSink canonicalDbSink;
    private Thread tickerplantThread;
    private Thread clientThread;
    private final BackendMetrics metrics;
    private final Supplier<DbWriterConfig> dbWriterConfigSupplier;
    private final BiFunction<DbWriterConfig, BackendMetrics, AsyncDbWriter> dbWriterFactory;
    private byte[] sessionMessageScratch = new byte[0];

    public ESBClusteredService(String aeronDirName, String hostname) {
        this(aeronDirName, hostname, new BackendMetrics());
    }

    ESBClusteredService(String aeronDirName, String hostname, BackendMetrics metrics) {
        this(aeronDirName, hostname, metrics, DbWriterConfig::fromEnvironment, AsyncDbWriterFactory::create);
    }

    ESBClusteredService(
        String aeronDirName,
        String hostname,
        Supplier<DbWriterConfig> dbWriterConfigSupplier,
        BiFunction<DbWriterConfig, BackendMetrics, AsyncDbWriter> dbWriterFactory
    ) {
        this(aeronDirName, hostname, new BackendMetrics(), dbWriterConfigSupplier, dbWriterFactory);
    }

    ESBClusteredService(
        String aeronDirName,
        String hostname,
        BackendMetrics metrics,
        Supplier<DbWriterConfig> dbWriterConfigSupplier,
        BiFunction<DbWriterConfig, BackendMetrics, AsyncDbWriter> dbWriterFactory
    ) {
        this.aeronDirName = aeronDirName;
        this.hostname = hostname;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.dbWriterConfigSupplier = Objects.requireNonNull(dbWriterConfigSupplier, "dbWriterConfigSupplier");
        this.dbWriterFactory = Objects.requireNonNull(dbWriterFactory, "dbWriterFactory");
    }

    ESBClusteredService(String aeronDirName, String hostname, DataProcessor processor) {
        this(aeronDirName, hostname);
        this.processor = Objects.requireNonNull(processor, "processor");
    }
    
    private void loadSnapshot(final Cluster cluster, final Image snapshotImage) {
        if (snapshotImage == null) {
            return;
        }

        boolean[] restored = {false};
        FragmentAssembler assembler = new FragmentAssembler((buf, offset, bufLength, header) -> {
            byte[] payload = new byte[bufLength];
            buf.getBytes(offset, payload);
            restoreSnapshotPayload(payload);
            restored[0] = true;
        });
        int fragmentsRead = 0;
        while (!restored[0]) {
            int fragments = snapshotImage.poll(assembler, 10);
            if (fragments == 0) {
                break;
            }
            fragmentsRead += fragments;
        }
        if (fragmentsRead > 0 && !restored[0]) {
            throw new IllegalArgumentException("Incomplete ESB cluster snapshot payload.");
        }
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        this.idleStrategy = cluster.idleStrategy();

        Aeron.Context ctx = new Aeron.Context().aeronDirectoryName(aeronDirName);
        aeron = Aeron.connect(ctx);

        this.communicationOrchestrator = new ESBClusterCommunicationOrchestrator(this.hostname, true, aeronDirName);
        initializeCanonicalDbSink();
        this.processor = new DataProcessor(communicationOrchestrator, metrics, canonicalDbSink);
        loadSnapshot(cluster, snapshotImage);

        this.tickerplantThread = new Thread(new Tickerplant(communicationOrchestrator));
        this.tickerplantThread.start();

        if (Boolean.parseBoolean(System.getenv().getOrDefault("BACKEND_START_DEMO_CLIENT", "false"))) {
            this.clientThread = new Thread(new MarketGridDemo(communicationOrchestrator));
            this.clientThread.start();
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        // Handle new client session

    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        // Handle client session closure
    }

    /**
     * Function triggered when a message is sent to the ESB cluster. Only triggered by the leader node.
     * Message is read then processed, and finally published to the corresponding channels.
     */
    @Override
    public void onSessionMessage(
        ClientSession session,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header) {
        
        
        // Process incoming messages from clients
        if (currentRole != Role.LEADER) {
            return;
        }
        
        byte[] messageBytes = ensureSessionMessageScratch(length);
        buffer.getBytes(offset, messageBytes, 0, length);

        // DataProcessor synchronously parses the borrowed slice and does not retain this scratch buffer.
        processor.processMessage(messageBytes, 0, length);
    }

    private byte[] ensureSessionMessageScratch(int length) {
        if (sessionMessageScratch.length < length) {
            sessionMessageScratch = new byte[length];
        }
        return sessionMessageScratch;
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Handle timer events
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        // Save the current state to a snapshot
        if (snapshotPublication == null) {
            throw new IllegalArgumentException("Snapshot publication must not be null.");
        }
        offerSnapshotPayload(snapshotPayloadBytes(), snapshotPublication::offer, idleStrategy);
        System.out.println("created recovery snapshot");
    }

    void offerSnapshotPayload(byte[] payload, SnapshotOffer offer, IdleStrategy idleStrategy) {
        if (payload == null) {
            throw new IllegalArgumentException("Snapshot payload must not be null.");
        }
        if (offer == null) {
            throw new IllegalArgumentException("Snapshot offer must not be null.");
        }
        if (idleStrategy == null) {
            throw new IllegalStateException("Cluster idle strategy is not initialized.");
        }

        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(payload.length));
        buffer.putBytes(0, payload);
        idleStrategy.reset();

        long lastResult = Publication.BACK_PRESSURED;
        for (int attempt = 1; attempt <= SNAPSHOT_OFFER_MAX_ATTEMPTS; attempt++) {
            long result = offer.offer(buffer, 0, payload.length);
            if (result >= 0L) {
                return;
            }
            if (isTerminalSnapshotOfferResult(result)) {
                throw snapshotOfferFailed(result);
            }
            if (!isRetryableSnapshotOfferResult(result)) {
                throw snapshotOfferFailed(result);
            }

            lastResult = result;
            if (attempt < SNAPSHOT_OFFER_MAX_ATTEMPTS) {
                idleStrategy.idle();
            }
        }

        throw new IllegalStateException(
            "Failed to offer ESB cluster snapshot after " + SNAPSHOT_OFFER_MAX_ATTEMPTS
                + " attempts: " + Publication.errorString(lastResult)
        );
    }

    private static boolean isRetryableSnapshotOfferResult(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION;
    }

    private static boolean isTerminalSnapshotOfferResult(long result) {
        return result == Publication.CLOSED
            || result == Publication.NOT_CONNECTED
            || result == Publication.MAX_POSITION_EXCEEDED;
    }

    private static IllegalStateException snapshotOfferFailed(long result) {
        return new IllegalStateException("Failed to offer ESB cluster snapshot: " + Publication.errorString(result));
    }

    @Override
    public void onRoleChange(Role newRole) {
        // React to role changes (LEADER, FOLLOWER, etc.)
        System.out.println("new role: " + newRole);
        this.currentRole = newRole;
        
        // if (newRole == Role.LEADER) {
        //     // streamIDMap.forEach((key, streamID) -> {
        //     //     Publication pub = aeron.addPublication("aeron:udp?endpoint=224.0.1.3:40456", streamID); // Stream ID can vary per topic
        //     //     publicationMap.put(key, pub);
        //     // });
        // } else {
        //     // publicationMap.values().forEach(Publication::close);
        //     // publicationMap.clear();
        // }
    }

    @Override
    public void onTerminate(Cluster cluster) {
        closeCanonicalDbSink();
    }

    void initializeCanonicalDbSink() {
        DbWriterConfig config = dbWriterConfigSupplier.get();
        AsyncDbWriter writer = dbWriterFactory.apply(config, metrics);
        this.canonicalDbSink = new CanonicalDbSink(writer);
    }

    void closeCanonicalDbSink() {
        if (canonicalDbSink != null) {
            canonicalDbSink.close();
        }
    }

    byte[] snapshotPayloadBytes() {
        if (processor == null) {
            throw new IllegalStateException("DataProcessor is not initialized.");
        }
        return ESBClusterSnapshotCodec.encode(processor.recoveryState());
    }

    void restoreSnapshotPayload(byte[] payload) {
        if (processor == null) {
            throw new IllegalStateException("DataProcessor is not initialized.");
        }
        processor.restoreRecoveryState(ESBClusterSnapshotCodec.decode(payload));
    }
}

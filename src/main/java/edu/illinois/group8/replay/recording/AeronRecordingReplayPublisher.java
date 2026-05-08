package edu.illinois.group8.replay.recording;

import edu.illinois.group8.canonical.StreamRegistry;
import edu.illinois.group8.metrics.BackendMetrics;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class AeronRecordingReplayPublisher implements RecordingReplayPublisher {
    private final String channel;
    private final BackendMetrics metrics;
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final Map<String, Publication> publications = new HashMap<>();

    public AeronRecordingReplayPublisher(String channel, BackendMetrics metrics) {
        this.channel = channel;
        this.metrics = metrics;
        this.mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .termBufferSparseFile(true));
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
    }

    @Override
    public boolean publish(RecordingEvent event, String payload) {
        Publication publication = publication(event.streamName());
        if (publication == null) {
            metrics.increment("backend_replay_publish_failed_total",
                BackendMetrics.labels("service", "recording_replay", "stream", event.streamName(), "reason", "unknown_stream"));
            return false;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.wrap(bytes));
        long startTsNs = System.nanoTime();
        long result = publication.offer(buffer, 0, bytes.length);
        long elapsedNs = Math.max(0L, System.nanoTime() - startTsNs);
        var labels = BackendMetrics.labels("service", "recording_replay", "stream", event.streamName());
        metrics.increment("backend_publication_offer_total", labels);
        metrics.observe("backend_publication_latency_ns", labels, elapsedNs);
        if (result < 0L) {
            metrics.increment("backend_publication_offer_failed_total", labels);
            metrics.observe("backend_publication_backpressure_ns", labels, elapsedNs);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        publications.values().forEach(Publication::close);
        aeron.close();
        mediaDriver.close();
    }

    private Publication publication(String streamName) {
        return publications.computeIfAbsent(streamName, name -> StreamRegistry.byName(name)
            .map(stream -> aeron.addPublication(channel, stream.streamId()))
            .orElse(null));
    }
}

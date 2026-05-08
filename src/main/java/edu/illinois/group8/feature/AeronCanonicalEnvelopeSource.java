package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class AeronCanonicalEnvelopeSource implements CanonicalEnvelopeSource {
    private final String channel;
    private final List<StreamContract> streams;
    private final ObjectMapper mapper;
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final List<Subscription> subscriptions;

    public AeronCanonicalEnvelopeSource(String channel, List<StreamContract> streams) {
        this.channel = channel;
        this.streams = List.copyOf(streams);
        this.mapper = new JsonCanonicalSerializer().mapper();
        this.mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .termBufferSparseFile(true));
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        this.subscriptions = this.streams.stream()
            .map(stream -> aeron.addSubscription(channel, stream.streamId()))
            .toList();
    }

    @Override
    public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
        int target = Math.max(1, fragmentLimit);
        int fragments = 0;
        for (int i = 0; i < subscriptions.size() && fragments < target; i++) {
            Subscription subscription = subscriptions.get(i);
            StreamContract stream = streams.get(i);
            int remaining = target - fragments;
            fragments += subscription.poll((buffer, offset, length, header) -> {
                byte[] bytes = new byte[length];
                buffer.getBytes(offset, bytes);
                String payload = new String(bytes, StandardCharsets.UTF_8);
                handler.onEvent(CanonicalEnvelope.fromPayload(stream.streamName(), payload, mapper));
            }, remaining);
        }
        return fragments;
    }

    @Override
    public void close() {
        subscriptions.forEach(Subscription::close);
        aeron.close();
        mediaDriver.close();
    }

    public String channel() {
        return channel;
    }
}

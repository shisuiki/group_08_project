package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.canonical.StreamContract;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;

import java.util.List;
import java.util.Objects;

public class AeronCanonicalEnvelopeSource implements CanonicalEnvelopeSource {
    private final String channel;
    private final List<StreamContract> streams;
    private final ObjectMapper mapper;
    private final List<StreamPoller> streamPollers;
    private final Runnable closeResources;
    private int nextStreamIndex;
    private boolean closed;

    public AeronCanonicalEnvelopeSource(String channel, List<StreamContract> streams) {
        this.channel = channel;
        this.streams = List.copyOf(streams);
        this.mapper = new JsonCanonicalSerializer().mapper();
        MediaDriver mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .termBufferSparseFile(true));
        Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        this.streamPollers = this.streams.stream()
            .<StreamPoller>map(stream -> new SubscriptionStreamPoller(aeron.addSubscription(channel, stream.streamId())))
            .toList();
        this.closeResources = () -> {
            aeron.close();
            mediaDriver.close();
        };
    }

    AeronCanonicalEnvelopeSource(
        String channel,
        List<StreamContract> streams,
        ObjectMapper mapper,
        List<StreamPoller> streamPollers,
        Runnable closeResources
    ) {
        if (streams.size() != streamPollers.size()) {
            throw new IllegalArgumentException("streams and streamPollers must have the same size");
        }
        this.channel = channel;
        this.streams = List.copyOf(streams);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.streamPollers = List.copyOf(streamPollers);
        this.closeResources = Objects.requireNonNull(closeResources, "closeResources");
    }

    @Override
    public int poll(CanonicalEnvelopeHandler handler, int fragmentLimit) {
        if (streamPollers.isEmpty()) {
            return 0;
        }
        int target = Math.max(1, fragmentLimit);
        int fragments = 0;
        int startIndex = nextStreamIndex;
        int visited = 0;
        int lastVisitedIndex = startIndex;
        while (visited < streamPollers.size() && fragments < target) {
            int index = (startIndex + visited) % streamPollers.size();
            lastVisitedIndex = index;
            StreamContract stream = streams.get(index);
            int remaining = target - fragments;
            fragments += streamPollers.get(index).poll((buffer, offset, length, header) -> {
                byte[] bytes = new byte[length];
                buffer.getBytes(offset, bytes);
                handler.onEvent(CanonicalEnvelope.fromPayloadBytes(stream.streamName(), bytes, 0, length, mapper));
            }, remaining);
            visited++;
        }
        nextStreamIndex = (lastVisitedIndex + 1) % streamPollers.size();
        return fragments;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        streamPollers.forEach(StreamPoller::close);
        closeResources.run();
    }

    public String channel() {
        return channel;
    }

    interface StreamPoller extends AutoCloseable {
        int poll(FragmentHandler handler, int fragmentLimit);

        @Override
        void close();
    }

    private static final class SubscriptionStreamPoller implements StreamPoller {
        private final Subscription subscription;

        private SubscriptionStreamPoller(Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public int poll(FragmentHandler handler, int fragmentLimit) {
            return subscription.poll(handler, fragmentLimit);
        }

        @Override
        public void close() {
            subscription.close();
        }
    }
}

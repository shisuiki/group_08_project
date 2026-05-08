package edu.illinois.group8.profile;

import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;
import edu.illinois.group8.publication.EventPublisher;

final class ProfilingEventPublishers {
    private ProfilingEventPublishers() {
    }

    static final class BlackholePublisher implements EventPublisher {
        private long consumed;

        @Override
        public boolean publish(CanonicalEvent event) {
            consumed += event.eventId().length();
            return true;
        }

        long consumed() {
            return consumed;
        }
    }

    static final class SerializingPublisher implements EventPublisher {
        private final JsonCanonicalSerializer serializer = new JsonCanonicalSerializer();
        private long bytes;

        @Override
        public boolean publish(CanonicalEvent event) {
            bytes += serializer.toBytes(event).length;
            return true;
        }

        long bytes() {
            return bytes;
        }
    }

    static final class CountingPublisher implements EventPublisher {
        private final EventPublisher delegate;
        private long events;

        CountingPublisher(EventPublisher delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean publish(CanonicalEvent event) {
            boolean published = delegate.publish(event);
            if (published) {
                events++;
            }
            return published;
        }

        long events() {
            return events;
        }

        EventPublisher delegate() {
            return delegate;
        }
    }
}

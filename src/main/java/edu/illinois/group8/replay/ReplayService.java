package edu.illinois.group8.replay;

import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.canonical.CanonicalEvent;
import edu.illinois.group8.parser.CanonicalParseResult;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.persistence.RawJournalReader;
import edu.illinois.group8.publication.EventPublisher;

import java.util.List;

public class ReplayService {
    private final KalshiCanonicalParser parser;
    private final RawJournalReader rawJournalReader;

    public ReplayService(KalshiCanonicalParser parser, RawJournalReader rawJournalReader) {
        this.parser = parser;
        this.rawJournalReader = rawJournalReader;
    }

    public ReplaySummary replay(ReplayOptions options, EventPublisher publisher, ReplayController controller) {
        List<String> rawPayloads = rawJournalReader.readRawPayloads();
        OrderBookStateManager bookStateManager = new OrderBookStateManager();
        long rawRead = 0L;
        long canonicalPublished = 0L;
        long skipped = 0L;
        Long previousEventTsMs = null;

        for (String rawPayload : rawPayloads) {
            if (controller.stopped()) {
                break;
            }
            controller.awaitIfPaused();
            CanonicalParseResult result = parser.parseWebSocketMessage(rawPayload, System.nanoTime(), options.replayId());
            rawRead++;
            List<CanonicalEvent> events = result.canonicalEvents();
            if (events.isEmpty()) {
                skipped++;
                continue;
            }

            Long batchTsMs = firstEventTsMs(events);
            if (!passesTimeFilter(options, batchTsMs) || !passesMarketFilter(options, events)) {
                skipped++;
                continue;
            }

            sleepForReplayPacing(options, previousEventTsMs, batchTsMs);
            previousEventTsMs = batchTsMs == null ? previousEventTsMs : batchTsMs;

            for (CanonicalEvent event : events) {
                if (publisher.publish(event)) {
                    canonicalPublished++;
                }
                for (CanonicalEvent generated : bookStateManager.apply(event).generatedEvents()) {
                    if (publisher.publish(generated)) {
                        canonicalPublished++;
                    }
                }
            }
        }

        return new ReplaySummary(options.replayId(), rawRead, canonicalPublished, skipped);
    }

    private static boolean passesTimeFilter(ReplayOptions options, Long eventTsMs) {
        if (eventTsMs == null) {
            return true;
        }
        if (options.startTsMs() != null && eventTsMs < options.startTsMs()) {
            return false;
        }
        return options.endTsMs() == null || eventTsMs <= options.endTsMs();
    }

    private static boolean passesMarketFilter(ReplayOptions options, List<CanonicalEvent> events) {
        if (options.marketTickers().isEmpty()) {
            return true;
        }
        return events.stream()
            .map(event -> event.metadata().marketTicker())
            .anyMatch(options.marketTickers()::contains);
    }

    private static Long firstEventTsMs(List<CanonicalEvent> events) {
        return events.stream()
            .map(event -> event.metadata().eventTsMs())
            .filter(value -> value != null)
            .findFirst()
            .orElse(null);
    }

    private static void sleepForReplayPacing(ReplayOptions options, Long previousTsMs, Long currentTsMs) {
        if (options.mode() == ReplayMode.STEP || previousTsMs == null || currentTsMs == null) {
            return;
        }
        long deltaMs = Math.max(0L, currentTsMs - previousTsMs);
        long sleepMs = options.mode() == ReplayMode.WALL_CLOCK
            ? deltaMs
            : Math.round(deltaMs / options.speedMultiplier());
        if (sleepMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

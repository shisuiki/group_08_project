package edu.illinois.group8.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReplaySessionManager {
    private final GatewayEventStore store;
    private final GatewayWebSocketBroadcaster broadcaster;
    private final ObjectMapper mapper;
    private final Map<String, ReplaySession> sessions = new ConcurrentHashMap<>();

    public ReplaySessionManager(GatewayEventStore store, GatewayWebSocketBroadcaster broadcaster, ObjectMapper mapper) {
        this.store = store;
        this.broadcaster = broadcaster;
        this.mapper = mapper;
    }

    public Map<String, Object> create(JsonNode request) {
        List<String> markets = parseMarkets(request.path("symbols").isMissingNode()
            ? request.path("market_tickers")
            : request.path("symbols"));
        Long fromMs = optionalLong(request, "from");
        Long toMs = optionalLong(request, "to");
        double speed = request.path("speed").asDouble(1.0);
        String mode = request.path("mode").asText("multiplier");
        String id = request.path("id").asText("");
        if (id.isBlank()) {
            id = "replay-" + UUID.randomUUID();
        }
        ReplaySession session = new ReplaySession(id, markets, fromMs, toMs, Math.max(speed, 0.01), mode);
        sessions.put(id, session);
        session.start();
        return session.snapshot();
    }

    public List<Map<String, Object>> list() {
        return sessions.values().stream()
            .map(ReplaySession::snapshot)
            .toList();
    }

    public Map<String, Object> pause(String id) {
        ReplaySession session = require(id);
        session.paused.set(true);
        session.status = "paused";
        return session.snapshot();
    }

    public Map<String, Object> resume(String id) {
        ReplaySession session = require(id);
        session.paused.set(false);
        session.status = "running";
        return session.snapshot();
    }

    public Map<String, Object> stop(String id) {
        ReplaySession session = require(id);
        session.stopped.set(true);
        session.status = "stopped";
        return session.snapshot();
    }

    public Map<String, Object> seek(String id, JsonNode request) {
        ReplaySession session = require(id);
        Long target = optionalLong(request, "time");
        if (target == null) {
            target = optionalLong(request, "time_ms");
        }
        if (target == null) {
            throw new IllegalArgumentException("seek requires time or time_ms");
        }
        session.seekToMs = target;
        session.currentReplayTimeMs = target;
        return session.snapshot();
    }

    private ReplaySession require(String id) {
        ReplaySession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Unknown replay session: " + id);
        }
        return session;
    }

    private static Long optionalLong(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (!node.isNumber() && !node.isTextual()) {
            return null;
        }
        long value = node.isNumber() ? node.asLong() : Long.parseLong(node.asText());
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    private static List<String> parseMarkets(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        }
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText());
                }
            });
        }
        return List.copyOf(result);
    }

    private final class ReplaySession {
        private final String id;
        private final List<String> markets;
        private final Long fromMs;
        private final Long toMs;
        private final double speed;
        private final String mode;
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile String status = "starting";
        private volatile long currentReplayTimeMs;
        private volatile long streamLagMs;
        private volatile int eventsPublished;
        private volatile Long seekToMs;
        private volatile Thread thread;

        private ReplaySession(String id, List<String> markets, Long fromMs, Long toMs, double speed, String mode) {
            this.id = id;
            this.markets = markets;
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.speed = speed;
            this.mode = mode;
        }

        private void start() {
            thread = new Thread(this::run, "integration-replay-" + id);
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            List<JsonNode> events = store.eventsForReplay(markets, fromMs, toMs);
            status = "running";
            long previousTs = 0L;
            int index = 0;
            while (index < events.size() && !stopped.get()) {
                while (paused.get() && !stopped.get()) {
                    sleep(20L);
                }
                Long requestedSeek = seekToMs;
                if (requestedSeek != null) {
                    index = firstIndexAtOrAfter(events, requestedSeek);
                    previousTs = 0L;
                    seekToMs = null;
                    continue;
                }
                JsonNode event = events.get(index++);
                long eventTs = GatewayEventStore.eventTsMs(event);
                pace(previousTs, eventTs);
                previousTs = eventTs == 0L ? previousTs : eventTs;
                currentReplayTimeMs = eventTs;
                streamLagMs = eventTs == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - eventTs);
                broadcast(event);
                eventsPublished++;
                if ("step".equalsIgnoreCase(mode)) {
                    paused.set(true);
                    status = "paused";
                }
            }
            status = stopped.get() ? "stopped" : "completed";
        }

        private int firstIndexAtOrAfter(List<JsonNode> events, long targetMs) {
            for (int i = 0; i < events.size(); i++) {
                long eventTs = GatewayEventStore.eventTsMs(events.get(i));
                if (eventTs >= targetMs) {
                    return i;
                }
            }
            return events.size();
        }

        private void pace(long previousTs, long eventTs) {
            if (previousTs <= 0L || eventTs <= 0L || "step".equalsIgnoreCase(mode)) {
                return;
            }
            long delta = Math.max(0L, eventTs - previousTs);
            long sleepMs = "wall_clock".equalsIgnoreCase(mode) ? delta : Math.round(delta / speed);
            sleep(Math.min(sleepMs, 5_000L));
        }

        private void broadcast(JsonNode event) {
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("type", "replay_event");
                envelope.put("session_id", id);
                envelope.put("replay_time_ms", currentReplayTimeMs);
                envelope.put("replay_time", currentReplayTimeMs == 0L ? null : Instant.ofEpochMilli(currentReplayTimeMs).toString());
                envelope.put("event", event);
                broadcaster.broadcast(mapper.writeValueAsString(envelope));
            } catch (Exception e) {
                status = "error";
                throw new IllegalStateException("Failed to broadcast replay event", e);
            }
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("id", id);
            snapshot.put("status", status);
            snapshot.put("symbols", markets);
            snapshot.put("from_ms", fromMs);
            snapshot.put("to_ms", toMs);
            snapshot.put("speed", speed);
            snapshot.put("mode", mode);
            snapshot.put("current_replay_time_ms", currentReplayTimeMs == 0L ? null : currentReplayTimeMs);
            snapshot.put("stream_lag_ms", streamLagMs);
            snapshot.put("events_published", eventsPublished);
            return snapshot;
        }

        private void sleep(long ms) {
            if (ms <= 0L) {
                return;
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped.set(true);
            }
        }
    }
}

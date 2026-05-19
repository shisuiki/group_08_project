package edu.illinois.group8.canonical;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record SerializedCanonicalEvent(CanonicalEvent event, byte[] utf8Json) {
    public SerializedCanonicalEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(utf8Json, "utf8Json");
    }

    public static SerializedCanonicalEvent from(CanonicalEvent event, JsonCanonicalSerializer serializer) {
        Objects.requireNonNull(serializer, "serializer");
        return new SerializedCanonicalEvent(event, serializer.toBytes(event));
    }

    public String payloadJson() {
        return new String(utf8Json, StandardCharsets.UTF_8);
    }
}

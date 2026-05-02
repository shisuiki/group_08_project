package edu.illinois.group8.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

public class JsonCanonicalSerializer {
    private final ObjectMapper objectMapper;

    public JsonCanonicalSerializer() {
        this.objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    public byte[] toBytes(CanonicalEvent event) {
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize canonical event " + event.eventId(), e);
        }
    }

    public String toJson(CanonicalEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize canonical event " + event.eventId(), e);
        }
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }
}

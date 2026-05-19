package edu.illinois.group8.backfill;

import edu.illinois.group8.storage.db.RawRestDbResponse;
import edu.illinois.group8.storage.db.RawRestResponseStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class DbRawRestBackfillSink implements RawRestBackfillSink {
    private final RawRestResponseStore store;

    DbRawRestBackfillSink(RawRestResponseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void write(String endpoint, String ticker, String rawPayload, long fetchTsNs, Instant fetchWallTs) throws Exception {
        String payloadSha256 = sha256(rawPayload);
        store.insertRawRestResponseBatch(List.of(new RawRestDbResponse(
            rawRestResponseId(endpoint, ticker, payloadSha256),
            endpoint,
            blankToNull(ticker),
            fetchTsNs,
            fetchWallTs,
            payloadSha256,
            rawPayload
        )));
    }

    static String rawRestResponseId(String endpoint, String ticker, String payloadSha256) {
        String identity = endpoint + '\0' + (ticker == null ? "" : ticker) + '\0' + payloadSha256;
        return "raw_rest_" + sha256(identity).substring(0, 24);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}

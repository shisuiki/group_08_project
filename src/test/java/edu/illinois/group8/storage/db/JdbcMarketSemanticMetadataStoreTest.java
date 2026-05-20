package edu.illinois.group8.storage.db;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMarketSemanticMetadataStoreTest {
    @Test
    void migrationCreatesSemanticMetadataAndJobTables() throws Exception {
        String sql = migration().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table if not exists market_semantic_metadata"));
        assertTrue(sql.contains("primary key (market_ticker, taxonomy_version)"));
        assertTrue(sql.contains("source_payload_sha256 text not null"));
        assertTrue(sql.contains("source_fingerprint text not null"));
        assertTrue(sql.contains("idempotency_key text not null"));
        assertTrue(sql.contains("tags jsonb"));
        assertTrue(sql.contains("raw_response jsonb"));
        assertTrue(sql.contains("status in ('generated', 'review_required', 'failed', 'rate_limited')"));
        assertTrue(sql.contains("create table if not exists market_semantic_metadata_jobs"));
        assertTrue(sql.contains("attempts integer not null default 0"));
        assertTrue(sql.contains("market_semantic_metadata_jobs_idempotency_uidx"));
        assertTrue(sql.contains("market_semantic_metadata_jobs_market_taxonomy_prompt_uidx"));
    }

    @Test
    void upsertSqlIsIdempotentAndBindsJsonPayloads() {
        String metadataSql = JdbcMarketSemanticMetadataStore.UPSERT_METADATA_SQL.toLowerCase(Locale.ROOT);
        String jobSql = JdbcMarketSemanticMetadataStore.UPSERT_JOB_SQL.toLowerCase(Locale.ROOT);
        assertTrue(metadataSql.contains("?::jsonb"));
        assertTrue(metadataSql.contains("on conflict (market_ticker, taxonomy_version) do update"));
        assertTrue(metadataSql.contains("is distinct from"));
        assertTrue(metadataSql.contains("source_fingerprint = excluded.source_fingerprint"));
        assertTrue(metadataSql.contains("idempotency_key = excluded.idempotency_key"));
        assertTrue(jobSql.contains("?::jsonb"));
        assertTrue(jobSql.contains("on conflict (idempotency_key) do update"));
        assertTrue(jobSql.contains("greatest(market_semantic_metadata_jobs.attempts"));

        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcMarketSemanticMetadataStore store = new JdbcMarketSemanticMetadataStore(jdbc::openConnection);
        store.upsertMetadata(new MarketSemanticMetadata(
            "M",
            "tax-v1",
            "model",
            "prompt-v1",
            "hash",
            "source-hash",
            "source-fingerprint",
            "idem-key",
            "politics",
            "election",
            "binary",
            "US",
            "near_term",
            "medium",
            "high",
            "[\"election\"]",
            new BigDecimal("0.75"),
            "rationale",
            "{\"raw\":true}",
            "generated",
            null,
            Instant.parse("2026-01-01T00:00:00Z")
        ));

        assertEquals(JdbcMarketSemanticMetadataStore.UPSERT_METADATA_SQL, jdbc.preparedSql);
        assertEquals("M", jdbc.parameters.get(1));
        assertEquals("tax-v1", jdbc.parameters.get(2));
        assertEquals("source-hash", jdbc.parameters.get(6));
        assertEquals("source-fingerprint", jdbc.parameters.get(7));
        assertEquals("idem-key", jdbc.parameters.get(8));
        assertEquals("[\"election\"]", jdbc.parameters.get(16));
        assertEquals(new BigDecimal("0.75"), jdbc.parameters.get(17));
        assertEquals("{\"raw\":true}", jdbc.parameters.get(19));
        assertInstanceOf(OffsetDateTime.class, jdbc.parameters.get(22));

        jdbc.parameters.clear();
        store.upsertJob(new MarketSemanticMetadataJob(
            "job-1",
            "M",
            "tax-v1",
            "hash",
            "source-hash",
            "source-fingerprint",
            "idem-key",
            "free-model",
            "paid-model",
            "rate_limited",
            2,
            Instant.parse("2026-01-01T00:01:00Z"),
            "429",
            "{\"total_tokens\":12}"
        ));

        assertEquals(JdbcMarketSemanticMetadataStore.UPSERT_JOB_SQL, jdbc.preparedSql);
        assertEquals("job-1", jdbc.parameters.get(1));
        assertEquals("source-hash", jdbc.parameters.get(5));
        assertEquals("source-fingerprint", jdbc.parameters.get(6));
        assertEquals("idem-key", jdbc.parameters.get(7));
        assertEquals("paid-model", jdbc.parameters.get(9));
        assertEquals("rate_limited", jdbc.parameters.get(10));
        assertEquals(2, jdbc.parameters.get(11));
        assertEquals("{\"total_tokens\":12}", jdbc.parameters.get(14));
    }

    @Test
    void validationAndSqlFailuresAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSemanticMetadata(
            " ",
            "tax",
            "model",
            "prompt",
            "hash",
            "source-hash",
            "source-fingerprint",
            "idem-key",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "[]",
            null,
            null,
            "{}",
            "generated",
            null,
            null
        ));
        assertThrows(IllegalArgumentException.class, () -> new MarketSemanticMetadataJob(
            "job",
            "M",
            "tax",
            "hash",
            "source-hash",
            "source-fingerprint",
            "idem-key",
            "model",
            null,
            "pending",
            -1,
            null,
            null,
            null
        ));

        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failExecuteUpdate = true;
        JdbcMarketSemanticMetadataStore store = new JdbcMarketSemanticMetadataStore(jdbc::openConnection);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> store.upsertJob(
            new MarketSemanticMetadataJob(
                "job",
                "M",
                "tax",
                "hash",
                "source-hash",
                "source-fingerprint",
                "idem-key",
                "model",
                null,
                "pending",
                0,
                null,
                null,
                null
            )
        ));
        assertTrue(thrown.getMessage().contains("market_semantic_metadata_jobs"));
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    private static String migration() throws Exception {
        try (InputStream inputStream = JdbcMarketSemanticMetadataStoreTest.class.getClassLoader()
            .getResourceAsStream("db/migration/V012__market_semantic_metadata.sql")) {
            assertNotNull(inputStream);
            return new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class RecordingJdbc {
        private final Map<Integer, Object> parameters = new HashMap<>();
        private String preparedSql;
        private boolean failExecuteUpdate;

        private Connection openConnection() {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                this::handleConnection
            );
        }

        private Object handleConnection(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "prepareStatement" -> {
                    preparedSql = (String) args[0];
                    yield preparedStatement();
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                this::handlePreparedStatement
            );
        }

        private Object handlePreparedStatement(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "setString", "setBigDecimal", "setObject", "setInt" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], null);
                    yield null;
                }
                case "executeUpdate" -> {
                    if (failExecuteUpdate) {
                        throw new SQLException("write failed");
                    }
                    yield 1;
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == byte.class || type == short.class || type == int.class || type == long.class) {
                return 0;
            }
            if (type == float.class || type == double.class) {
                return 0.0;
            }
            return null;
        }
    }
}

package edu.illinois.group8.storage.db;

import edu.illinois.group8.metrics.BackendMetrics;

import java.util.Objects;

public final class AsyncDbWriterFactory {
    private AsyncDbWriterFactory() {
    }

    public static AsyncDbWriter create(DbWriterConfig config, BackendMetrics metrics) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(metrics, "metrics");
        if (!config.enabled()) {
            return AsyncDbWriter.disabled();
        }
        return new BoundedAsyncDbWriter(
            JdbcAcceptedEventStore.fromDriverManager(
                config.databaseUrl(),
                config.databaseUser(),
                config.databasePassword()
            ),
            config.queueCapacity(),
            config.batchSize(),
            metrics
        );
    }

    static AsyncDbWriter create(
        DbWriterConfig config,
        BackendMetrics metrics,
        JdbcConnectionFactory connectionFactory
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        if (!config.enabled()) {
            return AsyncDbWriter.disabled();
        }
        AcceptedEventStore store = new JdbcAcceptedEventStore(connectionFactory);
        return new BoundedAsyncDbWriter(
            store,
            config.queueCapacity(),
            config.batchSize(),
            metrics
        );
    }
}

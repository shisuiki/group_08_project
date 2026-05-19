package edu.illinois.group8.frontend;

import edu.illinois.group8.canonical.StreamContract;
import edu.illinois.group8.feature.AeronCanonicalEnvelopeSource;
import edu.illinois.group8.feature.BestBidOfferFeatureModule;
import edu.illinois.group8.feature.CanonicalEnvelopeSource;
import edu.illinois.group8.feature.DbCanonicalEnvelopeSource;
import edu.illinois.group8.feature.FeatureModule;
import edu.illinois.group8.feature.FeatureOutput;
import edu.illinois.group8.feature.FeatureOutputSink;
import edu.illinois.group8.feature.FeaturePlantService;
import edu.illinois.group8.feature.RecordingCanonicalEnvelopeSource;
import edu.illinois.group8.feature.TickerSnapshotFeatureModule;
import edu.illinois.group8.feature.TradeTapeFeatureModule;
import edu.illinois.group8.metrics.BackendMetrics;
import edu.illinois.group8.storage.db.FeatureOutputReadRequest;
import edu.illinois.group8.storage.db.FeatureOutputReader;
import edu.illinois.group8.storage.db.JdbcCanonicalEventReader;
import edu.illinois.group8.storage.db.JdbcConnectionFactories;
import edu.illinois.group8.storage.db.JdbcFeatureOutputReader;
import edu.illinois.group8.storage.db.JdbcMarketMetadataReader;
import edu.illinois.group8.storage.db.MarketMetadata;
import edu.illinois.group8.storage.db.MarketMetadataReadRequest;
import edu.illinois.group8.storage.db.MarketMetadataReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class FrontendAdapterMain {
    private FrontendAdapterMain() {
    }

    public static void main(String[] args) throws Exception {
        FrontendAdapterConfig config = FrontendAdapterConfig.fromEnvironment();
        FrontendFeatureStore store = new FrontendFeatureStore(
            config.maxFeaturesPerMarket(),
            config.maxSymbolsIndexed()
        );
        if (config.featureSource() == FrontendAdapterConfig.FeatureSource.FEATURE_OUTPUTS) {
            int seeded = seedFeatureOutputs(config, store, buildFeatureOutputReader(config));
            FrontendMarketMetadataCatalog metadataCatalog = buildMarketMetadataCatalog(config);
            FrontendAdapterServer server = new FrontendAdapterServer(
                config,
                store,
                metadataCatalog,
                () -> FrontendAdapterServer.FeaturePlantStats.EMPTY
            );
            server.start();
            CountDownLatch stop = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                stop.countDown();
            }, "frontend-adapter-shutdown"));
            System.out.println("FrontendAdapter listening on " + config.host() + ":" + server.boundPort()
                + " feature_source=" + config.featureSource().name().toLowerCase(Locale.ROOT)
                + " seeded_feature_outputs=" + seeded
                + " market_metadata_status=" + metadataCatalog.loadStatus().name().toLowerCase(Locale.ROOT)
                + " market_metadata_rows=" + metadataCatalog.size()
                + " max_feature_output_rows=" + config.featureOutputMaxRows());
            stop.await();
            return;
        }

        CanonicalEnvelopeSource source = buildSource(config);
        List<FeatureModule> modules = resolveModules(config.moduleNames());
        BackendMetrics metrics = new BackendMetrics();
        FeatureOutputSink sink = output -> store.accept(output);
        FeaturePlantService service = new FeaturePlantService(source, modules, sink, metrics);

        FrontendAdapterServer server = new FrontendAdapterServer(
            config,
            store,
            buildMarketMetadataCatalog(config),
            () -> readFeaturePlantStats(metrics)
        );
        server.start();

        AtomicBoolean running = new AtomicBoolean(true);
        Thread feeder = new Thread(() -> feedLoop(service, config, running), "frontend-adapter-feeder");
        feeder.setDaemon(true);
        feeder.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            feeder.interrupt();
            server.stop();
            service.close();
        }, "frontend-adapter-shutdown"));

        System.out.println("FrontendAdapter listening on " + config.host() + ":" + server.boundPort()
            + " source_mode=" + config.sourceMode()
            + " feature_source=" + config.featureSource().name().toLowerCase(Locale.ROOT)
            + " streams=" + config.streams().stream().map(StreamContract::streamName).toList()
            + " modules=" + config.moduleNames()
            + " max_features_per_market=" + config.maxFeaturesPerMarket()
            + " fragment_limit=" + config.fragmentLimit());
        feeder.join();
    }

    static int seedFeatureOutputs(
        FrontendAdapterConfig config,
        FrontendFeatureStore store,
        FeatureOutputReader reader
    ) {
        List<FeatureOutput> outputs = reader.read(featureOutputReadRequest(config));
        for (int index = outputs.size() - 1; index >= 0; index--) {
            store.accept(outputs.get(index));
        }
        return outputs.size();
    }

    static FeatureOutputReadRequest featureOutputReadRequest(FrontendAdapterConfig config) {
        return FeatureOutputReadRequest.recent(resolveFeatureNames(config.moduleNames()), config.featureOutputMaxRows());
    }

    static FeatureOutputReader buildFeatureOutputReader(FrontendAdapterConfig config) {
        if (config.dbUrl().isBlank()) {
            throw new IllegalArgumentException(
                "FRONTEND_ADAPTER_DB_URL or DB_WRITER_DATABASE_URL is required when "
                    + "FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs"
            );
        }
        return JdbcFeatureOutputReader.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword());
    }

    static FrontendMarketMetadataCatalog buildMarketMetadataCatalog(FrontendAdapterConfig config) {
        return loadMarketMetadata(config, () -> buildMarketMetadataReader(config));
    }

    static FrontendMarketMetadataCatalog loadMarketMetadata(
        FrontendAdapterConfig config,
        java.util.function.Supplier<MarketMetadataReader> readerSupplier
    ) {
        if (config.metadataSource() == FrontendAdapterConfig.MetadataSource.DISABLED) {
            return FrontendMarketMetadataCatalog.disabled("disabled");
        }
        String source = config.metadataSource().name().toLowerCase(Locale.ROOT);
        if (config.dbUrl().isBlank()) {
            if (config.metadataSource() == FrontendAdapterConfig.MetadataSource.DB) {
                throw new IllegalArgumentException(
                    "FRONTEND_ADAPTER_DB_URL or DB_WRITER_DATABASE_URL is required when "
                        + "FRONTEND_ADAPTER_METADATA_SOURCE=db"
                );
            }
            return FrontendMarketMetadataCatalog.disabled(source);
        }
        try {
            List<MarketMetadata> rows = readerSupplier.get().read(marketMetadataReadRequest(config));
            return FrontendMarketMetadataCatalog.loaded(source, rows);
        } catch (RuntimeException e) {
            if (config.metadataSource() == FrontendAdapterConfig.MetadataSource.DB) {
                throw e;
            }
            return FrontendMarketMetadataCatalog.unavailable(source, e.getMessage());
        }
    }

    static MarketMetadataReadRequest marketMetadataReadRequest(FrontendAdapterConfig config) {
        return MarketMetadataReadRequest.search(null, null, config.metadataMaxRows());
    }

    static MarketMetadataReader buildMarketMetadataReader(FrontendAdapterConfig config) {
        if (config.dbUrl().isBlank()) {
            throw new IllegalArgumentException(
                "FRONTEND_ADAPTER_DB_URL or DB_WRITER_DATABASE_URL is required when "
                    + "FRONTEND_ADAPTER_METADATA_SOURCE=db"
            );
        }
        return JdbcMarketMetadataReader.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword());
    }

    private static void feedLoop(FeaturePlantService service, FrontendAdapterConfig config, AtomicBoolean running) {
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, config.idleSleepMillis()));
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int polled;
            try {
                polled = service.poll(config.fragmentLimit());
            } catch (RuntimeException e) {
                System.err.println("frontend-adapter feeder error: " + e.getMessage());
                LockSupport.parkNanos(idleNanos);
                continue;
            }
            if (polled == 0) {
                LockSupport.parkNanos(idleNanos);
            }
        }
    }

    static CanonicalEnvelopeSource buildSource(FrontendAdapterConfig config) {
        return switch (config.sourceMode()) {
            case AERON -> new AeronCanonicalEnvelopeSource(config.aeronChannel(), config.streams());
            case RECORDING -> RecordingCanonicalEnvelopeSource.fromRoot(
                config.recordingRoot(), config.streams(), config.recordingMaxEvents()
            );
            case DB -> dbSource(config);
        };
    }

    private static CanonicalEnvelopeSource dbSource(FrontendAdapterConfig config) {
        if (config.dbUrl().isBlank()) {
            throw new IllegalArgumentException(
                "FRONTEND_ADAPTER_DB_URL or DB_WRITER_DATABASE_URL is required when FRONTEND_ADAPTER_SOURCE=db"
            );
        }
        return new DbCanonicalEnvelopeSource(
            new JdbcCanonicalEventReader(
                JdbcConnectionFactories.fromDriverManager(config.dbUrl(), config.dbUser(), config.dbPassword())
            ),
            config.streams(),
            config.recordingMaxEvents(),
            config.dbIncludeReplayEvents(),
            config.dbReplayId()
        );
    }

    static List<FeatureModule> resolveModules(List<String> moduleNames) {
        List<FeatureModule> resolved = new ArrayList<>();
        for (String name : moduleNames) {
            switch (name.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "bbo", "best_bid_offer" -> resolved.add(new BestBidOfferFeatureModule());
                case "ticker", "ticker_snapshot" -> resolved.add(new TickerSnapshotFeatureModule());
                case "trade", "trade_tape" -> resolved.add(new TradeTapeFeatureModule());
                default -> throw new IllegalArgumentException("Unknown feature module: " + name);
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("FRONTEND_ADAPTER_MODULES must include at least one module.");
        }
        return List.copyOf(resolved);
    }

    static List<String> resolveFeatureNames(List<String> moduleNames) {
        List<String> resolved = new ArrayList<>();
        for (String name : moduleNames) {
            switch (name.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "bbo", "best_bid_offer" -> resolved.add(BestBidOfferFeatureModule.FEATURE_NAME);
                case "ticker", "ticker_snapshot" -> resolved.add(TickerSnapshotFeatureModule.FEATURE_NAME);
                case "trade", "trade_tape" -> resolved.add(TradeTapeFeatureModule.FEATURE_NAME);
                default -> throw new IllegalArgumentException("Unknown feature module: " + name);
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("FRONTEND_ADAPTER_MODULES must include at least one module.");
        }
        return List.copyOf(resolved);
    }

    static FrontendAdapterServer.FeaturePlantStats readFeaturePlantStats(BackendMetrics metrics) {
        Map<String, Long> snapshot = metrics.snapshot();
        long eventsIn = sumByPrefix(snapshot, "feature_module_events_in_total");
        long eventsOut = sumByPrefix(snapshot, "feature_module_events_out_total");
        long errors = sumByPrefix(snapshot, "feature_module_errors_total");
        return new FrontendAdapterServer.FeaturePlantStats(eventsIn, eventsOut, errors);
    }

    private static long sumByPrefix(Map<String, Long> snapshot, String prefix) {
        long total = 0L;
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            String key = entry.getKey();
            if (key.equals(prefix) || key.startsWith(prefix + "{")) {
                total += entry.getValue();
            }
        }
        return total;
    }
}

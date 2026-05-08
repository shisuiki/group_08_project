# Hot Path Profiling

This repo can profile the backend hot path without Kalshi credentials using deterministic synthetic WebSocket payloads.

## Build

```bash
mvn package -DskipTests
```

If Maven is not installed locally, use the Maven Docker image or a temporary Maven distribution. The profiling CLI is packaged into the normal shaded jar.

## Modes

Run the profiler with:

```bash
java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.profile.HotPathProfileCli \
  --mode=parse-only \
  --iterations=100000 \
  --warmup=20000 \
  --markets=1
```

Available modes:

- `parse-only`: raw JSON string to canonical parser result.
- `parse-book`: parser plus `OrderBookStateManager` and top-of-book generation.
- `processor-noop`: full `DataProcessor` with no-op journal and no-op publisher.
- `processor-serialize`: full `DataProcessor` with no-op journal and JSON serialization in the publisher.
- `processor-file-journal`: full `DataProcessor` with file journal and no-op publisher.
- `processor-full-local`: full `DataProcessor` with file journal and JSON serialization.

Use these modes in order. They isolate the current costs before any optimization work:

1. Parser/object creation cost.
2. Book-state and derived-event cost.
3. Processor orchestration and metrics cost.
4. Serialization cost.
5. Synchronous file-journal cost.

## JFR

Java Flight Recorder gives allocation and CPU attribution for the same run:

```bash
java \
  -XX:StartFlightRecording=filename=hotpath.jfr,settings=profile,dumponexit=true \
  -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.profile.HotPathProfileCli \
  --mode=processor-full-local \
  --iterations=100000 \
  --warmup=20000 \
  --markets=1 \
  --print-metrics
```

Open `hotpath.jfr` in Java Mission Control and inspect:

- Allocation pressure by class.
- CPU samples in Jackson parsing/serialization.
- File I/O time in `FileEventJournal`.
- Lock/contention events, especially around synchronized journal append.
- GC pauses and allocation rate.

## Output

The CLI prints:

- elapsed time
- throughput in messages/second
- canonical, generated, and published event counts
- serialized bytes where applicable
- min/mean/p50/p90/p95/p99/max latency in nanoseconds

With `--print-metrics`, it also prints the current `BackendMetrics` Prometheus text so metrics overhead can be evaluated in the same run.

## Evidence Rules

Do not optimize based on one mode alone. Compare adjacent modes:

- `parse-book - parse-only` approximates book-state overhead.
- `processor-noop - parse-book` approximates processor, metrics, and raw-event publication orchestration overhead.
- `processor-serialize - processor-noop` approximates canonical JSON serialization overhead.
- `processor-file-journal - processor-noop` approximates synchronous local journal overhead.
- `processor-full-local` shows the current local worst case without Aeron.

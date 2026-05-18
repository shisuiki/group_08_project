# Hot Path Profiling

This repo can profile the backend hot path without Kalshi credentials using deterministic synthetic WebSocket payloads.

## Build

```bash
./mvnw package -DskipTests
```

Use the Maven wrapper so contributors do not need a local Maven install. The profiling CLI is packaged into the normal shaded jar.

## Baseline Script

Use the baseline script to build the shaded jar and run all four modes in a
fixed order:

```bash
scripts/hot-path-baseline.sh
```

By default, the script writes raw profiler stdout to:

```text
target/hotpath-baselines/<timestamp>/
```

It creates one file per mode plus `summary.txt`, which records the timestamp,
git commit, parameters, and output paths. Outputs under `target/` are ignored by
Git.

Configuration:

- `HOTPATH_ITERATIONS`: measured messages per mode, default `100000`.
- `HOTPATH_WARMUP`: warmup messages per mode, default `20000`.
- `HOTPATH_MARKETS`: synthetic market count, default `1`.
- `HOTPATH_OUTPUT_DIR`: output directory, default `target/hotpath-baselines/<timestamp>`.
- `HOTPATH_PRINT_METRICS`: set to `true` to pass `--print-metrics`.

For a quick smoke run:

```bash
HOTPATH_ITERATIONS=1000 \
HOTPATH_WARMUP=100 \
HOTPATH_OUTPUT_DIR=target/hotpath-baselines/test \
scripts/hot-path-baseline.sh
```

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
- `processor-noop`: full `DataProcessor` with a blackhole publisher.
- `processor-serialize`: full `DataProcessor` with JSON serialization in the publisher.

Use these modes in order. They isolate the current costs before any optimization work:

1. Parser/object creation cost.
2. Book-state and derived-event cost.
3. Processor orchestration and metrics cost.
4. Serialization cost.

File/object recording is no longer in `DataProcessor`. Profile storage pressure
at the downstream Aeron-client boundary with `TickerplantStreamRecorder`.

## JFR

Java Flight Recorder gives allocation and CPU attribution for the same run:

```bash
java \
  -XX:StartFlightRecording=filename=hotpath.jfr,settings=profile,dumponexit=true \
  -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.profile.HotPathProfileCli \
  --mode=processor-serialize \
  --iterations=100000 \
  --warmup=20000 \
  --markets=1 \
  --print-metrics
```

Open `hotpath.jfr` in Java Mission Control and inspect:

- Allocation pressure by class.
- CPU samples in Jackson parsing/serialization.
- Lock/contention events around parser, book-state, and publisher code.
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

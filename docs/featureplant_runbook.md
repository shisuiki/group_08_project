# Featureplant Runbook

The featureplant is a downstream module boundary. It does not own Kalshi
ingestion, durable raw replay, or frontend APIs. It consumes canonical envelopes
through `CanonicalEnvelopeSource`, so the same feature module can run against:

- live tickerplant streams through `AeronCanonicalEnvelopeSource`;
- historical canonical recordings through `RecordingCanonicalEnvelopeSource`.

Downstream visualization, backtesting, and research export modules should attach
to `FeatureOutputSink` or a persistent feature store built behind that sink. They
should not subscribe directly to live tickerplant streams.

## Current Modules

- `BestBidOfferFeatureModule`: consumes `derived.top_of_book`, emits
  `feature.bbo`.
- `TickerSnapshotFeatureModule`: consumes `canonical.ticker`, emits
  `feature.ticker_snapshot`.
- `TradeTapeFeatureModule`: consumes `canonical.trade`, emits
  `feature.trade_tape`.

The current sinks are intentionally simple:

- `StdoutFeatureOutputSink` writes NDJSON-style feature outputs for smoke tests.
- `BoundedFeatureOutputBuffer` keeps recent outputs in memory for embedded
  adapters.
- `CollectingFeatureOutputSink` is for tests.

## Historical Run

```bash
docker compose --profile featureplant run --rm featureplant \
  --source=recording \
  --root=/app/recordings \
  --streams=canonical.trade,canonical.ticker,derived.top_of_book \
  --modules=bbo,ticker_snapshot,trade_tape \
  --max-events=10000 \
  --run-once
```

`RecordingCanonicalEnvelopeSource` reads `recordings/canonical`, which may
contain downstream stream-recorder records, REST historical backfill records, or
both.

## Live Run

```bash
docker compose --profile featureplant run --rm featureplant \
  --source=aeron \
  --channel='aeron:udp?endpoint=224.0.1.1:40456' \
  --streams=canonical.trade,canonical.ticker,derived.top_of_book \
  --modules=bbo,ticker_snapshot,trade_tape \
  --follow
```

Use live mode for low-latency feature experiments. Use recording mode for
dataviz, research export, reproducible backtests, and backfills.

## Metrics

`FeaturePlantService` emits:

- `feature_module_enabled`
- `feature_module_events_in_total`
- `feature_module_events_out_total`
- `feature_module_errors_total`
- `feature_module_latency_ns`
- `feature_module_lag_ms`

Expose those metrics from the module host that embeds `FeaturePlantService`.

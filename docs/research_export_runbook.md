# Research Export Runbook

`ResearchExportCli` is a batch exporter that turns historical canonical
recordings into per-feature CSV files for research and backtesting consumption.
It is downstream of `FeaturePlantService` and consumes `FeatureOutput` through
a `FeatureOutputSink` (`CsvFeatureExportSink`). It does not subscribe to live
tickerplant streams and does not introduce any new feature modules; see
`docs/featureplant_runbook.md` for upstream feature semantics.

The source is always `RecordingCanonicalEnvelopeSource`, which reads
`recordings/canonical` (stream-recorder canonical capture and/or REST
backfill output). Live `--source=aeron` is intentionally not supported here.

## Arguments

```text
--source=recording                       # only mode currently supported
--root=<recordings dir>                  # parent of canonical/
--streams=<csv canonical stream names>   # same names as FeaturePlantCli
--modules=<csv feature module names>     # bbo|ticker_snapshot|trade_tape
--output=<output dir>                    # CSV destination (auto-created)
--max-events=<int>                       # optional, source-side cap
--from-ts-ms=<long>                      # optional, inclusive lower bound
--to-ts-ms=<long>                        # optional, inclusive upper bound
--markets=<csv market tickers>           # optional filter
--batch-size=<int>                       # poll batch size, default 100
```

Module short names use the same mapping as `FeaturePlantCli`
(`bbo`, `ticker_snapshot`, `trade_tape`). Market and time-window filters are
applied inside the sink; envelopes whose `eventTsMs` is null are discarded when
any time bound is set.

## Local Run

```bash
mvn -q -DskipTests package

java -cp target/kalshi-project-1.0-SNAPSHOT.jar \
  edu.illinois.group8.export.ResearchExportCli \
  --source=recording \
  --root=recordings \
  --streams=canonical.trade,canonical.ticker,derived.top_of_book \
  --modules=bbo,ticker_snapshot,trade_tape \
  --output=exports/$(date -u +%Y%m%dT%H%M%SZ) \
  --max-events=100000
```

## Docker Run

The existing `featureplant` Compose profile builds the same image, so
`ResearchExportCli` can be invoked through it by overriding the entrypoint
and mounting a writable output directory:

```bash
mkdir -p exports

docker compose --profile featureplant run --rm \
  -v "$(pwd)/exports:/app/exports" \
  --entrypoint sh \
  featureplant \
  -c "java -cp /app/app.jar edu.illinois.group8.export.ResearchExportCli \
        --source=recording \
        --root=/app/recordings \
        --streams=canonical.trade,canonical.ticker,derived.top_of_book \
        --modules=bbo,ticker_snapshot,trade_tape \
        --output=/app/exports/run"
```

The `recordings` volume on the `featureplant` service is mounted read-only,
which is fine because `ResearchExportCli` only reads recordings; output is
written to the separately-mounted `exports` volume.

## Output Shape

For each feature stream that produced at least one row:

```text
<output>/feature.bbo.csv
<output>/feature.ticker_snapshot.csv
<output>/feature.trade_tape.csv
```

Each CSV starts with a header row of constant columns followed by feature-
specific columns derived from the first observed `FeatureOutput.values` keys:

```text
feature_name,stream_name,market_ticker,event_ts_ms,source_event_id,<value cols...>
```

The header is fixed once the first row is written. Later rows that contain
extra value keys are written without those keys, and one warning per unknown
key is emitted to `stderr`. Numeric values (`Long`, `Integer`, `Double`,
`Boolean`) are written as their raw form; string values follow minimal
RFC 4180 quoting.

Alongside the CSVs, `ResearchExportCli` writes `<output>/metadata.json`:

```json
{
  "run_ts_iso": "2026-05-12T17:03:21.412Z",
  "source_mode": "recording",
  "source_root": "recordings",
  "streams": ["canonical.trade", "canonical.ticker", "derived.top_of_book"],
  "modules": ["feature.bbo", "feature.ticker_snapshot", "feature.trade_tape"],
  "markets": null,
  "from_ts_ms": null,
  "to_ts_ms": null,
  "max_events": 100000,
  "total_envelopes": 84231,
  "output_rows": {
    "feature.bbo": 22014,
    "feature.ticker_snapshot": 19887,
    "feature.trade_tape": 5331
  }
}
```

Use `total_envelopes` and `output_rows` to sanity-check that the run consumed
what you expected; mismatches typically indicate an over-restrictive market
or time-window filter.

## Related Docs

- `docs/featureplant_runbook.md` — upstream feature module catalog and
  source/sink contracts.
- `docs/stream_recorder_runbook.md` — how `recordings/canonical` is produced.
- `docs/backend_stream_contracts.md` — canonical stream identities consumed by
  the feature modules.

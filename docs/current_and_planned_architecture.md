# Current And Planned Architecture

This note compares the repository as it exists now with the old block diagram in
`diagram.png` and the five implementation plans in the parent coursework
directory.

## Comparison Summary

The old diagram is still directionally correct for the original live path:
Kalshi feeds a data collection service, the ESB processes messages, an internal
Aeron channel feeds the tickerplant, and external Aeron channels serve clients
and storage.

The current codebase has expanded that simple path:

- `Kalshi Data Collection Service` now maps to `KalshiSystem`,
  `KalshiWrapper`, `KalshiWebSocketClient`, and `ClientClusterOrchestrator`.
- `Enterprise Service Bus` now maps to Aeron Cluster nodes running
  `ESBClusteredService`, `DataProcessor`, `KalshiCanonicalParser`,
  `OrderBookStateManager`, `FileEventJournal`, and the internal canonical bus.
- `Tickerplant` still exists, but it now routes by `stream_name` through
  `StreamRegistry` instead of hardcoded message offsets.
- `External Aeron Channels` now mean versioned stream IDs 10-19:
  raw, canonical, derived top-of-book, parser errors, and sequence gaps.
- `Data Warehousing Service -> Redshift` has been removed from the current
  source tree and replaced by file/object recording: `raw-ingest` for exact
  websocket payloads, `producer-canonical` for normalized producer-side events,
  and optional downstream `canonical` recorder output for Aeron-consumer
  validation.
- New current modules not shown in the old diagram include stream recording,
  storage-backed replay, source-agnostic featureplant templates, stream tap
  inspection, Prometheus/Grafana, and hot-path profiling.

Plan status from the markdowns:

| Plan | Current state | Where remaining planned modules belong |
| --- | --- | --- |
| `01_core_backend_implementation_plan.md` | Mostly represented in code: config, canonical events, parser, order book state, stream registry, file/object recording, replay, Docker profiles, docs, and metrics hooks. | Remaining hardening stays inside the core backend: cluster snapshots/recovery, fuller sequence recovery, object-store backfill, binary serialization experiments, and WebSocket heartbeat reliability. |
| `02_feature_plant_basic_implementation_plan.md` | Initial `feature` package exists: source-agnostic canonical envelope input, recording-backed and Aeron-backed sources, a feature runtime, bounded output buffer, and BBO/ticker/trade templates. | Add persistent feature outputs, richer stateful modules, and a query/export layer that can consume buffered feature outputs from live or historical sources. |
| `03_standard_frontend_integration_plan.md` | The old `IntegrationGatewayServer`, live chart demo, and research CSV gateway path have been removed. | Frontend visualization, backtesting, and research export should attach to the same feature/query boundary used for historical replay, not directly to live tickerplant streams. |
| `04_basic_instrumentation_plan.md` | Partially implemented: `BackendMetrics`, metrics catalog, recorder/streamtap metrics endpoints, feature module metrics, Prometheus, Grafana, and profiling CLI. | Add explicit data-quality events, trace sampling, and broader alert rules around the future feature and semantic layers. |
| `05_semantic_feature_plant_ontology_pricing_plan.md` | Not implemented in source packages today. | Add a downstream semantic/pricing service that consumes canonical streams, feature streams, market metadata, replay, and quality/staleness indicators. |

## Diagram 0: Legacy Baseline Codebase

This diagram treats `1181b6010da1d53d6cff073c07ff351cb57d313e` as the
baseline, before the newer work on the legacy code. At that point the codebase
already matched the old block diagram fairly closely: Kalshi ingestion fed an
Aeron Cluster ESB, the ESB normalized messages onto an internal Aeron stream,
and the tickerplant routed those formatted messages to stream-specific Aeron
publications.

```mermaid
flowchart LR
  classDef source fill:#fff4d6,stroke:#9a6b00,color:#1f1f1f;
  classDef legacy fill:#eef3ff,stroke:#315b7c,color:#102033;
  classDef bus fill:#e8f7ee,stroke:#2d6a4f,color:#102033;
  classDef storage fill:#f5e8ff,stroke:#6d4c86,color:#102033;
  classDef external fill:#f7f7f7,stroke:#555,color:#102033;
  classDef optional fill:#fff7ed,stroke:#9a6b00,color:#102033,stroke-dasharray: 5 5;

  KALSHI0["Kalshi API<br/>REST + WebSocket"]:::source

  subgraph Collect0["Kalshi Data Collection Service"]
    KS0["KalshiSystem<br/>KXHIGHCHI market discovery<br/>subscribes trade, ticker, orderbook_delta"]:::legacy
    KW0["KalshiWrapper<br/>signed REST wrapper<br/>markets, trades, orderbook, series"]:::legacy
    WS0["KalshiWebSocketClient<br/>custom WebSocket client<br/>type switch + single book sequence check"]:::legacy
    CCO0["ClientClusterOrchestrator<br/>Aeron Cluster ingress client"]:::bus
  end

  KALSHI0 -->|REST market discovery| KW0
  KS0 --> KW0
  KW0 --> WS0
  KALSHI0 -->|raw WS JSON| WS0
  WS0 -->|accepted trade, ticker, book JSON| CCO0

  subgraph ESB0["Enterprise Service Bus / Aeron Cluster"]
    CM0["ClusterMain<br/>MediaDriver + Archive + ConsensusModule<br/>ClusteredServiceContainer"]:::bus
    ECS0["ESBClusteredService<br/>leader-only onSessionMessage"]:::bus
    ORCH0["ESBClusterCommunicationOrchestrator<br/>internal pub/sub + char-keyed external streams"]:::bus
    DP0["DataProcessor<br/>legacy formatter + order book state<br/>emits top-of-book"]:::legacy
    MSG0["messages/*<br/>Trade, Ticker, Snapshot, Delta"]:::legacy
    INTERNAL0["Internal Aeron stream<br/>StreamIDs.INTERNAL_IDX = 3"]:::bus
    TP0["Tickerplant<br/>polls internal stream<br/>routes by JSON type char"]:::legacy
  end

  CCO0 -->|cluster ingress| ECS0
  CM0 --> ECS0
  ECS0 --> ORCH0
  ECS0 --> DP0
  DP0 --> MSG0
  DP0 -->|formatted JSON via orchestrator| INTERNAL0
  INTERNAL0 --> TP0

  subgraph Streams0["External Aeron Streams"]
    TRADE0["T trade stream<br/>ID 0"]:::bus
    TOB0["K top-of-book stream<br/>ID 1"]:::bus
    BOOK0["D/S book events stream<br/>ID 2"]:::bus
    TICKER0["R ticker stream<br/>ID 4"]:::bus
    OI0["O open-interest stream<br/>ID 5"]:::bus
  end

  TP0 --> TRADE0
  TP0 --> TOB0
  TP0 --> BOOK0
  TP0 --> TICKER0
  TP0 --> OI0

  subgraph Consumers0["Legacy Consumers And Storage"]
    GRID0["MarketGridDemo<br/>demo Aeron client<br/>started by ESB service"]:::external
    STORE0["TradeDataStorage<br/>trade-stream Redshift subscriber<br/>thread present but disabled in ESB startup"]:::optional
    LISTENER0["AeronListener + BatchProcessor<br/>separate batch storage listener"]:::optional
    DAO0["MessageDAO<br/>placeholder DAO methods"]:::storage
    REDSHIFT0["Amazon Redshift / RDS-era storage<br/>Trades and master tables"]:::storage
  end

  TRADE0 --> GRID0
  TOB0 --> GRID0
  TRADE0 -.-> STORE0
  STORE0 -.-> REDSHIFT0
  TRADE0 -.-> LISTENER0
  LISTENER0 -.-> REDSHIFT0
  DAO0 -.-> REDSHIFT0

  subgraph Historical0["Historical Backfill"]
    HF0["HistoricalFetcherRunner<br/>HistoricalDataFetcher + ThrottlingManager"]:::legacy
    HDP0["historicalDataFetcher.DataProcessor<br/>REST data to Redshift tables"]:::legacy
  end

  KALSHI0 -->|REST trades, events, series| HF0
  HF0 --> HDP0
  HDP0 --> REDSHIFT0
```

## Diagram 1: Current Codebase

```mermaid
flowchart LR
  classDef source fill:#fff4d6,stroke:#9a6b00,color:#1f1f1f;
  classDef current fill:#e7f1ff,stroke:#315b7c,color:#102033;
  classDef bus fill:#e8f7ee,stroke:#2d6a4f,color:#102033;
  classDef storage fill:#f5e8ff,stroke:#6d4c86,color:#102033;
  classDef external fill:#f7f7f7,stroke:#555,color:#102033;
  classDef optional fill:#fff7ed,stroke:#9a6b00,color:#102033,stroke-dasharray: 5 5;

  KALSHI["Kalshi API<br/>REST + WebSocket"]:::source

  subgraph Ingestion["Live Ingestion And Raw Capture"]
    KS["KalshiSystem<br/>configured or open-market capture<br/>chunked subscriptions + WS sharding"]:::current
    KW["KalshiWrapper<br/>REST market discovery"]:::current
    KWS["KalshiWebSocketClient shards<br/>trade, ticker, orderbook, lifecycle<br/>subscribe/update acknowledgements"]:::current
    RAWREC["RawIngestRecorder<br/>exact inbound WebSocket payloads"]:::current
    RAWSTORE["recordings/raw-ingest<br/>source/date/hour/minute NDJSON"]:::storage
    CCO["ClientClusterOrchestrator<br/>Aeron Cluster ingress"]:::bus
  end

  KALSHI -->|REST markets| KW
  KS --> KW
  KW --> KWS
  KALSHI -->|WebSocket messages| KWS
  KWS -->|recordInbound| RAWREC
  RAWREC --> RAWSTORE
  KWS -->|raw Kalshi JSON| CCO

  subgraph RawReplay["Raw Ingress Replay"]
    RAWREPLAY["RawIngressReplayCli / Service<br/>replays raw payloads to cluster ingress"]:::current
  end

  RAWSTORE --> RAWREPLAY
  RAWREPLAY --> CCO

  subgraph ESB["Aeron Cluster / ESB Runtime"]
    CM["ClusterMain<br/>node0-node2 profiles"]:::bus
    ECS["ESBClusteredService<br/>leader handles ingress"]:::bus
    DP["DataProcessor<br/>normalization hot path"]:::current
    PARSER["KalshiCanonicalParser<br/>RawSourceEvent + canonical events"]:::current
    SEQ["SourceSequenceMonitor<br/>optional source sequence gaps"]:::current
    BOOK["OrderBookStateManager<br/>optional snapshot/delta state<br/>derived top of book"]:::current
    JOURNAL["FileEventJournal<br/>optional producer-side recorder<br/>producer raw/canonical recordings"]:::storage
    PRODUCER["recordings/producer-canonical<br/>producer-side normalized source of truth"]:::storage
    INTERNAL["Internal canonical bus<br/>StreamRegistry ID 20"]:::bus
    TP["Tickerplant<br/>routes by stream_name"]:::current
  end

  CCO --> ECS
  CM --> ECS
  ECS --> DP
  DP --> PARSER
  DP --> SEQ
  DP --> BOOK
  DP --> JOURNAL
  JOURNAL --> PRODUCER
  DP -->|canonical JSON| INTERNAL
  INTERNAL --> TP

  subgraph Streams["External Aeron Streams"]
    EXT["StreamRegistry IDs 10-19<br/>raw, canonical, derived, system"]:::bus
  end

  TP -->|public stream payloads| EXT

  subgraph Tooling["Current Consumers And Tooling"]
    CLIENTS["External Aeron clients"]:::external
    TAP["StreamTapServer<br/>/events /health /metrics"]:::current
    RECORDER["TickerplantStreamRecorder<br/>records normalized streams"]:::current
    CANONREC["recordings/canonical<br/>consumer-side Aeron validation copy"]:::storage
    REPLAY["StorageBackedRecordingReplay<br/>republish recorded canonical streams"]:::current
    S3SYNC["s3-recording-sync<br/>uploads canonical, raw-ingest,<br/>producer-canonical subtrees"]:::optional
    FEATURECLI["FeaturePlantCli / FeaturePlantService<br/>source-agnostic canonical input"]:::current
    FEATUREBUF["FeatureOutputSink / BoundedFeatureOutputBuffer<br/>feature output boundary"]:::storage
    QUERYLAYER["Future dataviz, backtest,<br/>research export query modules"]:::external
    MON["Prometheus + Grafana<br/>dashboard scrape targets"]:::external
  end

  EXT --> CLIENTS
  EXT --> TAP
  EXT --> RECORDER
  RECORDER --> CANONREC
  EXT -.->|live feature source option| FEATURECLI
  PRODUCER -.->|historical feature source option| FEATURECLI
  CANONREC -.->|consumer-observed history option| FEATURECLI
  FEATURECLI --> FEATUREBUF
  FEATUREBUF --> QUERYLAYER
  CANONREC --> REPLAY
  REPLAY -->|republish stored stream events| EXT
  RAWSTORE --> S3SYNC
  CANONREC --> S3SYNC
  PRODUCER --> S3SYNC
  TAP --> MON
  RECORDER --> MON
```

## Diagram 2: Planned Module Placement

```mermaid
flowchart LR
  classDef current fill:#e7f1ff,stroke:#315b7c,color:#102033;
  classDef planned fill:#fff8e6,stroke:#9a6b00,color:#102033,stroke-dasharray: 6 4;
  classDef plannedStorage fill:#f5e8ff,stroke:#6d4c86,color:#102033,stroke-dasharray: 6 4;
  classDef output fill:#e8f7ee,stroke:#2d6a4f,color:#102033,stroke-dasharray: 6 4;
  classDef external fill:#f7f7f7,stroke:#555,color:#102033;

  subgraph Core["Current Core Backend Foundation"]
    KALSHI2["Kalshi REST/WS"]:::external
    CORE["Core backend<br/>KalshiSystem + ESBClusteredService + DataProcessor"]:::current
    CANON["Canonical tickerplant streams<br/>raw.*, canonical.*, derived.top_of_book, system.*"]:::current
    RAWSTORE["Raw/canonical recordings<br/>raw-ingest + producer-canonical + stream-recorder"]:::current
    REPLAY2["Replay services<br/>raw-ingest replay + storage-backed replay"]:::current
    META["Market metadata snapshots<br/>REST markets/events/series"]:::current
  end

  KALSHI2 --> CORE
  CORE --> CANON
  CORE --> RAWSTORE
  RAWSTORE --> REPLAY2
  KALSHI2 --> META

  subgraph Feature["Planned Basic Feature Plant"]
    FPSVC["FeaturePlantService<br/>module runtime, registry, toggles"]:::current
    FSRC["CanonicalEnvelopeSource<br/>Aeron live or recording/history source"]:::current
    STATE["MarketStateStore<br/>latest trade/ticker/OI/BBO/depth"]:::planned
    BASE["Baseline feature modules<br/>feature.bbo, ticker_snapshot,<br/>trade_tape, spread/depth templates"]:::current
    BARS["Bar and bucket modules<br/>1s, 1m, 5m, 1h, volume buckets"]:::planned
    GROUPS["Basic contract grouping<br/>contract_group, members, chain_snapshot"]:::planned
    FSTREAMS["Feature streams<br/>feature.*"]:::output
    FSTORE["Feature storage + backfill<br/>versioned, replay-compatible"]:::plannedStorage
    FAPI["Feature API<br/>/features, /bars, WS features"]:::planned
  end

  CANON -->|live tickerplant source| FSRC
  RAWSTORE -->|historical canonical source| FSRC
  REPLAY2 -->|deterministic replay inputs| FSRC
  FSRC --> FPSVC
  META -->|market metadata| FPSVC
  FPSVC --> STATE
  STATE --> BASE
  STATE --> BARS
  STATE --> GROUPS
  BASE --> FSTREAMS
  BARS --> FSTREAMS
  GROUPS --> FSTREAMS
  FSTREAMS --> FSTORE
  FSTREAMS --> FAPI
  FSTORE --> FAPI

  subgraph Frontend["Frontend / Research Placement"]
    FE2["Feature/query adapter<br/>reads feature buffers or storage"]:::planned
    CHART2["Dataviz dashboards"]:::external
    RESEARCH["Research exports + backtests"]:::external
  end

  FSTREAMS --> FE2
  FSTORE --> FE2
  FE2 --> CHART2
  FSTORE --> RESEARCH

  subgraph Semantic["Planned Semantic, Ontology, Pricing Layer"]
    TERMS["Contract terms + metadata corpus"]:::planned
    SPARSER["Semantic parser<br/>deterministic + LLM-assisted"]:::planned
    SCHEMA["SemanticContract schema"]:::planned
    ONTO["Ontology + fundamental registry"]:::planned
    CHAIN["Semantic chain builder<br/>relationships + chain snapshots"]:::planned
    CONSTRAINTS["Model-free constraint engine"]:::planned
    SYNTH["Synthetic contract engine"]:::planned
    PRICING["Pricing model layer"]:::planned
    ARB["Execution-aware arb scanner"]:::planned
    REVIEW["Human review + correction registry"]:::planned
    SSTREAMS["semantic.* streams<br/>constraints, synthetic prices,<br/>model prices, arb opportunities"]:::output
  end

  META --> TERMS
  TERMS --> SPARSER
  SPARSER --> SCHEMA
  REVIEW --> SPARSER
  SPARSER --> REVIEW
  SCHEMA --> ONTO
  ONTO --> CHAIN
  FSTREAMS -->|BBO, depth, bars, OI, simple groups| CHAIN
  CHAIN --> CONSTRAINTS
  FSTREAMS -->|executable bid/ask/depth| CONSTRAINTS
  CONSTRAINTS --> SYNTH
  SYNTH --> PRICING
  FSTREAMS -->|market state and history| PRICING
  PRICING --> ARB
  CONSTRAINTS --> ARB
  ARB --> SSTREAMS
  SSTREAMS --> FE2
  SSTREAMS --> RESEARCH

  subgraph Instrumentation["Planned Cross-Cutting Instrumentation"]
    HOOKS["Timestamp hooks + metric wrappers"]:::planned
    QUALITY["DataQualityEvent stream<br/>staleness, gaps, bad books, replay drift"]:::planned
    COLLECTOR["Prometheus / OpenTelemetry collector"]:::current
    ALERTS["Grafana dashboards + alerts + runbooks"]:::current
  end

  CORE --> HOOKS
  FPSVC --> HOOKS
  SPARSER --> HOOKS
  ARB --> HOOKS
  CORE --> QUALITY
  FPSVC --> QUALITY
  HOOKS --> COLLECTOR
  QUALITY --> COLLECTOR
  COLLECTOR --> ALERTS
```

The important placement decision is that the feature plant consumes canonical
envelopes through an abstract source. That source can be a live Aeron
tickerplant stream or a canonical historical source, so feature modules do not
care whether they are being run live, replayed, or backfilled. Frontend
visualization, backtesting, and research export should consume the same feature
output/query boundary instead of subscribing directly to live tickerplant
streams. The semantic/pricing layer sits even farther downstream and consumes
feature streams, market metadata, replay, and quality signals.

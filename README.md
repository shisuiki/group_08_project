# Final Report

## Members

- [Anushree Atmakuri](https://www.linkedin.com/in/anushree-atmakuri-072968243/) (aa127@illinois.edu)
- [Brian Eide](https://www.linkedin.com/in/brian-eide/) (eide2@illinois.edu)
- [Julie Lima](https://www.linkedin.com/in/julielima/) (julie11@illinois.edu)
- [Akul Sharma](https://linkedin.com/in/akulsharma1) (akuls2@illinois.edu)

## Introduction
Real-time market data feeds and stored historical market data are both crucial for ensuring that trading firms can make informed decisions when participating in the market. Real-time data feeds support automated trading strategies, risk monitoring, and demo-grade polling dashboards; production live dashboards remain roadmap work. Historical data is needed for developing trading strategies, creating back-testing libraries, and performing post-trade analysis. This repository provides a Kalshi market-data pipeline that publishes normalized streams to Aeron subscribers and exposes a DB-backed demo frontend adapter.

### What is Kalshi?
Kalshi is a U.S.-based exchange that allows users to trade financial contracts on the outcome of future events. Users are able to place trades on almost anything – from elections, to the weather in specific cities, to future Oscars winners. These contracts function like stock tickers, where the price reflects market sentiment; thus, Kalshi is often referred to as a “prediction market”. For example, a contract predicting that a particular politician will win an election might trade at $0.80 if they are expected to win by a large margin, while the opposing outcome trades at $0.20. Once the relevant data is released, the contract will settle either “Yes” or “No”. As a result of this type of binary trading, the prices of the two sides always sum to $1.

Kalshi aims to democratize access to event-driven markets, giving participants from all backgrounds the ability to gain insights into, speculate on, or hedge against a wide range of future events. The exchange provides continuously updated pricing data on the contracts traded on its platform. This data includes contract prices, volumes, open interest, and settlement information. Traders, analysts, and researchers rely on this data to assess market sentiment, evaluate probabilities, and track developments in real time.

## Overview
Our infrastructure is centered around an Enterprise Service Bus (ESB), acting as the “brain” of the data collection system. At a high level, the ESB facilitates communication and integration between the different entities in our system. As a whole, our system receives messages containing Kalshi market data, processes them, and then publishes the processed data to configured Aeron streams.

To get market data, we connect to the Kalshi API with a custom WebSocket client. We subscribe to an array of markets for three types of updates: orderbook deltas, trades, and “ticker”.

As stated before, our service running the WebSocket client sends data messages through the ESB orchestrator to the data processor, which processes them. Processing updates our internal copies of the order books, which lets the processor know if our custom “top of book” message type should be sent. Once the processor decides what should be published, the messages are sent to the tickerplant. The tickerplant then publishes each message to the appropriate configured external Aeron stream, where Aeron subscribers such as streamtap, recorder, FeaturePlant, and the frontend adapter can read.

Durable live storage is DB-primary: raw websocket input and canonical events are
written to Postgres/Timescale through the async DB writer path. FeaturePlant,
the frontend adapter, and research export default to the DB canonical reader.
Canonical NDJSON remains an explicit legacy/capture/export path for recording,
demo, import, and debug workflows.

## Implementation Status

| Capability | Status | Current boundary |
| --- | --- | --- |
| Live websocket ingestion | current | `KalshiSystem` subscribes to configured or discovered markets; reconnect/subscription restore hardening remains planned. |
| Aeron cluster / tickerplant streams | current | Cluster ingress, processor, tickerplant routing, and external Aeron streams exist; cluster recovery restores watermarks and paused book checkpoints, not full book depth. |
| DB raw/canonical writer | current | Async best-effort raw/canonical offers write to Timescale/Postgres; drops/disabled state are observable through `processor_db_offers_total` and `db_*` metrics. |
| Historical REST backfill | current-basic | REST raw/canonical/metadata DB targets exist; retry taxonomy and broad operational hardening remain limited. |
| FeaturePlant modules | current-basic | BBO, ticker snapshot, and trade tape modules run from DB, recording, or live Aeron sources; richer stateful/versioned feature streams remain planned. |
| `feature_outputs` persistent demo path | current-demo | Schema/store, explicit FeaturePlant DB output, deterministic seed, smoke script, and frontend startup snapshot mode exist; async/batched production feature persistence remains planned. |
| Frontend adapter/static chart | current-demo | HTTP polling adapter, startup-bounded market metadata search/catalog support, and TradingView static chart exist; no production realtime WS/SSE frontend or replay controls. |
| Raw replay | current-basic | Timescale source is default; local NDJSON is explicit import/debug fallback; replay publishes byte[] ingress envelopes. |
| Recording-capture / S3 archive | legacy/debug | Recorder and S3 sync are explicit capture/archive/import/export paths, not the live source of truth. |
| Observability | current-basic | Backend/FeaturePlant/streamtap metrics, pure Prometheus/Grafana profile, recorder metrics only with explicit `recording-capture`, CI compose gates, exact-SHA EC2 deploy, candidate prebuild, last-success rollback state, and deploy smoke checks exist; dependency scanning, alert rules, and tracing remain planned. |
| Pricing model | planned | Not present as a shipped runtime feature. |
| Arb scanner | planned | Not present as a shipped runtime feature. |
| Semantic matching / ontology | planned | Not present as a shipped runtime feature. |
| Durable query API | planned | Basic HTTP inspection endpoints exist; production durable query/export API is not present. |
| Alerts | planned-basic | Runbook/metrics groundwork exists; full alert rule set is incomplete. |
| Redshift writer / old warehouse | removed/not present | Legacy diagram artifact only; current storage is DB-primary plus explicit capture/archive paths. |

## Terminology

Contract - an agreement between two counterparties to transfer money based on the outcome. On Kalshi, $1 is awarded to the counterparty who had the correct position on the outcome of the event contract.

Tickerplant - a common software (or even hardware) system in HFT that handles the dissemination of massive streams of financial data in a timely manner

Orderbook deltas - a message type that describes an update to the state of an order book. On Kalshi, the message provides the market ticker, the price level that was modified, how much liquidity was added/removed, and the side (yes/no) that was modified.

Trades - a message type which states that a trade of some contract occurred. Contains metadata like the timestamp, the price at which the trade occurred, which side the user took, etc.

Ticker - a message type that gives a summary of the market’s latest state. It includes the last traded price, the best bid and offer, open interest, and volume data.

## Tools and Technologies

We used various tools and technologies for our project. We wanted to emphasize the low-latency requirement, so many of our technologies are HFT-related software.

For our ESB, we are using Aeron Cluster, a distributed system utilizing the Raft consensus algorithm for leader election, fault tolerance, and replication. Aeron clusters also have channels through which services can communicate in a publish/subscribe (pubsub) fashion. We specifically chose Aeron for its low-latency properties and keep the live path latency-first; a bounded `<100 microsecond` production end-to-end claim is not established by the current profiling and CI evidence.

We use Aeron for low-latency publication and Postgres/Timescale for live raw
ingest audit, canonical storage, replay, and FeaturePlant input. S3-backed
NDJSON remains a cold archive/export path for capture profiles, not the live
source of truth.

Because the Kalshi API uses the WebSocket protocol for streaming live data, we use a WebSocket connection to receive all live market updates. Due to the way that the API requires authentication through headers, it was hard to find a Java WebSocket library that could support this specific need. It ended up being easier to write our own lightweight WebSocket client, so we used our custom-made client to interface with the API.

## Components

### Raw And Canonical Recording

The storage path is split by purpose. Live ingestion writes raw websocket
payloads and canonical events to the DB first. `RawIngestRecorder` and
`TickerplantStreamRecorder` remain explicit NDJSON capture tools for recorder
soaks, archive/import, downstream-consumer validation, demos, and debug export.
`HistoricalBackfillCli` stores parsed REST canonical backfills in DB by default
and can explicitly preserve raw REST responses under `raw-rest`.

### Real-Time Market Data Listener

The real-time market data listener connects to the Kalshi API via custom WebSocket clients ([KalshiSystem.java](https://gitlab.engr.illinois.edu/ie421_high_frequency_trading_fall_2024/ie421_hft_fall_2024_group_08/group_08_project/-/blob/main/src/main/java/edu/illinois/group8/KalshiSystem.java)). It collects information from Kalshi’s `trade`, `ticker`, and `orderbook_delta` channels, sending this data to the Enterprise Service Bus through per-shard cluster ingress clients ([cluster/ClientClusterOrchestrator.java](https://gitlab.engr.illinois.edu/ie421_high_frequency_trading_fall_2024/ie421_hft_fall_2024_group_08/group_08_project/-/blob/main/src/main/java/edu/illinois/group8/cluster/ClientClusterOrchestrator.java)).

### Enterprise Service Bus

As stated previously, our overall architecture is centered around an Enterprise Service Bus (ESB) acting as the brain of our system. Below is a high-level diagram of this architecture.

![diagram.png](diagram.png)

Our system has to handle extremely high loads (potentially thousands of updates being recorded and processed per second), so we built it on top of an Aeron Cluster. The ESB receives messages from the real time market data listener and handles the message passing between the different entities in our system.

The ESB architecture and business logic was purposely designed to be abstracted away from internal services that publish to or subscribe from Aeron streams. For example, the ESBClusterCommunicationOrchestrator class ([cluster/ESBClusterCommunicationOrchestrator.java](https://gitlab.engr.illinois.edu/ie421_high_frequency_trading_fall_2024/ie421_hft_fall_2024_group_08/group_08_project/-/blob/main/src/main/java/edu/illinois/group8/cluster/ESBClusterCommunicationOrchestrator.java)) allows services like the Tickerplant to send or receive data through Aeron Channels, without having to manage the details of setting up the pubsub architecture or dealing with cluster node leaders.

### Data Processor

The data processing itself is done in [esb/DataProcessor.java](https://gitlab.engr.illinois.edu/ie421_high_frequency_trading_fall_2024/ie421_hft_fall_2024_group_08/group_08_project/-/blob/main/src/main/java/edu/illinois/group8/esb/DataProcessor.java), which receives the raw Kalshi API data from the real-time market data listener (through the ESB orchestrator) then classifies the message and cleans/formats it based on its type.

Furthermore, the data processor maintains an up-to-date order book for each subscribed symbol. Upon detecting an updated BBO, the data processor creates an additional top-of-book message for the respective symbol.

Each formatted message is then published to our system’s internal Aeron channel, once again handled by the ESB.

### Tickerplant

The tickerplant ([esb/Tickerplant.java](https://gitlab.engr.illinois.edu/ie421_high_frequency_trading_fall_2024/ie421_hft_fall_2024_group_08/group_08_project/-/blob/main/src/main/java/edu/illinois/group8/esb/Tickerplant.java)) is responsible for receiving all of the formatted messages from the internal Aeron channel and publishing them to configured external Aeron streams. It publishes on one configured external Aeron channel using stream IDs 10-19, routing by each message's `stream_name`. This design keeps stream selection explicit while still allowing many Aeron subscribers to receive the same data at the same time.

### Real-Time Data Storage

New live captures store raw websocket input and canonical events primarily in
Postgres/Timescale when a DB URL is configured. Docker Compose includes a
`local-db` Timescale profile and `db-migrate` runner for local smoke tests. Raw
replay and FeaturePlant default to DB sources. NDJSON/S3 recording is retained
for `recording-capture`, legacy archive/import, fixtures, demo data, and debug
exports. The downstream stream recorder remains useful for validating what Aeron
clients receive.

## Demo Video

https://drive.google.com/file/d/1o5qYAFJFuklDwqu1LvT3_zN3f_tN2OL_/view?usp=sharing

## How to Run
1. Copy `.env.example` to `.env`.
2. For live ingestion, set `KALSHI_KEY_ID`, `KALSHI_KEY_HOST_PATH`, and either `KALSHI_MARKET_TICKERS`, `KALSHI_MARKET_SERIES_TICKER`, or `KALSHI_MARKET_SELECTION_MODE=open_markets`.
3. Use `docker compose --profile cluster-live up --build` for the three-node live stack with `wsclient` and `streamtap`; use `docker compose --profile single-node-local up --build` only for a local node0 smoke check.
4. For local DB smoke tests, run `docker compose --profile local-db up -d timescaledb` and `docker compose --profile local-db run --rm db-migrate`; live/EC2 deployments should still use their configured external DB URL.
5. Raw replay defaults to DB/Timescale; use `RAW_REPLAY_SOURCE=local-ndjson` only for explicit fixture/import/debug mode.
6. Pure observability is available with `docker compose --profile observability up --build`; it starts Prometheus/Grafana and streamtap only, without `stream-recorder`, S3 sync, or `recordings/` writes. Use `recording-capture` explicitly for NDJSON capture/archive runs.
7. Featureplant templates default to canonical DB rows with `FEATUREPLANT_DB_URL` or `DB_WRITER_DATABASE_URL`; set `FEATUREPLANT_SOURCE=recording` for explicit legacy/demo recording runs.
8. Frontend adapter defaults to canonical DB rows with `FRONTEND_ADAPTER_DB_URL` or `DB_WRITER_DATABASE_URL` and can auto-load a bounded market metadata catalog; set `FRONTEND_ADAPTER_SOURCE=recording` only for explicit legacy/demo/debug recording runs.
9. Research export defaults to canonical DB rows with `RESEARCH_EXPORT_DB_URL` or `DB_WRITER_DATABASE_URL`; set `--source=recording` only for explicit legacy/export/debug recording runs.
10. For the reproducible DB-primary demo, run `scripts/db-primary-demo-seed.sh`, follow `docs/demo_db_primary_walkthrough.md`, use `docs/video_demo_checklist.md` for presentation boundaries, and run `scripts/db-primary-demo-smoke.sh` against the frontend adapter.

Docker Compose host-published admin, data, metrics, DB, Aeron, and cluster
ports default to loopback through `COMPOSE_HOST_BIND_IP=127.0.0.1`. Override
that only behind a firewall, authenticated reverse proxy, or isolated network.

Backend stream contracts, schema mappings, replay behavior, featureplant behavior, and operations notes are documented under `docs/`.

## Future Work

Currently, our local profiles use configured Aeron endpoints that are constrained by Docker networking. Multicast with Docker Bridges on MacOS does not work reliably; deploying on Linux/K8s remains the intended network path for multicast-style external delivery. We purposely architected our codebase with layers of abstraction that allow us to switch endpoints without rewriting stream routing.

TimescaleDB is the primary live raw ingest, canonical, replay, and FeaturePlant
source. S3 remains useful for cold archive/export and retained legacy imports
without adding more hot-path consumers.

Because our WebSocket client is custom-made to be lightweight, reliability work remains. Ping/pong replies exist, but production-grade reconnect, subscription restore, and connection health metrics are still future work.

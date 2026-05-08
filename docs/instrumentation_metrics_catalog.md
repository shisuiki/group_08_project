# Instrumentation Metrics Catalog

Metrics are exported in Prometheus text format. Labels should be bounded and stable.

## Label Policy

Use these labels where they apply:

- `service`: emitting service, for example `stream_recorder`.
- `stream`: normalized stream name.
- `event_type`: canonical event type.
- `schema_version`: canonical schema version.
- `source`: source system, for example `kalshi`.
- `replay_id`: replay session id, only on replay-specific metrics.

Avoid labels for `trade_id`, raw error messages, raw payload values, and unconstrained market tickers. Use sampled logs or recent-event buffers for payload-level investigation.

## Core Metrics

- `backend_ws_connected`: WebSocket connection state gauge.
- `backend_ws_reconnect_total`: reconnect counter.
- `backend_ws_messages_total`: source message counter.
- `backend_ws_bytes_total`: source byte counter.
- `backend_ws_message_age_ms`: source event age distribution.
- `backend_parser_messages_total`: parsed message counter.
- `backend_parser_errors_total`: parser error counter.
- `backend_parser_latency_ns`: parser latency distribution.
- `backend_unknown_message_type_total`: unsupported source message counter.
- `backend_orderbook_snapshot_total`: order book snapshot counter.
- `backend_orderbook_delta_total`: order book delta counter.
- `backend_orderbook_sequence_gap_total`: sequence gap counter.
- `backend_orderbook_delta_before_snapshot_total`: bad order book lifecycle counter.
- `backend_orderbook_crossed_total`: crossed book counter.
- `backend_orderbook_negative_level_total`: invalid level counter.
- `backend_publication_offer_total`: publication attempt/success counter.
- `backend_publication_offer_failed_total`: publication failure counter.
- `backend_publication_backpressure_ns`: publication backpressure distribution.
- `backend_publication_latency_ns`: publication latency distribution.
- `backend_storage_enqueue_total`: durable storage enqueue counter.
- `backend_storage_commit_total`: durable storage commit counter.
- `backend_storage_error_total`: durable storage error counter.
- `backend_storage_queue_depth`: durable storage queue depth gauge.
- `backend_storage_commit_latency_ns`: durable storage commit latency distribution.
- `backend_replay_sessions_active`: active replay session gauge.
- `backend_replay_events_published_total`: replay event counter.
- `backend_replay_lag_ms`: replay lag distribution.
- `backend_replay_speed_multiplier`: replay speed gauge.

## Feature Metrics

- `feature_module_enabled`: module enabled gauge.
- `feature_module_events_in_total`: module input counter.
- `feature_module_events_out_total`: module output counter.
- `feature_module_errors_total`: module error counter.
- `feature_module_latency_ns`: module latency distribution.
- `feature_module_state_size`: module state size gauge.
- `feature_module_lag_ms`: module lag distribution.
- `feature_market_state_stale_total`: stale market counter.
- `feature_bar_finalized_total`: finalized bar counter.
- `feature_bar_late_event_total`: late event counter.
- `feature_contract_group_created_total`: contract group counter.

## Current Implementation Notes

`stream-recorder` emits the storage, parser visibility, order book quality, and feature input metrics for normalized tickerplant streams. `frontend-adapter` emits frontend projection metrics under `frontend_adapter_*`. `streamtap` remains a lightweight inspection tool for recent normalized stream events.

For profiling before optimization, use `edu.illinois.group8.profile.HotPathProfileCli`. It exercises the parser, order-book state, processor, serialization, and file journal with synthetic Kalshi-shaped messages and can print the same Prometheus metrics emitted by the hot path.

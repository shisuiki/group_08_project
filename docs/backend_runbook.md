# Backend Runbook

## Local Replay

Local replay does not require Kalshi credentials.

```bash
BACKEND_PROFILE=replay \
BACKEND_JOURNAL_ROOT=/tmp/kalshi-backend/journal \
java -cp target/kalshi-project-1.0-SNAPSHOT.jar edu.illinois.group8.replay.ReplayCli
```

## Live Startup

Required live settings:

- `KALSHI_KEY_ID`
- `KALSHI_KEY_PATH`
- `KALSHI_MARKET_TICKERS` or `KALSHI_MARKET_SERIES_TICKER`
- `CLUSTER_ADDRESSES`
- `IP` for the websocket client

Docker live mode:

```bash
cp .env.example .env
# fill KALSHI_KEY_ID, KALSHI_KEY_HOST_PATH, and market selection
docker compose --profile cluster-live up --build
```

## Failure Modes

### WebSocket Auth Failure

Symptoms: websocket closes immediately or Kalshi sends an `error` message.

Actions:

- Verify `KALSHI_KEY_ID` and `KALSHI_KEY_PATH`.
- Confirm the private key file is mounted read-only at `KALSHI_KEY_PATH`.
- Check clock skew because signatures use millisecond timestamps.

### Sequence Gap

Symptoms: `system.sequence_gaps` events with `reason=out_of_order_delta` or `delta_before_snapshot`.

Actions:

- Pause consumers for the affected `market_ticker`.
- Request a fresh orderbook snapshot through `update_subscription` with `get_snapshot` when wired to the live client.
- Resume the market after a new `orderbook_snapshot` is accepted.

### Parser Failure Spike

Symptoms: `system.parser_errors` rate increases.

Actions:

- Inspect the raw journal event referenced by `metadata.raw_event_id`.
- Compare the raw payload against `docs/backend_schema_gap_report.md`.
- Add or update a parser fixture before changing parser behavior.

### Storage Outage

Symptoms: warehouse client errors or batch flush failures.

Actions:

- Keep live ingestion running; raw/canonical local journals are the source of truth.
- Restore `BACKEND_DATABASE_URL` connectivity.
- Backfill missed warehouse partitions from the raw journal.

### Cluster Leader Change

Symptoms: cluster logs report role changes.

Actions:

- Data processing only handles session messages on the leader.
- Confirm follower nodes are healthy and Aeron directories are writable.
- Consumers should reconnect to public stream subscriptions if their local subscription stalls.

## Graceful Shutdown

Use container stop or process interruption. The raw/canonical journal writes one event per line and closes file handles per append, so completed lines are durable without a separate flush command.

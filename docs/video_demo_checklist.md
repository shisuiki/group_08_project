# Video Demo Checklist

Use `docs/demo_db_primary_walkthrough.md` as the command runbook. This file is
the short presentation guardrail for the DB-primary demo.

## Show

1. Current/roadmap boundary from the README status table.
2. Local DB-primary seed with `scripts/db-primary-demo-seed.sh`.
3. Frontend adapter started with `FRONTEND_ADAPTER_FEATURE_SOURCE=feature_outputs`.
4. `scripts/db-primary-demo-smoke.sh` output, including
   `feature_source=feature_outputs`, `/health.market_metadata.status=loaded`,
   nonzero `/health.market_metadata.markets`, `/datafeed/search`, and
   `/markets`.
5. Static chart under `frontend/tradingview-lightweight/index.html` with seeded
   `feature_outputs` bars visible.
6. `/health` and `/metrics` from the frontend adapter or wsclient metrics
   service, depending on the profile being shown.

## Do Not Present As Complete

- Pricing model.
- Arbitrage scanner.
- Semantic matching or ontology.
- Production durable query API.
- WebSocket/SSE realtime frontend.
- Full alerting.
- Automated live order-book recovery or full order-book depth restore.

## Failure Switches

- Rerun `scripts/db-primary-demo-seed.sh` if the chart window has aged out.
- Restart `frontend-adapter`; `feature_outputs` and market metadata both load
  bounded startup snapshots and do not refresh automatically.
- Use alternate host ports if `8090`, `8091`, or `5432` are already bound.
- Check `/health` for `feature_source=feature_outputs`,
  `market_metadata.status=loaded`, and nonzero `market_metadata.markets`
  before recording.
- Run `scripts/db-primary-demo-smoke.sh` again after any restart.

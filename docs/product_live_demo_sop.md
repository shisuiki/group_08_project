# Product Live Demo SOP

Updated: 2026-05-22

1. Open `https://kalshi.ananthe.party/` and sign in with the runtime basic-auth credential.
2. Local operator checks run on EC2: frontend `127.0.0.1:11001`, DB `127.0.0.1:11000`, wsclient metrics `127.0.0.1:11003`.
3. Product purpose: ingest Kalshi live markets, write DB-primary state, compute features, serve dashboards, and classify markets with LLM metadata.
4. Data counts must be explained separately: indexed assets, chartable assets, live subscribed assets, and semantic-classified assets are not the same number.
5. Current public stack has roughly `151k` indexed assets, `17k` DB display-eligible assets, `80` live WebSocket subscriptions, and `52` generated semantic rows.
6. Health check:
   `curl -s -u "$AUTH" http://127.0.0.1:11001/health | jq '{ready:.product_readiness.status,age:.data_freshness.latest_event_age_ms,live:.data_freshness.live_data_observed,symbols:.store.symbols,features:.store.total_features,semantic:.semantic_metadata.generated_count}'`
7. Expected health: ready `ok`, live `true`, event age usually seconds, symbols/features non-zero.
8. Live wsclient check:
   `curl -s http://127.0.0.1:11003/metrics | grep -E 'backend_ws_session_established_total|orderbook_recovery_registered_markets|db_writer_queue_depth'`
9. Expected wsclient: session established, registered market count matches configured subscriptions, DB queue does not grow without bound.
10. Demo flow: Dashboard -> Asset Explorer -> chart click -> Semantic Map -> Operator Console -> live/readiness status.
11. Asset Explorer must only call a market chart-ready when `/datafeed/history` returns real bars.
12. Chart smoke:
    first 20 `/api/markets/capabilities` rows should return `/datafeed/history` status `ok` and at least `10` bars.
13. Current blocker: DB capability eligibility can include ticker-snapshot/trade markets that the in-memory chart endpoint cannot render.
14. Milestone CA fixes that by making capability eligibility and chart history share the same DB-backed/renderable path.
15. Catalog sync fills `market_metadata`; keep demo sync bounded and exclude unwanted MVE inventory unless intentionally showing broad index.
16. Live ingress subscribes selected high-liquidity markets; do not claim the full indexed catalog is live-subscribed.
17. Semantic metadata uses OpenRouter/DeepSeek; keys must come from runtime secret/env and never be committed.
18. Semantic Map should prioritize generated, chartable, high-liquidity markets for demo.
19. S3 target role: cold archive for raw/canonical/features/catalog/evidence/replay data outside EC2 disk.
20. DB target role: hot query path, latest state, recent chart history, operator status, and frontend read models.
21. Current S3 status: implementation is in progress; do not claim S3 archive is production-active until BZ is verified.
22. Operator actions require basic auth, bounds, redaction, dry-run where practical, and explicit confirmation for live-affecting writes.
23. Never expose Kalshi keys, OpenRouter keys, EC2 keys, S3 credentials, or DB passwords.
24. Before push/demo-ready: run targeted tests, package gate, migrations, service restart, health, wsclient metrics, chart smoke, semantic count.
25. Avoid heavy browser/Docker checks on the EC2 host when memory pressure is high; use lightweight API checks and webdebug for UI verification.
26. Remaining priority: CA chart contract, BZ S3 archive/replay, broader semantic classification, UI simplification, downstream client/distribution view.

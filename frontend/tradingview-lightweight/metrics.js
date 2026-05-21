const REFRESH_MS = 3000;
const REQUEST_TIMEOUT_MS = 5500;
const OPTIONAL_REQUEST_TIMEOUT_MS = 1800;
const LATENCY_BAR_MAX_MS = 1000;
const HOT_PATH_BAR_TARGET_NS = 1_000_000;
const HOT_PATH_STAGE_CONFIGS = [
  {
    id: 'wsclient_receive_to_cluster_offer',
    prefix: 'hot-ws',
    label: 'WS receive -> cluster offer',
    empty: 'No wsclient ingress samples'
  },
  {
    id: 'cluster_receive_to_tickerplant_publish',
    prefix: 'hot-cluster',
    label: 'Cluster receive -> tickerplant publish',
    empty: 'No cluster leader samples'
  },
  {
    id: 'ws_to_tickerplant_publish',
    prefix: 'hot-ws-tickerplant',
    label: 'WS receive -> tickerplant publish',
    empty: 'No full hot-path samples'
  },
  {
    id: 'featureplant_consumer_to_bbo_complete',
    prefix: 'hot-feature',
    label: 'FeaturePlant consumer -> BBO complete',
    empty: 'No FeaturePlant consumer samples'
  },
  {
    id: 'featureplant_bbo_module_processing',
    prefix: 'hot-module',
    label: 'FeaturePlant BBO processing',
    empty: 'No BBO module samples'
  }
];

const PROMETHEUS_ROWS = [
  ['frontend_adapter_symbols', 'Frontend symbols'],
  ['frontend_adapter_features_total', 'Frontend feature rows'],
  ['frontend_adapter_store_sequence', 'Frontend store sequence'],
  ['frontend_adapter_quote_update_requests_total', 'Long-poll requests'],
  ['frontend_adapter_quote_update_changed_total', 'Long-poll changed responses'],
  ['frontend_adapter_quote_update_timeouts_total', 'Long-poll timeouts'],
  ['frontend_adapter_quote_stream_requests_total', 'SSE stream requests'],
  ['frontend_adapter_quote_stream_events_total', 'SSE events emitted'],
  ['frontend_adapter_quote_stream_active', 'Active SSE streams']
];

const liveSnapshot = {};

function byId(id) {
  return document.getElementById(id);
}

function setText(id, value) {
  const element = byId(id);
  if (element) {
    element.textContent = value == null || value === '' ? '--' : String(value);
  }
}

function setPill(id, value, status) {
  const element = byId(id);
  if (!element) {
    return;
  }
  element.textContent = value == null || value === '' ? 'unknown' : String(value);
  element.classList.remove('warn', 'bad', 'neutral');
  if (status === 'bad') {
    element.classList.add('bad');
  } else if (status === 'warn') {
    element.classList.add('warn');
  } else if (status === 'neutral') {
    element.classList.add('neutral');
  }
}

function nested(source, path, fallback = null) {
  let value = source;
  for (const part of path) {
    if (value == null || typeof value !== 'object' || !(part in value)) {
      return fallback;
    }
    value = value[part];
  }
  return value == null ? fallback : value;
}

function asNumber(value) {
  if (value == null || value === '') {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatNumber(value, digits = 0) {
  const number = asNumber(value);
  if (number == null) {
    return '--';
  }
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits: digits,
    minimumFractionDigits: digits
  }).format(number);
}

function formatRate(count, seconds) {
  const value = asNumber(count);
  const windowSeconds = Math.max(1, asNumber(seconds) || 1);
  if (value == null) {
    return '--/s';
  }
  const rate = value / windowSeconds;
  return `${formatNumber(rate, rate >= 10 ? 0 : 1)}/s`;
}

function formatMs(value) {
  const number = asNumber(value);
  if (number == null) {
    return '--';
  }
  if (number >= 1000) {
    return `${formatNumber(number / 1000, 2)}s`;
  }
  return `${formatNumber(number, 0)}ms`;
}

function formatNs(value) {
  const number = asNumber(value);
  if (number == null) {
    return '--';
  }
  if (number >= 1_000_000) {
    return `${formatNumber(number / 1_000_000, 2)}ms`;
  }
  if (number >= 1_000) {
    return `${formatNumber(number / 1_000, 1)}us`;
  }
  return `${formatNumber(number, 0)}ns`;
}

function formatAge(value) {
  const number = asNumber(value);
  if (number == null) {
    return '--';
  }
  if (number < 1000) {
    return `${formatNumber(number, 0)}ms`;
  }
  if (number < 60000) {
    return `${formatNumber(number / 1000, 1)}s`;
  }
  return `${formatNumber(number / 60000, 1)}m`;
}

function statusClass(status) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'ok' || normalized === 'ready' || normalized === 'healthy' || normalized === 'completed') {
    return 'ok';
  }
  if (normalized === 'stale' || normalized === 'degraded' || normalized === 'running') {
    return 'warn';
  }
  if (normalized === 'disabled' || normalized === 'unknown') {
    return 'neutral';
  }
  return normalized ? 'bad' : 'neutral';
}

async function fetchWithTimeout(path, accept, timeoutMs = REQUEST_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(path, {
      cache: 'no-store',
      headers: { Accept: accept },
      signal: controller.signal
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${text.slice(0, 120)}`);
    }
    return text;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchJson(path, timeoutMs = REQUEST_TIMEOUT_MS) {
  return JSON.parse(await fetchWithTimeout(path, 'application/json', timeoutMs));
}

async function fetchPrometheus() {
  return fetchWithTimeout('/metrics?format=prometheus', 'text/plain');
}

function parsePrometheus(text) {
  const rows = new Map();
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const match = trimmed.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$/);
    if (!match) {
      continue;
    }
    const name = match[1];
    const value = Number(match[3]);
    if (!rows.has(name) || !match[2]) {
      rows.set(name, value);
    }
  }
  return rows;
}

function renderPipeline(pipeline) {
  const body = pipeline && pipeline.pipeline ? pipeline.pipeline : pipeline || {};
  const status = body.status || (pipeline && pipeline.status) || 'unknown';
  const windowSeconds = nested(body, ['recent_window_seconds'], 1);
  setText('data-age', formatAge(body.latest_state_age_ms));
  setText('data-source', body.latest_market_state_commit_seq
    ? `read-model commit ${body.latest_market_state_commit_seq}`
    : 'read model');
  setText('canonical-rate', formatRate(body.recent_canonical_events, windowSeconds));
  setText('canonical-total', `${formatNumber(body.recent_canonical_events)} events in ${formatNumber(windowSeconds)}s`);
  setText('feature-rate', formatRate(body.recent_feature_outputs, windowSeconds));
  setText('feature-total', `${formatNumber(body.recent_feature_outputs)} outputs in ${formatNumber(windowSeconds)}s`);
  setText('state-rate', formatRate(body.recent_latest_market_states, windowSeconds));
  setText('state-total', `${formatNumber(body.recent_latest_market_states)} latest states in ${formatNumber(windowSeconds)}s`);
  setText('cursor-lag', formatNumber(body.cursor_lag_events));
  setText('latest-state-age', `pipeline ${status}, latest state ${formatAge(body.latest_state_age_ms)}`);
  if (!liveSnapshot.latency) {
    setPill('latency-status', status === 'ok' ? 'read-model proxy' : status, statusClass(status));
    setLatencyBar('canonical-state', body.latest_state_age_ms);
  }
}

function renderHealth(health, operator) {
  const readiness = displayProductStatus(health, operator);
  const releaseSha = nested(operator, ['release', 'sha'], nested(health, ['release', 'sha'], 'unknown'));
  const runtimeProfile = nested(operator, ['release', 'profile'], nested(health, ['release', 'profile'], nested(health, ['source_mode'], 'unknown')));
  setText('product-status', readiness);
  setText('release-sha', String(releaseSha || 'unknown').slice(0, 12));
  setText('runtime-profile', runtimeProfile);
  const dataAge = nested(operator, ['data_freshness', 'age_ms'], nested(health, ['data_freshness', 'age_ms'], null));
  setText('data-age', formatAge(dataAge));
  setText('data-source', nested(operator, ['data_freshness', 'source_event_id'], nested(health, ['data_freshness', 'source_event_id'], 'latest rendered event')));
  const streamEvents = nested(health, ['quote_streams', 'events'], nested(operator, ['quote_streams', 'events'], null));
  const activeStreams = nested(health, ['quote_streams', 'active_streams'], nested(operator, ['quote_streams', 'active_streams'], null));
  const updateRequests = nested(health, ['quote_updates', 'requests'], nested(operator, ['quote_updates', 'requests'], null));
  setText('stream-events', formatNumber(streamEvents));
  setText('stream-active', `${formatNumber(activeStreams)} SSE active, ${formatNumber(updateRequests)} long-poll requests`);
  const semanticGenerated = nested(operator, ['semantic_metadata', 'generated'], nested(health, ['semantic_metadata', 'generated'], null));
  const semanticEligible = nested(operator, ['semantic_metadata', 'eligible_generated'], nested(health, ['semantic_metadata', 'eligible_generated'], null));
  const semanticRun = nested(operator, ['semantic_metadata_run', 'latest_run', 'state'], nested(operator, ['semantic_metadata', 'status'], 'semantic run'));
  setText('semantic-generated', semanticGenerated == null ? '--' : `${formatNumber(semanticGenerated)} / ${formatNumber(semanticEligible)}`);
  setText('semantic-run', semanticRun);
}

function displayProductStatus(health, operator) {
  const readiness = nested(operator, ['product_readiness'], nested(health, ['product_readiness'], null));
  const status = nested(readiness, ['status'], 'unknown');
  const reasons = Array.isArray(readiness && readiness.reasons) ? readiness.reasons : [];
  const latestEventAgeMs = asNumber(nested(operator, ['data_freshness', 'age_ms'], nested(health, ['data_freshness', 'latest_event_age_ms'], null)));
  const liveObserved = Boolean(nested(operator, ['data_freshness', 'live_data_observed'], nested(health, ['data_freshness', 'live_data_observed'], false)));
  const refreshRunning = Boolean(nested(health, ['feature_output_refresh', 'running'], false));
  const quietLiveOnly = reasons.length === 1 && reasons[0] === 'stale_feature_output';
  if (status === 'stale' && quietLiveOnly && liveObserved && refreshRunning && latestEventAgeMs != null && latestEventAgeMs < 60000) {
    return 'ok';
  }
  return status;
}

function renderLatency(latency) {
  const status = latency ? latency.status : 'unavailable';
  setPill('latency-status', status, statusClass(status));
  setLatencyBar('canonical-feature', latency && latency.canonical_to_feature_ms);
  setLatencyBar('feature-state', latency && latency.feature_to_latest_state_ms);
  setLatencyBar('canonical-state', latency && latency.canonical_to_latest_state_ms);
}

function renderLatencyUnavailable(reason) {
  const body = liveSnapshot.pipeline && liveSnapshot.pipeline.pipeline ? liveSnapshot.pipeline.pipeline : {};
  setPill('latency-status', reason || 'timeout', 'warn');
  setLatencyBar('canonical-feature', null);
  setLatencyBar('feature-state', null);
  setLatencyBar('canonical-state', body.latest_state_age_ms);
}

function renderHotPathLatency(hotPath) {
  const status = hotPath ? hotPath.status : 'unavailable';
  setPill('hot-path-status', status, statusClass(status));
  const stageViews = HOT_PATH_STAGE_CONFIGS.map((config) => {
    const stage = hotPathStage(hotPath, config.id);
    renderHotPathStage(config, stage);
    return { config, stage, series: bestSeries(stage) };
  });
  const headline = preferredHotPathHeadline(stageViews);
  const best = headline && headline.series;
  const bestTail = hotPathTailNs(best);
  setText('hot-path-latency', bestTail != null ? formatNs(bestTail) : status);
  setText(
    'hot-path-source',
    best
      ? `${headline.config.label} p99.9 / ${hotPathSeriesDetail(best)}`
      : (hotPath && hotPath.note) || 'missing hot-path samples'
  );
}

function renderHotPathUnavailable(reason) {
  setPill('hot-path-status', reason || 'timeout', 'warn');
  setText('hot-path-latency', '--');
  setText('hot-path-source', 'hot-path metrics unavailable');
  for (const config of HOT_PATH_STAGE_CONFIGS) {
    renderHotPathStage(config, null);
  }
}

function hotPathStage(hotPath, id) {
  const stages = hotPath && Array.isArray(hotPath.stages) ? hotPath.stages : [];
  return stages.find((stage) => stage.id === id) || null;
}

function bestSeries(stage) {
  const series = stage && Array.isArray(stage.series) ? stage.series : [];
  if (!series.length) {
    return null;
  }
  return [...series].sort((left, right) => {
    const preferred = hotPathEventRank(right) - hotPathEventRank(left);
    if (preferred !== 0) {
      return preferred;
    }
    return (asNumber(right.recent_count) || 0) - (asNumber(left.recent_count) || 0);
  })[0];
}

function preferredHotPathHeadline(stageViews) {
  const byId = new Map(stageViews.map((view) => [view.config.id, view]));
  for (const id of [
    'ws_to_tickerplant_publish',
    'cluster_receive_to_tickerplant_publish',
    'wsclient_receive_to_cluster_offer',
    'featureplant_consumer_to_bbo_complete',
    'featureplant_bbo_module_processing'
  ]) {
    const view = byId.get(id);
    if (view && view.series) {
      return view;
    }
  }
  return null;
}

function hotPathEventRank(series) {
  const labels = series && series.labels ? series.labels : {};
  const eventType = labels.event_type || labels.message_type || '';
  if (eventType === 'orderbook_delta') {
    return 2;
  }
  if (eventType === 'market_trade' || eventType === 'ticker') {
    return 1;
  }
  return 0;
}

function hotPathTailNs(series) {
  if (!series) {
    return null;
  }
  return asNumber(series.p999_ns) ?? asNumber(series.p99_ns) ?? asNumber(series.p95_ns) ?? asNumber(series.p50_ns);
}

function setLatencyBar(prefix, value) {
  const number = Math.max(0, asNumber(value) || 0);
  const width = Math.min(100, (number / LATENCY_BAR_MAX_MS) * 100);
  const bar = byId(`${prefix}-bar`);
  if (bar) {
    bar.style.width = `${width}%`;
  }
  setText(`${prefix}-ms`, formatMs(value));
}

function setHotPathDistribution(prefix, series) {
  setText(`${prefix}-p50`, formatNs(series && series.p50_ns));
  setText(`${prefix}-p95`, formatNs(series && series.p95_ns));
  setText(`${prefix}-p99`, formatNs(series && series.p99_ns));
  setText(`${prefix}-p999`, formatNs(series && (series.p999_ns ?? series.p99_ns)));
  setText(`${prefix}-count`, series ? formatNumber(series.recent_count ?? series.count) : '--');
  setText(`${prefix}-max`, formatNs(series && series.max_ns));
  setText(`${prefix}-avg`, formatNs(series && series.avg_ns));
  renderHotPathDistribution(prefix, series);
}

function renderHotPathStage(config, stage) {
  const series = bestSeries(stage);
  const status = stage ? stage.status : 'missing';
  setText(`${config.prefix}-label`, config.label);
  setPill(`${config.prefix}-state`, status, statusClass(status));
  setText(
    `${config.prefix}-detail`,
    series ? hotPathSeriesDetail(series) : (stage && stage.note) || config.empty
  );
  setHotPathDistribution(config.prefix, series);
}

function renderHotPathDistribution(prefix, series) {
  const container = byId(`${prefix}-dist`);
  if (!container) {
    return;
  }
  container.innerHTML = '';
  const rows = [
    ['P50', series && series.p50_ns],
    ['P95', series && series.p95_ns],
    ['P99', series && series.p99_ns],
    ['P99.9', series && (series.p999_ns ?? series.p99_ns)]
  ];
  const values = rows.map(([, value]) => asNumber(value)).filter((value) => value != null);
  const denominator = Math.max(1, ...values);
  for (const [label, value] of rows) {
    const number = Math.max(0, asNumber(value) || 0);
    const width = Math.min(100, (number / denominator) * 100);
    const row = document.createElement('div');
    row.className = 'hot-dist-row';
    const name = document.createElement('span');
    name.textContent = label;
    const track = document.createElement('div');
    track.className = 'bar-track';
    const bar = document.createElement('div');
    bar.className = 'bar';
    if (number > HOT_PATH_BAR_TARGET_NS) {
      bar.classList.add('bad');
    }
    bar.style.width = `${width}%`;
    track.appendChild(bar);
    const stat = document.createElement('strong');
    stat.textContent = value == null ? '--' : formatNs(value);
    row.append(name, track, stat);
    container.appendChild(row);
  }
}

function hotPathSeriesDetail(series) {
  const labels = series && series.labels ? series.labels : {};
  const type = labels.event_type || labels.message_type || labels.module || 'sample';
  const stream = labels.stream || labels.result || labels.source || '';
  const count = asNumber(series && (series.recent_count ?? series.count));
  return `${type}${stream ? ` / ${stream}` : ''}${count == null ? '' : ` / recent ${formatNumber(count)}`}`;
}

function renderPrometheus(text) {
  const rows = parsePrometheus(text);
  const table = byId('prometheus-table');
  if (!table) {
    return;
  }
  table.innerHTML = '';
  for (const [name, label] of PROMETHEUS_ROWS) {
    const tr = document.createElement('tr');
    const key = document.createElement('td');
    const value = document.createElement('td');
    key.textContent = label;
    value.textContent = formatNumber(rows.get(name));
    tr.append(key, value);
    table.append(tr);
  }
  setPill('prometheus-status', 'ready', 'ok');
}

function renderRaw(snapshot) {
  setText('last-refresh', new Date().toLocaleTimeString());
  setPill('raw-status', 'ready', 'ok');
  const raw = byId('raw-json');
  if (raw) {
    raw.textContent = JSON.stringify(snapshot, null, 2);
  }
}

function renderError(error) {
  setPill('raw-status', 'partial', 'warn');
  setText('last-refresh', new Date().toLocaleTimeString());
  const raw = byId('raw-json');
  if (raw) {
    raw.textContent = error && error.stack ? error.stack : String(error);
  }
}

async function refresh() {
  setText('last-refresh', new Date().toLocaleTimeString());
  const errors = [];
  const record = (key, value) => {
    liveSnapshot[key] = value;
    renderRaw(liveSnapshot);
  };
  fetchJson('/ops/pipeline')
    .then((body) => {
      record('pipeline', body);
      renderPipeline(body);
    })
    .catch((error) => {
      errors.push(`pipeline: ${error}`);
      record('errors', errors);
      setText('latest-state-age', 'pipeline unavailable');
    });
  fetchPrometheus()
    .then((body) => {
      record('prometheus', 'loaded');
      renderPrometheus(body);
    })
    .catch((error) => {
      errors.push(`prometheus: ${error}`);
      record('errors', errors);
      setPill('prometheus-status', 'unavailable', 'warn');
    });
  fetchJson('/ops/latency', OPTIONAL_REQUEST_TIMEOUT_MS)
    .then((body) => {
      record('latency', body);
      renderLatency(body);
    })
    .catch((error) => {
      errors.push(`latency: ${error}`);
      record('errors', errors);
      renderLatencyUnavailable('timeout');
    });
  fetchJson('/ops/hot-path-latency', OPTIONAL_REQUEST_TIMEOUT_MS)
    .then((body) => {
      record('hot_path_latency', body);
      renderHotPathLatency(body);
    })
    .catch((error) => {
      errors.push(`hot_path_latency: ${error}`);
      record('errors', errors);
      renderHotPathUnavailable('timeout');
    });
  Promise.allSettled([
    fetchJson('/health', OPTIONAL_REQUEST_TIMEOUT_MS),
    fetchJson('/operator/status', OPTIONAL_REQUEST_TIMEOUT_MS)
  ]).then((results) => {
    const health = results[0].status === 'fulfilled' ? results[0].value : null;
    const operator = results[1].status === 'fulfilled' ? results[1].value : null;
    if (health || operator) {
      record('health', health);
      record('operator_status', operator);
      renderHealth(health || {}, operator || {});
    } else {
      errors.push('health: timeout', 'operator_status: timeout');
      record('errors', errors);
    }
  }).catch(renderError);
}

refresh();
setInterval(refresh, REFRESH_MS);

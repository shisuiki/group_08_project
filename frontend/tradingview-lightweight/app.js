(function () {
    'use strict';

    const QUOTES_POLL_MS = 2000;
    const QUOTES_UPDATE_TIMEOUT_MS = 15000;
    const QUOTES_UPDATE_ERROR_LIMIT = 3;
    const QUOTES_STREAM_ERROR_LIMIT = 2;
    const QUOTES_UPDATE_RETRY_MS = 500;
    const HEALTH_POLL_MS = 5000;
    const MARKET_SEARCH_DEBOUNCE_MS = 250;
    const MARKET_CATALOG_LIMIT = 200;
    const FALLBACK_RESOLUTIONS = ['1S', '5S', '30S', '1', '5', '15', '60'];

    const dom = {
        adapterUrl: document.getElementById('adapter-url'),
        symbolSelect: document.getElementById('symbol-select'),
        resolutionSelect: document.getElementById('resolution-select'),
        lookbackSelect: document.getElementById('lookback-select'),
        refreshSymbols: document.getElementById('refresh-symbols'),
        loadHistory: document.getElementById('load-history'),
        chartContainer: document.getElementById('chart-container'),
        statusLine: document.getElementById('status-line'),
        footer: document.querySelector('footer'),
        marketCount: document.getElementById('market-count'),
        marketSearch: document.getElementById('market-search'),
        marketStatusFilter: document.getElementById('market-status-filter'),
        marketSearchApply: document.getElementById('market-search-apply'),
        marketState: document.getElementById('market-state'),
        marketList: document.getElementById('market-list'),
        marketStatus: document.getElementById('market-status'),
        marketEvent: document.getElementById('market-event'),
        marketSeries: document.getElementById('market-series'),
        marketOpen: document.getElementById('market-open'),
        marketClose: document.getElementById('market-close'),
        productReadinessState: document.getElementById('product-readiness-state'),
        productReadinessReasons: document.getElementById('product-readiness-reasons'),
        marketSourceKind: document.getElementById('market-source-kind'),
        marketLiveObserved: document.getElementById('market-live-observed'),
        marketSyntheticState: document.getElementById('market-synthetic-state'),
        researchFeatureSelect: document.getElementById('research-feature-select'),
        featureCount: document.getElementById('feature-count'),
        featureList: document.getElementById('feature-list'),
        adapterHealth: document.getElementById('adapter-health'),
        releaseIdentity: document.getElementById('release-identity'),
        healthDataAge: document.getElementById('health-data-age'),
        healthSequence: document.getElementById('health-sequence'),
        refreshHealth: document.getElementById('refresh-health'),
        quoteUpdateHealth: document.getElementById('quote-update-health'),
        metadataHealth: document.getElementById('metadata-health'),
        featureplantHealth: document.getElementById('featureplant-health'),
        runtimeSourceMode: document.getElementById('runtime-source-mode'),
        runtimeFeatureSource: document.getElementById('runtime-feature-source'),
        runtimeUptime: document.getElementById('runtime-uptime'),
        runtimeRefreshTotal: document.getElementById('runtime-refresh-total'),
        runtimeRefreshErrors: document.getElementById('runtime-refresh-errors'),
        runtimeQuoteStreams: document.getElementById('runtime-quote-streams'),
        runtimeQuoteWaits: document.getElementById('runtime-quote-waits'),
        freshnessAgeMs: document.getElementById('freshness-age-ms'),
        freshnessEventTsMs: document.getElementById('freshness-event-ts-ms'),
        freshnessSymbol: document.getElementById('freshness-symbol'),
        freshnessFeatureName: document.getElementById('freshness-feature-name'),
        freshnessSourceEventId: document.getElementById('freshness-source-event-id'),
        freshnessStoreSequence: document.getElementById('freshness-store-sequence'),
        freshnessStatusClass: document.getElementById('freshness-status-class'),
        featureplantLagEvents: document.getElementById('featureplant-lag-events'),
        operatorPlanState: document.getElementById('operator-plan-state'),
        operatorDeployProfile: document.getElementById('operator-deploy-profile'),
        operatorKalshiKeyId: document.getElementById('operator-kalshi-key-id'),
        operatorKeyPath: document.getElementById('operator-key-path'),
        operatorMarketMode: document.getElementById('operator-market-mode'),
        operatorMaxMarkets: document.getElementById('operator-max-markets'),
        operatorTickers: document.getElementById('operator-tickers'),
        operatorS3Bucket: document.getElementById('operator-s3-bucket'),
        operatorS3Prefix: document.getElementById('operator-s3-prefix'),
        operatorS3DeleteAfterUpload: document.getElementById('operator-s3-delete-after-upload'),
        operatorBasicAuthEnabled: document.getElementById('operator-basic-auth-enabled'),
        operatorBasicAuthUser: document.getElementById('operator-basic-auth-user'),
        operatorGeneratePlan: document.getElementById('operator-generate-plan'),
        operatorWarnings: document.getElementById('operator-warnings'),
        operatorEnvPlan: document.getElementById('operator-env-plan'),
        live: {
            symbol: document.getElementById('live-symbol'),
            bid: document.getElementById('live-bid'),
            ask: document.getElementById('live-ask'),
            midpoint: document.getElementById('live-midpoint'),
            ts: document.getElementById('live-ts'),
            age: document.getElementById('live-age'),
            sourceEvent: document.getElementById('source-event'),
            freshness: document.getElementById('freshness-state'),
            updated: document.getElementById('live-updated')
        }
    };

    const chart = LightweightCharts.createChart(dom.chartContainer, {
        layout: { background: { color: '#151719' }, textColor: '#e7ecef' },
        grid: {
            vertLines: { color: '#252b30' },
            horzLines: { color: '#252b30' }
        },
        timeScale: { timeVisible: true, secondsVisible: true, borderColor: '#343d44' },
        rightPriceScale: { borderColor: '#343d44' },
        crosshair: { mode: LightweightCharts.CrosshairMode.Normal }
    });

    const candleSeries = chart.addCandlestickSeries({
        upColor: '#2fbf71',
        downColor: '#e45757',
        borderUpColor: '#2fbf71',
        borderDownColor: '#e45757',
        wickUpColor: '#2fbf71',
        wickDownColor: '#e45757'
    });

    const volumeSeries = chart.addHistogramSeries({
        priceFormat: { type: 'volume' },
        priceScaleId: 'volume',
        color: '#8f6f32'
    });
    chart.priceScale('volume').applyOptions({
        scaleMargins: { top: 0.82, bottom: 0 }
    });

    new ResizeObserver(() => {
        chart.applyOptions({
            width: dom.chartContainer.clientWidth,
            height: dom.chartContainer.clientHeight
        });
    }).observe(dom.chartContainer);
    chart.applyOptions({
        width: dom.chartContainer.clientWidth,
        height: dom.chartContainer.clientHeight
    });

    let quotesTimer = null;
    let quotesAbortController = null;
    let quotesEventSource = null;
    let quotesLoopGeneration = 0;
    let quoteSequence = null;
    let quoteUpdateErrors = 0;
    let marketEntries = [];
    let marketSearchTimer = null;
    let lastHealthBody = null;

    function setStatus(message, tone) {
        dom.statusLine.textContent = message;
        dom.footer.classList.remove('ok', 'error');
        if (tone === 'ok' || tone === 'error') {
            dom.footer.classList.add(tone);
        }
    }

    function adapterBase() {
        const override = dom.adapterUrl.value.trim();
        if (override) {
            return override.replace(/\/+$/, '');
        }
        if (window.location.origin && window.location.origin !== 'null') {
            return window.location.origin;
        }
        return 'http://127.0.0.1:8090';
    }

    async function fetchJsonFromBase(base, path, options) {
        const url = base + path;
        const response = await fetch(url, Object.assign({ mode: 'cors' }, options || {}));
        if (!response.ok) {
            throw new Error(`HTTP ${response.status} from ${path}`);
        }
        return response.json();
    }

    async function fetchJson(path, options) {
        return fetchJsonFromBase(adapterBase(), path, options);
    }

    async function loadConfig() {
        try {
            const config = await fetchJson('/datafeed/config');
            const resolutions = Array.isArray(config.supported_resolutions)
                ? config.supported_resolutions
                : FALLBACK_RESOLUTIONS;
            populateResolutionDropdown(resolutions);
        } catch (err) {
            console.warn('Failed to load /datafeed/config', err);
            populateResolutionDropdown(FALLBACK_RESOLUTIONS);
            setStatus('Could not reach /datafeed/config; using fallback resolutions', 'error');
        }
    }

    function populateResolutionDropdown(resolutions) {
        dom.resolutionSelect.innerHTML = '';
        for (const res of resolutions) {
            const opt = document.createElement('option');
            opt.value = res;
            opt.textContent = labelForResolution(res);
            dom.resolutionSelect.appendChild(opt);
        }
        dom.resolutionSelect.value = resolutions.includes('1') ? '1' : resolutions[0];
    }

    function labelForResolution(res) {
        switch (res) {
            case '1S': return '1 second';
            case '5S': return '5 seconds';
            case '30S': return '30 seconds';
            case '1': return '1 minute';
            case '5': return '5 minutes';
            case '15': return '15 minutes';
            case '60': return '1 hour';
            default: return res;
        }
    }

    async function loadSymbols() {
        setStatus('Loading market catalog...');
        try {
            const previousSymbol = dom.symbolSelect.value;
            marketEntries = await loadMarketEntries();
            populateSymbolDropdown(marketEntries, previousSymbol);
            renderMarketCatalog(marketEntries);
            if (marketEntries.length === 0) {
                quotesLoopGeneration += 1;
                stopQuotesLoop();
                renderMissingQuote('');
                renderFeatures([]);
                renderQuoteUpdateState('no selected market', 'stale');
                const filtered = hasMarketFilter();
                const message = filtered
                    ? 'No markets match the current search/filter.'
                    : 'No markets indexed yet; confirm FeaturePlant outputs are flowing.';
                setStatus(message, filtered ? '' : 'error');
                return;
            }
            setStatus(`Loaded ${marketEntries.length} market(s)`, 'ok');
            onSelectedSymbolChanged();
        } catch (err) {
            renderMarketCatalogError(err.message);
            setStatus(`Failed to load markets: ${err.message}`, 'error');
        }
    }

    async function loadMarketEntries() {
        const query = dom.marketSearch ? dom.marketSearch.value.trim() : '';
        const status = dom.marketStatusFilter ? dom.marketStatusFilter.value.trim() : '';
        const filtered = query !== '' || status !== '';
        const params = [`limit=${MARKET_CATALOG_LIMIT}`];
        if (query) {
            params.push(`query=${encodeURIComponent(query)}`);
        }
        if (status) {
            params.push(`status=${encodeURIComponent(status)}`);
        }
        try {
            const markets = await fetchJson('/markets?' + params.join('&'));
            if (markets && Array.isArray(markets.markets) && markets.markets.length > 0) {
                return markets.markets.map(row => ({
                    symbol: row.market_ticker,
                    eventTicker: row.event_ticker || '-',
                    seriesTicker: row.series_ticker || '-',
                    status: row.status || '-',
                    openTime: row.open_time || null,
                    closeTime: row.close_time || null
                })).filter(row => row.symbol);
            }
            if (filtered) {
                return [];
            }
        } catch (err) {
            if (filtered) {
                throw err;
            }
            console.warn('Falling back to /symbols after /markets failed', err);
        }
        const symbols = await fetchJson('/symbols');
        const rows = (symbols && Array.isArray(symbols.symbols)) ? symbols.symbols : [];
        return rows.map(row => ({
            symbol: row.symbol,
            eventTicker: '-',
            seriesTicker: '-',
            status: row.latest_event_ts_ms == null ? 'waiting' : 'indexed',
            openTime: null,
            closeTime: null
        })).filter(row => row.symbol);
    }

    function populateSymbolDropdown(entries, preferredSymbol) {
        dom.symbolSelect.innerHTML = '';
        if (entries.length === 0) {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = '(no markets)';
            dom.symbolSelect.appendChild(opt);
            return;
        }
        for (const entry of entries) {
            const opt = document.createElement('option');
            opt.value = entry.symbol;
            opt.textContent = entry.symbol;
            dom.symbolSelect.appendChild(opt);
        }
        if (preferredSymbol && entries.some(entry => entry.symbol === preferredSymbol)) {
            dom.symbolSelect.value = preferredSymbol;
        }
    }

    function renderMarketCatalog(entries) {
        dom.marketCount.textContent = String(entries.length);
        dom.marketList.innerHTML = '';
        if (entries.length === 0) {
            dom.marketState.textContent = hasMarketFilter()
                ? 'No markets match the current search/filter.'
                : 'No markets indexed yet.';
            return;
        }
        const filters = [];
        const query = dom.marketSearch ? dom.marketSearch.value.trim() : '';
        const status = dom.marketStatusFilter ? dom.marketStatusFilter.value.trim() : '';
        if (query) {
            filters.push(`query "${query}"`);
        }
        if (status) {
            filters.push(`status ${status}`);
        }
        dom.marketState.textContent = filters.length
            ? `${entries.length} market(s) for ${filters.join(' / ')}`
            : `${entries.length} market(s)`;
        for (const entry of entries) {
            const button = document.createElement('button');
            button.type = 'button';
            button.dataset.symbol = entry.symbol;
            button.innerHTML = `<strong>${escapeHtml(entry.symbol)}</strong><span>${escapeHtml(entry.status || '-')}</span>`;
            button.addEventListener('click', () => {
                dom.symbolSelect.value = entry.symbol;
                onSelectedSymbolChanged();
            });
            dom.marketList.appendChild(button);
        }
    }

    function renderMarketCatalogError(message) {
        dom.marketCount.textContent = '0';
        dom.marketState.textContent = `Market catalog unavailable: ${message}`;
        dom.marketList.innerHTML = '<div class="empty">market catalog unavailable</div>';
    }

    function hasMarketFilter() {
        const query = dom.marketSearch ? dom.marketSearch.value.trim() : '';
        const status = dom.marketStatusFilter ? dom.marketStatusFilter.value.trim() : '';
        return query !== '' || status !== '';
    }

    function scheduleMarketSearch() {
        if (marketSearchTimer != null) {
            clearTimeout(marketSearchTimer);
        }
        marketSearchTimer = setTimeout(() => {
            marketSearchTimer = null;
            loadSymbols();
        }, MARKET_SEARCH_DEBOUNCE_MS);
    }

    async function loadMarketDetails(symbol) {
        const base = adapterBase();
        const cached = marketEntries.find(entry => entry.symbol === symbol);
        if (cached) {
            renderMarketDetails(cached);
        }
        try {
            const data = await fetchJsonFromBase(base, `/datafeed/symbols?symbol=${encodeURIComponent(symbol)}`);
            if (symbol !== dom.symbolSelect.value || base !== adapterBase()) {
                return;
            }
            renderMarketDetails({
                symbol,
                eventTicker: data.event_ticker || cached?.eventTicker || '-',
                seriesTicker: data.series_ticker || cached?.seriesTicker || '-',
                status: data.status || cached?.status || '-',
                openTime: data.open_time || cached?.openTime || null,
                closeTime: data.close_time || cached?.closeTime || null
            });
        } catch (err) {
            console.warn('Failed to load selected market metadata', err);
        }
    }

    function renderMarketDetails(entry) {
        dom.marketStatus.textContent = entry.status || '-';
        dom.marketEvent.textContent = entry.eventTicker || '-';
        dom.marketSeries.textContent = entry.seriesTicker || '-';
        dom.marketOpen.textContent = formatIso(entry.openTime);
        dom.marketClose.textContent = formatIso(entry.closeTime);
        for (const button of dom.marketList.querySelectorAll('button')) {
            button.classList.toggle('selected', button.dataset.symbol === entry.symbol);
        }
    }

    async function loadRecentFeatures(symbol) {
        if (!symbol) {
            renderFeatures([]);
            return;
        }
        const base = adapterBase();
        const feature = dom.researchFeatureSelect?.value || 'feature.bbo';
        try {
            const data = await fetchJsonFromBase(
                base,
                `/features?symbol=${encodeURIComponent(symbol)}&feature=${encodeURIComponent(feature)}&limit=8`
            );
            if (symbol !== dom.symbolSelect.value || base !== adapterBase()) {
                return;
            }
            renderFeatures(Array.isArray(data.outputs) ? data.outputs.slice().reverse() : []);
        } catch (err) {
            if (symbol !== dom.symbolSelect.value || base !== adapterBase()) {
                return;
            }
            dom.featureList.innerHTML = `<div class="empty">features unavailable: ${escapeHtml(err.message)}</div>`;
            dom.featureCount.textContent = '0';
        }
    }

    function renderFeatures(outputs) {
        dom.featureCount.textContent = String(outputs.length);
        dom.featureList.innerHTML = '';
        if (outputs.length === 0) {
            dom.featureList.innerHTML = '<div class="empty">no feature outputs</div>';
            return;
        }
        for (const output of outputs) {
            const row = document.createElement('div');
            row.className = 'feature-row';
            const midpoint = output.values ? output.values.midpoint_micros : null;
            const featureName = output.feature_name || dom.researchFeatureSelect?.value || 'feature';
            row.innerHTML =
                `<span>${escapeHtml(formatEventTs(output.event_ts_ms))}</span>` +
                `<strong>${escapeHtml(formatFeatureValue(featureName, output.values, midpoint))}</strong>` +
                `<small>${escapeHtml(output.source_event_id || '-')}</small>`;
            dom.featureList.appendChild(row);
        }
    }

    async function loadHistory() {
        const symbol = dom.symbolSelect.value;
        const resolution = dom.resolutionSelect.value;
        const lookbackSeconds = parseInt(dom.lookbackSelect.value, 10) || 3600;
        if (!symbol || !resolution) {
            setStatus('Select a symbol and resolution first', 'error');
            return;
        }
        const toSec = Math.floor(Date.now() / 1000);
        const fromSec = toSec - lookbackSeconds;
        const base = adapterBase();
        setStatus(`Loading history ${symbol} @ ${resolution}...`);
        try {
            const data = await fetchJsonFromBase(
                base,
                `/datafeed/history?symbol=${encodeURIComponent(symbol)}` +
                `&resolution=${encodeURIComponent(resolution)}` +
                `&from=${fromSec}&to=${toSec}`
            );
            if (symbol !== dom.symbolSelect.value ||
                resolution !== dom.resolutionSelect.value ||
                base !== adapterBase()) {
                return;
            }
            if (data.s !== 'ok') {
                candleSeries.setData([]);
                volumeSeries.setData([]);
                setStatus(`No chart bars for ${symbol} in window`, 'error');
                return;
            }
            const candles = [];
            const volumes = [];
            for (let i = 0; i < data.t.length; i++) {
                const time = data.t[i];
                const open = data.o[i];
                const close = data.c[i];
                candles.push({
                    time,
                    open,
                    high: data.h[i],
                    low: data.l[i],
                    close
                });
                volumes.push({
                    time,
                    value: data.v[i],
                    color: close >= open ? 'rgba(47, 191, 113, 0.5)' : 'rgba(228, 87, 87, 0.5)'
                });
            }
            candleSeries.setData(candles);
            volumeSeries.setData(volumes);
            chart.timeScale().fitContent();
            setStatus(`Rendered ${candles.length} bar(s) for ${symbol}`, 'ok');
        } catch (err) {
            if (symbol !== dom.symbolSelect.value ||
                resolution !== dom.resolutionSelect.value ||
                base !== adapterBase()) {
                return;
            }
            setStatus(`Failed to load history: ${err.message}`, 'error');
        }
    }

    function renderMissingQuote(symbol) {
        dom.live.symbol.textContent = symbol || '-';
        dom.live.bid.textContent = '-';
        dom.live.ask.textContent = '-';
        dom.live.midpoint.textContent = '-';
        dom.live.ts.textContent = '-';
        dom.live.age.textContent = '-';
        dom.live.sourceEvent.textContent = '-';
        dom.live.freshness.textContent = 'waiting';
        dom.live.updated.textContent = new Date().toLocaleTimeString();
    }

    function renderQuote(symbol, quote, serverTsMs) {
        if (!quote) {
            renderMissingQuote(symbol);
            return;
        }
        dom.live.symbol.textContent = quote.symbol;
        dom.live.bid.textContent = formatMicros(quote.bid_micros);
        dom.live.ask.textContent = formatMicros(quote.ask_micros);
        dom.live.midpoint.textContent = formatMicros(quote.midpoint_micros);
        dom.live.ts.textContent = formatEventTs(quote.event_ts_ms);
        dom.live.sourceEvent.textContent = quote.source_event_id || '-';
        dom.live.updated.textContent = new Date().toLocaleTimeString();
        renderFreshness(quote.event_ts_ms, serverTsMs);
    }

    function renderFreshness(eventTsMs, serverTsMs) {
        if (eventTsMs == null) {
            dom.live.age.textContent = '-';
            dom.live.freshness.textContent = 'waiting';
            dom.live.freshness.className = 'stale';
            return;
        }
        const reference = Number.isFinite(serverTsMs) ? serverTsMs : Date.now();
        const ageMs = Math.max(0, reference - Number(eventTsMs));
        dom.live.age.textContent = formatAge(ageMs);
        const fresh = ageMs <= 15000;
        dom.live.freshness.textContent = fresh ? 'fresh' : 'stale';
        dom.live.freshness.className = fresh ? 'fresh' : 'stale';
    }

    function staleQuoteLoop(generation, symbol, base) {
        return generation !== quotesLoopGeneration ||
            symbol !== dom.symbolSelect.value ||
            base !== adapterBase();
    }

    function applyQuoteResponse(data, symbol) {
        const nextSequence = typeof data.sequence === 'number' ? data.sequence : null;
        if (nextSequence != null && quoteSequence != null && nextSequence < quoteSequence) {
            return false;
        }
        if (nextSequence != null) {
            quoteSequence = nextSequence;
        }
        const quote = (data.quotes || []).find(q => q.symbol === symbol);
        renderQuote(symbol, quote, data.server_ts_ms);
        loadRecentFeatures(symbol);
        return true;
    }

    async function pollQuotes(generation, symbol, base) {
        if (!symbol) {
            return false;
        }
        try {
            const data = await fetchJsonFromBase(base, `/quotes?symbols=${encodeURIComponent(symbol)}`);
            if (staleQuoteLoop(generation, symbol, base)) {
                return false;
            }
            applyQuoteResponse(data, symbol);
            return true;
        } catch (err) {
            if (staleQuoteLoop(generation, symbol, base)) {
                return false;
            }
            dom.live.updated.textContent = `error: ${err.message}`;
            return false;
        }
    }

    async function loadHealth() {
        try {
            const body = await fetchJson('/health');
            lastHealthBody = body;
            dom.adapterHealth.textContent = adapterStatusText(body);
            dom.adapterHealth.className = body.status === 'ok' ? 'fresh' : 'stale';
            dom.releaseIdentity.textContent = releaseStatusText(body.release);
            dom.healthDataAge.textContent = dataFreshnessText(body.data_freshness);
            dom.healthDataAge.className = dataFreshnessClass(body.data_freshness);
            dom.healthSequence.textContent = body.store ? String(body.store.sequence) : '-';
            dom.refreshHealth.textContent = refreshStatusText(body.feature_output_refresh);
            dom.refreshHealth.className = refreshStatusClass(body.feature_output_refresh);
            dom.quoteUpdateHealth.textContent = quoteUpdateStatusText(body.quote_updates, body.quote_streams);
            dom.metadataHealth.textContent = body.market_metadata
                ? `${body.market_metadata.status || '-'} / ${body.market_metadata.markets || 0}`
                : '-';
            dom.featureplantHealth.textContent = body.feature_plant
                ? `${body.feature_plant.events_out || 0} out / ${body.feature_plant.errors || 0} err`
                : '-';
            renderProductReadiness(body);
            renderRuntimeOperator(body);
            renderLatencyFreshness(body);
        } catch (err) {
            dom.adapterHealth.textContent = 'unavailable';
            dom.adapterHealth.className = 'stale';
            dom.releaseIdentity.textContent = '-';
            dom.healthDataAge.textContent = '-';
            dom.healthDataAge.className = 'stale';
            dom.refreshHealth.textContent = err.message;
            dom.refreshHealth.className = 'stale';
            dom.quoteUpdateHealth.textContent = 'unavailable';
            dom.quoteUpdateHealth.className = 'stale';
        }
    }

    function renderProductReadiness(body) {
        const readiness = body.product_readiness || {};
        const freshness = body.data_freshness || {};
        const state = readiness.status || 'unknown';
        dom.productReadinessState.textContent = state;
        dom.productReadinessState.className = state === 'ok' ? 'fresh' : 'stale';
        const reasons = Array.isArray(readiness.reasons) && readiness.reasons.length
            ? readiness.reasons.join(', ')
            : 'ready';
        dom.productReadinessReasons.textContent = reasons;
        dom.marketSourceKind.textContent = freshness.source_kind || '-';
        dom.marketLiveObserved.textContent = freshness.live_data_observed === true ? 'yes' : 'no';
        dom.marketSyntheticState.textContent = freshness.synthetic === true ? 'yes' : 'no';
    }

    function renderRuntimeOperator(body) {
        const refresh = body.feature_output_refresh || {};
        const streams = body.quote_streams || {};
        const updates = body.quote_updates || {};
        dom.runtimeSourceMode.textContent = body.source_mode || '-';
        dom.runtimeFeatureSource.textContent = body.feature_source || '-';
        dom.runtimeUptime.textContent = formatAge(Number(body.uptime_ms || 0));
        dom.runtimeRefreshTotal.textContent = String(refresh.total_loaded ?? 0);
        dom.runtimeRefreshErrors.textContent = String(refresh.refresh_errors ?? 0);
        dom.runtimeQuoteStreams.textContent =
            `${streams.active_streams || 0}/${streams.max_streams || 0} active, ${streams.events || 0} events`;
        dom.runtimeQuoteWaits.textContent =
            `${updates.active_waits || 0}/${updates.max_waits || 0} active, ${updates.timeouts || 0} timeout`;
    }

    function renderLatencyFreshness(body) {
        const freshness = body.data_freshness || {};
        const fp = body.feature_plant || {};
        const store = body.store || {};
        const age = freshness.latest_event_age_ms;
        const state = dataFreshnessLiveState(freshness);
        dom.freshnessStatusClass.textContent = state;
        dom.freshnessStatusClass.className = state === 'live' ? 'fresh' : 'stale';
        dom.freshnessAgeMs.textContent = age == null ? '-' : `${age} ms (${formatAge(Number(age))})`;
        dom.freshnessEventTsMs.textContent = freshness.latest_event_ts_ms == null
            ? '-'
            : `${freshness.latest_event_ts_ms} / ${formatEventTs(freshness.latest_event_ts_ms)}`;
        dom.freshnessSymbol.textContent = freshness.symbol || '-';
        dom.freshnessFeatureName.textContent = freshness.feature_name || '-';
        dom.freshnessSourceEventId.textContent = freshness.source_event_id || '-';
        dom.freshnessStoreSequence.textContent = String(freshness.store_sequence ?? store.sequence ?? '-');
        if (typeof store.total_features === 'number' && typeof fp.events_out === 'number') {
            dom.featureplantLagEvents.textContent = String(Math.max(0, store.total_features - fp.events_out));
        } else {
            dom.featureplantLagEvents.textContent = '-';
        }
    }

    function adapterStatusText(body) {
        if (!body || !body.status) {
            return 'unavailable';
        }
        return body.status === 'ok' ? 'up' : String(body.status);
    }

    function releaseStatusText(release) {
        if (!release) {
            return '-';
        }
        const sha = release.sha ? String(release.sha).slice(0, 12) : '';
        const profile = release.profile || '';
        const image = release.image ? String(release.image).split('/').pop() : '';
        const parts = [];
        if (sha) {
            parts.push(sha);
        }
        if (profile) {
            parts.push(profile);
        }
        if (image) {
            parts.push(image);
        }
        return parts.length ? parts.join(' / ') : '-';
    }

    function dataFreshnessText(freshness) {
        if (!freshness || freshness.latest_event_ts_ms == null) {
            return 'waiting';
        }
        const age = freshness.latest_event_age_ms == null ? '-' : formatAge(Number(freshness.latest_event_age_ms));
        const state = dataFreshnessLiveState(freshness);
        const kind = freshness.source_kind || 'unknown';
        const symbol = freshness.symbol || '-';
        const source = freshness.source_event_id || '-';
        return `${state} ${kind} / ${age} / ${symbol} / ${source}`;
    }

    function dataFreshnessLiveState(freshness) {
        if (!freshness || freshness.latest_event_age_ms == null) {
            return 'stale';
        }
        return Number(freshness.latest_event_age_ms) <= 15000 ? 'live' : 'stale';
    }

    function dataFreshnessClass(freshness) {
        return dataFreshnessLiveState(freshness) === 'live' ? 'fresh' : 'stale';
    }

    function refreshStatusText(status) {
        if (!status || status.enabled !== true) {
            return 'disabled';
        }
        const running = status.running ? 'running' : 'stopped';
        return `${running} / ${status.total_loaded || 0} rows / ${status.refresh_errors || 0} err`;
    }

    function refreshStatusClass(status) {
        if (!status || status.enabled !== true || status.running !== true || (status.refresh_errors || 0) > 0) {
            return 'stale';
        }
        return 'fresh';
    }

    function quoteUpdateStatusText(updates, streams) {
        if (!updates && !streams) {
            return 'unavailable';
        }
        const active = updates ? updates.active_waits || 0 : 0;
        const max = updates ? updates.max_waits || 0 : 0;
        const streamActive = streams ? streams.active_streams || 0 : 0;
        const streamMax = streams ? streams.max_streams || 0 : 0;
        return `SSE req ${streams ? streams.requests || 0 : 0} / events ${streams ? streams.events || 0 : 0}` +
            ` / active ${streamActive}/${streamMax}` +
            ` / long-poll req ${updates ? updates.requests || 0 : 0}` +
            ` / changed ${updates ? updates.changed || 0 : 0}` +
            ` / timeout ${updates ? updates.timeouts || 0 : 0}` +
            ` / rejected ${updates ? updates.rejected || 0 : 0}` +
            ` / active ${active}/${max}`;
    }

    function generateOperatorPlan() {
        const profile = dom.operatorDeployProfile.value || 'live-product';
        const marketMode = dom.operatorMarketMode.value || 'configured';
        const maxMarkets = dom.operatorMaxMarkets.value || '0';
        const tickers = dom.operatorTickers.value.trim();
        const keyId = dom.operatorKalshiKeyId.value.trim();
        const keyPath = dom.operatorKeyPath.value.trim() || '/run/secrets/kalshi_private_key';
        const s3Bucket = dom.operatorS3Bucket.value.trim();
        const s3Prefix = dom.operatorS3Prefix.value.trim();
        const basicAuthEnabled = dom.operatorBasicAuthEnabled.checked;
        const basicAuthUser = dom.operatorBasicAuthUser.value.trim();
        const warnings = [];
        if (marketMode === 'configured' && !tickers) {
            warnings.push('configured market mode needs KALSHI_MARKET_TICKERS before deploy');
        }
        if (marketMode === 'open_markets' && Number(maxMarkets) <= 0) {
            warnings.push('open_markets should set KALSHI_MARKET_DISCOVERY_MAX_MARKETS above zero');
        }
        if (!keyId) {
            warnings.push('Kalshi key id is blank; live websocket auth will fail');
        }
        if (basicAuthEnabled && !basicAuthUser) {
            warnings.push('Basic Auth is enabled but user is blank');
        }
        if (s3Bucket && !s3Prefix) {
            warnings.push('S3 bucket is set without a prefix');
        }
        if (lastHealthBody?.product_readiness?.status && lastHealthBody.product_readiness.status !== 'ok') {
            warnings.push(`current dashboard readiness is ${lastHealthBody.product_readiness.status}`);
        }
        const env = [
            ['KALSHI_DEPLOY_PROFILE', profile],
            ['KALSHI_KEY_ID', keyId ? '<redacted>' : ''],
            ['KALSHI_KEY_PATH', keyPath],
            ['KALSHI_MARKET_SELECTION_MODE', marketMode],
            ['KALSHI_MARKET_DISCOVERY_MAX_MARKETS', maxMarkets],
            ['KALSHI_MARKET_TICKERS', tickers],
            ['S3_RECORDING_BUCKET', s3Bucket],
            ['S3_RECORDING_PREFIX', s3Prefix],
            ['S3_DELETE_AFTER_UPLOAD', dom.operatorS3DeleteAfterUpload.checked ? 'true' : 'false'],
            ['FRONTEND_ADAPTER_BASIC_AUTH_USER', basicAuthEnabled && basicAuthUser ? '<redacted>' : ''],
            ['FRONTEND_ADAPTER_BASIC_AUTH_PASSWORD', basicAuthEnabled ? '<set via secret>' : '']
        ];
        const workflow = {
            deploy_profile: profile,
            run_live_product_smoke: profile === 'live-product' || profile === 'live-product-local-db',
            run_live_product_browser_smoke: true,
            require_live_product_data: profile === 'live-product',
            market_selection_mode: marketMode,
            market_tickers_configured: tickers ? tickers.split(',').map(value => value.trim()).filter(Boolean).length : 0,
            basic_auth_enabled: basicAuthEnabled,
            s3_capture_configured: Boolean(s3Bucket)
        };
        dom.operatorWarnings.innerHTML = warnings.length
            ? warnings.map(warning => `<div class="operator-plan-warning">${escapeHtml(warning)}</div>`).join('')
            : '<div class="operator-plan-step">No local warnings from current inputs.</div>';
        dom.operatorEnvPlan.textContent =
            env.map(([key, value]) => `${key}=${value}`).join('\n') +
            '\n\nworkflow_inputs=' + JSON.stringify(workflow, null, 2);
        dom.operatorPlanState.textContent = warnings.length ? `${warnings.length} warning(s)` : 'ready';
        dom.operatorPlanState.className = warnings.length ? 'stale' : 'fresh';
    }

    function renderQuoteUpdateState(message, tone) {
        dom.quoteUpdateHealth.textContent = message;
        dom.quoteUpdateHealth.className = tone || '';
    }

    function formatMicros(micros) {
        if (micros == null) {
            return '-';
        }
        const dollars = Number(micros) / 1_000_000;
        return dollars.toFixed(4);
    }

    function formatFeatureValue(featureName, values, fallbackMidpoint) {
        if (!values) {
            return '-';
        }
        if (featureName === 'feature.ticker_snapshot') {
            return formatMicros(values.price_micros ?? values.yes_bid_micros ?? values.midpoint_micros);
        }
        if (featureName === 'feature.trade_tape') {
            const price = values.yes_price_micros ?? values.no_price_micros ?? values.price_micros;
            const quantity = values.quantity_micros == null ? '' : ` x ${values.quantity_micros}`;
            return `${formatMicros(price)}${quantity}`;
        }
        return formatMicros(fallbackMidpoint);
    }

    function formatEventTs(value) {
        if (value == null) {
            return '-';
        }
        return new Date(Number(value)).toISOString();
    }

    function formatIso(value) {
        if (value == null || value === '') {
            return '-';
        }
        return String(value);
    }

    function formatAge(ageMs) {
        if (ageMs < 1000) {
            return `${ageMs} ms`;
        }
        if (ageMs < 60000) {
            return `${(ageMs / 1000).toFixed(1)} s`;
        }
        return `${Math.floor(ageMs / 60000)} min`;
    }

    function escapeHtml(value) {
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    function stopQuotesLoop() {
        if (quotesTimer != null) {
            clearInterval(quotesTimer);
            quotesTimer = null;
        }
        if (quotesAbortController != null) {
            quotesAbortController.abort();
            quotesAbortController = null;
        }
        if (quotesEventSource != null) {
            quotesEventSource.close();
            quotesEventSource = null;
        }
    }

    function startQuotesFallback(generation, symbol, base) {
        if (staleQuoteLoop(generation, symbol, base)) {
            return;
        }
        stopQuotesLoop();
        renderQuoteUpdateState('fallback polling', 'stale');
        pollQuotes(generation, symbol, base);
        quotesTimer = setInterval(() => pollQuotes(generation, symbol, base), QUOTES_POLL_MS);
    }

    async function runQuotesLongPoll(generation, symbol, base) {
        symbol = symbol || dom.symbolSelect.value;
        base = base || adapterBase();
        if (!symbol) {
            return;
        }
        await pollQuotes(generation, symbol, base);
        while (!staleQuoteLoop(generation, symbol, base)) {
            const after = Number.isFinite(quoteSequence) ? quoteSequence : 0;
            const controller = new AbortController();
            quotesAbortController = controller;
            try {
                const data = await fetchJsonFromBase(
                    base,
                    `/quotes/updates?symbols=${encodeURIComponent(symbol)}` +
                        `&after=${encodeURIComponent(after)}` +
                        `&timeout_ms=${QUOTES_UPDATE_TIMEOUT_MS}`,
                    { signal: controller.signal }
                );
                if (quotesAbortController === controller) {
                    quotesAbortController = null;
                }
                if (staleQuoteLoop(generation, symbol, base)) {
                    return;
                }
                quoteUpdateErrors = 0;
                applyQuoteResponse(data, symbol);
                renderQuoteUpdateState(data.changed ? 'long-poll changed' : 'long-poll timeout', 'fresh');
            } catch (err) {
                if (quotesAbortController === controller) {
                    quotesAbortController = null;
                }
                if (err.name === 'AbortError' || staleQuoteLoop(generation, symbol, base)) {
                    return;
                }
                quoteUpdateErrors += 1;
                dom.live.updated.textContent = `error: ${err.message}`;
                renderQuoteUpdateState(`long-poll error ${quoteUpdateErrors}`, 'stale');
                if (quoteUpdateErrors >= QUOTES_UPDATE_ERROR_LIMIT) {
                    startQuotesFallback(generation, symbol, base);
                    return;
                }
                await sleep(QUOTES_UPDATE_RETRY_MS);
            }
        }
    }

    function handleQuoteStreamMessage(generation, symbol, base, event) {
        if (staleQuoteLoop(generation, symbol, base)) {
            return;
        }
        try {
            const data = JSON.parse(event.data);
            quoteUpdateErrors = 0;
            if (applyQuoteResponse(data, symbol)) {
                renderQuoteUpdateState(data.changed ? 'SSE changed' : 'SSE snapshot', 'fresh');
            }
        } catch (err) {
            quoteUpdateErrors += 1;
            renderQuoteUpdateState(`SSE parse error ${quoteUpdateErrors}`, 'stale');
        }
    }

    function scheduleQuoteStreamReconnect(generation, symbol, base) {
        if (staleQuoteLoop(generation, symbol, base)) {
            return;
        }
        if (quoteUpdateErrors >= QUOTES_STREAM_ERROR_LIMIT) {
            renderQuoteUpdateState('long-poll fallback', 'stale');
            runQuotesLongPoll(generation, symbol, base);
            return;
        }
        quotesTimer = setTimeout(() => {
            quotesTimer = null;
            startQuotesStream(generation, symbol, base);
        }, QUOTES_UPDATE_RETRY_MS);
    }

    function startQuotesStream(generation, symbol, base) {
        symbol = symbol || dom.symbolSelect.value;
        base = base || adapterBase();
        if (!symbol) {
            return;
        }
        if (typeof window.EventSource !== 'function') {
            renderQuoteUpdateState('EventSource unavailable; long-poll fallback', 'stale');
            runQuotesLongPoll(generation, symbol, base);
            return;
        }
        const source = new EventSource(base + `/quotes/stream?symbols=${encodeURIComponent(symbol)}`);
        quotesEventSource = source;
        renderQuoteUpdateState('SSE connecting', '');
        const onQuote = event => handleQuoteStreamMessage(generation, symbol, base, event);
        source.addEventListener('quotes', onQuote);
        source.onmessage = onQuote;
        source.onopen = () => {
            if (!staleQuoteLoop(generation, symbol, base)) {
                renderQuoteUpdateState('SSE connected', 'fresh');
            }
        };
        source.onerror = () => {
            if (quotesEventSource === source) {
                quotesEventSource = null;
            }
            source.close();
            if (staleQuoteLoop(generation, symbol, base)) {
                return;
            }
            quoteUpdateErrors += 1;
            renderQuoteUpdateState(`SSE error ${quoteUpdateErrors}`, 'stale');
            scheduleQuoteStreamReconnect(generation, symbol, base);
        };
    }

    function restartQuotesPolling() {
        quotesLoopGeneration += 1;
        stopQuotesLoop();
        quoteSequence = null;
        quoteUpdateErrors = 0;
        renderQuoteUpdateState('SSE starting', '');
        startQuotesStream(quotesLoopGeneration);
    }

    function onSelectedSymbolChanged() {
        const symbol = dom.symbolSelect.value;
        renderMissingQuote(symbol);
        loadMarketDetails(symbol);
        loadRecentFeatures(symbol);
        restartQuotesPolling();
        loadHistory();
        loadHealth();
    }

    dom.refreshSymbols.addEventListener('click', () => {
        loadConfig();
        loadSymbols();
    });
    dom.marketSearch.addEventListener('input', scheduleMarketSearch);
    dom.marketStatusFilter.addEventListener('change', loadSymbols);
    dom.marketSearchApply.addEventListener('click', loadSymbols);
    dom.loadHistory.addEventListener('click', loadHistory);
    dom.symbolSelect.addEventListener('change', onSelectedSymbolChanged);
    dom.researchFeatureSelect.addEventListener('change', () => loadRecentFeatures(dom.symbolSelect.value));
    dom.operatorGeneratePlan.addEventListener('click', generateOperatorPlan);
    for (const button of document.querySelectorAll('#view-tabs [data-target]')) {
        button.addEventListener('click', () => {
            const target = document.getElementById(button.dataset.target);
            if (target) {
                target.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        });
    }
    dom.adapterUrl.addEventListener('change', () => {
        quotesLoopGeneration += 1;
        stopQuotesLoop();
        loadConfig();
        loadSymbols();
        loadHealth();
    });

    setInterval(loadHealth, HEALTH_POLL_MS);
    generateOperatorPlan();
    loadConfig().then(loadSymbols).then(loadHealth);
})();

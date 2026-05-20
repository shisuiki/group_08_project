(function () {
    'use strict';

    const QUOTES_POLL_MS = 2000;
    const QUOTES_UPDATE_TIMEOUT_MS = 15000;
    const QUOTES_UPDATE_ERROR_LIMIT = 3;
    const QUOTES_UPDATE_RETRY_MS = 500;
    const HEALTH_POLL_MS = 5000;
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
        marketList: document.getElementById('market-list'),
        marketStatus: document.getElementById('market-status'),
        marketEvent: document.getElementById('market-event'),
        marketSeries: document.getElementById('market-series'),
        marketOpen: document.getElementById('market-open'),
        marketClose: document.getElementById('market-close'),
        featureCount: document.getElementById('feature-count'),
        featureList: document.getElementById('feature-list'),
        adapterHealth: document.getElementById('adapter-health'),
        releaseIdentity: document.getElementById('release-identity'),
        healthDataAge: document.getElementById('health-data-age'),
        healthSequence: document.getElementById('health-sequence'),
        refreshHealth: document.getElementById('refresh-health'),
        metadataHealth: document.getElementById('metadata-health'),
        featureplantHealth: document.getElementById('featureplant-health'),
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
    let quotesLoopGeneration = 0;
    let quoteSequence = null;
    let quoteUpdateErrors = 0;
    let marketEntries = [];

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
            marketEntries = await loadMarketEntries();
            populateSymbolDropdown(marketEntries);
            renderMarketCatalog(marketEntries);
            if (marketEntries.length === 0) {
                setStatus('No markets indexed yet; confirm FeaturePlant outputs are flowing.', 'error');
                return;
            }
            setStatus(`Loaded ${marketEntries.length} market(s)`, 'ok');
            onSelectedSymbolChanged();
        } catch (err) {
            setStatus(`Failed to load markets: ${err.message}`, 'error');
        }
    }

    async function loadMarketEntries() {
        try {
            const markets = await fetchJson('/markets?limit=100');
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
        } catch (err) {
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

    function populateSymbolDropdown(entries) {
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
    }

    function renderMarketCatalog(entries) {
        dom.marketCount.textContent = String(entries.length);
        dom.marketList.innerHTML = '';
        for (const entry of entries.slice(0, 80)) {
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

    async function loadMarketDetails(symbol) {
        const cached = marketEntries.find(entry => entry.symbol === symbol);
        if (cached) {
            renderMarketDetails(cached);
        }
        try {
            const data = await fetchJson(`/datafeed/symbols?symbol=${encodeURIComponent(symbol)}`);
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
        try {
            const data = await fetchJson(
                `/features?symbol=${encodeURIComponent(symbol)}&feature=feature.bbo&limit=5`
            );
            renderFeatures(Array.isArray(data.outputs) ? data.outputs.slice().reverse() : []);
        } catch (err) {
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
            row.innerHTML =
                `<span>${escapeHtml(formatEventTs(output.event_ts_ms))}</span>` +
                `<strong>${escapeHtml(formatMicros(midpoint))}</strong>` +
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
        setStatus(`Loading history ${symbol} @ ${resolution}...`);
        try {
            const data = await fetchJson(
                `/datafeed/history?symbol=${encodeURIComponent(symbol)}` +
                `&resolution=${encodeURIComponent(resolution)}` +
                `&from=${fromSec}&to=${toSec}`
            );
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
            dom.adapterHealth.textContent = body.status || '-';
            dom.releaseIdentity.textContent = releaseStatusText(body.release);
            dom.healthDataAge.textContent = dataFreshnessText(body.data_freshness);
            dom.healthSequence.textContent = body.store ? String(body.store.sequence) : '-';
            dom.refreshHealth.textContent = refreshStatusText(body.feature_output_refresh);
            dom.metadataHealth.textContent = body.market_metadata
                ? `${body.market_metadata.status || '-'} / ${body.market_metadata.markets || 0}`
                : '-';
            dom.featureplantHealth.textContent = body.feature_plant
                ? `${body.feature_plant.events_out || 0} out / ${body.feature_plant.errors || 0} err`
                : '-';
        } catch (err) {
            dom.adapterHealth.textContent = 'unavailable';
            dom.releaseIdentity.textContent = '-';
            dom.healthDataAge.textContent = '-';
            dom.refreshHealth.textContent = err.message;
        }
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
        const symbol = freshness.symbol || '-';
        const source = freshness.source_event_id || '-';
        return `${age} / ${symbol} / ${source}`;
    }

    function refreshStatusText(status) {
        if (!status || status.enabled !== true) {
            return 'disabled';
        }
        const running = status.running ? 'running' : 'stopped';
        return `${running} / ${status.total_loaded || 0} rows`;
    }

    function formatMicros(micros) {
        if (micros == null) {
            return '-';
        }
        const dollars = Number(micros) / 1_000_000;
        return dollars.toFixed(4);
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
    }

    function startQuotesFallback(generation, symbol, base) {
        if (staleQuoteLoop(generation, symbol, base)) {
            return;
        }
        stopQuotesLoop();
        pollQuotes(generation, symbol, base);
        quotesTimer = setInterval(() => pollQuotes(generation, symbol, base), QUOTES_POLL_MS);
    }

    async function runQuotesLongPoll(generation) {
        const symbol = dom.symbolSelect.value;
        const base = adapterBase();
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
            } catch (err) {
                if (quotesAbortController === controller) {
                    quotesAbortController = null;
                }
                if (err.name === 'AbortError' || staleQuoteLoop(generation, symbol, base)) {
                    return;
                }
                quoteUpdateErrors += 1;
                dom.live.updated.textContent = `error: ${err.message}`;
                if (quoteUpdateErrors >= QUOTES_UPDATE_ERROR_LIMIT) {
                    startQuotesFallback(generation, symbol, base);
                    return;
                }
                await sleep(QUOTES_UPDATE_RETRY_MS);
            }
        }
    }

    function restartQuotesPolling() {
        quotesLoopGeneration += 1;
        stopQuotesLoop();
        quoteSequence = null;
        quoteUpdateErrors = 0;
        runQuotesLongPoll(quotesLoopGeneration);
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
    dom.loadHistory.addEventListener('click', loadHistory);
    dom.symbolSelect.addEventListener('change', onSelectedSymbolChanged);
    dom.adapterUrl.addEventListener('change', () => {
        quotesLoopGeneration += 1;
        stopQuotesLoop();
        loadConfig();
        loadSymbols();
        loadHealth();
    });

    setInterval(loadHealth, HEALTH_POLL_MS);
    loadConfig().then(loadSymbols).then(loadHealth);
})();

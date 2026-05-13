(function () {
    'use strict';

    const QUOTES_POLL_MS = 2000;
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
        live: {
            symbol: document.getElementById('live-symbol'),
            bid: document.getElementById('live-bid'),
            ask: document.getElementById('live-ask'),
            midpoint: document.getElementById('live-midpoint'),
            ts: document.getElementById('live-ts'),
            updated: document.getElementById('live-updated')
        }
    };

    const chart = LightweightCharts.createChart(dom.chartContainer, {
        layout: { background: { color: '#0e1116' }, textColor: '#d9e0e8' },
        grid: {
            vertLines: { color: '#1a2029' },
            horzLines: { color: '#1a2029' }
        },
        timeScale: { timeVisible: true, secondsVisible: true, borderColor: '#2a3340' },
        rightPriceScale: { borderColor: '#2a3340' },
        crosshair: { mode: LightweightCharts.CrosshairMode.Normal }
    });

    const candleSeries = chart.addCandlestickSeries({
        upColor: '#4ade80',
        downColor: '#f87171',
        borderUpColor: '#4ade80',
        borderDownColor: '#f87171',
        wickUpColor: '#4ade80',
        wickDownColor: '#f87171'
    });

    const volumeSeries = chart.addHistogramSeries({
        priceFormat: { type: 'volume' },
        priceScaleId: 'volume',
        color: '#3b4453'
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

    function setStatus(message, tone) {
        dom.statusLine.textContent = message;
        dom.footer.classList.remove('ok', 'error');
        if (tone === 'ok' || tone === 'error') {
            dom.footer.classList.add(tone);
        }
    }

    function adapterBase() {
        return dom.adapterUrl.value.trim().replace(/\/+$/, '');
    }

    async function fetchJson(path) {
        const url = adapterBase() + path;
        const response = await fetch(url, { mode: 'cors' });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status} from ${path}`);
        }
        return response.json();
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
        setStatus('Loading symbols...');
        try {
            const data = await fetchJson('/symbols');
            const symbols = (data && Array.isArray(data.symbols)) ? data.symbols : [];
            dom.symbolSelect.innerHTML = '';
            if (symbols.length === 0) {
                const opt = document.createElement('option');
                opt.value = '';
                opt.textContent = '(no symbols)';
                dom.symbolSelect.appendChild(opt);
                setStatus('No symbols indexed yet — make sure the adapter has data flowing.', 'error');
                return;
            }
            for (const entry of symbols) {
                const opt = document.createElement('option');
                opt.value = entry.symbol;
                opt.textContent = entry.symbol;
                dom.symbolSelect.appendChild(opt);
            }
            setStatus(`Loaded ${symbols.length} symbol(s)`, 'ok');
            restartQuotesPolling();
        } catch (err) {
            setStatus(`Failed to load symbols: ${err.message}`, 'error');
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
                setStatus(`No data for ${symbol} in window`, 'error');
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
                    color: close >= open ? 'rgba(74, 222, 128, 0.5)' : 'rgba(248, 113, 113, 0.5)'
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

    async function pollQuotes() {
        const symbol = dom.symbolSelect.value;
        if (!symbol) {
            return;
        }
        try {
            const data = await fetchJson(`/quotes?symbols=${encodeURIComponent(symbol)}`);
            const quote = (data.quotes || []).find(q => q.symbol === symbol);
            if (!quote) {
                dom.live.symbol.textContent = symbol;
                dom.live.bid.textContent = '—';
                dom.live.ask.textContent = '—';
                dom.live.midpoint.textContent = '—';
                dom.live.ts.textContent = '—';
                dom.live.updated.textContent = new Date().toLocaleTimeString();
                return;
            }
            dom.live.symbol.textContent = quote.symbol;
            dom.live.bid.textContent = formatMicros(quote.bid_micros);
            dom.live.ask.textContent = formatMicros(quote.ask_micros);
            dom.live.midpoint.textContent = formatMicros(quote.midpoint_micros);
            dom.live.ts.textContent = quote.event_ts_ms == null
                ? '—'
                : new Date(quote.event_ts_ms).toISOString();
            dom.live.updated.textContent = new Date().toLocaleTimeString();
        } catch (err) {
            dom.live.updated.textContent = `error: ${err.message}`;
        }
    }

    function formatMicros(micros) {
        if (micros == null) {
            return '—';
        }
        const dollars = Number(micros) / 1_000_000;
        return dollars.toFixed(4);
    }

    function restartQuotesPolling() {
        if (quotesTimer != null) {
            clearInterval(quotesTimer);
        }
        pollQuotes();
        quotesTimer = setInterval(pollQuotes, QUOTES_POLL_MS);
    }

    dom.refreshSymbols.addEventListener('click', () => {
        loadConfig();
        loadSymbols();
    });
    dom.loadHistory.addEventListener('click', loadHistory);
    dom.symbolSelect.addEventListener('change', () => {
        restartQuotesPolling();
        loadHistory();
    });
    dom.adapterUrl.addEventListener('change', () => {
        loadConfig();
        loadSymbols();
    });

    loadConfig().then(loadSymbols);
})();

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
    const SEMANTIC_MAP_DEFAULT_LIMIT = 200;
    const SEMANTIC_MAP_MAX_LIMIT = 500;
    const SEMANTIC_RENDER_LEAF_LIMIT = 120;
    const SEMANTIC_SEARCH_DEBOUNCE_MS = 300;
    const OPERATOR_LATENCY_BUDGET_MS = 30000;
    const FALLBACK_RESOLUTIONS = ['1S', '5S', '30S', '1', '5', '15', '60'];
    const COMPACT_NUMBER_FORMAT = new Intl.NumberFormat(undefined, {
        notation: 'compact',
        maximumFractionDigits: 1
    });

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
        traderQuoteMode: document.getElementById('trader-quote-mode'),
        traderSymbol: document.getElementById('trader-symbol'),
        traderMarketStatus: document.getElementById('trader-market-status'),
        traderBid: document.getElementById('trader-bid'),
        traderAsk: document.getElementById('trader-ask'),
        traderMidpoint: document.getElementById('trader-midpoint'),
        traderSseStatus: document.getElementById('trader-sse-status'),
        traderSourceEvent: document.getElementById('trader-source-event'),
        productReadinessState: document.getElementById('product-readiness-state'),
        productReadinessReasons: document.getElementById('product-readiness-reasons'),
        marketSourceKind: document.getElementById('market-source-kind'),
        marketLiveObserved: document.getElementById('market-live-observed'),
        marketSyntheticState: document.getElementById('market-synthetic-state'),
        researchFeatureSelect: document.getElementById('research-feature-select'),
        researchFeatureLimit: document.getElementById('research-feature-limit'),
        researchFeatureWindow: document.getElementById('research-feature-window'),
        researchExportCsv: document.getElementById('research-export-csv'),
        researchExportLink: document.getElementById('research-export-link'),
        featureCount: document.getElementById('feature-count'),
        featureList: document.getElementById('feature-list'),
        semanticTab: document.getElementById('semantic-tab'),
        semanticMapCount: document.getElementById('semantic-map-count'),
        semanticGroupBy: document.getElementById('semantic-group-by'),
        semanticRenderMode: document.getElementById('semantic-render-mode'),
        semanticStatusFilter: document.getElementById('semantic-status-filter'),
        semanticLimit: document.getElementById('semantic-limit'),
        semanticTagFilter: document.getElementById('semantic-tag-filter'),
        semanticSearch: document.getElementById('semantic-search'),
        semanticRefresh: document.getElementById('semantic-refresh'),
        semanticDrillup: document.getElementById('semantic-drillup'),
        semanticMapState: document.getElementById('semantic-map-state'),
        semanticGroupSummary: document.getElementById('semantic-group-summary'),
        semanticTreemap: document.getElementById('semantic-treemap'),
        semanticDetailMarket: document.getElementById('semantic-detail-market'),
        semanticDetailTitle: document.getElementById('semantic-detail-title'),
        semanticDetailStatus: document.getElementById('semantic-detail-status'),
        semanticDetailGroup: document.getElementById('semantic-detail-group'),
        semanticDetailSector: document.getElementById('semantic-detail-sector'),
        semanticDetailSubsector: document.getElementById('semantic-detail-subsector'),
        semanticDetailEventType: document.getElementById('semantic-detail-event-type'),
        semanticDetailTags: document.getElementById('semantic-detail-tags'),
        semanticDetailConfidence: document.getElementById('semantic-detail-confidence'),
        semanticDetailOpenInterest: document.getElementById('semantic-detail-open-interest'),
        semanticDetailFreshness: document.getElementById('semantic-detail-freshness'),
        semanticDetailQuote: document.getElementById('semantic-detail-quote'),
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
        runtimePipelineStatus: document.getElementById('runtime-pipeline-status'),
        runtimeCursorLag: document.getElementById('runtime-cursor-lag'),
        runtimeQuoteStreams: document.getElementById('runtime-quote-streams'),
        runtimeQuoteWaits: document.getElementById('runtime-quote-waits'),
        operatorReleaseProfile: document.getElementById('operator-release-profile'),
        operatorProductReadiness: document.getElementById('operator-product-readiness'),
        operatorDataSourceSummary: document.getElementById('operator-data-source-summary'),
        operatorFeatureSourceSummary: document.getElementById('operator-feature-source-summary'),
        operatorFreshnessSummary: document.getElementById('operator-freshness-summary'),
        operatorPipelineCounts: document.getElementById('operator-pipeline-counts'),
        operatorE2eLatency: document.getElementById('operator-e2e-latency'),
        operatorLatencyBudget: document.getElementById('operator-latency-budget'),
        freshnessAgeMs: document.getElementById('freshness-age-ms'),
        freshnessEventTsMs: document.getElementById('freshness-event-ts-ms'),
        freshnessSymbol: document.getElementById('freshness-symbol'),
        freshnessFeatureName: document.getElementById('freshness-feature-name'),
        freshnessSourceEventId: document.getElementById('freshness-source-event-id'),
        freshnessStoreSequence: document.getElementById('freshness-store-sequence'),
        freshnessStatusClass: document.getElementById('freshness-status-class'),
        featureplantLagEvents: document.getElementById('featureplant-lag-events'),
        canonicalMaxSeq: document.getElementById('canonical-max-seq'),
        operatorPlanState: document.getElementById('operator-plan-state'),
        operatorControlEnabled: document.getElementById('operator-control-enabled'),
        operatorDbStatus: document.getElementById('operator-db-status'),
        operatorKalshiStatus: document.getElementById('operator-kalshi-status'),
        operatorAuthStatus: document.getElementById('operator-auth-status'),
        operatorDataSource: document.getElementById('operator-data-source'),
        operatorDeployProfile: document.getElementById('operator-deploy-profile'),
        operatorKalshiKeyId: document.getElementById('operator-kalshi-key-id'),
        operatorKeyPath: document.getElementById('operator-key-path'),
        operatorPrivateKeyPemPresent: document.getElementById('operator-private-key-pem-present'),
        operatorMarketMode: document.getElementById('operator-market-mode'),
        operatorMaxMarkets: document.getElementById('operator-max-markets'),
        operatorTickers: document.getElementById('operator-tickers'),
        operatorS3Bucket: document.getElementById('operator-s3-bucket'),
        operatorS3Region: document.getElementById('operator-s3-region'),
        operatorS3Prefix: document.getElementById('operator-s3-prefix'),
        operatorS3DeleteAfterUpload: document.getElementById('operator-s3-delete-after-upload'),
        operatorDbUrl: document.getElementById('operator-db-url'),
        operatorDbUser: document.getElementById('operator-db-user'),
        operatorDbPasswordPresent: document.getElementById('operator-db-password-present'),
        operatorBasicAuthUser: document.getElementById('operator-basic-auth-user'),
        operatorBasicAuthPasswordPresent: document.getElementById('operator-basic-auth-password-present'),
        operatorImage: document.getElementById('operator-image'),
        operatorRef: document.getElementById('operator-ref'),
        operatorGeneratePlan: document.getElementById('operator-generate-plan'),
        operatorWarnings: document.getElementById('operator-warnings'),
        operatorChecklist: document.getElementById('operator-checklist'),
        operatorCommandPlan: document.getElementById('operator-command-plan'),
        operatorEnvPlan: document.getElementById('operator-env-plan'),
        demoRunState: document.getElementById('demo-run-state'),
        demoRunId: document.getElementById('demo-run-id'),
        demoRunMode: document.getElementById('demo-run-mode'),
        demoRunRelease: document.getElementById('demo-run-release'),
        demoRunDataSource: document.getElementById('demo-run-data-source'),
        demoRunDashboardSource: document.getElementById('demo-run-dashboard-source'),
        demoRunLiveSource: document.getElementById('demo-run-live-source'),
        demoRunLiveCredentials: document.getElementById('demo-run-live-credentials'),
        demoRunCatalogBounds: document.getElementById('demo-run-catalog-bounds'),
        demoRunS3Preflight: document.getElementById('demo-run-s3-preflight'),
        demoRunFreshness: document.getElementById('demo-run-freshness'),
        demoRunEvidence: document.getElementById('demo-run-evidence'),
        demoRunError: document.getElementById('demo-run-error'),
        demoRunAction: document.getElementById('demo-run-action'),
        demoRunConfirmRow: document.getElementById('demo-run-confirm-row'),
        demoRunConfirmLive: document.getElementById('demo-run-confirm-live'),
        demoRunStart: document.getElementById('demo-run-start'),
        demoRunRefresh: document.getElementById('demo-run-refresh'),
        demoRunOutput: document.getElementById('demo-run-output'),
        catalogSyncState: document.getElementById('catalog-sync-state'),
        catalogSyncId: document.getElementById('catalog-sync-id'),
        catalogSyncCounts: document.getElementById('catalog-sync-counts'),
        catalogSyncConfig: document.getElementById('catalog-sync-config'),
        catalogSyncError: document.getElementById('catalog-sync-error'),
        catalogSyncSeries: document.getElementById('catalog-sync-series'),
        catalogSyncStatus: document.getElementById('catalog-sync-status'),
        catalogSyncLimit: document.getElementById('catalog-sync-limit'),
        catalogSyncMaxPages: document.getElementById('catalog-sync-max-pages'),
        catalogSyncMaxTickers: document.getElementById('catalog-sync-max-tickers'),
        catalogSyncMveFilter: document.getElementById('catalog-sync-mve-filter'),
        catalogSyncDryRun: document.getElementById('catalog-sync-dry-run'),
        catalogSyncStart: document.getElementById('catalog-sync-start'),
        catalogSyncRefresh: document.getElementById('catalog-sync-refresh'),
        catalogSyncOutput: document.getElementById('catalog-sync-output'),
        semanticRunState: document.getElementById('semantic-run-state'),
        semanticRunId: document.getElementById('semantic-run-id'),
        semanticRunCounts: document.getElementById('semantic-run-counts'),
        semanticRunConfig: document.getElementById('semantic-run-config'),
        semanticRunError: document.getElementById('semantic-run-error'),
        semanticRunModel: document.getElementById('semantic-run-model'),
        semanticRunFallbackModel: document.getElementById('semantic-run-fallback-model'),
        semanticRunTaxonomy: document.getElementById('semantic-run-taxonomy'),
        semanticRunMarketTicker: document.getElementById('semantic-run-market-ticker'),
        semanticRunSeriesTicker: document.getElementById('semantic-run-series-ticker'),
        semanticRunMarketStatus: document.getElementById('semantic-run-market-status'),
        semanticRunMaxMarkets: document.getElementById('semantic-run-max-markets'),
        semanticRunMaxTokens: document.getElementById('semantic-run-max-tokens'),
        semanticRunMaxRetries: document.getElementById('semantic-run-max-retries'),
        semanticRunBudgetUsd: document.getElementById('semantic-run-budget-usd'),
        semanticRunEstimatedCost: document.getElementById('semantic-run-estimated-cost'),
        semanticRunOpenRouterKey: document.getElementById('semantic-run-openrouter-key'),
        semanticRunOpenRouterKeyPresent: document.getElementById('semantic-run-openrouter-key-present'),
        semanticRunDryRun: document.getElementById('semantic-run-dry-run'),
        semanticRunOverwrite: document.getElementById('semantic-run-overwrite'),
        semanticRunAllowPaidFallback: document.getElementById('semantic-run-allow-paid-fallback'),
        semanticRunStart: document.getElementById('semantic-run-start'),
        catalogSyncUseForSemantic: document.getElementById('catalog-sync-use-for-semantic'),
        semanticRunFromCatalog: document.getElementById('semantic-run-from-catalog'),
        semanticRunRefresh: document.getElementById('semantic-run-refresh'),
        semanticRunOutput: document.getElementById('semantic-run-output'),
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
    let semanticSearchTimer = null;
    let semanticMapGeneration = 0;
    let lastSemanticMapBody = null;
    let semanticActiveGroupKey = '';
    let catalogSyncStatusTimer = null;
    let semanticRunStatusTimer = null;
    let demoRunStatusTimer = null;
    let lastCatalogSyncCompletion = '';
    let lastSemanticRunCompletion = '';
    let lastHealthBody = null;
    let lastOpsPipeline = null;
    let lastOpsLatency = null;
    let lastResearchOutputs = [];

    dom.semanticDrillup.disabled = true;

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
            button.title = `${entry.symbol} / ${entry.status || '-'}`;
            button.setAttribute('aria-label', button.title);
            button.innerHTML = `<strong class="ticker-text">${escapeHtml(entry.symbol)}</strong>` +
                `<span>${escapeHtml(entry.status || '-')}</span>`;
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
        dom.traderMarketStatus.textContent = entry.status || '-';
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
        const limit = Math.max(1, Math.min(500, Number(dom.researchFeatureLimit?.value || 8) || 8));
        const windowSeconds = Number(dom.researchFeatureWindow?.value || 0) || 0;
        const params = [
            `symbol=${encodeURIComponent(symbol)}`,
            `feature=${encodeURIComponent(feature)}`,
            `limit=${limit}`
        ];
        if (windowSeconds > 0) {
            params.push(`from_ms=${Date.now() - windowSeconds * 1000}`);
        }
        try {
            const data = await fetchJsonFromBase(
                base,
                `/features?${params.join('&')}`
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
        lastResearchOutputs = outputs.slice();
        dom.featureCount.textContent = String(outputs.length);
        dom.researchExportLink.style.display = 'none';
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

    function exportResearchCsv() {
        if (lastResearchOutputs.length === 0) {
            dom.researchExportLink.style.display = 'none';
            return;
        }
        const rows = [['feature_name', 'market_ticker', 'event_ts_ms', 'source_event_id', 'values_json']];
        for (const output of lastResearchOutputs) {
            rows.push([
                output.feature_name || '',
                output.market_ticker || '',
                output.event_ts_ms == null ? '' : String(output.event_ts_ms),
                output.source_event_id || '',
                JSON.stringify(output.values || {})
            ]);
        }
        const csv = rows.map(row => row.map(escapeCsv).join(',')).join('\n') + '\n';
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
        if (dom.researchExportLink.href && dom.researchExportLink.href.startsWith('blob:')) {
            URL.revokeObjectURL(dom.researchExportLink.href);
        }
        dom.researchExportLink.href = URL.createObjectURL(blob);
        dom.researchExportLink.download =
            `${dom.symbolSelect.value || 'market'}-${dom.researchFeatureSelect.value || 'feature'}-features.csv`;
        dom.researchExportLink.style.display = 'inline-block';
    }

    function escapeCsv(value) {
        const text = value == null ? '' : String(value);
        return `"${text.replaceAll('"', '""')}"`;
    }

    async function loadSemanticMap() {
        const generation = ++semanticMapGeneration;
        const base = adapterBase();
        const params = semanticMapParams();
        dom.semanticMapState.textContent = 'Loading semantic metadata...';
        dom.semanticMapState.className = 'semantic-map-state';
        dom.semanticTreemap.innerHTML = '';
        try {
            const body = await fetchJsonFromBase(base, `/api/semantic-metadata/treemap?${params.join('&')}`);
            if (generation !== semanticMapGeneration || base !== adapterBase()) {
                return;
            }
            renderSemanticMap(body);
        } catch (err) {
            if (generation !== semanticMapGeneration || base !== adapterBase()) {
                return;
            }
            renderSemanticMapError(err.message);
        }
    }

    function semanticMapParams() {
        const limit = Math.max(
            1,
            Math.min(SEMANTIC_MAP_MAX_LIMIT, Number(dom.semanticLimit.value || SEMANTIC_MAP_DEFAULT_LIMIT) || 1)
        );
        dom.semanticLimit.value = String(limit);
        const params = [
            `group_by=${encodeURIComponent(dom.semanticGroupBy.value || 'sector')}`,
            `limit=${limit}`
        ];
        const status = dom.semanticStatusFilter.value.trim();
        const tag = dom.semanticTagFilter.value.trim();
        const search = dom.semanticSearch.value.trim();
        if (status) {
            params.push(`status=${encodeURIComponent(status)}`);
        }
        if (tag) {
            params.push(`tag=${encodeURIComponent(tag)}`);
        }
        if (search) {
            params.push(`q=${encodeURIComponent(search)}`);
        }
        return params;
    }

    function renderSemanticMap(body) {
        const groups = Array.isArray(body?.groups) ? body.groups : [];
        lastSemanticMapBody = body || null;
        const leaves = semanticLeaves(groups);
        dom.semanticMapCount.textContent = String(body?.count ?? leaves.length);
        dom.semanticTreemap.innerHTML = '';
        dom.semanticGroupSummary.innerHTML = '';
        dom.semanticDrillup.disabled = !semanticActiveGroupKey;
        if (body?.status === 'disabled') {
            dom.semanticMapState.textContent = 'Semantic metadata disabled';
            renderSemanticDetail(null);
            return;
        }
        if (body?.status === 'unavailable') {
            dom.semanticMapState.textContent = `Semantic metadata unavailable: ${body.error || 'unknown'}`;
            dom.semanticMapState.className = 'semantic-map-state stale';
            renderSemanticDetail(null);
            return;
        }
        if (leaves.length === 0) {
            dom.semanticMapState.textContent = 'No semantic metadata rows';
            renderSemanticDetail(null);
            return;
        }
        const groupBy = body.group_by || dom.semanticGroupBy.value || 'sector';
        renderSemanticGroupSummary(groups);
        const activeGroup = semanticActiveGroupKey
            ? groups.find(group => String(group.key || 'unknown') === semanticActiveGroupKey)
            : null;
        if (semanticActiveGroupKey && !activeGroup) {
            semanticActiveGroupKey = '';
            dom.semanticDrillup.disabled = true;
        }
        const mode = dom.semanticRenderMode.value || 'groups';
        if (!semanticActiveGroupKey && mode === 'groups') {
            dom.semanticMapState.textContent =
                `${groups.length} group(s) / ${leaves.length} market(s) grouped by ${groupBy}`;
            renderSemanticGroupTiles(groups);
            const firstGroup = groups.slice().sort((a, b) => Number(b.value || 0) - Number(a.value || 0))[0];
            renderSemanticDetailFromGroup(firstGroup);
            return;
        }
        const selectedGroups = activeGroup ? [activeGroup] : groups;
        const limit = mode === 'top_markets'
            ? Math.min(60, SEMANTIC_RENDER_LEAF_LIMIT)
            : SEMANTIC_RENDER_LEAF_LIMIT;
        const displayLeaves = semanticRenderableLeaves(selectedGroups, limit);
        const hiddenCount = Number(displayLeaves.find(leaf => leaf.is_other)?.hidden_count || 0);
        dom.semanticMapState.textContent =
            `${displayLeaves.length} rendered / ${leaves.length} market(s)` +
            `${hiddenCount > 0 ? ` / ${hiddenCount} in Other` : ''}` +
            `${activeGroup ? ` / ${activeGroup.label || activeGroup.key}` : ` / grouped by ${groupBy}`}`;
        const fragment = document.createDocumentFragment();
        for (const rect of layoutSemanticLeafTreemap(displayLeaves)) {
            const tile = document.createElement('button');
            const sizeClass = semanticTileSizeClass(rect);
            const title = semanticTileTitle(rect.leaf, rect.groupKey);
            tile.type = 'button';
            tile.className = `semantic-tile ${sizeClass} semantic-${semanticStatusClass(rect.leaf.semantic_status)}` +
                `${rect.leaf.is_other ? ' semantic-other-tile' : ''}`;
            tile.dataset.market = rect.leaf.market_ticker || '';
            tile.title = title;
            tile.setAttribute('aria-label', title.replace(/\n/g, '; '));
            tile.style.left = `${rect.x}%`;
            tile.style.top = `${rect.y}%`;
            tile.style.width = `${rect.width}%`;
            tile.style.height = `${rect.height}%`;
            tile.style.background = semanticTileColor(rect.leaf);
            tile.innerHTML = semanticTileMarkup(rect.leaf, rect.groupKey, sizeClass);
            tile.addEventListener('click', () => {
                if (rect.leaf.market_ticker) {
                    selectSemanticMarket(rect.leaf.market_ticker, rect.groupKey);
                } else {
                    renderSemanticDetailFromLeaf(rect.leaf);
                }
            });
            fragment.appendChild(tile);
        }
        dom.semanticTreemap.appendChild(fragment);
        const first = displayLeaves[0];
        if (first) {
            renderSemanticDetailFromLeaf(first);
        }
    }

    function semanticLeaves(groups) {
        const leaves = [];
        for (const group of groups) {
            for (const leaf of Array.isArray(group.leaves) ? group.leaves : []) {
                leaves.push(Object.assign({ group_key: group.key || 'unknown' }, leaf));
            }
        }
        return leaves;
    }

    function renderSemanticGroupSummary(groups) {
        const ordered = groups.slice().sort((a, b) => Number(b.value || 0) - Number(a.value || 0)).slice(0, 6);
        for (const group of ordered) {
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'semantic-group-chip';
            chip.innerHTML =
                `<strong>${escapeHtml(group.label || group.key || 'unknown')}</strong>` +
                `<span>${Number(group.count || 0)} / ${formatCompactNumber(group.value || 0)}</span>`;
            chip.addEventListener('click', () => selectSemanticGroup(group.key || 'unknown'));
            dom.semanticGroupSummary.appendChild(chip);
        }
    }

    function renderSemanticGroupTiles(groups) {
        const sourceGroups = groups.map(group => ({
            key: group.key || 'unknown',
            label: group.label || group.key || 'unknown',
            count: Number(group.count || 0),
            value: Math.max(1, Number(group.value || 0)),
            generated_count: Number(group.generated_count || 0),
            review_required_count: Number(group.review_required_count || 0),
            average_confidence: group.average_confidence
        })).filter(group => group.count > 0);
        const groupRects = sliceDice(
            sourceGroups.map(group => ({ item: group, value: group.value })),
            { x: 0, y: 0, width: 100, height: 100 },
            true
        );
        for (const groupRect of groupRects) {
            const group = groupRect.item;
            const tile = document.createElement('button');
            tile.type = 'button';
            tile.className = 'semantic-tile semantic-group-tile';
            tile.title = `${group.label}\n${group.count} market(s) / ${formatCompactNumber(group.value)}`;
            tile.setAttribute('aria-label', tile.title.replace(/\n/g, '; '));
            tile.style.left = `${groupRect.x}%`;
            tile.style.top = `${groupRect.y}%`;
            tile.style.width = `${groupRect.width}%`;
            tile.style.height = `${groupRect.height}%`;
            tile.style.background = semanticGroupTileColor(group);
            tile.innerHTML =
                `<strong>${escapeHtml(group.label)}</strong>` +
                `<span>${group.count} market(s)</span>` +
                `<small>${formatCompactNumber(group.value)} value / ${confidenceText(group.average_confidence)}</small>`;
            tile.addEventListener('click', () => selectSemanticGroup(group.key));
            dom.semanticTreemap.appendChild(tile);
        }
    }

    function semanticRenderableLeaves(groups, limit) {
        const leaves = semanticLeaves(groups)
            .filter(leaf => leaf && leaf.market_ticker)
            .map(leaf => Object.assign({}, leaf, {
                value: Math.max(1, Number(leaf.value || 0))
            }))
            .sort((a, b) => Number(b.value || 0) - Number(a.value || 0));
        if (leaves.length <= limit) {
            return leaves;
        }
        const visible = leaves.slice(0, Math.max(1, limit - 1));
        const hidden = leaves.slice(visible.length);
        visible.push(semanticOtherLeaf(hidden));
        return visible;
    }

    function semanticOtherLeaf(hidden) {
        const value = hidden.reduce((sum, leaf) => sum + Math.max(1, Number(leaf.value || 0)), 0);
        const groupKeys = Array.from(new Set(hidden.map(leaf => leaf.group_key || 'unknown')));
        const confidenceValues = hidden
            .map(leaf => Number(leaf.metadata_confidence))
            .filter(value => Number.isFinite(value));
        const confidence = confidenceValues.length === 0
            ? null
            : confidenceValues.reduce((sum, value) => sum + value, 0) / confidenceValues.length;
        return {
            is_other: true,
            label: `Other (${hidden.length})`,
            market_ticker: '',
            group_key: groupKeys.length === 1 ? groupKeys[0] : 'multiple groups',
            value,
            semantic_status: 'other',
            metadata_confidence: confidence,
            tags: [],
            quote: null,
            hidden_count: hidden.length
        };
    }

    function layoutSemanticLeafTreemap(leaves) {
        const rects = sliceDice(
            leaves.map(leaf => ({ item: leaf, value: Math.max(1, Number(leaf.value || 0)) })),
            { x: 0, y: 0, width: 100, height: 100 },
            true
        ).map(rect => ({
            groupKey: rect.item.group_key || 'unknown',
            leaf: rect.item,
            x: rect.x,
            y: rect.y,
            width: rect.width,
            height: rect.height
        }));
        return rects;
    }

    function semanticGroupTileColor(group) {
        const confidence = Math.max(0, Math.min(1, Number(group.average_confidence)));
        const confidenceValue = Number.isFinite(confidence) ? confidence : 0.5;
        const hueOffset = semanticStableHueOffset(group.key || group.label || '');
        return `hsl(${154 + Math.round(confidenceValue * 18) + hueOffset}, 42%, ${Math.round(28 + confidenceValue * 11)}%)`;
    }

    function sliceDice(items, rect, horizontal) {
        if (items.length === 0) {
            return [];
        }
        const total = items.reduce((sum, item) => sum + Math.max(1, Number(item.value || 0)), 0);
        let cursor = horizontal ? rect.x : rect.y;
        const output = [];
        for (let index = 0; index < items.length; index++) {
            const item = items[index];
            const remaining = horizontal
                ? rect.x + rect.width - cursor
                : rect.y + rect.height - cursor;
            const size = index === items.length - 1
                ? Math.max(0, remaining)
                : (horizontal ? rect.width : rect.height) * (Math.max(1, Number(item.value || 0)) / total);
            const next = horizontal
                ? { item: item.item, x: cursor, y: rect.y, width: size, height: rect.height }
                : { item: item.item, x: rect.x, y: cursor, width: rect.width, height: size };
            output.push(next);
            cursor += size;
        }
        return output;
    }

    function insetRect(rect, padding) {
        const xPadding = Math.min(padding, rect.width / 4);
        const yPadding = Math.min(padding, rect.height / 4);
        return {
            x: rect.x + xPadding,
            y: rect.y + yPadding,
            width: Math.max(0, rect.width - xPadding * 2),
            height: Math.max(0, rect.height - yPadding * 2)
        };
    }

    async function selectSemanticMarket(marketTicker, groupKey) {
        if (!marketTicker) {
            return;
        }
        ensureSymbolOption(marketTicker);
        dom.symbolSelect.value = marketTicker;
        renderSemanticDetail({ market: marketTicker, group: groupKey });
        onSelectedSymbolChanged();
        await loadSemanticMarketDetails(marketTicker, groupKey);
    }

    function selectSemanticGroup(groupKey) {
        semanticActiveGroupKey = String(groupKey || 'unknown');
        dom.semanticDrillup.disabled = false;
        if (lastSemanticMapBody) {
            renderSemanticMap(lastSemanticMapBody);
        }
    }

    function clearSemanticGroupDrilldown() {
        semanticActiveGroupKey = '';
        dom.semanticDrillup.disabled = true;
        if (lastSemanticMapBody) {
            renderSemanticMap(lastSemanticMapBody);
        } else {
            loadSemanticMap();
        }
    }

    function ensureSymbolOption(symbol) {
        if (!Array.from(dom.symbolSelect.options).some(option => option.value === symbol)) {
            const opt = document.createElement('option');
            opt.value = symbol;
            opt.textContent = symbol;
            dom.symbolSelect.appendChild(opt);
        }
    }

    async function loadSemanticMarketDetails(marketTicker, groupKey) {
        const base = adapterBase();
        const params = [
            `market_ticker=${encodeURIComponent(marketTicker)}`,
            'limit=1'
        ];
        const status = dom.semanticStatusFilter.value.trim();
        if (status) {
            params.push(`status=${encodeURIComponent(status)}`);
        }
        try {
            const body = await fetchJsonFromBase(base, `/api/semantic-metadata/markets?${params.join('&')}`);
            if (marketTicker !== dom.symbolSelect.value || base !== adapterBase()) {
                return;
            }
            const row = Array.isArray(body.markets) ? body.markets[0] : null;
            renderSemanticDetailFromMarket(row, groupKey);
        } catch (err) {
            if (marketTicker !== dom.symbolSelect.value || base !== adapterBase()) {
                return;
            }
            dom.semanticDetailMarket.textContent = marketTicker;
            dom.semanticDetailGroup.textContent = groupKey || '-';
            dom.semanticDetailConfidence.textContent = `unavailable: ${err.message}`;
            dom.semanticDetailQuote.textContent = '-';
        }
    }

    function renderSemanticDetailFromLeaf(leaf) {
        renderSemanticDetail({
            market: leaf.market_ticker,
            title: leaf.title || leaf.label,
            status: leaf.market_status || leaf.semantic_status,
            group: leaf.group_key || '-',
            sector: leaf.sector,
            subsector: leaf.subsector,
            eventType: leaf.event_type,
            tags: Array.isArray(leaf.tags) ? leaf.tags.join(', ') : '',
            confidence: confidenceText(leaf.metadata_confidence),
            openInterest: leaf.open_interest == null ? null : formatCompactNumber(leaf.open_interest),
            freshness: semanticFreshnessText(leaf.quote),
            quote: semanticQuoteText(leaf.quote)
        });
    }

    function renderSemanticDetailFromGroup(group) {
        if (!group) {
            renderSemanticDetail(null);
            return;
        }
        renderSemanticDetail({
            market: '-',
            title: group.label || group.key || 'unknown',
            status: `${Number(group.generated_count || 0)} generated / ${Number(group.review_required_count || 0)} review`,
            group: group.key || 'unknown',
            sector: dom.semanticGroupBy.value === 'sector' ? group.key : '-',
            subsector: dom.semanticGroupBy.value === 'subsector' ? group.key : '-',
            eventType: dom.semanticGroupBy.value === 'event_type' ? group.key : '-',
            tags: dom.semanticGroupBy.value === 'tag' ? group.key : '-',
            confidence: confidenceText(group.average_confidence),
            openInterest: formatCompactNumber(group.value || 0),
            freshness: `${Number(group.count || 0)} market(s)`,
            quote: '-'
        });
    }

    function renderSemanticDetailFromMarket(row, groupKey) {
        if (!row) {
            renderSemanticDetail(null);
            return;
        }
        const metadata = row.semantic_metadata || {};
        renderSemanticDetail({
            market: row.market_ticker,
            title: row.title,
            status: `${row.status || '-'} / ${metadata.status || '-'}`,
            group: groupKey || metadata.sector || metadata.event_type || '-',
            sector: metadata.sector,
            subsector: metadata.subsector,
            eventType: metadata.event_type,
            tags: Array.isArray(metadata.tags) ? metadata.tags.join(', ') : '',
            confidence: `${confidenceText(metadata.confidence)} / ${metadata.status || '-'}`,
            openInterest: row.quote?.open_interest == null ? null : formatCompactNumber(row.quote.open_interest),
            freshness: semanticFreshnessText(row.quote),
            quote: semanticQuoteText(row.quote)
        });
    }

    function renderSemanticDetail(detail) {
        dom.semanticDetailMarket.textContent = detail?.market || '-';
        dom.semanticDetailTitle.textContent = detail?.title || '-';
        dom.semanticDetailStatus.textContent = detail?.status || '-';
        dom.semanticDetailGroup.textContent = detail?.group || '-';
        dom.semanticDetailSector.textContent = detail?.sector || '-';
        dom.semanticDetailSubsector.textContent = detail?.subsector || '-';
        dom.semanticDetailEventType.textContent = detail?.eventType || '-';
        dom.semanticDetailTags.textContent = detail?.tags || '-';
        dom.semanticDetailConfidence.textContent = detail?.confidence || '-';
        dom.semanticDetailOpenInterest.textContent = detail?.openInterest || '-';
        dom.semanticDetailFreshness.textContent = detail?.freshness || '-';
        dom.semanticDetailQuote.textContent = detail?.quote || '-';
    }

    function semanticFreshnessText(quote) {
        if (!quote) {
            return '-';
        }
        const state = quote.freshness_status || 'unknown';
        const age = quote.latest_state_age_ms == null ? '-' : formatAge(Number(quote.latest_state_age_ms));
        return `${state} / ${age}`;
    }

    function renderSemanticMapError(message) {
        dom.semanticMapCount.textContent = '0';
        dom.semanticMapState.textContent = `Semantic metadata unavailable: ${message}`;
        dom.semanticMapState.className = 'semantic-map-state stale';
        dom.semanticGroupSummary.innerHTML = '';
        dom.semanticTreemap.innerHTML = '<div class="semantic-empty">semantic metadata unavailable</div>';
        renderSemanticDetail(null);
    }

    function scheduleSemanticMapLoad() {
        if (semanticSearchTimer != null) {
            clearTimeout(semanticSearchTimer);
        }
        semanticSearchTimer = setTimeout(() => {
            semanticSearchTimer = null;
            loadSemanticMap();
        }, SEMANTIC_SEARCH_DEBOUNCE_MS);
    }

    function semanticStatusClass(status) {
        return String(status || 'unknown').toLowerCase().replace(/[^a-z0-9_-]/g, '-');
    }

    function semanticTileSizeClass(rect) {
        const area = Number(rect.width || 0) * Number(rect.height || 0);
        if (rect.width < 4.5 || rect.height < 4.5 || area < 32) {
            return 'semantic-tile-tiny';
        }
        if (rect.width < 9 || rect.height < 8 || area < 95) {
            return 'semantic-tile-small';
        }
        return 'semantic-tile-regular';
    }

    function semanticTileMarkup(leaf, groupKey, sizeClass) {
        if (leaf.is_other) {
            return `<strong>${escapeHtml(leaf.label || 'Other')}</strong>` +
                `<span>${escapeHtml(groupKey || '-')}</span>` +
                `<small>${Number(leaf.hidden_count || 0)} hidden market(s)</small>`;
        }
        const ticker = escapeHtml(leaf.market_ticker || '-');
        if (sizeClass === 'semantic-tile-tiny') {
            return `<strong>${ticker}</strong>`;
        }
        const confidence = escapeHtml(confidenceText(leaf.metadata_confidence));
        if (sizeClass === 'semantic-tile-small') {
            return `<strong>${ticker}</strong><small>${confidence}</small>`;
        }
        return `<strong>${ticker}</strong>` +
            `<span>${escapeHtml(leaf.label || leaf.market_ticker || '-')}</span>` +
            `<small>${escapeHtml(groupKey)} / ${confidence}</small>` +
            `<em>${escapeHtml(semanticQuoteText(leaf.quote))}</em>`;
    }

    function semanticTileTitle(leaf, groupKey) {
        if (leaf.is_other) {
            return [
                leaf.label || 'Other',
                `${Number(leaf.hidden_count || 0)} hidden market(s)`,
                groupKey || 'multiple groups'
            ].join('\n');
        }
        return [
            leaf.market_ticker || '-',
            leaf.label || '-',
            `${groupKey || 'unknown'} / ${confidenceText(leaf.metadata_confidence)}`,
            semanticQuoteText(leaf.quote)
        ].join('\n');
    }

    function semanticTileColor(leaf) {
        const status = String(leaf.semantic_status || '').toLowerCase();
        if (leaf.is_other) {
            return 'hsl(210, 20%, 30%)';
        }
        const confidence = Math.max(0, Math.min(1, Number(leaf.metadata_confidence)));
        const confidenceValue = Number.isFinite(confidence) ? confidence : 0.5;
        const hueOffset = semanticStableHueOffset(leaf.market_ticker || leaf.label || '');
        const lightness = Math.round(27 + confidenceValue * 12);
        const stale = leaf.quote && leaf.quote.freshness_status && leaf.quote.freshness_status !== 'available';
        if (status === 'failed') {
            return `hsl(${4 + hueOffset}, 42%, ${Math.max(30, lightness)}%)`;
        }
        if (status === 'review_required') {
            return `hsl(${38 + hueOffset}, 53%, ${Math.max(32, lightness)}%)`;
        }
        if (status === 'rate_limited') {
            return `hsl(${82 + hueOffset}, 18%, ${Math.max(31, lightness)}%)`;
        }
        if (stale) {
            return `hsl(${63 + hueOffset}, 22%, ${Math.max(30, lightness)}%)`;
        }
        return `hsl(${150 + Math.round(confidenceValue * 22) + hueOffset}, 49%, ${lightness}%)`;
    }

    function semanticStableHueOffset(value) {
        let hash = 0;
        for (let index = 0; index < value.length; index++) {
            hash = (hash * 31 + value.charCodeAt(index)) % 997;
        }
        return (hash % 15) - 7;
    }

    function confidenceText(value) {
        const number = Number(value);
        if (!Number.isFinite(number)) {
            return 'confidence -';
        }
        return `${Math.round(number * 100)}%`;
    }

    function semanticQuoteText(quote) {
        if (!quote) {
            return 'quote -';
        }
        const mid = formatMicros(quote.midpoint_micros);
        const openInterest = quote.open_interest == null ? '-' : formatCompactNumber(quote.open_interest);
        const age = quote.latest_state_age_ms == null ? '-' : formatAge(Number(quote.latest_state_age_ms));
        return `mid ${mid} / oi ${openInterest} / ${age}`;
    }

    function formatCompactNumber(value) {
        const number = Number(value);
        if (!Number.isFinite(number)) {
            return '-';
        }
        return COMPACT_NUMBER_FORMAT.format(number);
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
        dom.live.symbol.title = symbol || '';
        dom.live.bid.textContent = '-';
        dom.live.ask.textContent = '-';
        dom.live.midpoint.textContent = '-';
        dom.live.ts.textContent = '-';
        dom.live.age.textContent = '-';
        dom.live.sourceEvent.textContent = '-';
        dom.live.sourceEvent.title = '';
        dom.live.freshness.textContent = 'waiting';
        dom.live.updated.textContent = new Date().toLocaleTimeString();
        dom.traderSymbol.textContent = symbol || '-';
        dom.traderSymbol.title = symbol || '';
        dom.traderBid.textContent = '-';
        dom.traderAsk.textContent = '-';
        dom.traderMidpoint.textContent = '-';
        dom.traderSourceEvent.textContent = '-';
        dom.traderSourceEvent.title = '';
    }

    function renderQuote(symbol, quote, serverTsMs) {
        if (!quote) {
            renderMissingQuote(symbol);
            return;
        }
        dom.live.symbol.textContent = quote.symbol;
        dom.live.symbol.title = quote.symbol || '';
        dom.live.bid.textContent = formatMicros(quote.bid_micros);
        dom.live.ask.textContent = formatMicros(quote.ask_micros);
        dom.live.midpoint.textContent = formatMicros(quote.midpoint_micros);
        dom.live.ts.textContent = formatEventTs(quote.event_ts_ms);
        dom.live.sourceEvent.textContent = quote.source_event_id || '-';
        dom.live.sourceEvent.title = quote.source_event_id || '';
        dom.live.updated.textContent = new Date().toLocaleTimeString();
        dom.traderSymbol.textContent = quote.symbol || symbol || '-';
        dom.traderSymbol.title = quote.symbol || symbol || '';
        dom.traderBid.textContent = formatMicros(quote.bid_micros);
        dom.traderAsk.textContent = formatMicros(quote.ask_micros);
        dom.traderMidpoint.textContent = formatMicros(quote.midpoint_micros);
        dom.traderSourceEvent.textContent = quote.source_event_id || '-';
        dom.traderSourceEvent.title = quote.source_event_id || '';
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
            renderOpsTelemetry();
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

    async function loadOpsTelemetry() {
        const sourceEventId = lastHealthBody?.data_freshness?.source_event_id || '';
        try {
            lastOpsPipeline = await fetchJson('/ops/pipeline');
        } catch (err) {
            lastOpsPipeline = { status: 'unavailable', error: err.message, pipeline: { status: 'unavailable' } };
        }
        try {
            const suffix = sourceEventId ? `?source_event_id=${encodeURIComponent(sourceEventId)}` : '';
            lastOpsLatency = await fetchJson('/ops/latency' + suffix);
        } catch (err) {
            lastOpsLatency = { status: 'unavailable', error: err.message };
        }
        renderOpsTelemetry();
    }

    async function loadOperatorStatus() {
        try {
            const body = await fetchJson('/operator/status');
            renderOperatorStatus(body);
        } catch (err) {
            dom.operatorPlanState.textContent = 'unavailable';
            dom.operatorPlanState.className = 'stale';
            dom.operatorControlEnabled.textContent = err.message;
            dom.operatorDbStatus.textContent = '-';
            dom.operatorKalshiStatus.textContent = '-';
            dom.operatorAuthStatus.textContent = '-';
            dom.operatorDataSource.textContent = '-';
        }
    }

    function renderOperatorStatus(body) {
        const control = body.operator_control || {};
        const config = body.configuration || {};
        const db = config.db || {};
        const kalshi = config.kalshi || {};
        const auth = config.basic_auth || {};
        const freshness = body.data_freshness || {};
        const release = body.release || {};
        const controlText = control.enabled
            ? control.post_allowed ? 'enabled' : 'enabled / auth required'
            : 'disabled';
        dom.operatorPlanState.textContent = controlText;
        dom.operatorPlanState.className = control.post_allowed ? 'fresh' : 'stale';
        dom.operatorGeneratePlan.disabled = control.post_allowed !== true;
        dom.operatorControlEnabled.textContent = controlText;
        dom.operatorDbStatus.textContent = db.url_configured
            ? `url yes / user ${yesNo(db.user_configured)} / password ${yesNo(db.password_configured)}`
            : 'url no';
        dom.operatorKalshiStatus.textContent =
            `key ${yesNo(kalshi.key_id_configured)} / path ${yesNo(kalshi.private_key_path_configured)}` +
            ` / pem ${yesNo(kalshi.private_key_pem_configured)}`;
        dom.operatorAuthStatus.textContent = auth.enabled
            ? `enabled / user ${yesNo(auth.user_configured)} / password ${yesNo(auth.password_configured)}`
            : 'disabled';
        dom.operatorDataSource.textContent =
            `${freshness.source_kind || 'unknown'} / live ${yesNo(freshness.live_data_observed)}`;
        if (body.catalog_sync_run || body.catalog_sync) {
            renderCatalogSyncStatus(body.catalog_sync_run || body.catalog_sync);
        }
        if (body.semantic_metadata_run) {
            renderSemanticRunStatus(body.semantic_metadata_run);
        }
        if (body.demo_orchestrator) {
            renderDemoOrchestratorStatus(body.demo_orchestrator, body);
        }
        const semanticConfig = body.semantic_metadata || {};
        if (!dom.semanticRunModel.value && semanticConfig.model) {
            dom.semanticRunModel.value = semanticConfig.model;
        }
        if (!dom.semanticRunFallbackModel.value && semanticConfig.fallback_model) {
            dom.semanticRunFallbackModel.value = semanticConfig.fallback_model;
        }
        if (!dom.semanticRunTaxonomy.value && semanticConfig.taxonomy_version) {
            dom.semanticRunTaxonomy.value = semanticConfig.taxonomy_version;
        }
        if (!dom.operatorImage.value && release.image) {
            dom.operatorImage.value = release.image;
        }
        if (!dom.operatorRef.value && release.sha) {
            dom.operatorRef.value = release.sha;
        }
    }

    async function loadSemanticRunStatus() {
        try {
            const body = await fetchJson('/operator/semantic-metadata/run-status');
            renderSemanticRunStatus(body);
        } catch (err) {
            dom.semanticRunState.textContent = 'unavailable';
            dom.semanticRunState.className = 'stale';
            dom.semanticRunError.textContent = err.message;
            dom.semanticRunStart.disabled = true;
            dom.semanticRunOutput.textContent = '';
        }
    }

    async function loadDemoOrchestratorStatus() {
        try {
            const body = await fetchJson('/operator/demo-orchestrator/run-status');
            renderDemoOrchestratorStatus(body, lastHealthBody);
        } catch (err) {
            dom.demoRunState.textContent = 'unavailable';
            dom.demoRunState.className = 'stale';
            dom.demoRunError.textContent = err.message;
            dom.demoRunStart.disabled = true;
            dom.demoRunOutput.textContent = '';
        }
    }

    async function loadCatalogSyncStatus() {
        try {
            const body = await fetchJson('/operator/catalog/sync-status');
            renderCatalogSyncStatus(body);
        } catch (err) {
            dom.catalogSyncState.textContent = 'unavailable';
            dom.catalogSyncState.className = 'stale';
            dom.catalogSyncError.textContent = err.message;
            dom.catalogSyncStart.disabled = true;
            dom.catalogSyncOutput.textContent = '';
        }
    }

    function renderCatalogSyncStatus(body) {
        const latest = body?.latest_run || null;
        const state = latest?.state || body?.status || 'idle';
        const summary = latest?.summary || null;
        const config = latest?.config || {};
        dom.catalogSyncState.textContent = state;
        dom.catalogSyncState.className = state === 'completed' ? 'fresh'
            : state === 'running' ? ''
            : state === 'idle' ? ''
            : 'stale';
        dom.catalogSyncId.textContent = latest
            ? `${latest.run_id} / ${latest.started_at || '-'}${latest.finished_at ? ' -> ' + latest.finished_at : ''}`
            : '-';
        dom.catalogSyncCounts.textContent = summary
            ? `pages ${summary.pages_fetched || 0} / discovered ${summary.markets_discovered || 0}` +
                ` / selected ${summary.markets_selected || 0} / upserted ${summary.rows_upserted || 0}` +
                ` / dry ${summary.dry_run_rows || 0} / failures ${summary.failures || 0}` +
                ` / ${summary.outcome || 'completed'}`
            : '-';
        dom.catalogSyncConfig.textContent = latest
            ? `series ${config.series_ticker || '-'} / status ${config.market_status || '-'}` +
                ` / limit ${config.limit || '-'} / pages ${config.max_pages || '-'}` +
                ` / max ${config.max_tickers ?? '-'} / dry ${yesNo(config.dry_run)}` +
                ` / db ${yesNo(config.db_configured)} / key ${yesNo(config.kalshi_key_id_configured)}`
            : `db ${yesNo(body?.db_configured)} / key ${yesNo(body?.kalshi_key_id_configured)}` +
                ` / key path ${yesNo(body?.kalshi_private_key_path_configured)}`;
        dom.catalogSyncError.textContent = latest?.last_error || (state === 'disabled' ? 'operator control disabled' : '-');
        dom.catalogSyncStart.disabled = body?.running === true || body?.operator_control_enabled !== true;
        dom.catalogSyncOutput.textContent = latest ? JSON.stringify({
            state: latest.state,
            run_id: latest.run_id,
            summary,
            config,
            last_error: latest.last_error || null
        }, null, 2) : '';
        if (body?.running === true) {
            scheduleCatalogSyncStatusRefresh();
        } else {
            clearCatalogSyncStatusTimer();
        }
        const completionKey = latest && latest.state === 'completed'
            ? `${latest.run_id}:${latest.finished_at || ''}`
            : '';
        if (completionKey && completionKey !== lastCatalogSyncCompletion) {
            lastCatalogSyncCompletion = completionKey;
            applyCatalogSyncToSemanticRun(config, summary);
            loadSymbols();
        }
    }

    function applyCatalogSyncToSemanticRun(config, summary) {
        if (!config) {
            return;
        }
        if (config.series_ticker) {
            dom.semanticRunSeriesTicker.value = config.series_ticker;
        }
        if (config.market_status != null) {
            dom.semanticRunMarketStatus.value = semanticStatusFromCatalogStatus(config.market_status);
        }
        const selected = Number(summary?.rows_upserted || summary?.markets_selected || config.max_tickers || 0);
        if (selected > 0) {
            dom.semanticRunMaxMarkets.value = String(Math.max(1, Math.min(5, selected)));
        }
        dom.semanticRunMarketTicker.value = '';
    }

    function semanticStatusFromCatalogStatus(status) {
        const normalized = String(status || '').trim().toLowerCase();
        return normalized === 'open' ? 'active' : normalized;
    }

    function renderSemanticRunStatus(body) {
        const latest = body?.latest_run || null;
        const state = latest?.state || body?.status || 'idle';
        const summary = latest?.summary || null;
        const config = latest?.config || {};
        dom.semanticRunState.textContent = state;
        dom.semanticRunState.className = state === 'completed' ? 'fresh'
            : state === 'running' ? ''
            : state === 'idle' ? ''
            : 'stale';
        dom.semanticRunId.textContent = latest
            ? `${latest.run_id} / ${latest.started_at || '-'}${latest.finished_at ? ' -> ' + latest.finished_at : ''}`
            : '-';
        dom.semanticRunCounts.textContent = summary
            ? `processed ${summary.processed || 0} / generated ${summary.generated || 0}` +
                ` / review ${summary.review_required || 0} / rate ${summary.rate_limited || 0}` +
                ` / failed ${summary.failed || 0} / skipped ${summary.skipped || 0}` +
                ` / ${summary.outcome || 'completed'}`
            : '-';
        dom.semanticRunConfig.textContent = latest
            ? `model ${config.model || '-'} / fallback ${config.fallback_model || '-'} / taxonomy ${config.taxonomy_version || '-'}` +
                ` / max ${config.max_markets || '-'} / tokens ${config.max_tokens || '-'}` +
                ` / retries ${config.max_retries ?? '-'} / overwrite ${yesNo(config.overwrite)}` +
                ` / dry ${yesNo(config.dry_run)}` +
                ` / db ${yesNo(config.db_configured)} / key ${yesNo(config.openrouter_api_key_configured)}`
            : `db ${yesNo(body?.db_configured)} / key ${yesNo(body?.openrouter_api_key_configured)}`;
        dom.semanticRunError.textContent = latest?.last_error || (state === 'disabled' ? 'operator control disabled' : '-');
        dom.semanticRunStart.disabled = body?.running === true || body?.operator_control_enabled !== true;
        dom.semanticRunOpenRouterKeyPresent.checked = body?.openrouter_api_key_configured === true;
        dom.semanticRunOutput.textContent = latest ? JSON.stringify({
            state: latest.state,
            run_id: latest.run_id,
            summary,
            config,
            last_error: latest.last_error || null
        }, null, 2) : '';
        if (body?.running === true) {
            scheduleSemanticRunStatusRefresh();
        } else {
            clearSemanticRunStatusTimer();
        }
        const completionKey = latest && latest.state === 'completed'
            ? `${latest.run_id}:${latest.finished_at || ''}`
            : '';
        if (completionKey && completionKey !== lastSemanticRunCompletion) {
            lastSemanticRunCompletion = completionKey;
            loadSemanticMap();
        }
    }

    function renderDemoOrchestratorStatus(body, healthBody) {
        const latest = body?.latest_run || null;
        const summary = latest?.summary || {};
        const dataSource = summary?.data_source || latest?.data_source || body?.data_source || {};
        const release = summary?.release || body?.release || healthBody?.release || {};
        const freshness = healthBody?.data_freshness || summary?.status_snapshot?.data_freshness || {};
        const state = latest?.state || body?.status || 'idle';
        dom.demoRunState.textContent = state;
        dom.demoRunState.className = state === 'completed' ? 'fresh'
            : state === 'running' ? ''
            : state === 'idle' ? ''
            : 'stale';
        dom.demoRunId.textContent = latest
            ? `${latest.run_id} / ${latest.started_at || '-'}${latest.finished_at ? ' -> ' + latest.finished_at : ''}`
            : '-';
        dom.demoRunMode.textContent = latest
            ? `${latest.mode || '-'} / ${latest.action || '-'}`
            : (Array.isArray(body?.actions) ? `${body.actions.length} action(s)` : '-');
        dom.demoRunRelease.textContent = releaseStatusText(release);
        dom.demoRunDataSource.textContent =
            `${dataSource.source_mode || healthBody?.source_mode || 'unknown'}` +
            ` / ${dataSource.feature_source || healthBody?.feature_source || 'unknown'}` +
            ` / db ${yesNo(dataSource.db_configured)}`;
        dom.demoRunDashboardSource.textContent = dashboardSourceText(dataSource, healthBody);
        dom.demoRunLiveSource.textContent = liveSourceText(summary);
        dom.demoRunLiveCredentials.textContent = credentialStatusText(summary.live_credential_preflight);
        dom.demoRunCatalogBounds.textContent = catalogBoundsText(summary.catalog_bounds);
        dom.demoRunS3Preflight.textContent = s3PreflightText(summary.s3_preflight);
        dom.demoRunFreshness.textContent = dataFreshnessBadgeText(freshness);
        dom.demoRunFreshness.className = dataFreshnessClass(freshness);
        const evidence = latest?.evidence_url || summary?.evidence_url || '-';
        dom.demoRunEvidence.textContent = evidence;
        dom.demoRunEvidence.title = Array.isArray(latest?.evidence_urls)
            ? latest.evidence_urls.join('\n')
            : evidence;
        dom.demoRunError.textContent = latest?.last_error || (state === 'disabled' ? 'operator control disabled' : '-');
        dom.demoRunStart.disabled = body?.running === true || body?.operator_control_enabled !== true;
        dom.demoRunOutput.textContent = latest ? JSON.stringify({
            state: latest.state,
            action: latest.action,
            mode: latest.mode,
            stdout_summary: latest.stdout_summary,
            evidence_urls: latest.evidence_urls,
            release_sha: latest.release_sha,
            release_profile: latest.release_profile,
            data_source: latest.data_source,
            summary,
            last_error: latest.last_error || null
        }, null, 2) : JSON.stringify({
            actions: body?.actions || [],
            safe_defaults: body?.safe_defaults || {},
            data_source: body?.data_source || {}
        }, null, 2);
        if (body?.running === true) {
            scheduleDemoRunStatusRefresh();
        } else {
            clearDemoRunStatusTimer();
        }
        updateDemoRunConfirmState();
    }

    function dashboardSourceText(dataSource, healthBody) {
        const sourceMode = dataSource.source_mode || healthBody?.source_mode || 'unknown';
        const featureSource = dataSource.feature_source || healthBody?.feature_source || 'unknown';
        const metadataSource = dataSource.metadata_source || healthBody?.market_metadata?.source || 'unknown';
        return `${sourceMode} / ${featureSource} / metadata ${metadataSource}`;
    }

    function liveSourceText(summary) {
        const credential = summary?.live_credential_preflight || {};
        if (credential.source === 'live') {
            return `live / auth ${credential.auth_ok === true ? 'ok' : 'not ok'}`;
        }
        return '-';
    }

    function credentialStatusText(credential) {
        if (!credential) {
            return '-';
        }
        if (credential.auth_ok === true) {
            const ticker = credential.sample_ticker ? ` / ${credential.sample_ticker}` : '';
            return `ok / HTTP ${credential.http_status || '-'} / markets ${credential.market_count || 0}${ticker}`;
        }
        if (credential.configured === false) {
            return 'missing credentials';
        }
        return `${credential.failure_category || 'not ok'} / HTTP ${credential.http_status || '-'}`;
    }

    function catalogBoundsText(bounds) {
        if (!bounds) {
            return '-';
        }
        return `${bounds.dry_run === false ? 'write' : 'dry-run'} / limit ${bounds.limit}` +
            ` / pages ${bounds.max_pages} / tickers ${bounds.max_tickers}`;
    }

    function s3PreflightText(preflight) {
        if (!preflight) {
            return '-';
        }
        return `${preflight.status || 'unknown'} / verified ${yesNo(preflight.verified)}`;
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
        const pipeline = body.operator_pipeline || {};
        dom.runtimeSourceMode.textContent = body.source_mode || '-';
        dom.runtimeFeatureSource.textContent = body.feature_source || '-';
        dom.runtimeUptime.textContent = formatAge(Number(body.uptime_ms || 0));
        dom.runtimeRefreshTotal.textContent = String(refresh.total_loaded ?? 0);
        dom.runtimeRefreshErrors.textContent = String(refresh.refresh_errors ?? 0);
        dom.runtimePipelineStatus.textContent = pipeline.status || '-';
        dom.runtimePipelineStatus.className = pipeline.status === 'ok' ? 'fresh'
            : pipeline.status === 'disabled' ? ''
            : 'stale';
        dom.runtimeCursorLag.textContent = pipeline.cursor_lag_events == null
            ? '-'
            : `${pipeline.cursor_lag_events} event(s)`;
        dom.runtimeQuoteStreams.textContent =
            `${streams.active_streams || 0}/${streams.max_streams || 0} active, ${streams.events || 0} events`;
        dom.runtimeQuoteWaits.textContent =
            `${updates.active_waits || 0}/${updates.max_waits || 0} active, ${updates.timeouts || 0} timeout`;
        dom.traderQuoteMode.textContent = streams.active_streams > 0 ? 'SSE' : 'long-poll';
        dom.traderSseStatus.textContent =
            `${streams.active_streams || 0}/${streams.max_streams || 0} active, ${streams.events || 0} events`;
        dom.operatorReleaseProfile.textContent = releaseStatusText(body.release);
        dom.operatorProductReadiness.textContent = readinessText(body.product_readiness);
        dom.operatorDataSourceSummary.textContent = dataFreshnessText(body.data_freshness);
        dom.operatorFeatureSourceSummary.textContent = body.feature_source || '-';
        dom.operatorFreshnessSummary.textContent = dataFreshnessText(body.data_freshness);
    }

    function renderLatencyFreshness(body) {
        const freshness = body.data_freshness || {};
        const fp = body.feature_plant || {};
        const store = body.store || {};
        const pipeline = body.operator_pipeline || {};
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
        dom.canonicalMaxSeq.textContent = pipeline.canonical_max_commit_seq == null
            ? '-'
            : String(pipeline.canonical_max_commit_seq);
    }

    function renderOpsTelemetry() {
        const pipeline = lastOpsPipeline?.pipeline || lastHealthBody?.operator_pipeline || {};
        const latency = lastOpsLatency || {};
        const canonical = pipeline.recent_canonical_events ?? 0;
        const features = pipeline.recent_feature_outputs ?? 0;
        const latest = pipeline.recent_latest_market_states ?? 0;
        const windowSeconds = pipeline.recent_window_seconds ?? 0;
        dom.operatorPipelineCounts.textContent =
            `canonical ${canonical} / features ${features} / latest ${latest}` +
            (windowSeconds ? ` / ${Math.round(windowSeconds / 60)}m` : '');
        const e2e = latency.canonical_to_latest_state_ms;
        dom.operatorE2eLatency.textContent = e2e == null
            ? `${latency.status || 'missing'} / ${latency.reason || latency.error || '-'}`
            : `${e2e} ms / ${latency.status || 'ok'}`;
        dom.operatorE2eLatency.className = latency.status === 'ok' ? 'fresh' : 'stale';
        dom.operatorLatencyBudget.textContent = e2e == null
            ? `${OPERATOR_LATENCY_BUDGET_MS} ms / missing`
            : `${OPERATOR_LATENCY_BUDGET_MS} ms / ${e2e <= OPERATOR_LATENCY_BUDGET_MS ? 'within' : 'over'}`;
        dom.operatorLatencyBudget.className = e2e != null && e2e <= OPERATOR_LATENCY_BUDGET_MS ? 'fresh' : 'stale';
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
        const sourceState = state === kind ? state : `${state} ${kind}`;
        return `${sourceState} / ${age} / ${symbol} / ${source}`;
    }

    function dataFreshnessBadgeText(freshness) {
        if (!freshness || freshness.latest_event_ts_ms == null) {
            return 'waiting / unknown';
        }
        const state = dataFreshnessLiveState(freshness);
        const age = freshness.latest_event_age_ms == null ? '-' : formatAge(Number(freshness.latest_event_age_ms));
        return `${state} / ${freshness.source_kind || 'unknown'} / ${age}`;
    }

    function readinessText(readiness) {
        if (!readiness) {
            return '-';
        }
        const status = readiness.status || 'unknown';
        const reasons = Array.isArray(readiness.reasons) && readiness.reasons.length
            ? readiness.reasons.join(', ')
            : 'ready';
        return `${status} / ${reasons}`;
    }

    function dataFreshnessLiveState(freshness) {
        if (!freshness || freshness.latest_event_age_ms == null) {
            return 'stale';
        }
        if (freshness.synthetic === true) {
            return freshness.source_kind === 'demo' ? 'demo' : 'synthetic';
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

    function buildOperatorPlanRequest() {
        const profile = dom.operatorDeployProfile.value || 'live-product';
        const marketMode = dom.operatorMarketMode.value || 'configured';
        const maxMarkets = dom.operatorMaxMarkets.value || '0';
        return {
            profile,
            market_selection: {
                mode: marketMode,
                max_markets: Number(maxMarkets) || 0,
                tickers: dom.operatorTickers.value.trim()
            },
            kalshi: {
                key_id: dom.operatorKalshiKeyId.value.trim(),
                private_key_path: dom.operatorKeyPath.value.trim() || '/run/secrets/kalshi_private_key',
                private_key_pem_present: dom.operatorPrivateKeyPemPresent.checked
            },
            s3: {
                bucket: dom.operatorS3Bucket.value.trim(),
                region: dom.operatorS3Region.value.trim(),
                prefix: dom.operatorS3Prefix.value.trim(),
                delete_after_upload: dom.operatorS3DeleteAfterUpload.checked
            },
            db: {
                url: dom.operatorDbUrl.value.trim(),
                user: dom.operatorDbUser.value.trim(),
                password_present: dom.operatorDbPasswordPresent.checked
            },
            basic_auth: {
                user: dom.operatorBasicAuthUser.value.trim(),
                password_present: dom.operatorBasicAuthPasswordPresent.checked
            },
            release: {
                image: dom.operatorImage.value.trim(),
                ref: dom.operatorRef.value.trim()
            }
        };
    }

    async function generateOperatorPlan() {
        dom.operatorGeneratePlan.disabled = true;
        dom.operatorWarnings.innerHTML = '<div class="operator-plan-step">Building plan...</div>';
        dom.operatorChecklist.innerHTML = '';
        dom.operatorCommandPlan.textContent = '';
        dom.operatorEnvPlan.textContent = '';
        try {
            const plan = await fetchJson('/operator/plan', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(buildOperatorPlanRequest())
            });
            renderOperatorPlanResponse(plan);
        } catch (err) {
            dom.operatorPlanState.textContent = 'blocked';
            dom.operatorPlanState.className = 'stale';
            dom.operatorWarnings.innerHTML =
                `<div class="operator-plan-warning">${escapeHtml(err.message)}</div>`;
        } finally {
            await loadOperatorStatus();
        }
    }

    function buildSemanticRunRequest() {
        const maxMarkets = Number(dom.semanticRunMaxMarkets.value);
        const maxTokens = Number(dom.semanticRunMaxTokens.value);
        const maxRetries = Number(dom.semanticRunMaxRetries.value);
        const request = {
            dry_run: dom.semanticRunDryRun.checked,
            overwrite: dom.semanticRunOverwrite.checked,
            max_markets: Number.isFinite(maxMarkets) && maxMarkets > 0 ? maxMarkets : 5,
            max_tokens: Number.isFinite(maxTokens) && maxTokens > 0 ? maxTokens : 2200,
            max_retries: Number.isFinite(maxRetries) && maxRetries >= 0 ? maxRetries : 2,
            taxonomy_version: dom.semanticRunTaxonomy.value.trim(),
            model: dom.semanticRunModel.value.trim(),
            fallback_model: dom.semanticRunFallbackModel.value.trim(),
            allow_paid_fallback: dom.semanticRunAllowPaidFallback.checked,
            budget_usd: dom.semanticRunBudgetUsd.value.trim() || '0',
            estimated_paid_request_cost_usd: dom.semanticRunEstimatedCost.value.trim() || '0.01',
            openrouter_api_key_present: dom.semanticRunOpenRouterKeyPresent.checked
        };
        const ticker = dom.semanticRunMarketTicker.value.trim();
        if (ticker) {
            request.market_ticker = ticker;
        }
        const series = dom.semanticRunSeriesTicker.value.trim();
        if (series) {
            request.series_ticker = series;
        }
        const status = dom.semanticRunMarketStatus.value.trim();
        if (status) {
            request.market_status = status;
        }
        const apiKey = dom.semanticRunOpenRouterKey.value.trim();
        if (apiKey) {
            request.openrouter_api_key = apiKey;
        }
        return request;
    }

    function buildCatalogSyncRequest() {
        const request = {
            dry_run: dom.catalogSyncDryRun.checked,
            limit: Number(dom.catalogSyncLimit.value || 20) || 20,
            max_pages: Number(dom.catalogSyncMaxPages.value || 1) || 1,
            max_tickers: Number(dom.catalogSyncMaxTickers.value || 0) || 0
        };
        const series = dom.catalogSyncSeries.value.trim();
        if (series) {
            request.series_ticker = series;
        }
        const status = dom.catalogSyncStatus.value.trim();
        if (status) {
            request.market_status = status;
        }
        const mveFilter = dom.catalogSyncMveFilter.value.trim();
        if (mveFilter) {
            request.mve_filter = mveFilter;
        }
        return request;
    }

    function buildDemoRunRequest() {
        const action = dom.demoRunAction.value || 'product_readiness_check';
        const liveCatalog = action === 'live_catalog_sync_bounded';
        return {
            action,
            confirm_live: dom.demoRunConfirmLive.checked,
            catalog: {
                dry_run: liveCatalog ? dom.catalogSyncDryRun.checked !== false : true,
                limit: Number(dom.catalogSyncLimit.value || 20) || 20,
                max_pages: Number(dom.catalogSyncMaxPages.value || 1) || 1,
                max_tickers: Number(dom.catalogSyncMaxTickers.value || 5) || 5,
                series_ticker: dom.catalogSyncSeries.value.trim(),
                market_status: dom.catalogSyncStatus.value.trim(),
                mve_filter: dom.catalogSyncMveFilter.value.trim()
            },
            semantic: {
                dry_run: true,
                overwrite: false,
                allow_paid_fallback: false,
                max_markets: Number(dom.semanticRunMaxMarkets.value || 5) || 5,
                max_tokens: Number(dom.semanticRunMaxTokens.value || 2200) || 2200,
                max_retries: Number(dom.semanticRunMaxRetries.value || 2) || 2,
                market_ticker: dom.semanticRunMarketTicker.value.trim(),
                series_ticker: dom.semanticRunSeriesTicker.value.trim(),
                market_status: dom.semanticRunMarketStatus.value.trim() || 'active',
                taxonomy_version: dom.semanticRunTaxonomy.value.trim(),
                model: dom.semanticRunModel.value.trim(),
                fallback_model: dom.semanticRunFallbackModel.value.trim(),
                budget_usd: dom.semanticRunBudgetUsd.value.trim() || '0',
                estimated_paid_request_cost_usd: dom.semanticRunEstimatedCost.value.trim() || '0.01'
            }
        };
    }

    function demoActionRequiresConfirm(action) {
        return [
            'live_product_check',
            'live_credential_check',
            'live_catalog_sync_bounded',
            's3_preflight_check'
        ].includes(action || '');
    }

    function updateDemoRunConfirmState() {
        const requiresConfirm = demoActionRequiresConfirm(dom.demoRunAction.value || '');
        dom.demoRunConfirmRow.classList.toggle('requires-confirm', requiresConfirm);
        dom.demoRunConfirmLive.disabled = !requiresConfirm;
        if (!requiresConfirm) {
            dom.demoRunConfirmLive.checked = false;
        }
    }

    async function startCatalogSync() {
        dom.catalogSyncStart.disabled = true;
        dom.catalogSyncState.textContent = 'starting';
        dom.catalogSyncError.textContent = '-';
        try {
            const body = await fetchJson('/operator/catalog/sync', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(buildCatalogSyncRequest())
            });
            renderCatalogSyncStatus(body);
        } catch (err) {
            dom.catalogSyncState.textContent = 'blocked';
            dom.catalogSyncState.className = 'stale';
            dom.catalogSyncError.textContent = err.message;
        } finally {
            await loadOperatorStatus();
        }
    }

    async function startSemanticMetadataRun() {
        dom.semanticRunStart.disabled = true;
        dom.semanticRunState.textContent = 'starting';
        dom.semanticRunError.textContent = '-';
        try {
            const body = await fetchJson('/operator/semantic-metadata/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(buildSemanticRunRequest())
            });
            renderSemanticRunStatus(body);
        } catch (err) {
            dom.semanticRunState.textContent = 'blocked';
            dom.semanticRunState.className = 'stale';
            dom.semanticRunError.textContent = err.message;
        } finally {
            dom.semanticRunOpenRouterKey.value = '';
            await loadOperatorStatus();
        }
    }

    async function startDemoOrchestratorRun() {
        dom.demoRunStart.disabled = true;
        dom.demoRunState.textContent = 'starting';
        dom.demoRunError.textContent = '-';
        try {
            const body = await fetchJson('/operator/demo-orchestrator/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(buildDemoRunRequest())
            });
            renderDemoOrchestratorStatus(body, lastHealthBody);
        } catch (err) {
            dom.demoRunState.textContent = 'blocked';
            dom.demoRunState.className = 'stale';
            dom.demoRunError.textContent = err.message;
        } finally {
            await loadOperatorStatus();
            if ((dom.demoRunAction.value || '').includes('semantic')) {
                await loadSemanticRunStatus();
                await loadSemanticMap();
            }
            if ((dom.demoRunAction.value || '').includes('catalog')) {
                await loadCatalogSyncStatus();
            }
        }
    }

    async function startSemanticRunFromCatalog() {
        applyCatalogSyncToSemanticRun(buildCatalogSyncRequest(), null);
        await startSemanticMetadataRun();
    }

    function scheduleSemanticRunStatusRefresh() {
        clearSemanticRunStatusTimer();
        semanticRunStatusTimer = setTimeout(loadSemanticRunStatus, 2500);
    }

    function clearSemanticRunStatusTimer() {
        if (semanticRunStatusTimer != null) {
            clearTimeout(semanticRunStatusTimer);
            semanticRunStatusTimer = null;
        }
    }

    function scheduleCatalogSyncStatusRefresh() {
        clearCatalogSyncStatusTimer();
        catalogSyncStatusTimer = setTimeout(loadCatalogSyncStatus, 2500);
    }

    function clearCatalogSyncStatusTimer() {
        if (catalogSyncStatusTimer != null) {
            clearTimeout(catalogSyncStatusTimer);
            catalogSyncStatusTimer = null;
        }
    }

    function scheduleDemoRunStatusRefresh() {
        clearDemoRunStatusTimer();
        demoRunStatusTimer = setTimeout(loadDemoOrchestratorStatus, 2500);
    }

    function clearDemoRunStatusTimer() {
        if (demoRunStatusTimer != null) {
            clearTimeout(demoRunStatusTimer);
            demoRunStatusTimer = null;
        }
    }

    function renderOperatorPlanResponse(plan) {
        const checks = Array.isArray(plan.checklist) ? plan.checklist : [];
        dom.operatorPlanState.textContent = `${plan.status || 'unknown'}`;
        dom.operatorPlanState.className = plan.can_deploy || plan.can_replay ? 'fresh' : 'stale';
        dom.operatorWarnings.innerHTML =
            `<div class="operator-plan-step">can_deploy=${yesNo(plan.can_deploy)} / can_replay=${yesNo(plan.can_replay)}</div>`;
        dom.operatorChecklist.innerHTML = checks.map(check => {
            const cls = check.passed ? 'operator-plan-step' : 'operator-plan-warning';
            const required = check.required ? 'required' : 'optional';
            return `<div class="${cls}">${escapeHtml(check.label || check.id)}: ` +
                `${check.passed ? 'pass' : 'blocked'} / ${required}` +
                `${check.message ? ` / ${escapeHtml(check.message)}` : ''}</div>`;
        }).join('');
        const env = plan.redacted_env || {};
        dom.operatorEnvPlan.textContent = Object.keys(env)
            .map(key => `${key}=${env[key] == null ? '' : env[key]}`)
            .join('\n');
        dom.operatorCommandPlan.textContent = Array.isArray(plan.commands)
            ? plan.commands.join('\n')
            : '';
    }

    function yesNo(value) {
        return value === true ? 'yes' : 'no';
    }

    function renderQuoteUpdateState(message, tone) {
        dom.quoteUpdateHealth.textContent = message;
        dom.quoteUpdateHealth.className = tone || '';
        dom.traderQuoteMode.textContent = message || '-';
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
        pollQuotes(generation, symbol, base);
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
        loadOpsTelemetry();
    }

    function setActiveRole(role) {
        for (const button of document.querySelectorAll('#view-tabs [data-role]')) {
            const active = button.dataset.role === role;
            button.setAttribute('aria-selected', active ? 'true' : 'false');
        }
        applyRoleVisibility(role);
        const button = document.querySelector(`#view-tabs [data-role="${role}"]`);
        const target = button ? document.getElementById(button.dataset.target) : null;
        if (target) {
            target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
        chart.applyOptions({
            width: dom.chartContainer.clientWidth,
            height: dom.chartContainer.clientHeight
        });
        if (role === 'semantic') {
            loadSemanticMap();
        }
    }

    function applyRoleVisibility(role) {
        for (const panel of document.querySelectorAll('[data-role-panel]')) {
            const roles = String(panel.dataset.rolePanel || '')
                .split(',')
                .map(value => value.trim())
                .filter(Boolean);
            panel.classList.toggle('role-hidden', roles.length > 0 && !roles.includes(role));
        }
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
    dom.researchFeatureLimit.addEventListener('change', () => loadRecentFeatures(dom.symbolSelect.value));
    dom.researchFeatureWindow.addEventListener('change', () => loadRecentFeatures(dom.symbolSelect.value));
    dom.researchExportCsv.addEventListener('click', exportResearchCsv);
    dom.semanticGroupBy.addEventListener('change', () => {
        semanticActiveGroupKey = '';
        loadSemanticMap();
    });
    dom.semanticRenderMode.addEventListener('change', () => {
        semanticActiveGroupKey = '';
        if (lastSemanticMapBody) {
            renderSemanticMap(lastSemanticMapBody);
        } else {
            loadSemanticMap();
        }
    });
    dom.semanticStatusFilter.addEventListener('change', loadSemanticMap);
    dom.semanticLimit.addEventListener('change', loadSemanticMap);
    dom.semanticTagFilter.addEventListener('input', scheduleSemanticMapLoad);
    dom.semanticSearch.addEventListener('input', scheduleSemanticMapLoad);
    dom.semanticRefresh.addEventListener('click', loadSemanticMap);
    dom.semanticDrillup.addEventListener('click', clearSemanticGroupDrilldown);
    dom.catalogSyncStart.addEventListener('click', startCatalogSync);
    dom.catalogSyncRefresh.addEventListener('click', loadCatalogSyncStatus);
    dom.semanticRunStart.addEventListener('click', startSemanticMetadataRun);
    dom.catalogSyncUseForSemantic.addEventListener('click', () => applyCatalogSyncToSemanticRun(buildCatalogSyncRequest(), null));
    dom.semanticRunFromCatalog.addEventListener('click', startSemanticRunFromCatalog);
    dom.semanticRunRefresh.addEventListener('click', loadSemanticRunStatus);
    dom.demoRunStart.addEventListener('click', startDemoOrchestratorRun);
    dom.demoRunRefresh.addEventListener('click', loadDemoOrchestratorStatus);
    dom.demoRunAction.addEventListener('change', updateDemoRunConfirmState);
    dom.operatorGeneratePlan.addEventListener('click', generateOperatorPlan);
    for (const button of document.querySelectorAll('#view-tabs [data-role]')) {
        button.addEventListener('click', () => setActiveRole(button.dataset.role));
    }
    dom.adapterUrl.addEventListener('change', () => {
        quotesLoopGeneration += 1;
        stopQuotesLoop();
        loadConfig();
        loadSymbols();
        loadHealth();
        loadOpsTelemetry();
        loadOperatorStatus();
        loadSemanticMap();
        loadCatalogSyncStatus();
        loadSemanticRunStatus();
        loadDemoOrchestratorStatus();
    });

    updateDemoRunConfirmState();
    applyRoleVisibility('viewer');
    setInterval(() => {
        loadHealth();
        loadOpsTelemetry();
        loadOperatorStatus();
    }, HEALTH_POLL_MS);
    loadConfig().then(loadSymbols).then(() => {
        loadHealth();
        loadOpsTelemetry();
        loadOperatorStatus();
        loadSemanticMap();
        loadCatalogSyncStatus();
        loadSemanticRunStatus();
        loadDemoOrchestratorStatus();
    });
})();

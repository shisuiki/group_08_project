(function () {
    'use strict';

    const QUOTES_POLL_MS = 2000;
    const QUOTES_UPDATE_TIMEOUT_MS = 15000;
    const QUOTES_UPDATE_ERROR_LIMIT = 3;
    const QUOTES_STREAM_ERROR_LIMIT = 2;
    const QUOTES_UPDATE_RETRY_MS = 500;
    const CHART_AUTO_REFRESH_MS = 15000;
    const HEALTH_POLL_MS = 5000;
    const MARKET_SEARCH_DEBOUNCE_MS = 250;
    const MARKET_CATALOG_LIMIT = 200;
    const MARKET_QUOTE_PROBE_LIMIT = 200;
    const DEMO_MARKET_HISTORY_PROBE_LIMIT = 12;
    const DEMO_DEFAULT_RESOLUTION = '1S';
    const DEMO_DEFAULT_LOOKBACK_SECONDS = 900;
    const SEMANTIC_MAP_DEFAULT_LIMIT = 500;
    const SEMANTIC_MAP_MAX_LIMIT = 500;
    const SEMANTIC_RENDER_LEAF_LIMIT = 500;
    const SEMANTIC_SEARCH_DEBOUNCE_MS = 300;
    const OPERATOR_LATENCY_BUDGET_MS = 30000;
    const FALLBACK_RESOLUTIONS = ['1S', '5S', '30S', '1', '5', '15', '60'];
    const COMPACT_NUMBER_FORMAT = new Intl.NumberFormat(undefined, {
        notation: 'compact',
        maximumFractionDigits: 1
    });

    const dom = {
        dashboardShell: document.getElementById('dashboard-shell'),
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
        marketCapabilityFilter: document.getElementById('market-capability-filter'),
        marketStatusFilter: document.getElementById('market-status-filter'),
        marketSearchApply: document.getElementById('market-search-apply'),
        marketCapabilitySummary: document.getElementById('market-capability-summary'),
        marketPrevPage: document.getElementById('market-prev-page'),
        marketNextPage: document.getElementById('market-next-page'),
        marketPageState: document.getElementById('market-page-state'),
        marketState: document.getElementById('market-state'),
        marketList: document.getElementById('market-list'),
        chartState: document.getElementById('chart-state'),
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
        demoSignalLive: document.getElementById('demo-signal-live'),
        demoSignalLatency: document.getElementById('demo-signal-latency'),
        demoSignalThroughput: document.getElementById('demo-signal-throughput'),
        demoSignalDistribution: document.getElementById('demo-signal-distribution'),
        demoSignalReplay: document.getElementById('demo-signal-replay'),
        distributionConnectionStatus: document.getElementById('distribution-connection-status'),
        distributionProtocols: document.getElementById('distribution-protocols'),
        distributionEndpoints: document.getElementById('distribution-endpoints'),
        distributionUpdateCount: document.getElementById('distribution-update-count'),
        distributionUpdateRate: document.getElementById('distribution-update-rate'),
        distributionStreamStatus: document.getElementById('distribution-stream-status'),
        distributionLastEvent: document.getElementById('distribution-last-event'),
        distributionSamplePayload: document.getElementById('distribution-sample-payload'),
        productReadinessState: document.getElementById('product-readiness-state'),
        productReadinessReasons: document.getElementById('product-readiness-reasons'),
        marketSourceKind: document.getElementById('market-source-kind'),
        marketLiveObserved: document.getElementById('market-live-observed'),
        marketSyntheticState: document.getElementById('market-synthetic-state'),
        productModeState: document.getElementById('product-mode-state'),
        modeChipReplay: document.getElementById('mode-chip-replay'),
        modeChipLive: document.getElementById('mode-chip-live'),
        modeChipSemantic: document.getElementById('mode-chip-semantic'),
        modeChipOps: document.getElementById('mode-chip-ops'),
        modeSourceMode: document.getElementById('mode-source-mode'),
        modeFeatureSource: document.getElementById('mode-feature-source'),
        modeReleaseProfile: document.getElementById('mode-release-profile'),
        modeLiveFreshness: document.getElementById('mode-live-freshness'),
        modeReplayReadiness: document.getElementById('mode-replay-readiness'),
        modeReplayProjection: document.getElementById('mode-replay-projection'),
        modeSemanticStatus: document.getElementById('mode-semantic-status'),
        modeOpsLatency: document.getElementById('mode-ops-latency'),
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
        semanticCoverageSummary: document.getElementById('semantic-coverage-summary'),
        semanticGroupSummary: document.getElementById('semantic-group-summary'),
        semanticTreemap: document.getElementById('semantic-treemap'),
        semanticMarketDetail: document.getElementById('semantic-market-detail'),
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
        latencyThroughput: document.getElementById('latency-throughput'),
        latencyWsSubscribed: document.getElementById('latency-ws-subscribed'),
        latencyDbQueueDepth: document.getElementById('latency-db-queue-depth'),
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
        replayStatusState: document.getElementById('replay-status-state'),
        replayStatusId: document.getElementById('replay-status-id'),
        replayStatusDataset: document.getElementById('replay-status-dataset'),
        replayStatusProjection: document.getElementById('replay-status-projection'),
        replayStatusWindow: document.getElementById('replay-status-window'),
        replayStatusSymbols: document.getElementById('replay-status-symbols'),
        replayStatusError: document.getElementById('replay-status-error'),
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
    let distributionUpdateCount = 0;
    let distributionFirstUpdateAtMs = null;
    let distributionLastUpdateAtMs = null;
    let distributionLastPayload = null;
    let distributionConnectionState = 'idle';
    let lastHistoryRefreshMs = 0;
    let marketEntries = [];
    let marketCatalogTotal = 0;
    let marketCatalogOffset = 0;
    let marketCapabilitySummary = null;
    let marketSearchTimer = null;
    let marketCatalogGeneration = 0;
    let marketCatalogAbortController = null;
    let semanticSearchTimer = null;
    let semanticMapGeneration = 0;
    let semanticMarketDetailGeneration = 0;
    let semanticMapDirty = true;
    let lastSemanticMapBody = null;
    let semanticActiveGroupKey = '';
    let activeRole = 'live';
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
        dom.resolutionSelect.value = resolutions.includes(DEMO_DEFAULT_RESOLUTION)
            ? DEMO_DEFAULT_RESOLUTION
            : resolutions.includes('1')
                ? '1'
                : resolutions[0];
        if (dom.lookbackSelect && Array.from(dom.lookbackSelect.options).some(option => option.value === String(DEMO_DEFAULT_LOOKBACK_SECONDS))) {
            dom.lookbackSelect.value = String(DEMO_DEFAULT_LOOKBACK_SECONDS);
        }
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
        const generation = ++marketCatalogGeneration;
        if (marketCatalogAbortController) {
            marketCatalogAbortController.abort();
        }
        marketCatalogAbortController = new AbortController();
        const requestContext = {
            generation,
            signal: marketCatalogAbortController.signal
        };
        setStatus('Loading market catalog...');
        try {
            const previousSymbol = dom.symbolSelect.value;
            const loadedEntries = await loadMarketEntries(requestContext);
            ensureActiveMarketCatalogRequest(requestContext);
            marketEntries = loadedEntries;
            const preferredSymbol = previousSymbol || await chooseDemoDefaultSymbol(marketEntries, requestContext);
            ensureActiveMarketCatalogRequest(requestContext);
            populateSymbolDropdown(marketEntries, preferredSymbol);
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
            if (isStaleMarketCatalogError(err)) {
                return;
            }
            renderMarketCatalogError(err.message);
            setStatus(`Failed to load markets: ${err.message}`, 'error');
        }
    }

    async function loadMarketEntries(requestContext) {
        const query = dom.marketSearch ? dom.marketSearch.value.trim() : '';
        const status = dom.marketStatusFilter ? dom.marketStatusFilter.value.trim() : '';
        const capability = dom.marketCapabilityFilter ? dom.marketCapabilityFilter.value.trim() : 'all';
        const filtered = query !== '' || status !== '' || (capability && capability !== 'all');
        const capabilityParams = [`limit=${MARKET_CATALOG_LIMIT}`, `offset=${marketCatalogOffset}`];
        if (query) {
            capabilityParams.push(`query=${encodeURIComponent(query)}`);
        }
        if (status) {
            capabilityParams.push(`status=${encodeURIComponent(status)}`);
        }
        if (capability && capability !== 'all') {
            capabilityParams.push(`capability=${encodeURIComponent(capability)}`);
        }
        try {
            const body = await fetchJson(
                '/api/markets/capabilities?' + capabilityParams.join('&'),
                { signal: requestContext.signal }
            );
            ensureActiveMarketCatalogRequest(requestContext);
            if (body && Array.isArray(body.markets)) {
                const entries = body.markets.map(mapCapabilityMarketRow).filter(row => row.symbol);
                marketCatalogTotal = Number(body.total_count || body.summary?.total_assets || entries.length) || entries.length;
                marketCatalogOffset = Math.max(0, Number(body.offset || marketCatalogOffset) || 0);
                marketCapabilitySummary = body.summary || null;
                renderCapabilitySummary(marketCapabilitySummary);
                return sortMarketEntries(entries);
            }
        } catch (err) {
            if (isStaleMarketCatalogError(err)) {
                throw err;
            }
            console.warn('Falling back after /api/markets/capabilities failed', err);
        }
        ensureActiveMarketCatalogRequest(requestContext);
        marketCapabilitySummary = null;
        renderCapabilitySummary(null);
        const params = [`limit=${MARKET_CATALOG_LIMIT}`];
        if (query) {
            params.push(`query=${encodeURIComponent(query)}`);
        }
        if (status) {
            params.push(`status=${encodeURIComponent(status)}`);
        }
        try {
            const markets = await fetchJson('/markets?' + params.join('&'), { signal: requestContext.signal });
            ensureActiveMarketCatalogRequest(requestContext);
            if (markets && Array.isArray(markets.markets) && markets.markets.length > 0) {
                const entries = markets.markets.map(mapCatalogMarketRow).filter(row => row.symbol);
                marketCatalogTotal = Number(markets.total_count || markets.count || entries.length) || entries.length;
                return (
                    await prioritizeQuotedMarkets(
                        await mergeLatestStateMarkets(entries, query, status, requestContext),
                        requestContext
                    )
                ).slice(0, MARKET_CATALOG_LIMIT);
            }
            if (filtered) {
                const symbols = await fetchJson('/symbols', { signal: requestContext.signal });
                ensureActiveMarketCatalogRequest(requestContext);
                const rows = (symbols && Array.isArray(symbols.symbols)) ? symbols.symbols : [];
                const entries = rows
                    .map(mapLatestStateSymbolRow)
                    .filter(row => row.symbol && latestStateMatchesFilters(row, query, status));
                marketCatalogTotal = entries.length;
                return (
                    await prioritizeQuotedMarkets(sortMarketEntries(entries), requestContext)
                ).slice(marketCatalogOffset, marketCatalogOffset + MARKET_CATALOG_LIMIT);
            }
        } catch (err) {
            if (isStaleMarketCatalogError(err)) {
                throw err;
            }
            if (filtered) {
                throw err;
            }
            console.warn('Falling back to /symbols after /markets failed', err);
        }
        const symbols = await fetchJson('/symbols', { signal: requestContext.signal });
        ensureActiveMarketCatalogRequest(requestContext);
        const rows = (symbols && Array.isArray(symbols.symbols)) ? symbols.symbols : [];
        const entries = sortMarketEntries(rows.map(mapLatestStateSymbolRow).filter(row => row.symbol));
        marketCatalogTotal = entries.length;
        return entries.slice(marketCatalogOffset, marketCatalogOffset + MARKET_CATALOG_LIMIT);
    }

    function mapCatalogMarketRow(row) {
        return {
            symbol: row.market_ticker,
            eventTicker: row.event_ticker || '-',
            seriesTicker: row.series_ticker || '-',
            status: row.status || '-',
            openTime: row.open_time || null,
            closeTime: row.close_time || null,
            quoteEventTsMs: null,
            hasQuote: false,
            chartable: false,
            bestChartSource: null,
            sourceKind: row.source_kind || '-',
            catalogSource: row.catalog_source || 'catalog',
            synthetic: row.synthetic === true
        };
    }

    function mapCapabilityMarketRow(row) {
        const quoteEventTsMs = row.quote_event_ts_ms == null ? null : Number(row.quote_event_ts_ms);
        const hasQuote = row.has_quote === true || row.has_live_quote === true;
        const chartable = row.chartable === true
            || row.chartable_from_bbo === true
            || row.chartable_from_ticker_snapshot === true
            || row.chartable_from_trade_tape === true;
        return {
            symbol: row.market_ticker,
            eventTicker: row.event_ticker || '-',
            seriesTicker: row.series_ticker || '-',
            status: row.status || '-',
            openTime: row.open_time || null,
            closeTime: row.close_time || null,
            quoteEventTsMs: Number.isFinite(quoteEventTsMs) ? quoteEventTsMs : null,
            quoteAgeMs: row.quote_age_ms == null ? null : Number(row.quote_age_ms),
            quoteStatus: row.quote_status || 'missing_quote',
            hasQuote,
            hasLatestState: row.has_latest_state === true,
            hasBboHistory: row.has_bbo_history === true,
            chartableFromBbo: row.chartable_from_bbo === true,
            chartableFromTickerSnapshot: row.chartable_from_ticker_snapshot === true,
            chartableFromTradeTape: row.chartable_from_trade_tape === true,
            bestChartSource: row.best_chart_source || null,
            chartable,
            chartable1h: row.chartable_1h === true,
            chartable24h: row.chartable_24h === true,
            chartStatus: row.chart_status || (chartable ? 'chartable_history' : hasQuote ? 'quote_only' : 'not_chartable'),
            chartReason: row.chart_reason || '',
            semanticStatus: row.semantic_status || 'missing',
            semanticSector: row.semantic_sector || '',
            semanticSubsector: row.semantic_subsector || '',
            semanticEventType: row.semantic_event_type || '',
            featureCount: Number(row.feature_count || 0),
            bboSampleCount: Number(row.bbo_sample_count || 0),
            tradeSampleCount: Number(row.trade_sample_count || 0),
            tickerSampleCount: Number(row.ticker_sample_count || 0),
            bars24hCount: Number(row.bars_24h_count || row.history_bars_24h || 0),
            trade24hCount: Number(row.trade_24h_count || 0),
            quote24hCount: Number(row.quote_24h_count || 0),
            lastEventTsMs: row.last_event_ts_ms == null ? null : Number(row.last_event_ts_ms),
            liquidityRank: row.liquidity_rank == null ? null : Number(row.liquidity_rank),
            displayEligible: row.display_eligible === true || row.eligible === true,
            sourceKind: row.source_kind || '-',
            catalogSource: row.catalog_source || 'catalog',
            synthetic: row.synthetic === true
        };
    }

    function mapLatestStateSymbolRow(row) {
        const eventTsMs = row.latest_event_ts_ms == null ? null : Number(row.latest_event_ts_ms);
        const hasQuote = Number.isFinite(eventTsMs);
        return {
            symbol: row.symbol,
            eventTicker: '-',
            seriesTicker: '-',
            status: hasQuote ? 'indexed' : 'waiting',
            openTime: null,
            closeTime: null,
            quoteEventTsMs: hasQuote ? eventTsMs : null,
            hasQuote,
            chartable: false,
            bestChartSource: null,
            sourceKind: row.source_kind || '-',
            catalogSource: 'latest_state',
            synthetic: row.synthetic === true
        };
    }

    async function mergeLatestStateMarkets(entries, query, status, requestContext) {
        try {
            const symbols = await fetchJson('/symbols', { signal: requestContext.signal });
            ensureActiveMarketCatalogRequest(requestContext);
            const rows = (symbols && Array.isArray(symbols.symbols)) ? symbols.symbols : [];
            if (rows.length === 0) {
                return entries;
            }
            const merged = new Map(entries.map(entry => [entry.symbol, { ...entry }]));
            for (const row of rows) {
                const latest = mapLatestStateSymbolRow(row);
                if (!latest.symbol) {
                    continue;
                }
                const current = merged.get(latest.symbol);
                if (!current) {
                    if (!latestStateMatchesFilters(latest, query, status)) {
                        continue;
                    }
                    merged.set(latest.symbol, latest);
                    continue;
                }
                current.quoteEventTsMs = latest.quoteEventTsMs;
                current.hasQuote = latest.hasQuote;
                current.sourceKind = latest.sourceKind;
                current.catalogSource = current.catalogSource || latest.catalogSource;
                current.synthetic = latest.synthetic;
            }
            return sortMarketEntries(Array.from(merged.values()));
        } catch (err) {
            if (isStaleMarketCatalogError(err)) {
                throw err;
            }
            console.warn('Failed to merge latest-state markets into catalog', err);
            return entries;
        }
    }

    function latestStateMatchesFilters(entry, query, status) {
        if (status && entry.status !== status) {
            return false;
        }
        if (!query) {
            return true;
        }
        const needle = query.toLowerCase();
        return [
            entry.symbol,
            entry.eventTicker,
            entry.seriesTicker,
            entry.status
        ].some(value => String(value || '').toLowerCase().includes(needle));
    }

    async function prioritizeQuotedMarkets(entries, requestContext) {
        if (entries.length === 0) {
            return entries;
        }
        const base = adapterBase();
        const symbols = entries.slice(0, MARKET_QUOTE_PROBE_LIMIT).map(entry => entry.symbol);
        try {
            const body = await fetchJsonFromBase(
                base,
                `/quotes?symbols=${encodeURIComponent(symbols.join(','))}`,
                { signal: requestContext.signal }
            );
            ensureActiveMarketCatalogRequest(requestContext);
            const quotes = new Map((body.quotes || []).map(quote => [quote.symbol, quote]));
            for (const entry of entries) {
                const quote = quotes.get(entry.symbol);
                const eventTsMs = quote?.event_ts_ms == null ? null : Number(quote.event_ts_ms);
                entry.quoteEventTsMs = Number.isFinite(eventTsMs) ? eventTsMs : null;
                entry.hasQuote = entry.quoteEventTsMs != null && quote?.midpoint_micros != null;
            }
            return sortMarketEntries(entries);
        } catch (err) {
            if (isStaleMarketCatalogError(err)) {
                throw err;
            }
            console.warn('Failed to prioritize markets by latest quote', err);
            return sortMarketEntries(entries);
        }
    }

    function sortMarketEntries(entries) {
        return entries.slice().sort((left, right) => {
            if (left.displayEligible !== right.displayEligible) {
                return left.displayEligible ? -1 : 1;
            }
            const leftRank = Number(left.liquidityRank || 0);
            const rightRank = Number(right.liquidityRank || 0);
            if (leftRank > 0 && rightRank > 0 && leftRank !== rightRank) {
                return leftRank - rightRank;
            }
            const barsDiff = Number(right.bars24hCount || 0) - Number(left.bars24hCount || 0);
            if (barsDiff !== 0) {
                return barsDiff;
            }
            const tradeDiff = Number(right.trade24hCount || 0) - Number(left.trade24hCount || 0);
            if (tradeDiff !== 0) {
                return tradeDiff;
            }
            const quoteDiff = Number(right.quote24hCount || 0) - Number(left.quote24hCount || 0);
            if (quoteDiff !== 0) {
                return quoteDiff;
            }
            if (left.chartable24h !== right.chartable24h) {
                return left.chartable24h ? -1 : 1;
            }
            if (left.hasQuote !== right.hasQuote) {
                return left.hasQuote ? -1 : 1;
            }
            const timeDiff = Number(right.lastEventTsMs || right.quoteEventTsMs || 0)
                - Number(left.lastEventTsMs || left.quoteEventTsMs || 0);
            if (timeDiff !== 0) {
                return timeDiff;
            }
            return String(left.symbol || '').localeCompare(String(right.symbol || ''));
        });
    }

    async function chooseDemoDefaultSymbol(entries, requestContext) {
        const candidates = sortMarketEntries(entries)
            .filter(entry => entry.symbol && entry.hasQuote && entry.chartable)
            .slice(0, DEMO_MARKET_HISTORY_PROBE_LIMIT);
        if (candidates.length === 0) {
            return sortMarketEntries(entries)
                .filter(entry => entry.symbol)
                .sort((left, right) => demoFallbackScore(right) - demoFallbackScore(left))[0]?.symbol || '';
        }
        const base = adapterBase();
        const nowSec = Math.floor(Date.now() / 1000);
        const fromSec = nowSec - DEMO_DEFAULT_LOOKBACK_SECONDS;
        const scored = await Promise.all(candidates.map(async entry => {
            let barCount = 0;
            try {
                const data = await fetchJsonFromBase(
                    base,
                    `/datafeed/history?symbol=${encodeURIComponent(entry.symbol)}` +
                    `&resolution=${encodeURIComponent(DEMO_DEFAULT_RESOLUTION)}` +
                    `&from=${fromSec}&to=${nowSec}`,
                    { signal: requestContext.signal }
                );
                barCount = data?.s === 'ok' && Array.isArray(data.t) ? data.t.length : 0;
            } catch (err) {
                if (isStaleMarketCatalogError(err)) {
                    throw err;
                }
            }
            return {
                entry,
                score: barCount * 100000 + demoFallbackScore(entry),
                barCount
            };
        }));
        scored.sort((left, right) => right.score - left.score || String(left.entry.symbol).localeCompare(right.entry.symbol));
        return scored[0]?.entry.symbol || candidates[0].symbol;
    }

    function demoFallbackScore(entry) {
        const quoteAgeMs = Number(entry.quoteAgeMs);
        const freshnessBonus = Number.isFinite(quoteAgeMs)
            ? Math.max(0, 60000 - quoteAgeMs) / 100
            : 0;
        return Number(entry.bars24hCount || 0) * 1000
            + Number(entry.quote24hCount || 0)
            + Number(entry.trade24hCount || 0) * 25
            + (entry.chartableFromBbo ? 5000 : 0)
            + (entry.hasQuote ? 2500 : 0)
            + freshnessBonus;
    }

    function populateSymbolDropdown(entries, preferredSymbol) {
        dom.symbolSelect.innerHTML = '';
        const selectable = entries.filter(entry => entry.displayEligible || entry.chartable || entry.hasQuote);
        const dropdownEntries = selectable.length > 0 ? selectable : entries;
        if (dropdownEntries.length === 0) {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = '(no markets)';
            dom.symbolSelect.appendChild(opt);
            return;
        }
        for (const entry of dropdownEntries) {
            const opt = document.createElement('option');
            opt.value = entry.symbol;
            opt.textContent = `${entry.symbol} (${marketCapabilityLabel(entry)})`;
            dom.symbolSelect.appendChild(opt);
        }
        if (preferredSymbol && dropdownEntries.some(entry => entry.symbol === preferredSymbol)) {
            dom.symbolSelect.value = preferredSymbol;
        } else {
            dom.symbolSelect.value = dropdownEntries[0].symbol;
        }
    }

    function renderMarketCatalog(entries) {
        const pageStart = entries.length === 0 ? 0 : marketCatalogOffset + 1;
        const pageEnd = marketCatalogOffset + entries.length;
        const total = Math.max(marketCatalogTotal, pageEnd);
        dom.marketCount.textContent = total > entries.length
            ? `${pageStart}-${pageEnd}/${total}`
            : String(entries.length);
        if (dom.marketPageState) {
            dom.marketPageState.textContent = entries.length === 0
                ? `Showing 0 of ${formatCompactNumber(total)}`
                : `Showing ${formatCompactNumber(pageStart)}-${formatCompactNumber(pageEnd)} of ${formatCompactNumber(total)}`;
        }
        if (dom.marketPrevPage) {
            dom.marketPrevPage.disabled = marketCatalogOffset <= 0;
        }
        if (dom.marketNextPage) {
            dom.marketNextPage.disabled = pageEnd >= total || entries.length === 0;
        }
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
            filters.push(`raw status ${status}`);
        }
        const capability = dom.marketCapabilityFilter ? dom.marketCapabilityFilter.value.trim() : 'all';
        if (capability && capability !== 'all') {
            filters.push(`capability ${marketFilterLabel(capability)}`);
        }
        dom.marketState.textContent = filters.length
            ? `Showing ${pageStart}-${pageEnd} of ${total} asset(s) for ${filters.join(' / ')}`
            : marketCatalogTotal > entries.length
                ? `Showing ${pageStart}-${pageEnd} of ${total} asset(s)`
                : `${entries.length} asset(s)`;
        for (const entry of entries) {
            const button = document.createElement('button');
            button.type = 'button';
            button.dataset.symbol = entry.symbol;
            button.title = `${entry.symbol} / ${marketCapabilityText(entry)}`;
            button.setAttribute('aria-label', button.title);
            button.innerHTML = `<strong class="ticker-text">${escapeHtml(entry.symbol)}</strong>` +
                `<span>${escapeHtml(marketCatalogStatusText(entry))}</span>`;
            button.addEventListener('click', () => {
                dom.symbolSelect.value = entry.symbol;
                onSelectedSymbolChanged();
            });
            dom.marketList.appendChild(button);
        }
    }

    function marketFilterLabel(value) {
        const option = dom.marketCapabilityFilter
            ? Array.from(dom.marketCapabilityFilter.options).find(item => item.value === value)
            : null;
        return option ? option.textContent : value;
    }

    function marketCatalogStatusText(entry) {
        const rank = entry.liquidityRank ? `#${entry.liquidityRank}` : null;
        const bars = entry.displayEligible ? `${formatCompactNumber(entry.bars24hCount || 0)} 24h bars` : null;
        return [entry.status || '-', rank, marketCapabilityLabel(entry), bars]
            .filter(Boolean)
            .join(' / ');
    }

    function marketCapabilityLabel(entry) {
        if (entry.displayEligible) {
            return `${chartSourceLabel(entry.bestChartSource)} eligible`;
        }
        if (entry.chartable1h) {
            return `${chartSourceLabel(entry.bestChartSource)} 1h`;
        }
        if (entry.chartable24h) {
            return `${chartSourceLabel(entry.bestChartSource)} 24h`;
        }
        if (entry.chartable) {
            return `${chartSourceLabel(entry.bestChartSource)} history`;
        }
        if (entry.hasQuote) {
            return entry.quoteStatus === 'stale_quote' ? 'stale quote' : 'quote only';
        }
        if (entry.semanticStatus && entry.semanticStatus !== 'missing') {
            return `semantic ${entry.semanticStatus}`;
        }
        if (entry.catalogSource === 'market_metadata') {
            return 'metadata only';
        }
        return entry.catalogSource || 'catalog only';
    }

    function chartSourceLabel(source) {
        switch (source) {
            case 'bbo':
                return 'BBO';
            case 'ticker_snapshot':
                return 'ticker snapshot';
            case 'trade_tape':
                return 'trade tape';
            default:
                return 'chart';
        }
    }

    function marketCapabilityText(entry) {
        const parts = [marketCapabilityLabel(entry)];
        if (entry.quoteEventTsMs != null) {
            parts.push(`quote ${formatAge(Date.now() - entry.quoteEventTsMs)}`);
        }
        if (entry.bestChartSource) {
            parts.push(`chart source ${chartSourceLabel(entry.bestChartSource)}`);
        }
        if (entry.bars24hCount != null) {
            parts.push(`${formatCompactNumber(entry.bars24hCount)} 24h bars`);
        }
        if (entry.liquidityRank) {
            parts.push(`rank ${entry.liquidityRank}`);
        }
        if (entry.semanticStatus) {
            parts.push(`semantic ${entry.semanticStatus}`);
        }
        return parts.join(' / ');
    }

    function renderCapabilitySummary(summary) {
        if (!dom.marketCapabilitySummary) {
            return;
        }
        if (!summary) {
            dom.marketCapabilitySummary.innerHTML = '';
            renderSemanticCoverageSummary(null);
            return;
        }
        const total = Number(summary.total_assets || 0);
        const eligible = Number(summary.display_eligible_count || summary.eligible_count || 0);
        const filteredOut = Number(summary.filtered_out_count || summary.excluded_count || 0);
        const subscribed = Number(summary.subscribed_count || eligible);
        const capped = Number(summary.capped_count || 0);
        const items = [
            ['Catalog total', total],
            ['Eligible', eligible],
            ['Filtered <10 bars', filteredOut],
            ['Subscribed', subscribed],
            ['Remaining cap', capped],
            ['Chartable', summary.chartable_count],
            ['With quote', summary.quote_count],
            ['Stale quote', summary.stale_quote_count]
        ];
        dom.marketCapabilitySummary.innerHTML = items.map(([label, value]) =>
            `<div class="capability-chip"><strong>${escapeHtml(formatCompactNumber(value || 0))}</strong>` +
            `<span>${escapeHtml(label)}</span></div>`
        ).join('');
        renderSemanticCoverageSummary(summary);
    }

    function renderSemanticCoverageSummary(summary) {
        if (!dom.semanticCoverageSummary) {
            return;
        }
        if (!summary) {
            dom.semanticCoverageSummary.innerHTML =
                '<div class="coverage-chip"><strong>-</strong><span>coverage unavailable</span></div>';
            return;
        }
        const generated = Number(summary.semantic_eligible_generated_count || summary.semantic_generated_count || 0);
        const missing = Number(summary.semantic_eligible_missing_count || summary.semantic_missing_count || 0);
        const total = Number(summary.display_eligible_count || summary.eligible_count || summary.total_assets || 0);
        const review = Number(summary.semantic_review_required_count || 0);
        const failed = Number(summary.semantic_failed_count || 0);
        const rateLimited = Number(summary.semantic_rate_limited_count || 0);
        const coveragePct = total > 0 ? Math.round((generated / total) * 100) : 0;
        const items = [
            ['Eligible generated', `${formatCompactNumber(generated)} / ${formatCompactNumber(total)} (${coveragePct}%)`],
            ['Missing', formatCompactNumber(missing)],
            ['Review', formatCompactNumber(review)],
            ['Failed', formatCompactNumber(failed + rateLimited)]
        ];
        dom.semanticCoverageSummary.innerHTML = items.map(([label, value]) =>
            `<div class="coverage-chip"><strong>${escapeHtml(String(value || '-'))}</strong>` +
            `<span>${escapeHtml(label)}</span></div>`
        ).join('');
    }

    function renderMarketCatalogError(message) {
        dom.marketCount.textContent = '0';
        dom.marketState.textContent = `Market catalog unavailable: ${message}`;
        dom.marketList.innerHTML = '<div class="empty">market catalog unavailable</div>';
    }

    function hasMarketFilter() {
        const query = dom.marketSearch ? dom.marketSearch.value.trim() : '';
        const status = dom.marketStatusFilter ? dom.marketStatusFilter.value.trim() : '';
        const capability = dom.marketCapabilityFilter ? dom.marketCapabilityFilter.value.trim() : 'all';
        return query !== '' || status !== '' || (capability !== '' && capability !== 'all');
    }

    function scheduleMarketSearch() {
        if (marketSearchTimer != null) {
            clearTimeout(marketSearchTimer);
        }
        marketSearchTimer = setTimeout(() => {
            marketSearchTimer = null;
            resetMarketPageAndLoad();
        }, MARKET_SEARCH_DEBOUNCE_MS);
    }

    function ensureActiveMarketCatalogRequest(requestContext) {
        if (!requestContext
            || requestContext.generation !== marketCatalogGeneration
            || requestContext.signal?.aborted === true) {
            const error = new Error('stale market catalog request');
            error.marketCatalogStale = true;
            throw error;
        }
    }

    function isStaleMarketCatalogError(error) {
        return error?.marketCatalogStale === true || error?.name === 'AbortError';
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
                ...(cached || {}),
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
        dom.marketStatus.textContent = `${entry.status || '-'} / ${marketCapabilityLabel(entry)}`;
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
        semanticMapDirty = false;
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

    function requestSemanticMapLoad() {
        semanticMapDirty = true;
        if (activeRole === 'semantic') {
            loadSemanticMap();
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
        const coverageSuffix = marketCapabilitySummary
            ? ` / classified ${formatCompactNumber(
                marketCapabilitySummary.semantic_eligible_generated_count
                    || marketCapabilitySummary.semantic_generated_count
                    || 0
            )}` +
                ` of ${formatCompactNumber(
                    marketCapabilitySummary.display_eligible_count
                        || marketCapabilitySummary.eligible_count
                        || 0
                )} eligible asset(s)`
            : '';
        // The heatmap itself carries sector grouping; external chips add noise here.
        const activeGroup = semanticActiveGroupKey
            ? groups.find(group => String(group.key || 'unknown') === semanticActiveGroupKey)
            : null;
        if (semanticActiveGroupKey && !activeGroup) {
            semanticActiveGroupKey = '';
            dom.semanticDrillup.disabled = true;
        }
        const selectedGroups = activeGroup ? [activeGroup] : groups;
        const limit = Math.min(500, SEMANTIC_RENDER_LEAF_LIMIT);
        const sectors = semanticRenderableFinvizSectors(selectedGroups, limit);
        const displayedMarketCount = sectors.reduce((sum, sector) => sum + sector.markets.length, 0);
        const sourceMarketCount = semanticBaseMarketCount(selectedGroups);
        const hiddenCount = Math.max(0, sourceMarketCount - displayedMarketCount);
        dom.semanticMapState.textContent =
            `${displayedMarketCount} market cell(s) / ${sectors.length} sector(s)` +
            ` / size by aggregate open interest / color by 24h midpoint move` +
            `${hiddenCount > 0 ? ` / ${hiddenCount} hidden` : ''}` +
            `${activeGroup ? ` / ${activeGroup.label || activeGroup.key}` : ` / grouped by ${groupBy}`}` +
            coverageSuffix;
        renderSemanticFinvizSectorMap(sectors);
        renderSemanticDetail(null);
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

    function semanticBaseMarketCount(groups) {
        const keys = new Set();
        for (const leaf of semanticLeaves(groups).filter(item => item && item.market_ticker)) {
            keys.add(semanticBaseMarketKey(leaf));
        }
        return keys.size;
    }

    function semanticRenderableFinvizSectors(groups, limit) {
        const markets = [];
        for (const group of groups) {
            const sectorKey = String(group.key || 'unknown');
            const sectorLabel = group.label || sectorKey;
            const marketsByKey = new Map();
            for (const leaf of Array.isArray(group.leaves) ? group.leaves : []) {
                if (!leaf || !leaf.market_ticker) {
                    continue;
                }
                const key = semanticBaseMarketKey(leaf);
                if (!marketsByKey.has(key)) {
                    marketsByKey.set(key, {
                        key,
                        label: key,
                        market_ticker: key,
                        is_base_market_cell: true,
                        representative_market_ticker: leaf.market_ticker,
                        group_key: sectorKey,
                        sector: leaf.sector || sectorLabel,
                        subsector: leaf.subsector,
                        event_type: leaf.event_type,
                        semantic_status: leaf.semantic_status,
                        market_status: leaf.market_status,
                        title: leaf.title || leaf.label,
                        tags: Array.isArray(leaf.tags) ? leaf.tags.slice(0, 8) : [],
                        metadata_confidence: leaf.metadata_confidence,
                        aggregate_open_interest: 0,
                        value: 0,
                        representative_value: 0,
                        representative_pct: null,
                        representative_change_micros: null,
                        representative_midpoint_micros: null,
                        side_value_sum: 0,
                        side_count: 0,
                        latestEventTsMs: 0,
                        leaves: [],
                        weightedPct: 0,
                        pctWeight: 0,
                        weightedChange: 0,
                        changeWeight: 0,
                        weightedCurrentMidpoint: 0,
                        midpointWeight: 0
                    });
                }
                const market = marketsByKey.get(key);
                const sideValue = Math.max(1, Number(leaf.open_interest ?? leaf.quote?.open_interest ?? leaf.value ?? 0) || 0);
                const aggregateValue = Number(
                    leaf.aggregate_open_interest
                        ?? leaf.quote?.aggregate_open_interest
                        ?? leaf.base_open_interest
                        ?? leaf.quote?.base_open_interest
                        ?? 0
                );
                market.leaves.push(leaf);
                market.side_value_sum += sideValue;
                market.aggregate_open_interest = Math.max(market.aggregate_open_interest, Number.isFinite(aggregateValue) ? aggregateValue : 0);
                market.side_count += 1;
                market.latestEventTsMs = Math.max(
                    market.latestEventTsMs,
                    Number(leaf.quote?.last_event_ts_ms ?? leaf.last_event_ts_ms ?? 0) || 0
                );
                if (sideValue > Number(market.representative_value || 0)) {
                    market.representative_value = sideValue;
                    market.representative_market_ticker = leaf.market_ticker;
                    market.title = leaf.title || leaf.label || market.title;
                    market.representative_pct = semanticPriceChangePct(leaf);
                    market.representative_change_micros = semanticPriceChange24hMicros(leaf);
                    market.representative_midpoint_micros =
                        leaf.current_midpoint_micros
                        ?? leaf.quote?.current_midpoint_micros
                        ?? leaf.midpoint_micros
                        ?? leaf.quote?.midpoint_micros
                        ?? null;
                }
                const pct = semanticPriceChangePct(leaf);
                if (Number.isFinite(pct)) {
                    market.weightedPct += pct * sideValue;
                    market.pctWeight += sideValue;
                }
                const change = Number(semanticPriceChange24hMicros(leaf));
                if (Number.isFinite(change)) {
                    market.weightedChange += change * sideValue;
                    market.changeWeight += sideValue;
                }
                const midpoint = Number(
                    leaf.current_midpoint_micros
                        ?? leaf.quote?.current_midpoint_micros
                        ?? leaf.midpoint_micros
                        ?? leaf.quote?.midpoint_micros
                        ?? 0
                );
                if (Number.isFinite(midpoint) && midpoint > 0) {
                    market.weightedCurrentMidpoint += midpoint * sideValue;
                    market.midpointWeight += sideValue;
                }
            }
            for (const market of marketsByKey.values()) {
                market.value = Math.max(1, market.aggregate_open_interest, market.side_value_sum);
                if (Number.isFinite(Number(market.representative_pct))) {
                    market.midpoint_change_pct = Number(market.representative_pct);
                } else if (market.pctWeight > 0) {
                    market.midpoint_change_pct = market.weightedPct / market.pctWeight;
                }
                if (Number.isFinite(Number(market.representative_change_micros))) {
                    market.price_change_24h_micros = Math.round(Number(market.representative_change_micros));
                } else if (market.changeWeight > 0) {
                    market.price_change_24h_micros = Math.round(market.weightedChange / market.changeWeight);
                }
                if (Number.isFinite(Number(market.representative_midpoint_micros)) && Number(market.representative_midpoint_micros) > 0) {
                    market.current_midpoint_micros = Math.round(Number(market.representative_midpoint_micros));
                } else if (market.midpointWeight > 0) {
                    market.current_midpoint_micros = Math.round(market.weightedCurrentMidpoint / market.midpointWeight);
                }
                markets.push(market);
            }
        }
        const selectedMarkets = markets
            .sort((left, right) => {
                const valueDelta = Number(right.value || 0) - Number(left.value || 0);
                if (valueDelta !== 0) {
                    return valueDelta;
                }
                return String(left.key).localeCompare(String(right.key));
            })
            .slice(0, Math.max(1, Number(limit || SEMANTIC_RENDER_LEAF_LIMIT)));
        const sectorsByKey = new Map();
        for (const market of selectedMarkets) {
            const key = String(market.group_key || market.sector || 'unknown');
            if (!sectorsByKey.has(key)) {
                sectorsByKey.set(key, {
                    key,
                    label: market.sector || key,
                    count: 0,
                    value: 0,
                    markets: []
                });
            }
            const sector = sectorsByKey.get(key);
            sector.markets.push(market);
            sector.count += 1;
            sector.value += Math.max(1, Number(market.value || 0));
        }
        return Array.from(sectorsByKey.values())
            .sort((left, right) => {
                const valueDelta = Number(right.value || 0) - Number(left.value || 0);
                if (valueDelta !== 0) {
                    return valueDelta;
                }
                return String(left.label).localeCompare(String(right.label));
            });
    }

    function renderSemanticFinvizSectorMap(sectors) {
        const bounds = semanticLayoutBounds(4 / 3);
        const sectorRects = kdTreemap(
            sectors.map(sector => ({ item: sector, value: semanticSectorLayoutValue(sector) })),
            bounds
        );
        const fragment = document.createDocumentFragment();
        for (const rawSectorRect of sectorRects) {
            const sectorRect = semanticPercentRect(rawSectorRect, bounds);
            const sector = sectorRect.item;
            const region = document.createElement('section');
            region.className = 'semantic-sector-region';
            region.setAttribute('aria-label', `${sector.label}; ${sector.count} market cell(s)`);
            region.style.left = `${sectorRect.x}%`;
            region.style.top = `${sectorRect.y}%`;
            region.style.width = `${sectorRect.width}%`;
            region.style.height = `${sectorRect.height}%`;

            const label = document.createElement('button');
            label.type = 'button';
            label.className = 'semantic-sector-label';
            label.title = `${sector.label}\n${sector.count} market(s)\naggregate OI ${formatCompactNumber(sector.value || 0)}`;
            label.innerHTML =
                `<strong>${escapeHtml(sector.label)}</strong>` +
                `<span>${sector.count}</span>`;
            label.addEventListener('click', () => selectSemanticGroup(sector.key || 'unknown'));
            label.addEventListener('mouseenter', event => showSemanticDetailFromGroup(sector, event));
            label.addEventListener('mousemove', event => positionSemanticDetail(event));
            label.addEventListener('mouseleave', hideSemanticDetail);
            label.addEventListener('focus', event => showSemanticDetailFromGroup(sector, semanticElementAnchor(event.currentTarget)));
            label.addEventListener('blur', hideSemanticDetail);
            region.appendChild(label);

            const leafLayer = document.createElement('div');
            leafLayer.className = 'semantic-sector-leaves';
            const leafAspect = Math.max(0.25, Math.min(4, rawSectorRect.width / Math.max(1, rawSectorRect.height)));
            for (const childRect of layoutSemanticLeafTreemap(sector.markets, leafAspect)) {
                leafLayer.appendChild(createSemanticMarketTile(childRect));
            }
            region.appendChild(leafLayer);
            fragment.appendChild(region);
        }
        dom.semanticTreemap.appendChild(fragment);
    }

    function semanticSectorLayoutValue(sector) {
        const value = Math.max(1, Number(sector?.value || 0));
        const count = Math.max(1, Number(sector?.count || 0));
        return Math.sqrt(value) * Math.sqrt(count);
    }

    function semanticRenderableBaseMarkets(groups, limit) {
        const clustersByKey = new Map();
        for (const leaf of semanticLeaves(groups).filter(item => item && item.market_ticker)) {
            const key = semanticBaseMarketKey(leaf);
            if (!clustersByKey.has(key)) {
                clustersByKey.set(key, {
                    key,
                    label: key,
                    count: 0,
                    value: 0,
                    latestEventTsMs: 0,
                    leaves: []
                });
            }
            const cluster = clustersByKey.get(key);
            const side = Object.assign({}, leaf, {
                base_market_key: key,
                side_tag: semanticSideTag(leaf),
                value: Math.max(1, Number(leaf.open_interest ?? leaf.quote?.open_interest ?? leaf.value ?? 0))
            });
            cluster.leaves.push(side);
            cluster.count += 1;
            cluster.value = Math.max(
                cluster.value,
                Math.max(1, Number(
                    leaf.aggregate_open_interest
                        ?? leaf.quote?.aggregate_open_interest
                        ?? leaf.base_open_interest
                        ?? leaf.quote?.base_open_interest
                        ?? 0
                ))
            );
            cluster.latestEventTsMs = Math.max(
                cluster.latestEventTsMs,
                Number(leaf.quote?.last_event_ts_ms ?? leaf.last_event_ts_ms ?? 0) || 0
            );
        }
        const clusters = Array.from(clustersByKey.values())
            .map(cluster => {
                const sideSum = cluster.leaves.reduce((sum, leaf) => sum + Math.max(1, Number(leaf.value || 0)), 0);
                cluster.value = Math.max(cluster.value, sideSum);
                cluster.leaves.sort((left, right) => {
                    const valueDelta = Number(right.value || 0) - Number(left.value || 0);
                    if (valueDelta !== 0) {
                        return valueDelta;
                    }
                    return String(left.side_tag || left.market_ticker || '')
                        .localeCompare(String(right.side_tag || right.market_ticker || ''));
                });
                return cluster;
            })
            .filter(cluster => cluster.count > 0)
            .sort((left, right) => {
                const valueDelta = Number(right.value || 0) - Number(left.value || 0);
                if (valueDelta !== 0) {
                    return valueDelta;
                }
                const recencyDelta = Number(right.latestEventTsMs || 0) - Number(left.latestEventTsMs || 0);
                if (recencyDelta !== 0) {
                    return recencyDelta;
                }
                return String(left.key).localeCompare(String(right.key));
            });
        return clusters.slice(0, Math.max(1, Number(limit || SEMANTIC_RENDER_LEAF_LIMIT)));
    }

    function renderSemanticBaseMarketClusters(sourceGroups) {
        const groupBounds = semanticLayoutBounds(4 / 3);
        const groupRects = squarifiedTreemap(
            sourceGroups.map(group => ({ item: group, value: semanticGroupLayoutValue(group) })),
            groupBounds,
        );
        for (const rawGroupRect of groupRects) {
            const groupRect = semanticPercentRect(rawGroupRect, groupBounds);
            const group = groupRect.item;
            const region = document.createElement('section');
            region.className = 'semantic-group-region semantic-base-cluster';
            region.setAttribute('aria-label', `${group.label}; ${group.count} side tile(s)`);
            region.style.left = `${groupRect.x}%`;
            region.style.top = `${groupRect.y}%`;
            region.style.width = `${groupRect.width}%`;
            region.style.height = `${groupRect.height}%`;
            region.style.background = semanticBaseClusterColor(group);
            const label = document.createElement('button');
            label.type = 'button';
            label.className = 'semantic-group-label';
            label.title = `${group.label}\n${group.count} side(s)\naggregate OI ${formatCompactNumber(group.value || 0)}`;
            label.setAttribute('aria-label', `${group.label}; ${group.count} side(s)`);
            label.innerHTML =
                `<strong>${escapeHtml(group.label)}</strong>` +
                `<span>${group.count} / ${formatCompactNumber(group.value || 0)}</span>`;
            label.addEventListener('mouseenter', event => showSemanticDetailFromBaseCluster(group, event));
            label.addEventListener('mousemove', event => positionSemanticDetail(event));
            label.addEventListener('mouseleave', hideSemanticDetail);
            label.addEventListener('focus', event => showSemanticDetailFromBaseCluster(group, semanticElementAnchor(event.currentTarget)));
            label.addEventListener('blur', hideSemanticDetail);
            region.appendChild(label);
            const leafLayer = document.createElement('div');
            leafLayer.className = 'semantic-group-leaves';
            const leafAspect = Math.max(0.25, Math.min(4, rawGroupRect.width / Math.max(1, rawGroupRect.height)));
            for (const childRect of layoutSemanticLeafTreemap(group.leaves, leafAspect)) {
                leafLayer.appendChild(createSemanticMarketTile(childRect));
            }
            region.appendChild(leafLayer);
            dom.semanticTreemap.appendChild(region);
        }
    }

    function createSemanticMarketTile(rect) {
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
        tile.addEventListener('mouseenter', event => showSemanticDetailFromLeaf(rect.leaf, event));
        tile.addEventListener('mousemove', event => positionSemanticDetail(event));
        tile.addEventListener('mouseleave', hideSemanticDetail);
        tile.addEventListener('focus', event => showSemanticDetailFromLeaf(rect.leaf, semanticElementAnchor(event.currentTarget)));
        tile.addEventListener('blur', hideSemanticDetail);
        tile.addEventListener('click', () => {
            const ticker = rect.leaf.representative_market_ticker || rect.leaf.market_ticker;
            if (ticker) {
                selectSemanticMarket(ticker, rect.groupKey);
            } else {
                showSemanticDetailFromLeaf(rect.leaf, semanticElementAnchor(tile));
            }
        });
        return tile;
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
        return leaves.slice(0, limit);
    }

    function layoutSemanticLeafTreemap(leaves, aspectRatio = 4 / 3) {
        const bounds = semanticLayoutBounds(aspectRatio);
        const rects = kdTreemap(
            leaves.map(leaf => ({ item: leaf, value: Math.max(1, Number(leaf.value || 0)) })),
            bounds,
        ).map(rect => Object.assign(semanticPercentRect(rect, bounds), {
            groupKey: rect.item.group_key || 'unknown',
            leaf: rect.item
        }));
        return rects;
    }

    function semanticLayoutBounds(aspectRatio) {
        const ratio = Math.max(0.25, Math.min(4, Number(aspectRatio) || 1));
        return { x: 0, y: 0, width: 100, height: 100 / ratio };
    }

    function semanticPercentRect(rect, bounds) {
        return {
            item: rect.item,
            x: (rect.x - bounds.x) / bounds.width * 100,
            y: (rect.y - bounds.y) / bounds.height * 100,
            width: rect.width / bounds.width * 100,
            height: rect.height / bounds.height * 100
        };
    }

    function semanticGroupLayoutValue(group) {
        const visibleCount = Math.max(1, Number(group.count || 0));
        const visibleValue = Math.max(1, Number(group.value || 0));
        return Math.sqrt(visibleCount) * Math.log1p(visibleValue);
    }

    function kdTreemap(items, rect) {
        const clean = items
            .map(item => ({
                item: item.item,
                value: Math.max(0, Number(item.value || 0))
            }))
            .filter(item => item.value > 0)
            .sort((left, right) => {
                const valueDelta = right.value - left.value;
                if (valueDelta !== 0) {
                    return valueDelta;
                }
                return String(left.item?.key || left.item?.market_ticker || '')
                    .localeCompare(String(right.item?.key || right.item?.market_ticker || ''));
            });
        const output = [];
        splitKdTreemap(clean, Object.assign({}, rect), output);
        return output;
    }

    function splitKdTreemap(items, rect, output) {
        if (items.length === 0 || rect.width <= 0 || rect.height <= 0) {
            return;
        }
        if (items.length === 1) {
            output.push(Object.assign({ item: items[0].item }, rect));
            return;
        }
        const total = items.reduce((sum, item) => sum + item.value, 0);
        if (total <= 0) {
            return;
        }
        let running = 0;
        let splitIndex = 1;
        let bestDelta = Infinity;
        for (let index = 1; index < items.length; index += 1) {
            running += items[index - 1].value;
            const delta = Math.abs(total / 2 - running);
            if (delta < bestDelta) {
                bestDelta = delta;
                splitIndex = index;
            }
        }
        const leftItems = items.slice(0, splitIndex);
        const rightItems = items.slice(splitIndex);
        const leftValue = leftItems.reduce((sum, item) => sum + item.value, 0);
        const ratio = Math.max(0.04, Math.min(0.96, leftValue / total));
        if (rect.width >= rect.height) {
            const leftWidth = rect.width * ratio;
            splitKdTreemap(leftItems, {
                x: rect.x,
                y: rect.y,
                width: leftWidth,
                height: rect.height
            }, output);
            splitKdTreemap(rightItems, {
                x: rect.x + leftWidth,
                y: rect.y,
                width: rect.width - leftWidth,
                height: rect.height
            }, output);
        } else {
            const topHeight = rect.height * ratio;
            splitKdTreemap(leftItems, {
                x: rect.x,
                y: rect.y,
                width: rect.width,
                height: topHeight
            }, output);
            splitKdTreemap(rightItems, {
                x: rect.x,
                y: rect.y + topHeight,
                width: rect.width,
                height: rect.height - topHeight
            }, output);
        }
    }

    function squarifiedTreemap(items, rect) {
        if (items.length === 0) {
            return [];
        }
        const area = Math.max(0, rect.width * rect.height);
        const total = items.reduce((sum, item) => sum + Math.max(0, Number(item.value || 0)), 0);
        if (area <= 0 || total <= 0) {
            return [];
        }
        const sorted = items
            .map(item => ({
                item: item.item,
                value: Math.max(0, Number(item.value || 0)) / total * area
            }))
            .filter(item => item.value > 0)
            .sort((a, b) => b.value - a.value);
        const output = [];
        const row = [];
        const remaining = Object.assign({}, rect);
        while (sorted.length > 0) {
            const next = sorted[0];
            const side = Math.max(1, Math.min(remaining.width, remaining.height));
            if (row.length === 0 || treemapWorst(row.concat(next), side) <= treemapWorst(row, side)) {
                row.push(next);
                sorted.shift();
            } else {
                layoutTreemapRow(row.splice(0, row.length), remaining, output);
            }
        }
        if (row.length > 0) {
            layoutTreemapRow(row, remaining, output);
        }
        return output;
    }

    function treemapWorst(row, side) {
        if (row.length === 0) {
            return Infinity;
        }
        const values = row.map(item => item.value);
        const sum = values.reduce((total, value) => total + value, 0);
        const min = Math.max(0.0001, Math.min(...values));
        const max = Math.max(...values);
        const sideSquared = side * side;
        const sumSquared = sum * sum;
        return Math.max(sideSquared * max / sumSquared, sumSquared / (sideSquared * min));
    }

    function layoutTreemapRow(row, rect, output) {
        if (row.length === 0 || rect.width <= 0 || rect.height <= 0) {
            return;
        }
        const rowArea = row.reduce((sum, item) => sum + item.value, 0);
        if (rect.width >= rect.height) {
            const rowHeight = Math.min(rect.height, rowArea / Math.max(1, rect.width));
            let x = rect.x;
            for (const item of row) {
                const width = item === row[row.length - 1]
                    ? rect.x + rect.width - x
                    : item.value / Math.max(0.0001, rowHeight);
                output.push({
                    item: item.item,
                    x,
                    y: rect.y,
                    width: Math.max(0, width),
                    height: Math.max(0, rowHeight)
                });
                x += width;
            }
            rect.y += rowHeight;
            rect.height = Math.max(0, rect.height - rowHeight);
        } else {
            const rowWidth = Math.min(rect.width, rowArea / Math.max(1, rect.height));
            let y = rect.y;
            for (const item of row) {
                const height = item === row[row.length - 1]
                    ? rect.y + rect.height - y
                    : item.value / Math.max(0.0001, rowWidth);
                output.push({
                    item: item.item,
                    x: rect.x,
                    y,
                    width: Math.max(0, rowWidth),
                    height: Math.max(0, height)
                });
                y += height;
            }
            rect.x += rowWidth;
            rect.width = Math.max(0, rect.width - rowWidth);
        }
    }

    async function selectSemanticMarket(marketTicker, groupKey) {
        if (!marketTicker) {
            return;
        }
        if (marketEntries.some(entry => entry.symbol === marketTicker)) {
            dom.symbolSelect.value = marketTicker;
            onSelectedSymbolChanged();
        }
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
            requestSemanticMapLoad();
        }
    }

    async function loadSemanticMarketDetails(marketTicker, groupKey, detailGeneration) {
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
            if (detailGeneration !== semanticMarketDetailGeneration || base !== adapterBase()) {
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
        if (leaf?.is_base_market_cell) {
            const sideCount = Number(leaf.side_count || leaf.leaves?.length || 0);
            renderSemanticDetail({
                market: leaf.market_ticker,
                title: leaf.title || leaf.label,
                status: `base market / ${sideCount} side(s)`,
                group: leaf.group_key || '-',
                sector: leaf.sector,
                subsector: leaf.subsector,
                eventType: leaf.event_type,
                tags: Array.isArray(leaf.tags) ? leaf.tags.join(', ') : '',
                confidence: confidenceText(leaf.metadata_confidence),
                openInterest: formatCompactNumber(leaf.value || leaf.aggregate_open_interest || 0),
                freshness: `${sideCount} aggregated contract(s)`,
                quote: semanticAggregateQuoteText(leaf)
            });
            return;
        }
        renderSemanticDetail({
            market: leaf.market_ticker,
            title: leaf.title || leaf.label,
            status: leaf.market_status || leaf.semantic_status,
            group: `${semanticBaseMarketKey(leaf)} / ${semanticSideTag(leaf) || '-'}`,
            sector: leaf.sector,
            subsector: leaf.subsector,
            eventType: leaf.event_type,
            tags: Array.isArray(leaf.tags) ? leaf.tags.join(', ') : '',
            confidence: confidenceText(leaf.metadata_confidence),
            openInterest: formatSemanticOpenInterest(leaf, leaf.quote),
            freshness: semanticFreshnessText(leaf.quote),
            quote: semanticQuoteText(leaf.quote, leaf)
        });
    }

    function renderSemanticDetailFromBaseCluster(group) {
        if (!group) {
            renderSemanticDetail(null);
            return;
        }
        const sides = (Array.isArray(group.leaves) ? group.leaves : [])
            .map(leaf => semanticSideTag(leaf) || leaf.market_ticker)
            .filter(Boolean)
            .slice(0, 8)
            .join(', ');
        renderSemanticDetail({
            market: group.key || '-',
            title: `${Number(group.count || 0)} side(s)`,
            status: 'base market cluster',
            group: group.key || '-',
            sector: '-',
            subsector: '-',
            eventType: '-',
            tags: sides || '-',
            confidence: '-',
            openInterest: formatCompactNumber(group.value || 0),
            freshness: `${Number(group.count || 0)} eligible side(s)`,
            quote: semanticClusterChangeText(group)
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
            group: `${row.base_market_key || groupKey || '-'} / ${row.side_tag || '-'}`,
            sector: metadata.sector,
            subsector: metadata.subsector,
            eventType: metadata.event_type,
            tags: Array.isArray(metadata.tags) ? metadata.tags.join(', ') : '',
            confidence: `${confidenceText(metadata.confidence)} / ${metadata.status || '-'}`,
            openInterest: formatSemanticOpenInterest(row, row.quote),
            freshness: semanticFreshnessText(row.quote),
            quote: semanticQuoteText(row.quote, row)
        });
    }

    function renderSemanticDetail(detail) {
        dom.semanticMarketDetail.hidden = !detail;
        dom.semanticMarketDetail.classList.toggle('visible', Boolean(detail));
        dom.semanticMarketDetail.setAttribute('aria-hidden', detail ? 'false' : 'true');
        if (!detail) {
            dom.semanticMarketDetail.removeAttribute('style');
            return;
        }
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

    function showSemanticDetailFromLeaf(leaf, anchor) {
        renderSemanticDetailFromLeaf(leaf);
        positionSemanticDetail(anchor);
    }

    function showSemanticDetailFromGroup(group, anchor) {
        renderSemanticDetailFromGroup(group);
        positionSemanticDetail(anchor);
    }

    function showSemanticDetailFromBaseCluster(group, anchor) {
        renderSemanticDetailFromBaseCluster(group);
        positionSemanticDetail(anchor);
    }

    function hideSemanticDetail() {
        renderSemanticDetail(null);
    }

    function semanticElementAnchor(element) {
        if (!element || typeof element.getBoundingClientRect !== 'function') {
            return null;
        }
        const rect = element.getBoundingClientRect();
        return {
            clientX: rect.left + Math.min(rect.width / 2, 32),
            clientY: rect.top + Math.min(rect.height / 2, 32)
        };
    }

    function positionSemanticDetail(anchor) {
        if (!anchor || anchor.clientX == null || anchor.clientY == null || !dom.semanticMarketDetail) {
            return;
        }
        const margin = 12;
        const offset = 16;
        const card = dom.semanticMarketDetail;
        const width = Math.min(380, window.innerWidth - margin * 2);
        card.style.width = `${Math.max(260, width)}px`;
        let left = Number(anchor.clientX) + offset;
        let top = Number(anchor.clientY) + offset;
        const rect = card.getBoundingClientRect();
        if (left + rect.width + margin > window.innerWidth) {
            left = Number(anchor.clientX) - rect.width - offset;
        }
        if (top + rect.height + margin > window.innerHeight) {
            top = Number(anchor.clientY) - rect.height - offset;
        }
        card.style.left = `${Math.max(margin, left)}px`;
        card.style.top = `${Math.max(margin, top)}px`;
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
            requestSemanticMapLoad();
        }, SEMANTIC_SEARCH_DEBOUNCE_MS);
    }

    function semanticStatusClass(status) {
        return String(status || 'unknown').toLowerCase().replace(/[^a-z0-9_-]/g, '-');
    }

    function semanticTileSizeClass(rect) {
        const area = Number(rect.width || 0) * Number(rect.height || 0);
        if (rect.width >= 18 && rect.height >= 13 && area >= 420) {
            return 'semantic-tile-large';
        }
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
        const ticker = escapeHtml(semanticDisplayTicker(leaf));
        const move = escapeHtml(semanticPriceMoveText(leaf));
        if (sizeClass === 'semantic-tile-tiny') {
            return `<strong>${ticker}</strong>`;
        }
        if (sizeClass === 'semantic-tile-small') {
            return `<strong>${ticker}</strong><small>${move}</small>`;
        }
        return `<strong>${ticker}</strong><small>${move}</small>`;
    }

    function semanticTileTitle(leaf, groupKey) {
        if (leaf.is_other) {
            return [
                leaf.label || 'Other',
                `${Number(leaf.hidden_count || 0)} hidden market(s)`,
                groupKey || 'multiple groups'
            ].join('\n');
        }
        if (leaf.is_base_market_cell) {
            return [
                leaf.market_ticker || '-',
                `${leaf.sector || groupKey || 'unknown'} / ${Number(leaf.side_count || 0)} side(s)`,
                `aggregate OI ${formatCompactNumber(leaf.value || leaf.aggregate_open_interest || 0)}`,
                semanticPriceMoveText(leaf),
                leaf.title || '-'
            ].join('\n');
        }
        return [
            leaf.market_ticker || '-',
            `base ${semanticBaseMarketKey(leaf)} / side ${semanticSideTag(leaf) || '-'}`,
            leaf.label || '-',
            `${groupKey || 'unknown'} / ${semanticPriceChangeText(leaf)}`,
            semanticQuoteText(leaf.quote, leaf)
        ].join('\n');
    }

    function semanticTileColor(leaf) {
        if (leaf.is_other) {
            return 'hsl(210, 20%, 30%)';
        }
        const pct = semanticPriceChangePct(leaf);
        if (Number.isFinite(pct) && pct !== 0) {
            const intensity = Math.min(1, Math.abs(pct) / 5);
            const saturation = Math.round(32 + intensity * 44);
            const lightness = Math.round(29 + intensity * 15);
            return pct > 0
                ? `hsl(132, ${saturation}%, ${lightness}%)`
                : `hsl(0, ${saturation}%, ${lightness}%)`;
        }
        const changeMicros = Number(semanticPriceChange24hMicros(leaf));
        if (!Number.isFinite(changeMicros) || changeMicros === 0) {
            return 'hsl(216, 10%, 30%)';
        }
        const intensity = Math.min(1, Math.abs(changeMicros) / 100_000);
        const saturation = Math.round(34 + intensity * 38);
        const lightness = Math.round(29 + intensity * 14);
        return changeMicros > 0
            ? `hsl(143, ${saturation}%, ${lightness}%)`
            : `hsl(0, ${saturation}%, ${lightness}%)`;
    }

    function semanticDisplayTicker(leaf) {
        const ticker = String(leaf?.base_market_key || leaf?.market_ticker || '-');
        return ticker.replace(/^KX/, 'KX');
    }

    function semanticPriceMoveText(source) {
        const pct = semanticPriceChangePct(source);
        if (Number.isFinite(pct)) {
            const sign = pct > 0 ? '+' : '';
            return `${sign}${pct.toFixed(Math.abs(pct) >= 10 ? 1 : 2)}%`;
        }
        const change = Number(semanticPriceChange24hMicros(source));
        if (!Number.isFinite(change)) {
            return '-';
        }
        const sign = change > 0 ? '+' : '';
        return `${sign}${formatMicros(change)}`;
    }

    function semanticAggregateQuoteText(source) {
        const midpoint = Number(source?.current_midpoint_micros ?? 0);
        const mid = Number.isFinite(midpoint) && midpoint > 0 ? `mid ${formatMicros(midpoint)}` : 'mid -';
        return `${mid} / ${semanticPriceMoveText(source)}`;
    }

    function semanticBaseClusterColor(group) {
        const leaves = Array.isArray(group?.leaves) ? group.leaves : [];
        let weightedChange = 0;
        let weightSum = 0;
        for (const leaf of leaves) {
            const change = Number(semanticPriceChange24hMicros(leaf));
            if (!Number.isFinite(change)) {
                continue;
            }
            const weight = Math.max(1, Number(leaf.value || leaf.open_interest || 0));
            weightedChange += change * weight;
            weightSum += weight;
        }
        if (weightSum <= 0) {
            return 'hsl(215, 14%, 24%)';
        }
        return semanticTileColor({ price_change_24h_micros: Math.round(weightedChange / weightSum) });
    }

    function semanticBaseMarketKey(leaf) {
        const explicit = leaf?.base_market_key || leaf?.base_market_ticker
            || leaf?.quote?.base_market_key || leaf?.quote?.base_market_ticker;
        if (explicit) {
            return String(explicit);
        }
        const ticker = String(leaf?.market_ticker || '');
        const index = ticker.lastIndexOf('-');
        return index > 0 ? ticker.slice(0, index) : ticker;
    }

    function semanticSideTag(leaf) {
        const explicit = leaf?.side_tag || leaf?.quote?.side_tag;
        if (explicit) {
            return String(explicit);
        }
        const ticker = String(leaf?.market_ticker || '');
        const index = ticker.lastIndexOf('-');
        return index >= 0 && index < ticker.length - 1 ? ticker.slice(index + 1) : '';
    }

    function semanticPriceChange24hMicros(source) {
        return source?.price_change_24h_micros
            ?? source?.priceChange24hMicros
            ?? source?.midpoint_change_micros
            ?? source?.midpointChangeMicros
            ?? source?.quote?.price_change_24h_micros
            ?? source?.quote?.priceChange24hMicros
            ?? source?.quote?.midpoint_change_micros
            ?? source?.quote?.midpointChangeMicros
            ?? null;
    }

    function semanticPriceChangePct(source) {
        const value = source?.midpoint_change_pct
            ?? source?.midpointChangePct
            ?? source?.quote?.midpoint_change_pct
            ?? source?.quote?.midpointChangePct
            ?? null;
        const number = Number(value);
        if (Number.isFinite(number)) {
            return number;
        }
        const change = Number(semanticPriceChange24hMicros(source));
        const midpoint = Number(
            source?.current_midpoint_micros
                ?? source?.quote?.current_midpoint_micros
                ?? source?.midpoint_micros
                ?? source?.quote?.midpoint_micros
                ?? 0
        );
        if (Number.isFinite(change) && Number.isFinite(midpoint) && midpoint > 0) {
            return change / midpoint * 100;
        }
        return null;
    }

    function semanticPriceChangeText(source) {
        const change = Number(semanticPriceChange24hMicros(source));
        if (!Number.isFinite(change)) {
            return '24h -';
        }
        const sign = change > 0 ? '+' : '';
        return `24h ${sign}${formatMicros(change)}`;
    }

    function confidenceText(value) {
        const number = Number(value);
        if (!Number.isFinite(number)) {
            return 'confidence -';
        }
        return `${Math.round(number * 100)}%`;
    }

    function semanticQuoteText(quote, source) {
        if (!quote) {
            return 'quote -';
        }
        const mid = formatMicros(semanticMidpointMicros(quote));
        const openInterest = formatSemanticOpenInterest(source, quote);
        const change = semanticPriceChangeText(source || quote);
        const age = quote.latest_state_age_ms == null ? '-' : formatAge(Number(quote.latest_state_age_ms));
        return `mid ${mid} / ${change} / oi ${openInterest} / ${age}`;
    }

    function semanticClusterChangeText(group) {
        let weightedChange = 0;
        let weightSum = 0;
        for (const leaf of Array.isArray(group?.leaves) ? group.leaves : []) {
            const change = Number(semanticPriceChange24hMicros(leaf));
            if (!Number.isFinite(change)) {
                continue;
            }
            const weight = Math.max(1, Number(leaf.value || leaf.open_interest || 0));
            weightedChange += change * weight;
            weightSum += weight;
        }
        if (weightSum <= 0) {
            return '24h -';
        }
        return semanticPriceChangeText({ price_change_24h_micros: Math.round(weightedChange / weightSum) });
    }

    function semanticMidpointMicros(quote) {
        if (!quote) {
            return null;
        }
        const explicit = quote.midpoint_micros ?? quote.midpointMicros ?? quote.mid_price_micros;
        if (explicit != null) {
            return explicit;
        }
        const bid = quote.best_bid_micros ?? quote.bid_micros ?? quote.yes_bid_micros;
        const ask = quote.best_ask_micros ?? quote.ask_micros ?? quote.yes_ask_micros;
        const bidNumber = Number(bid);
        const askNumber = Number(ask);
        if (Number.isFinite(bidNumber) && Number.isFinite(askNumber)) {
            return Math.round((bidNumber + askNumber) / 2);
        }
        return bid ?? ask ?? null;
    }

    function formatSemanticOpenInterest(source, quote) {
        const value = source?.open_interest
            ?? source?.openInterest
            ?? source?.dollar_open_interest
            ?? quote?.open_interest
            ?? quote?.openInterest
            ?? quote?.dollar_open_interest;
        return value == null ? '-' : formatCompactNumber(value);
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
        const capability = selectedMarketEntry(symbol);
        if (capability && !capability.chartable) {
            candleSeries.setData([]);
            volumeSeries.setData([]);
            const message = nonChartableMessage(capability);
            renderChartState(message, capability.hasQuote ? 'stale' : 'stale');
            setStatus(message, capability.hasQuote ? '' : 'error');
            return;
        }
        const toSec = Math.floor(Date.now() / 1000);
        const fromSec = toSec - lookbackSeconds;
        const base = adapterBase();
        setStatus(`Loading history ${symbol} @ ${resolution}...`);
        renderChartState(`Loading ${symbol} bars...`, '');
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
                if (capability && capability.chartable24h && lookbackSeconds < 86400) {
                    dom.lookbackSelect.value = '86400';
                    setStatus(`No 1h bars for ${symbol}; retrying 24h window`, '');
                    loadHistory();
                    return;
                }
                candleSeries.setData([]);
                volumeSeries.setData([]);
                const source = capability?.bestChartSource ? chartSourceLabel(capability.bestChartSource) : 'chart';
                const message = capability && capability.chartable
                    ? `No ${source} bars for ${symbol} in selected window`
                    : `No chart bars for ${symbol} in selected window`;
                renderChartState(message, 'stale');
                setStatus(message, 'error');
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
            lastHistoryRefreshMs = Date.now();
            const source = data.source || capability?.bestChartSource || 'chart';
            const rendered = `Rendered ${candles.length} ${chartSourceLabel(source)} bar(s) for ${symbol}`;
            renderChartState(rendered, 'fresh');
            setStatus(rendered, 'ok');
        } catch (err) {
            if (symbol !== dom.symbolSelect.value ||
                resolution !== dom.resolutionSelect.value ||
                base !== adapterBase()) {
                return;
            }
            setStatus(`Failed to load history: ${err.message}`, 'error');
            renderChartState(`History unavailable: ${err.message}`, 'stale');
        }
    }

    function selectedMarketEntry(symbol) {
        return marketEntries.find(entry => entry.symbol === symbol) || null;
    }

    function nonChartableMessage(entry) {
        if (!entry) {
            return 'Select a chart-ready or quote-ready market';
        }
        if (entry.hasQuote) {
            return `${entry.symbol}: Quote only; no BBO, ticker snapshot, or trade tape bars indexed`;
        }
        if (entry.catalogSource === 'market_metadata') {
            return `${entry.symbol}: Catalog only; no quote or chart history indexed`;
        }
        return `${entry.symbol}: No chart history in the selected dataset`;
    }

    function renderChartState(message, tone) {
        if (!dom.chartState) {
            return;
        }
        dom.chartState.textContent = message || '';
        dom.chartState.className = 'chart-state';
        if (tone === 'fresh' || tone === 'stale') {
            dom.chartState.classList.add(tone);
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
        recordDistributionUpdate(data, symbol, quote);
        loadRecentFeatures(symbol);
        if (data.changed === true && Date.now() - lastHistoryRefreshMs >= CHART_AUTO_REFRESH_MS) {
            loadHistory();
        }
        return true;
    }

    function recordDistributionUpdate(data, symbol, quote) {
        distributionUpdateCount += 1;
        const now = Date.now();
        if (distributionFirstUpdateAtMs == null) {
            distributionFirstUpdateAtMs = now;
        }
        distributionLastUpdateAtMs = now;
        distributionLastPayload = {
            endpoint: distributionEndpointName(distributionConnectionState),
            protocol: distributionProtocolName(distributionConnectionState),
            symbol,
            changed: data.changed === true,
            sequence: data.sequence ?? null,
            server_ts_ms: data.server_ts_ms ?? null,
            quote: quote ? {
                symbol: quote.symbol,
                bid_micros: quote.bid_micros ?? null,
                ask_micros: quote.ask_micros ?? null,
                midpoint_micros: quote.midpoint_micros ?? null,
                event_ts_ms: quote.event_ts_ms ?? null,
                source_event_id: quote.source_event_id || null
            } : null
        };
        renderDistributionStatus(lastHealthBody);
    }

    function distributionProtocolName(state) {
        const text = String(state || '').toLowerCase();
        if (text.includes('sse')) {
            return 'server-sent events';
        }
        if (text.includes('long-poll')) {
            return 'long-poll';
        }
        return 'snapshot REST';
    }

    function distributionEndpointName(state) {
        const text = String(state || '').toLowerCase();
        if (text.includes('sse')) {
            return '/quotes/stream';
        }
        if (text.includes('long-poll')) {
            return '/quotes/updates';
        }
        return '/quotes';
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
            renderProductModeSurface(body);
            renderDistributionStatus(body);
            renderReplayStatus({ replay_demo: body.replay_demo || {} });
            renderDemoSignal(body);
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
            requestSemanticMapLoad();
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

    async function loadReplayStatus() {
        try {
            const body = await fetchJson('/api/demo/replay/status');
            renderReplayStatus(body);
        } catch (err) {
            renderReplayStatus({ replay_demo: { status: 'unavailable', error: err.message } });
        }
    }

    function renderReplayStatus(body) {
        if (!dom.replayStatusState) {
            return;
        }
        const replay = body?.replay_demo || body || {};
        const state = replay.status || body?.status || 'unknown';
        dom.replayStatusState.textContent = state;
        dom.replayStatusState.className = replay.dataset_ready === true || state === 'projected' ? 'fresh'
            : state === 'empty' || state === 'disabled' ? ''
            : 'stale';
        dom.replayStatusId.textContent = replay.replay_id || '-';
        dom.replayStatusDataset.textContent =
            `markets ${replay.market_count || 0} / canonical ${replay.canonical_event_count || 0}`;
        dom.replayStatusProjection.textContent =
            `features ${replay.feature_output_count || 0} / latest ${replay.latest_market_state_count || 0}` +
            ` / projected ${yesNo(replay.featureplant_projected)}`;
        const first = replay.first_event_ts_ms == null ? '-' : formatEventTs(replay.first_event_ts_ms);
        const last = replay.last_event_ts_ms == null ? '-' : formatEventTs(replay.last_event_ts_ms);
        dom.replayStatusWindow.textContent =
            `${first} -> ${last} / commit ` +
            `${replay.first_canonical_commit_seq ?? '-'}-${replay.last_canonical_commit_seq ?? '-'}`;
        const symbols = Array.isArray(replay.available_symbols) ? replay.available_symbols : [];
        dom.replayStatusSymbols.textContent = symbols.length
            ? `${symbols.length}: ${symbols.slice(0, 5).join(', ')}${symbols.length > 5 ? ', ...' : ''}`
            : '-';
        dom.replayStatusError.textContent = replay.error || '-';
    }

    function renderDistributionStatus(body) {
        if (!dom.distributionConnectionStatus) {
            return;
        }
        const streams = body?.quote_streams || {};
        const updates = body?.quote_updates || {};
        const state = distributionConnectionState || 'idle';
        dom.distributionConnectionStatus.textContent = state;
        dom.distributionConnectionStatus.className = String(state).toLowerCase().includes('error') ? 'stale' : 'fresh';
        dom.distributionProtocols.textContent = 'SSE / long-poll / snapshot REST';
        dom.distributionEndpoints.textContent = '/quotes/stream, /quotes/updates, /quotes';
        dom.distributionUpdateCount.textContent =
            `${distributionUpdateCount} local / SSE ${streams.events || 0} / long-poll ${updates.changed || 0}`;
        const elapsedSeconds = distributionFirstUpdateAtMs == null
            ? 0
            : Math.max(1, (Date.now() - distributionFirstUpdateAtMs) / 1000);
        dom.distributionUpdateRate.textContent = elapsedSeconds > 0
            ? `${(distributionUpdateCount / elapsedSeconds).toFixed(2)} update/s`
            : '-';
        dom.distributionStreamStatus.textContent =
            `${streams.active_streams || 0}/${streams.max_streams || 0} SSE active` +
            ` / ${updates.active_waits || 0}/${updates.max_waits || 0} long-poll waits`;
        dom.distributionLastEvent.textContent = distributionLastUpdateAtMs == null
            ? '-'
            : new Date(distributionLastUpdateAtMs).toLocaleTimeString();
        dom.distributionSamplePayload.textContent = distributionLastPayload
            ? JSON.stringify(distributionLastPayload, null, 2)
            : JSON.stringify({
                endpoints: ['/quotes/stream', '/quotes/updates', '/quotes'],
                selected_symbol: dom.symbolSelect.value || null,
                waiting_for: 'quote update'
            }, null, 2);
    }

    function throughputText(pipeline) {
        if (!pipeline) {
            return '-';
        }
        const windowSeconds = Number(pipeline.recent_window_seconds || 0);
        const canonical = Number(pipeline.recent_canonical_events || 0);
        const features = Number(pipeline.recent_feature_outputs || 0);
        const latest = Number(pipeline.recent_latest_market_states || 0);
        if (windowSeconds <= 0) {
            return `canonical ${canonical} / features ${features} / latest ${latest}`;
        }
        return `${((canonical + features + latest) / windowSeconds).toFixed(2)} event/s` +
            ` / canonical ${canonical} / features ${features} / latest ${latest}`;
    }

    function wsSubscribedText(body) {
        const streams = body?.quote_streams || {};
        const updates = body?.quote_updates || {};
        return `${streams.active_streams || 0}/${streams.max_streams || 0} SSE` +
            ` / ${updates.active_waits || 0}/${updates.max_waits || 0} waits`;
    }

    function dbQueueDepthText(body) {
        const db = body?.db_writer || body?.database_writer || body?.db || {};
        const raw = db.raw_queue_depth ?? db.queue_depth ?? db.pending_rows ?? db.pending_events;
        const canonical = db.canonical_queue_depth ?? db.canonical_pending_rows ?? null;
        if (raw == null && canonical == null) {
            return 'unavailable';
        }
        return canonical == null ? String(raw) : `raw ${raw ?? '-'} / canonical ${canonical}`;
    }

    function renderDemoSignal(healthBody) {
        if (!dom.demoSignalLive) {
            return;
        }
        const health = healthBody || lastHealthBody || {};
        const freshness = health.data_freshness || {};
        const replay = health.replay_demo || {};
        const pipeline = lastOpsPipeline?.pipeline || health.operator_pipeline || {};
        const latency = lastOpsLatency || {};
        const ageText = typeof freshness.latest_event_age_ms === 'number'
            ? formatAge(Math.max(0, freshness.latest_event_age_ms))
            : '-';
        const latestSymbol = freshness.symbol ? ` / ${freshness.symbol}` : '';
        const liveText = freshness.live_data_observed === true ? 'LIVE' : 'not live';
        dom.demoSignalLive.textContent = `${liveText} age ${ageText}${latestSymbol}`;
        dom.demoSignalLive.parentElement.classList.toggle('stale', freshness.live_data_observed !== true);
        dom.demoSignalLatency.textContent = latency.canonical_to_latest_state_ms == null
            ? ageText
            : `${latency.canonical_to_latest_state_ms} ms projection`;
        dom.demoSignalThroughput.textContent = throughputText(pipeline);
        dom.demoSignalDistribution.textContent =
            `${distributionUpdateCount} browser / ${health.quote_streams?.events || 0} SSE / ` +
            `${health.quote_updates?.changed || 0} long-poll`;
        dom.demoSignalReplay.textContent = replay.status
            ? `${replay.status} / ${replay.feature_output_count || 0} features`
            : '-';
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
        dom.latencyThroughput.textContent = throughputText(pipeline);
        dom.latencyWsSubscribed.textContent = wsSubscribedText(body);
        dom.latencyDbQueueDepth.textContent = dbQueueDepthText(body);
        renderDemoSignal(lastHealthBody);
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
            : `${e2e} ms projection / ${latency.status || 'ok'}`;
        dom.operatorE2eLatency.className = latency.status === 'ok' ? 'fresh' : 'stale';
        dom.operatorLatencyBudget.textContent = e2e == null
            ? `${OPERATOR_LATENCY_BUDGET_MS} ms / missing`
            : `${OPERATOR_LATENCY_BUDGET_MS} ms / ${e2e <= OPERATOR_LATENCY_BUDGET_MS ? 'within' : 'over'}`;
        dom.operatorLatencyBudget.className = e2e != null && e2e <= OPERATOR_LATENCY_BUDGET_MS ? 'fresh' : 'stale';
        dom.latencyThroughput.textContent = throughputText(pipeline);
        dom.latencyWsSubscribed.textContent = wsSubscribedText(lastHealthBody);
        dom.latencyDbQueueDepth.textContent = dbQueueDepthText(lastHealthBody);
        renderDemoSignal(lastHealthBody);
        renderProductModeSurface(lastHealthBody);
    }

    function renderProductModeSurface(body) {
        const health = body || {};
        const replay = health.replay_demo || {};
        const semantic = health.semantic_metadata || {};
        const freshness = health.data_freshness || {};
        const release = health.release || {};
        const latency = lastOpsLatency || {};
        const liveState = dataFreshnessLiveState(freshness);
        const replayReady = replay.dataset_ready === true || replay.canonical_event_count > 0;
        const semanticRows = (semantic.generated_count || 0)
            + (semantic.review_required_count || 0)
            + (semantic.failed_count || 0)
            + (semantic.rate_limited_count || 0);
        const mode = freshness.live_data_observed === true
            ? 'Live Product'
            : replayReady && freshness.source_kind === 'demo'
                ? 'Replay Demo'
                : semanticRows > 0
                    ? 'Semantic Metadata'
                    : 'Ops/Latency';
        dom.productModeState.textContent = mode;
        dom.productModeState.className = mode === 'Live Product' && liveState === 'live' ? 'fresh'
            : mode === 'Replay Demo' && replayReady ? 'fresh'
            : 'stale';
        setModeChip(dom.modeChipReplay, mode === 'Replay Demo', replayReady);
        setModeChip(dom.modeChipLive, mode === 'Live Product', freshness.live_data_observed === true);
        setModeChip(dom.modeChipSemantic, mode === 'Semantic Metadata', semanticRows > 0);
        setModeChip(dom.modeChipOps, mode === 'Ops/Latency', latency.status === 'ok');
        dom.modeSourceMode.textContent = health.source_mode || '-';
        dom.modeFeatureSource.textContent = health.feature_source || '-';
        dom.modeReleaseProfile.textContent = releaseStatusText(release);
        dom.modeLiveFreshness.textContent = dataFreshnessText(freshness);
        dom.modeLiveFreshness.className = dataFreshnessClass(freshness);
        dom.modeReplayReadiness.textContent = replayStatusText(replay);
        dom.modeReplayReadiness.className = replayReady ? 'fresh' : 'stale';
        dom.modeReplayProjection.textContent = replayProjectionText(replay);
        dom.modeReplayProjection.className = replay.featureplant_projected === true ? 'fresh' : 'stale';
        dom.modeSemanticStatus.textContent = semanticStatusText(semantic);
        dom.modeSemanticStatus.className = semanticRows > 0 ? 'fresh' : 'stale';
        dom.modeOpsLatency.textContent = latencyStatusText(latency);
        dom.modeOpsLatency.className = latency.status === 'ok' ? 'fresh' : 'stale';
    }

    function setModeChip(element, active, ready) {
        if (!element) {
            return;
        }
        element.classList.toggle('active', active);
        element.classList.toggle('fresh', ready === true);
        element.classList.toggle('stale', active && ready !== true);
    }

    function replayStatusText(replay) {
        if (!replay || !replay.status) {
            return 'unknown';
        }
        const symbols = Array.isArray(replay.available_symbols) ? replay.available_symbols.length : 0;
        return `${replay.status} / markets ${replay.market_count || 0}` +
            ` / canonical ${replay.canonical_event_count || 0}` +
            ` / symbols ${symbols}`;
    }

    function replayProjectionText(replay) {
        if (!replay || !replay.status) {
            return 'unknown';
        }
        return `features ${replay.feature_output_count || 0}` +
            ` / latest ${replay.latest_market_state_count || 0}` +
            ` / projected ${yesNo(replay.featureplant_projected)}`;
    }

    function semanticStatusText(semantic) {
        if (!semantic || !semantic.status) {
            return 'unknown';
        }
        const rows = (semantic.generated_count || 0)
            + (semantic.review_required_count || 0)
            + (semantic.failed_count || 0)
            + (semantic.rate_limited_count || 0);
        return `${semantic.status} / rows ${rows} / treemap ${yesNo(semantic.read_api?.available)}`;
    }

    function latencyStatusText(latency) {
        if (!latency || !latency.status) {
            return 'missing';
        }
        return latency.canonical_to_latest_state_ms == null
            ? `${latency.status} / ${latency.reason || latency.error || '-'}`
            : `${latency.status} / ${latency.canonical_to_latest_state_ms} ms projection`;
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
                requestSemanticMapLoad();
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
        distributionConnectionState = message || 'idle';
        renderDistributionStatus(lastHealthBody);
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
        role = role || 'live';
        activeRole = role;
        if (dom.dashboardShell) {
            dom.dashboardShell.dataset.activeRole = role;
        }
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
        if (dom.chartContainer.clientWidth > 0 && dom.chartContainer.clientHeight > 0) {
            chart.applyOptions({
                width: dom.chartContainer.clientWidth,
                height: dom.chartContainer.clientHeight
            });
        }
        if (role === 'semantic') {
            if (semanticMapDirty || !lastSemanticMapBody) {
                loadSemanticMap();
            }
        }
        if (role === 'replay') {
            loadReplayStatus();
            loadDemoOrchestratorStatus();
        }
        if (role === 'distribution') {
            renderDistributionStatus(lastHealthBody);
        }
        if (role === 'live') {
            loadHistory();
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

    function resetMarketPageAndLoad() {
        marketCatalogOffset = 0;
        loadSymbols();
    }

    function changeMarketPage(delta) {
        marketCatalogOffset = Math.max(0, marketCatalogOffset + delta);
        loadSymbols();
    }

    dom.refreshSymbols.addEventListener('click', () => {
        marketCatalogOffset = 0;
        loadConfig();
        loadSymbols();
    });
    dom.marketSearch.addEventListener('input', scheduleMarketSearch);
    dom.marketCapabilityFilter.addEventListener('change', resetMarketPageAndLoad);
    dom.marketStatusFilter.addEventListener('change', resetMarketPageAndLoad);
    dom.marketSearchApply.addEventListener('click', resetMarketPageAndLoad);
    dom.marketPrevPage.addEventListener('click', () => changeMarketPage(-MARKET_CATALOG_LIMIT));
    dom.marketNextPage.addEventListener('click', () => changeMarketPage(MARKET_CATALOG_LIMIT));
    dom.loadHistory.addEventListener('click', loadHistory);
    dom.symbolSelect.addEventListener('change', onSelectedSymbolChanged);
    dom.researchFeatureSelect.addEventListener('change', () => loadRecentFeatures(dom.symbolSelect.value));
    dom.researchFeatureLimit.addEventListener('change', () => loadRecentFeatures(dom.symbolSelect.value));
    dom.researchFeatureWindow.addEventListener('change', () => loadRecentFeatures(dom.symbolSelect.value));
    dom.researchExportCsv.addEventListener('click', exportResearchCsv);
    dom.semanticGroupBy.addEventListener('change', () => {
        semanticActiveGroupKey = '';
        requestSemanticMapLoad();
    });
    dom.semanticRenderMode.addEventListener('change', () => {
        semanticActiveGroupKey = '';
        if (lastSemanticMapBody) {
            renderSemanticMap(lastSemanticMapBody);
        } else {
            requestSemanticMapLoad();
        }
    });
    dom.semanticStatusFilter.addEventListener('change', requestSemanticMapLoad);
    dom.semanticLimit.addEventListener('change', requestSemanticMapLoad);
    dom.semanticTagFilter.addEventListener('input', scheduleSemanticMapLoad);
    dom.semanticSearch.addEventListener('input', scheduleSemanticMapLoad);
    dom.semanticRefresh.addEventListener('click', requestSemanticMapLoad);
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
        marketCatalogOffset = 0;
        loadConfig();
        loadSymbols();
        loadHealth();
        loadOpsTelemetry();
        loadOperatorStatus();
        requestSemanticMapLoad();
        loadCatalogSyncStatus();
        loadSemanticRunStatus();
        loadDemoOrchestratorStatus();
        loadReplayStatus();
    });

    updateDemoRunConfirmState();
    applyRoleVisibility('live');
    renderDistributionStatus(null);
    renderReplayStatus(null);
    renderDemoSignal(null);
    setInterval(() => {
        loadHealth();
        loadOpsTelemetry();
        loadOperatorStatus();
        if (activeRole === 'replay') {
            loadReplayStatus();
        }
    }, HEALTH_POLL_MS);
    loadConfig().then(loadSymbols).then(() => {
        loadHealth();
        loadOpsTelemetry();
        loadOperatorStatus();
        loadCatalogSyncStatus();
        loadSemanticRunStatus();
        loadDemoOrchestratorStatus();
        loadReplayStatus();
    });
})();

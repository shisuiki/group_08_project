create index if not exists feature_outputs_market_ticker_idx
    on feature_outputs (market_ticker)
    where market_ticker is not null;

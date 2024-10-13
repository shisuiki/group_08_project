import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Test;

import edu.illinois.group8.wrapper.KalshiWrapper;
import edu.illinois.group8.wrapper.RequestParameters;

public class KalshiWrapperTest {

    private KalshiWrapper wrapper = new KalshiWrapper();

    @Test
    public void testGetExchangeSchedule() {
        assertNotNull(wrapper.getExchangeSchedule());
    }

    @Test
    public void testGetExchangeAnnouncements() {
        assertNotNull(wrapper.getExchangeAnnouncements());
    }

    @Test
    public void testGetEvents() {
        assertNotNull(wrapper.getEvents());

        RequestParameters params = new RequestParameters();
        params.addParam("limit", 1);
        assertNotNull(wrapper.getEvents(params));

        params.addParam("status", "open, settled");
        params.addParam("invalid_param", "invalid_value");
        assertNotNull(wrapper.getEvents(params));
    }

    @Test
    public void testGetEvent() {
        String ticker = "JOBLESS-21AUG28";
        assertNotNull(wrapper.getEvent(ticker));

        RequestParameters params = new RequestParameters();
        params.addParam("with_nested_markets", true);
        assertNotNull(wrapper.getEvent(ticker, params));

        String invalid_ticker = "invalid_ticker";
        assertNull(wrapper.getEvent(invalid_ticker));
    }

    // WIP: need authorization for getMarkets API call
    // @Test
    // public void testGetMarkets() {
    //     assertNotNull(wrapper.getMarkets());

    //     RequestParameters params = new RequestParameters();
    //     params.addParam("tickers", "JOBLESS-21AUG28, EUCLIMATE");
    //     assertNotNull(wrapper.getMarkets(params));
    // }
    
    @Test
    public void testGetTrades() {
        assertNotNull(wrapper.getTrades());

        RequestParameters params = new RequestParameters();
        params.addParam("min_ts", 32);
        assertNotNull(wrapper.getTrades(params));
    }

    @Test
    public void testGetMarket() {
        String market_ticker = "JOBLESS-21AUG28-C350";
        assertNotNull(wrapper.getMarket(market_ticker));

        String invalid_market_ticker = "invalid_market_ticker";
        assertNull(wrapper.getMarket(invalid_market_ticker));
    }

    // WIP: need authorization for getMarketOrderbook API call
    // @Test
    // public void testGetMarketOrderbook() {
    //     String ticker = "JOBLESS-21AUG28";
    //     assertNotNull(wrapper.getMarketOrderbook(ticker));

    //     RequestParameters params =  new RequestParameters();
    //     params.addParam("depth", 32);
    //     params.addParam("invalid_param", 2);
    //     assertNotNull(wrapper.getMarketOrderbook(ticker, params));

    //     String invalid_ticker = "invalid_ticker";
    //     assertNull(wrapper.getMarketOrderbook(invalid_ticker));
    // }

    @Test
    public void testGetSeries() {
        String series_ticker = "JOBLESS";
        assertNotNull(wrapper.getSeries(series_ticker));

        String invalid_ticker = "invalid_ticker";
        assertNull(wrapper.getSeries(invalid_ticker));
    }

    // WIP: need authorization for getMarketCandlesticks API call
    // @Test
    // public void testGetMarketCandlesticks() {
    //     String market_ticker = "JOBLESS-21AUG28-C350";
    //     String series_ticker = "JOBLESS";
    //     assertNotNull(wrapper.getMarketCandlesticks(market_ticker, series_ticker, 200, 2000, 60));

    //     assertNull(wrapper.getMarketCandlesticks(ticker, series_ticker, 200, 2000, 20));
    // }
}

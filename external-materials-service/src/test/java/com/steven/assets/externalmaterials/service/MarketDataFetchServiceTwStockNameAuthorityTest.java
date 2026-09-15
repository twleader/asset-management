package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarketDataFetchServiceTwStockNameAuthorityTest {
    private static final String TWSE = "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL";
    private static final String TPEX = "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes";

    @Test
    void fubonValidNameWinsWithoutAnyFallback() {
        MarketDataFetchService service = authorities();
        doReturn("富邦驗證名稱").when(service).fetchFubonTwName("00850");

        assertThat(service.fetchTwStockName("00850")).isEqualTo("富邦驗證名稱");
        verify(service, never()).fetchExchangeTwName(anyString(), anyString());
        verify(service, never()).fetchYahooTwName(anyString());
    }

    @Test
    void unavailableFubonFallsBackInExactTwseThenTpexOrder() {
        MarketDataFetchService service = authorities();
        doReturn("").when(service).fetchFubonTwName("00850");
        doReturn("").when(service).fetchExchangeTwName(TWSE, "00850");
        doReturn("交易所名稱").when(service).fetchExchangeTwName(TPEX, "00850");

        assertThat(service.fetchTwStockName("00850")).isEqualTo("交易所名稱");
        var order = inOrder(service);
        order.verify(service).fetchFubonTwName("00850");
        order.verify(service).fetchExchangeTwName(TWSE, "00850");
        order.verify(service).fetchExchangeTwName(TPEX, "00850");
        verify(service, never()).fetchYahooTwName(anyString());
    }

    @Test
    void allOfficialSourcesUnavailableTryYahooTwThenTwoAndNeverFinmind() {
        StockSourceQuery store = mock(StockSourceQuery.class);
        MarketDataFetchService service = spy(new MarketDataFetchService(store, mock(TwTyphoonClosureService.class), "",
                year -> Optional.empty()));
        doReturn("").when(service).fetchFubonTwName("00850");
        doReturn("").when(service).fetchExchangeTwName(anyString(), eq("00850"));
        doReturn("").when(service).fetchYahooTwName("00850.TW");
        doReturn("Yahoo 兩市場名稱").when(service).fetchYahooTwName("00850.TWO");

        assertThat(service.fetchTwStockName("00850")).isEqualTo("Yahoo 兩市場名稱");
        var order = inOrder(service);
        order.verify(service).fetchFubonTwName("00850");
        order.verify(service).fetchExchangeTwName(TWSE, "00850");
        order.verify(service).fetchExchangeTwName(TPEX, "00850");
        order.verify(service).fetchYahooTwName("00850.TW");
        order.verify(service).fetchYahooTwName("00850.TWO");
        verifyNoInteractions(store);
    }

    @Test
    void tpexRealFieldNamesResolveExactIdentityWithoutYahooFallback() throws Exception {
        var data = new ObjectMapper().readTree("""
                [{"SecuritiesCompanyCode":"00850","CompanyName":"元大臺灣ESG永續"},
                 {"SecuritiesCompanyCode":"00851","CompanyName":"00851"}]
                """);

        assertThat(MarketDataFetchService.parseExchangeTwName(data, "00850")).isEqualTo("元大臺灣ESG永續");
        assertThat(MarketDataFetchService.parseExchangeTwName(data, "00851")).isEmpty();
        assertThat(MarketDataFetchService.parseExchangeTwName(data, "00852")).isEmpty();
    }

    @Test
    void blankOrCodeNamesAreRejectedBeforeTryingTheNextSource() {
        MarketDataFetchService service = authorities();
        doReturn("00850").when(service).fetchFubonTwName("00850");
        doReturn(" ").when(service).fetchExchangeTwName(TWSE, "00850");
        doReturn("TPEx validated name").when(service).fetchExchangeTwName(TPEX, "00850");

        assertThat(service.fetchTwStockName("00850")).isEqualTo("TPEx validated name");
        verify(service, never()).fetchYahooTwName(anyString());
    }

    @Test
    void yahooParserRequiresExactTickerAndTaiwanExchange() throws Exception {
        var mapper = new ObjectMapper();
        var mismatch = mapper.readTree("{\"symbol\":\"00850.TWO\",\"exchangeName\":\"TWO\",\"shortName\":\"元大臺灣ESG永續\"}");
        var valid = mapper.readTree("{\"symbol\":\"00850.TWO\",\"exchangeName\":\"TWO\",\"shortName\":\"元大臺灣ESG永續\"}");
        var nonTaiwan = mapper.readTree("{\"symbol\":\"00850.TW\",\"exchangeName\":\"NMS\",\"shortName\":\"not Taiwan\"}");

        assertThat(MarketDataFetchService.parseYahooTwName(mismatch, "00850.TW")).isEmpty();
        assertThat(MarketDataFetchService.parseYahooTwName(valid, "00850.TWO")).isEqualTo("元大臺灣ESG永續");
        assertThat(MarketDataFetchService.parseYahooTwName(nonTaiwan, "00850.TW")).isEmpty();
    }

    private static MarketDataFetchService authorities() {
        return spy(new MarketDataFetchService(mock(StockSourceQuery.class), mock(TwTyphoonClosureService.class), "",
                year -> Optional.empty()));
    }
}

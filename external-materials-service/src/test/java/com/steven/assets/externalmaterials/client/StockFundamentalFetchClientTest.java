package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.MarketDataFetchService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 292 官方基本面 parser 守門：欄名、民國年與虧損語意不得靜默漂移。 */
class StockFundamentalFetchClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketDataFetchService marketData = mock(MarketDataFetchService.class);
    private final StockFundamentalFetchClient client = new StockFundamentalFetchClient("", marketData);

    @Test
    void twseValuationEmptyPeMeansCredibleLossButMissingKeyMeansUnknown() throws Exception {
        var loss = client.parseValuation(mapper.readTree("""
                {"Code":"2330","Date":"1150807","PEratio":"-","PBratio":"7.2","DividendYield":"1.5"}
                """), "https://openapi.twse.test", false).orElseThrow();
        assertEquals(LocalDate.of(2026, 8, 7), loss.tradingDate());
        assertNull(loss.peRatio());
        assertTrue(loss.peLossFlag());

        var unknown = client.parseValuation(mapper.readTree("""
                {"Code":"2330","Date":"1150807","PBratio":"7.2"}
                """), "https://openapi.twse.test", false).orElseThrow();
        assertNull(unknown.peLossFlag());
    }

    @Test
    void tpexEnglishCandidatesAndRevenueFieldsAreParsed() throws Exception {
        var valuation = client.parseValuation(mapper.readTree("""
                {"SecuritiesCompanyCode":"6488","Date":"20260807","PriceEarningRatio":"18.5",
                 "PriceBookRatio":"4.2","YieldRatio":"2.1"}
                """), "https://openapi.tpex.test", true).orElseThrow();
        assertEquals(new BigDecimal("18.5"), valuation.peRatio());
        assertFalse(valuation.peLossFlag());

        var revenue = client.parseRevenue(mapper.readTree("""
                {"公司代號":"6488","出表日期":"1150717","資料年月":"11506","產業別":"半導體業",
                 "營業收入-當月營收":"1200","營業收入-去年當月營收":"1000","營業收入-去年同月增減(%)":"20"}
                """), "https://openapi.twse.test").orElseThrow();
        assertEquals(2026, revenue.revenueYear());
        assertEquals(6, revenue.revenueMonth());
        assertEquals(1200L, revenue.revenue());
        assertEquals(new BigDecimal("20"), revenue.revenueYoyPct());
    }

    @Test
    void allFinancialIndustrySchemasShareNamedCandidateParser() throws Exception {
        var zh = StockFundamentalFetchClient.incomeFields(mapper.readTree("""
                {"基本每股盈餘（元）":"12.3","淨利（淨損）歸屬於母公司業主":"1,234,567"}
                """));
        var en = StockFundamentalFetchClient.incomeFields(mapper.readTree("""
                {"EPS":"2.5","NetIncomeAttributableToOwnersOfParent":"987654"}
                """));
        assertEquals(new BigDecimal("12.3"), zh.eps());
        assertEquals(1_234_567L, zh.netIncome());
        assertEquals(new BigDecimal("2.5"), en.eps());
        assertEquals(987_654L, en.netIncome());
        assertEquals(8_765_432L, StockFundamentalFetchClient.equityField(mapper.readTree("""
                {"EquityAttributableToOwnersOfParent":"8765432"}
                """)));
        assertEquals(7_654_321L, StockFundamentalFetchClient.incomeFields(mapper.readTree("""
                {"淨利（損）歸屬於母公司業主":"7654321"}
                """)).netIncome(), "證券業／銀行業 schema 的『淨利（損）』欄不得漏接");
        assertEquals(6_543_210L, StockFundamentalFetchClient.equityField(mapper.readTree("""
                {"歸屬於母公司業主之權益":"6543210"}
                """)), "金控業 schema 的權益欄不得漏接");
        assertEquals(5_432_109L, StockFundamentalFetchClient.equityField(mapper.readTree("""
                {"歸屬於母公司業主權益合計":"5432109"}
                """)), "證券業 schema 的權益欄不得漏接");
    }

    @Test
    void rocDateAndYearMonthRejectMalformedValues() {
        assertEquals(LocalDate.of(2026, 8, 7), StockFundamentalFetchClient.rocDate("115/08/07"));
        assertEquals(2026, StockFundamentalFetchClient.rocYear("115"));
        int[] ym = StockFundamentalFetchClient.rocYearMonth("11506");
        assertEquals(2026, ym[0]);
        assertEquals(6, ym[1]);
        assertNull(StockFundamentalFetchClient.rocDate("115/99/99"));
    }

    @Test
    void yahooFallbackUsesSharedCrumbGatewayAndKeepsCanonicalSourceUrl() {
        String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/2330.TW"
                + "?modules=summaryDetail,defaultKeyStatistics";
        when(marketData.getYahooValuation("2330.TW")).thenReturn(
                new MarketDataFetchService.YahooValuationFetch(true,
                        new MarketDataFetchService.YahooValuation(
                                new BigDecimal("18.2"), new BigDecimal("5.1"),
                                new BigDecimal("1.7"), url), null));

        var bundle = client.fetchYahoo("2330", Instant.parse("2026-08-08T02:00:00Z"));

        assertEquals(1, bundle.valuations().size());
        assertEquals(StockFundamentalFetchClient.YAHOO, bundle.valuations().get(0).provider());
        assertEquals(List.of(url), bundle.valuations().get(0).sourceUrls());
        assertTrue(bundle.valuations().get(0).sourceUrls().stream().noneMatch(v -> v.contains("crumb=")));
        assertEquals(1, bundle.attempts());
        assertEquals(1, bundle.successes());
        assertTrue(bundle.failures().isEmpty());
    }

    @Test
    void yahooFailureIsNotReportedAsSuccessfulEmptyData() {
        when(marketData.getYahooValuation("2330.TW")).thenReturn(
                new MarketDataFetchService.YahooValuationFetch(false, null, "HTTP 429"));
        when(marketData.getYahooValuation("2330.TWO")).thenReturn(
                new MarketDataFetchService.YahooValuationFetch(false, null, "HTTP 404"));

        var bundle = client.fetchYahoo("2330", Instant.parse("2026-08-08T02:00:00Z"));

        assertTrue(bundle.valuations().isEmpty());
        assertEquals(2, bundle.attempts());
        assertEquals(0, bundle.successes());
        assertEquals(2, bundle.failures().size());
    }
}

package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.MarketDataFetchService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 292 官方基本面 parser 守門：欄名、民國年與虧損語意不得靜默漂移；Task 293 新增美股 SEC EDGAR。 */
class StockFundamentalFetchClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketDataFetchService marketData = mock(MarketDataFetchService.class);
    private final StockFundamentalFetchClient client = new StockFundamentalFetchClient("", marketData);
    private HttpServer stubServer;

    @AfterEach
    void stopStubServer() {
        if (stubServer != null) stubServer.stop(0);
    }

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

        var bundle = client.fetchYahoo("2330", "台股", Instant.parse("2026-08-08T02:00:00Z"));

        assertEquals(1, bundle.valuations().size());
        assertEquals("台股", bundle.valuations().get(0).market());
        assertEquals(StockFundamentalFetchClient.YAHOO, bundle.valuations().get(0).provider());
        assertEquals(List.of(url), bundle.valuations().get(0).sourceUrls());
        assertTrue(bundle.valuations().get(0).sourceUrls().stream().noneMatch(v -> v.contains("crumb=")));
        assertEquals(1, bundle.attempts());
        assertEquals(1, bundle.successes());
        assertTrue(bundle.failures().isEmpty());
        verify(marketData, never()).getYahooValuation("2330.TWO");
    }

    @Test
    void yahooFailureIsNotReportedAsSuccessfulEmptyData() {
        when(marketData.getYahooValuation("2330.TW")).thenReturn(
                new MarketDataFetchService.YahooValuationFetch(false, null, "HTTP 429"));
        when(marketData.getYahooValuation("2330.TWO")).thenReturn(
                new MarketDataFetchService.YahooValuationFetch(false, null, "HTTP 404"));

        var bundle = client.fetchYahoo("2330", "台股", Instant.parse("2026-08-08T02:00:00Z"));

        assertTrue(bundle.valuations().isEmpty());
        assertEquals(2, bundle.attempts());
        assertEquals(0, bundle.successes());
        assertEquals(2, bundle.failures().size());
    }

    // ── 293.2 fetchYahoo 美股不加後綴（回歸：台股加後綴行為見上兩條既有測試）───────────

    @Test
    void yahooFallbackForUsMarketQueriesSymbolWithoutAnySuffix() {
        String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/AAPL"
                + "?modules=summaryDetail,defaultKeyStatistics";
        when(marketData.getYahooValuation("AAPL")).thenReturn(
                new MarketDataFetchService.YahooValuationFetch(true,
                        new MarketDataFetchService.YahooValuation(
                                new BigDecimal("28.4"), new BigDecimal("35.1"),
                                new BigDecimal("0.5"), url), null));

        var bundle = client.fetchYahoo("AAPL", "美股", Instant.parse("2026-08-08T02:00:00Z"));

        assertEquals(1, bundle.attempts());
        assertEquals(1, bundle.successes());
        assertEquals(1, bundle.valuations().size());
        assertEquals("美股", bundle.valuations().get(0).market());
        assertEquals(StockFundamentalFetchClient.YAHOO, bundle.valuations().get(0).provider());
        verify(marketData, never()).getYahooValuation("AAPL.TW");
        verify(marketData, never()).getYahooValuation("AAPL.TWO");
    }

    // ── 293.1 SEC EDGAR：呼叫失敗 fail-soft（測試 a）──────────────────────────────

    @Test
    void secEdgarHttpErrorReturnsEmptyInsteadOfThrowing() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/error", exchange -> {
            byte[] body = "server error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubServer.start();
        String url = "http://127.0.0.1:" + stubServer.getAddress().getPort() + "/error";

        var result = client.fetchSecJson(url);

        assertTrue(result.isEmpty());
    }

    @Test
    void secEdgarMalformedJsonReturnsEmptyInsteadOfThrowing() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/bad-json", exchange -> {
            byte[] body = "{not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubServer.start();
        String url = "http://127.0.0.1:" + stubServer.getAddress().getPort() + "/bad-json";

        var result = client.fetchSecJson(url);

        assertTrue(result.isEmpty());
    }

    @Test
    void secEdgarTimeoutReturnsEmptyInsteadOfThrowing() throws IOException {
        // 只 bind、不 accept：握手完成但永遠沒有回應，逼出真正的逾時（用極短逾時保持測試快速）。
        try (ServerSocket silent = new ServerSocket(0)) {
            String url = "http://127.0.0.1:" + silent.getLocalPort() + "/";

            var result = client.fetchSecJson(url, Duration.ofMillis(300));

            assertTrue(result.isEmpty());
        }
    }

    // ── 293.1 ticker→CIK 映射（測試 b）────────────────────────────────────────

    @Test
    void findCikReturnsEmptyWhenTickerIsNotInMap() throws Exception {
        JsonNode tickerMap = mapper.readTree("""
                {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},
                 "1":{"cik_str":789019,"ticker":"MSFT","title":"Microsoft Corp"}}
                """);
        Map<String, Long> parsed = StockFundamentalFetchClient.parseTickerMap(tickerMap);

        assertEquals(320193L, parsed.get("AAPL"));
        assertTrue(StockFundamentalFetchClient.findCik(parsed, "NOSUCHTICKER").isEmpty());
        // ticker 比對不分大小寫（stockCode 一律轉大寫再比對）。
        assertEquals(789019L, StockFundamentalFetchClient.findCik(parsed, "msft").orElseThrow());
    }

    // ── 293.1 累計口徑陷阱（測試 l）───────────────────────────────────────────

    @Test
    void companyFactsPicksCumulativeValueNotStandaloneQuarterValue() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1.50,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"},
                        {"start":"2025-04-01","end":"2025-06-30","val":1.60,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"},
                        {"start":"2025-01-01","end":"2025-06-30","val":3.10,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1000000000,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"},
                        {"start":"2025-04-01","end":"2025-06-30","val":1100000000,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"},
                        {"start":"2025-01-01","end":"2025-06-30","val":2100000000,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"}
                    ]}},
                    "StockholdersEquity":{"units":{"USD":[
                        {"end":"2025-06-30","val":50000000000,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "TESTCO", "https://data.sec.gov/test.json");

        var q2 = financials.stream()
                .filter(f -> f.fiscalYear() == 2025 && f.fiscalQuarter() == 2).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3.10").compareTo(q2.cumulativeEps()),
                "Q2 必須取『自年度起算』的累計值 3.10，不得取單季 3 個月的 1.60");
        assertEquals(2_100_000_000L, q2.cumulativeNetIncomeParent(),
                "淨利同樣必須取累計值 21 億，不得取單季 11 億");
        assertEquals(50_000_000_000L, q2.equityParent());
        assertEquals("美股", q2.market());
        assertEquals(StockFundamentalFetchClient.SEC_EDGAR, q2.provider());

        var q1 = financials.stream()
                .filter(f -> f.fiscalYear() == 2025 && f.fiscalQuarter() == 1).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1.50").compareTo(q1.cumulativeEps()), "Q1 單季本身即累計");
    }

    // ── 293.1 追加修正 v3：比較年度 fy 標籤污染（實測 MSFT 發現的真實情況）────────

    /**
     * 重現 2026-08-09 部署後實測發現的真實 bug：同一組真實 (start,end) 累計數字被兩份不同申報文件
     * 各報一次，較晚的一份把 {@code fy} 標成申報文件自己所屬的年度而非數字實際所屬的年度。舊邏輯
     * （信任 fy 分組）會把同一組數字寫成兩個不同 fiscal_year；新邏輯依 (start,end) 去重，只應留下
     * 一筆、取 filed 較新者。
     */
    @Test
    void parseCompanyFactsCollapsesComparativeYearMislabeling() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2024-07-01","end":"2024-09-30","val":2.99,
                         "fy":2024,"fp":"Q1","form":"10-Q","filed":"2024-10-25"},
                        {"start":"2024-07-01","end":"2024-12-31","val":5.92,
                         "fy":2024,"fp":"Q2","form":"10-Q","filed":"2025-01-25"},
                        {"start":"2024-07-01","end":"2024-09-30","val":2.99,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-10-30"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2024-07-01","end":"2024-09-30","val":22291000000,
                         "fy":2024,"fp":"Q1","form":"10-Q","filed":"2024-10-25"},
                        {"start":"2024-07-01","end":"2024-12-31","val":44161000000,
                         "fy":2024,"fp":"Q2","form":"10-Q","filed":"2025-01-25"},
                        {"start":"2024-07-01","end":"2024-09-30","val":22291000000,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-10-30"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "MSFTTEST", "https://data.sec.gov/test.json");

        long q1Rows = financials.stream()
                .filter(f -> f.cumulativeEps() != null
                        && 0 == new BigDecimal("2.99").compareTo(f.cumulativeEps()))
                .count();
        assertEquals(1, q1Rows,
                "同一組真實 2.99 數字被兩份文件各貼一次不同 fy，去重後只能留一筆，不得寫成兩個不同 fiscal_year");
        var only = financials.stream()
                .filter(f -> f.cumulativeEps() != null
                        && 0 == new BigDecimal("2.99").compareTo(f.cumulativeEps()))
                .findFirst().orElseThrow();
        assertEquals(2025, only.fiscalYear(), "start=2024-07-01 落在 7-12 月，fiscalYear 應標為隔年即 2025");
    }

    /** 同一 start 下即使只出現 1 個 end，只要是真正的 fp=Q1，就必須立即信任回填，不必等第二季佐證。 */
    @Test
    void parseCompanyFactsTrustsSoloGenuineFirstQuarterImmediately() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2026-01-01","end":"2026-03-31","val":9.99,
                         "fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-04-25"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2026-01-01","end":"2026-03-31","val":5000000000,
                         "fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-04-25"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "SOLOTEST", "https://data.sec.gov/test.json");

        var q1 = financials.stream()
                .filter(f -> f.fiscalYear() == 2026 && f.fiscalQuarter() == 1).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("9.99").compareTo(q1.cumulativeEps()));
    }

    /**
     * 重現 2026-08-09 部署後、v3 上線再實測真實 AMZN companyfacts 才發現的第二個 bug：{@code NetIncomeLoss}
     * 對 AMZN 額外揭露一筆 trailing-twelve-month（非季度邊界）事實，其 {@code start} 恰好與一筆真實的
     * 單季 standalone 事實撞在一起湊出 2 個相異 end，但組內沒有任何 {@code fp=Q1} 成員——這種偽分組
     * 不得被信任，否則會把單季數值錯貼上不存在的年度標籤（v3 的「≥2 個 end」判準會誤判此情況）。
     */
    @Test
    void parseCompanyFactsRejectsCohortWithoutGenuineFirstQuarter() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2025-07-01","end":"2025-09-30","val":1.95,
                         "fy":2025,"fp":"Q3","form":"10-Q","filed":"2025-10-31"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2025-07-01","end":"2025-09-30","val":21187000000,
                         "fy":2025,"fp":"Q3","form":"10-Q","filed":"2025-10-31"},
                        {"start":"2025-07-01","end":"2026-06-30","val":135281000000,
                         "fy":2026,"fp":"Q2","form":"10-Q","filed":"2026-07-31"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "AMZNTTM", "https://data.sec.gov/test.json");

        assertTrue(financials.isEmpty(),
                "start=2025-07-01 分組沒有任何 fp=Q1 成員（僅巧合湊出 2 個 end），不得被信任為真實年度序列");
    }

    /**
     * 重現 v4 上線後再實測 AMZN 真實資料才發現的第三個 bug：AMZN 每年 Q1 10-Q 慣例會額外附一筆
     * trailing-twelve-month（跨度 364–365 天）的補充淨利數字，卻同樣貼上 {@code fp="Q1"}——若只檢查
     * 「組內是否存在 {@code fp=Q1}」，這筆貼錯量級的 Q1 標籤會讓一個非年度起點的 start（此處刻意用
     * start=2025-04-01，真正的 Q2 單季起點）被誤判為真實會計年度，把該 start 底下真正的 Q2 單季數值
     * 誤標成年度標籤、進而覆蓋掉真正 2025 年度分組算出的正確 Q2 累計值。
     */
    @Test
    void parseCompanyFactsRejectsQ1TaggedTrailingTwelveMonthFact() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1.59,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-02"},
                        {"start":"2025-01-01","end":"2025-06-30","val":3.27,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-08-01"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2025-01-01","end":"2025-03-31","val":17127000000,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-02"},
                        {"start":"2025-01-01","end":"2025-06-30","val":35291000000,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-08-01"},
                        {"start":"2025-04-01","end":"2026-03-31","val":90798000000,
                         "fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-04-30"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "AMZNQ1TTM", "https://data.sec.gov/test.json");

        var q2 = financials.stream()
                .filter(f -> f.fiscalYear() == 2025 && f.fiscalQuarter() == 2).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3.27").compareTo(q2.cumulativeEps()),
                "真正 2025 年度分組算出的 Q2 累計值 3.27 不得被 start=2025-04-01 那個貼錯量級的 Q1 標籤覆蓋");
        assertTrue(financials.stream().noneMatch(f -> f.fiscalYear() == 2026 && f.fiscalQuarter() == 1),
                "貼了 fp=Q1 但跨度 364 天的 trailing-twelve-month 事實不得被信任為真正的年度起點");
    }

    /** 權益（資產負債表時點值）與 EPS／淨利共用同一份期間對照表，同一真實季度必須落在同一個 key 下。 */
    @Test
    void parseCompanyFactsAlignsEquityToSamePeriodAsIncomeStatement() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1.50,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"},
                        {"start":"2025-01-01","end":"2025-06-30","val":3.10,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1000000000,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"},
                        {"start":"2025-01-01","end":"2025-06-30","val":2100000000,
                         "fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-07-25"}
                    ]}},
                    "StockholdersEquity":{"units":{"USD":[
                        {"end":"2025-03-31","val":48000000000,
                         "fy":2099,"fp":"Q4","form":"10-Q","filed":"2025-04-25"},
                        {"end":"2025-06-30","val":50000000000,
                         "fy":2099,"fp":"Q4","form":"10-Q","filed":"2025-07-25"},
                        {"end":"2025-12-31","val":52000000000,
                         "fy":2099,"fp":"Q4","form":"10-Q","filed":"2025-10-25"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "EQTEST", "https://data.sec.gov/test.json");

        var q1 = financials.stream()
                .filter(f -> f.fiscalYear() == 2025 && f.fiscalQuarter() == 1).findFirst().orElseThrow();
        assertEquals(48_000_000_000L, q1.equityParent(),
                "權益值須依 duration 概念已驗證的期間標籤對齊，即使自己的 fy/fp 標籤（此處刻意寫成無意義的 2099/Q4）不可信");

        var q2 = financials.stream()
                .filter(f -> f.fiscalYear() == 2025 && f.fiscalQuarter() == 2).findFirst().orElseThrow();
        assertEquals(50_000_000_000L, q2.equityParent());

        assertTrue(financials.stream().noneMatch(f -> f.equityParent() != null
                        && f.equityParent() == 52_000_000_000L),
                "查無對應損益表期間（2025-12-31）的權益時點值須直接捨棄，不得產生孤兒列");
    }

    // ── Task 334 對抗式審查：source_available_at 必須是「首次申報時點」且晚於當日美股收盤 ────────

    /**
     * 同一期別被兩份申報各報一次（第二份是次年 10-Q 夾帶的比較數字）時，<b>值</b>取 filed 較新的那一筆
     * （重述後的正確數字），<b>可見時點</b>必須取最早的那一筆。
     *
     * <p>做錯不會有任何錯誤訊息，但會讓每個舊期別被推遲整整一年才「可見」——實測 GOOGL 舊季
     * {@code source_available_at} 距其日曆期末 388–401 天、最新四季只有 23–36 天。這個位移對期別是保序的，
     * {@code UsValuationDerivationService} 的單調化（min-over-newer）取不掉，結果是推導序列的歷史區段用
     * 落後約四季的 TTM 分母、最近一年用當期值，成長股必然被判成「現在最便宜」。</p>
     */
    @Test
    void companyFactsAvailabilityUsesTheFirstFilingNotTheLatestRestatement() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1.50,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"},
                        {"start":"2025-01-01","end":"2025-03-31","val":1.55,
                         "fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-04-24"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1000000000,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"},
                        {"start":"2025-01-01","end":"2025-03-31","val":1050000000,
                         "fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-04-24"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "FIRSTFILED", "https://data.sec.gov/test.json");

        var q1 = financials.stream()
                .filter(f -> f.fiscalYear() == 2025 && f.fiscalQuarter() == 1).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1.55").compareTo(q1.cumulativeEps()),
                "值仍取 filed 最新的那一筆（重述後的正確數字）");
        assertEquals("PUBLISHED", q1.availabilityBasis());
        assertEquals(LocalDate.of(2025, 4, 25),
                q1.sourceAvailableAt().atZone(java.time.ZoneId.of("America/New_York")).toLocalDate(),
                "可見時點必須是首次申報日 2025-04-25，不得跟著次年比較數字被推遲到 2026-04-24");
    }

    /**
     * {@code filed} 只有日期精度，換算後必須<b>晚於申報日的美股收盤（16:00 America/New_York）</b>，
     * 讓該期別從下一個交易日起才可見。寫成當日中午 UTC（＝08:00 ET，開盤前）會讓申報當日的推導列變成
     * 「盤前價 ÷ 尚未公開的財報」，構成一個交易日的 look-ahead。
     */
    @Test
    void filedInstantLandsAfterTheUsCloseOfTheFilingDay() throws Exception {
        JsonNode root = mapper.readTree("""
                {"facts":{"us-gaap":{
                    "EarningsPerShareDiluted":{"units":{"USD/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1.50,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"}
                    ]}},
                    "NetIncomeLoss":{"units":{"USD":[
                        {"start":"2025-01-01","end":"2025-03-31","val":1000000000,
                         "fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-04-25"}
                    ]}}
                }}}
                """);

        List<StockFundamentalFetchClient.Financial> financials =
                StockFundamentalFetchClient.parseCompanyFacts(root, "CLOSETIME", "https://data.sec.gov/test.json");

        Instant usClose = LocalDate.of(2025, 4, 25).atTime(16, 0)
                .atZone(java.time.ZoneId.of("America/New_York")).toInstant();
        Instant nextDayClose = LocalDate.of(2025, 4, 28).atTime(16, 0)
                .atZone(java.time.ZoneId.of("America/New_York")).toInstant();
        Instant available = financials.get(0).sourceAvailableAt();
        assertTrue(available.isAfter(usClose), "申報日當日收盤時仍不得可見（否則是盤前價 ÷ 未公開財報）");
        assertTrue(available.isBefore(nextDayClose), "下一個交易日收盤時必須已可見");
    }
}

package com.steven.assets.bff.publicquote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DataStatus;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DetailedLatestQuote;
import com.steven.assets.bff.stockanalysis.StockAnalysisChartDataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 108 的 public aggregation 契約：原始欄位不變、四個市場 child 固定、沒有個人資料來源。 */
class PublicQuoteMarketDataServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"),
            ZoneId.of("Asia/Taipei"));
    private static final List<String> RAW_KEYS = List.of(
            "stockCode", "stockName", "market", "price", "previousClose", "priceChange", "changePercent",
            "buyPrice", "sellPrice", "openPrice", "highPrice", "lowPrice", "volume", "tradingDate",
            "updatedAt", "closed", "source", "quoteStatus", "premiumDiscountPct");

    @Test
    void oneRelaysAllRawFieldsThenDirectNormalizedFieldsWithoutAdditionalRequests() {
        List<ClientRequest> rawRequests = new ArrayList<>();
        List<ClientRequest> marketRequests = new ArrayList<>();
        PublicQuoteMarketDataService service = service(
                request -> {
                    rawRequests.add(request);
                    return ok(rawQuote("2330", "台股", "2026-08-24"));
                },
                request -> {
                    marketRequests.add(request);
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote).isNotNull();
        assertThat(quote.stockCode()).isEqualTo("2330");
        assertThat(quote.stockName()).isEqualTo("台積電");
        assertThat(quote.market()).isEqualTo("台股");
        assertThat(quote.price()).hasToString("100.50");
        assertThat(quote.previousClose()).hasToString("99.00");
        assertThat(quote.priceChange()).hasToString("1.50");
        assertThat(quote.changePercent()).hasToString("1.52");
        assertThat(quote.buyPrice()).hasToString("100.00");
        assertThat(quote.sellPrice()).hasToString("101.00");
        assertThat(quote.openPrice()).hasToString("99.50");
        assertThat(quote.highPrice()).hasToString("101.00");
        assertThat(quote.lowPrice()).hasToString("99.00");
        assertThat(quote.volume()).isEqualTo(123456L);
        assertThat(quote.tradingDate()).isEqualTo("2026-08-24");
        assertThat(quote.updatedAt()).isEqualTo("2026-08-24T10:00:00+08:00");
        assertThat(quote.closed()).isFalse();
        assertThat(quote.source()).isEqualTo("REDIS");
        assertThat(quote.quoteStatus()).isEqualTo("LIVE");
        assertThat(quote.premiumDiscountPct()).hasToString("0.25");

        assertThat(quote.marketData().chart().status()).isEqualTo(DataStatus.AVAILABLE);
        assertThat(quote.marketData().chart().requestedStart().toString()).isEqualTo("2026-08-01");
        assertThat(quote.marketData().chart().requestedEnd().toString()).isEqualTo("2026-08-24");
        assertThat(quote.marketData().chart().intraday().status()).isEqualTo(DataStatus.AVAILABLE);
        assertThat(quote.marketData().quoteDetail().available()).isTrue();
        assertThat(quote.marketData().quoteDetail().source()).isEqualTo("FUBON_BOOKS");
        assertThat(quote.quoteDetail()).isSameAs(quote.marketData().quoteDetail());
        assertThat(quote.dividendHistory()).isSameAs(quote.marketData().dividends());
        assertThat(quote.bidLevels()).isEmpty();
        assertThat(quote.askLevels()).isEmpty();
        assertThat(quote.marketData().etfConstituents().holdings()).singleElement()
                .extracting("stockCode", "shares").containsExactly("1101", new java.math.BigDecimal("12"));
        assertThat(quote.marketData().dividends().rows()).singleElement()
                .extracting("year", "exRightsDate").containsExactly(2025, "2025-07-01");
        assertThat(quote.dividendHistory().rows()).singleElement()
                .extracting("cashYieldPct").isEqualTo(new BigDecimal("1.000000"));
        assertThat(quote.dividendHistory().annualSummaries()).singleElement()
                .extracting("year", "cashDividend", "stockDividend", "cashYieldPct")
                .containsExactly(2025, new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("1.000000"));

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(quote);
        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyElementsOf(concat(RAW_KEYS,
                "marketData", "quoteDetail", "bidLevels", "askLevels", "dividendHistory"));
        List<String> childKeys = new ArrayList<>();
        json.path("marketData").fieldNames().forEachRemaining(childKeys::add);
        assertThat(childKeys).containsExactly("chart", "quoteDetail", "etfConstituents", "dividends");
        assertThat(json.toString()).doesNotContain("AssetSnapshot", "StockHolding", "costPrice", "investmentCost",
                "currentValue", "transaction", "userId", "owner");

        assertThat(rawRequests).singleElement().satisfies(request -> {
            assertThat(request.url().getPath()).isEqualTo("/api/quotes/one");
            assertThat(request.headers()).doesNotContainKeys("X-User-Id", "X-User-Role", "X-User-Status");
        });
        assertThat(marketRequests).extracting(request -> request.url().getPath()).containsExactlyInAnyOrder(
                "/api/market-data/history/stock", "/api/market-data/indicators/series",
                "/internal/public-market-data/intraday-ticks-readonly", "/api/market-data/quote-detail",
                "/api/market-data/etf-holdings", "/internal/public-market-data/dividends-readonly-result");
        assertThat(marketRequests).allSatisfy(request ->
                assertThat(request.headers()).doesNotContainKeys("X-User-Id", "X-User-Role", "X-User-Status"));
    }

    @Test
    void rawCacheMissStaysEmptyAndDoesNotStartAnyMarketChild() {
        AtomicInteger marketCalls = new AtomicInteger();
        PublicQuoteMarketDataService service = service(
                request -> ClientResponse.create(HttpStatus.NO_CONTENT).build(),
                request -> {
                    marketCalls.incrementAndGet();
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", null, null).block();

        assertThat(quote).isNull();
        assertThat(marketCalls).hasValue(0);
    }

    @Test
    void nonTaiwanQuoteDetailIsTypedUnsupportedWithoutCallingQuoteDetailEndpoint() {
        AtomicInteger quoteDetailCalls = new AtomicInteger();
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("AAPL", "美股", "2026-08-24")),
                request -> {
                    if (request.url().getPath().endsWith("/quote-detail")) {
                        quoteDetailCalls.incrementAndGet();
                    }
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("AAPL", "美股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.marketData().quoteDetail().supported()).isFalse();
        assertThat(quote.marketData().quoteDetail().available()).isFalse();
        assertThat(quoteDetailCalls).hasValue(0);
    }

    @Test
    void listPreservesRawOrderAndAnEmptyRawListStaysAnEmptyArray() {
        PublicQuoteMarketDataService ordered = service(
                request -> ok("[" + rawQuote("0050", "台股", "2026-08-24") + ","
                        + rawQuote("2330", "台股", "2026-08-24") + "]"),
                PublicQuoteMarketDataServiceTest::marketResponse);

        List<DetailedLatestQuote> rows = ordered.list("台股", "2026-08-01", "2026-08-24").block();

        assertThat(rows).extracting(DetailedLatestQuote::stockCode).containsExactly("0050", "2330");

        PublicQuoteMarketDataService empty = service(request -> ok("[]"),
                PublicQuoteMarketDataServiceTest::marketResponse);
        assertThat(empty.list("台股", null, null).block()).isEmpty();
    }

    @Test
    void chartAndIntradayUseCanonicalNoDataShapesWhenTheirSuccessfulSourcesAreEmpty() {
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("2330", "台股", "2026-08-24")),
                request -> switch (request.url().getPath()) {
                    case "/api/market-data/history/stock", "/api/market-data/indicators/series" -> ok("[]");
                    case "/internal/public-market-data/intraday-ticks-readonly" -> ok("{"
                            + "\"tradingDate\":\"2026-08-24\",\"readStatus\":\"EMPTY\",\"ticks\":[]}");
                    default -> marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.marketData().chart().status()).isEqualTo(DataStatus.NO_DATA);
        assertThat(quote.marketData().chart().series().dates()).isEmpty();
        assertThat(quote.marketData().chart().series().daily().dates()).isEmpty();
        assertThat(quote.marketData().chart().series().weekly().dates()).isEmpty();
        assertThat(quote.marketData().chart().intraday().status()).isEqualTo(DataStatus.NO_DATA);
        assertThat(quote.marketData().chart().intraday().ticks()).isEmpty();
    }

    @Test
    void failedMarketChildrenFallBackIndependentlyWithoutClearingOtherChildren() {
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("2330", "台股", "2026-08-24")),
                request -> switch (request.url().getPath()) {
                    case "/api/market-data/history/stock", "/api/market-data/indicators/series",
                            "/api/market-data/quote-detail" -> ClientResponse.create(HttpStatus.BAD_GATEWAY).build();
                    case "/internal/public-market-data/intraday-ticks-readonly" -> ok("{"
                            + "\"tradingDate\":\"2026-08-24\",\"readStatus\":\"MALFORMED\",\"ticks\":[]}");
                    default -> marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.stockCode()).isEqualTo("2330");
        assertThat(quote.marketData().chart().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(quote.marketData().chart().series().dates()).isEmpty();
        assertThat(quote.marketData().chart().intraday().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(quote.marketData().chart().intraday().ticks()).isEmpty();
        assertThat(quote.marketData().quoteDetail().supported()).isTrue();
        assertThat(quote.marketData().quoteDetail().available()).isFalse();
        assertThat(quote.marketData().quoteDetail().source()).isNull();
        assertThat(quote.marketData().quoteDetail().marketStatus()).isEqualTo("UNKNOWN");
        assertThat(quote.marketData().quoteDetail().message()).isEqualTo("暫時無法取得行情五檔");
        assertThat(quote.marketData().etfConstituents().supported()).isTrue();
        assertThat(quote.marketData().dividends().rows()).singleElement();
    }

    @Test
    void nonFubonQuoteDetailPayloadIsNormalizedToTypedUnavailable() {
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("2330", "台股", "2026-08-24")),
                request -> {
                    if ("/api/market-data/quote-detail".equals(request.url().getPath())) {
                        return ok("{\"stockCode\":\"2330\",\"market\":\"台股\",\"supported\":true,"
                                + "\"available\":true,\"source\":\"YAHOO_TW\",\"marketStatus\":\"OPEN\",\"levels\":[]}");
                    }
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.marketData().quoteDetail().supported()).isTrue();
        assertThat(quote.marketData().quoteDetail().available()).isFalse();
        assertThat(quote.marketData().quoteDetail().source()).isNull();
        assertThat(quote.marketData().quoteDetail().marketStatus()).isEqualTo("UNKNOWN");
        assertThat(quote.marketData().quoteDetail().levels()).isEmpty();
        assertThat(quote.marketData().quoteDetail().message()).isEqualTo("暫時無法取得行情五檔");
        assertThat(quote.bidLevels()).isEmpty();
        assertThat(quote.askLevels()).isEmpty();
    }

    @Test
    void fubonBookSnapshotProjectsBothOrderedFiveSidesFromTheSameQuoteDetail() {
        AtomicInteger quoteDetailCalls = new AtomicInteger();
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("2330", "台股", "2026-08-24")),
                request -> {
                    if ("/api/market-data/quote-detail".equals(request.url().getPath())) {
                        quoteDetailCalls.incrementAndGet();
                        return ok("{\"stockCode\":\"2330\",\"market\":\"台股\",\"supported\":true,"
                                + "\"available\":true,\"source\":\"FUBON_BOOKS\",\"levels\":["
                                + "{\"level\":1,\"bidPrice\":100,\"bidVolumeLots\":10,\"askPrice\":101,\"askVolumeLots\":11},"
                                + "{\"level\":2,\"bidPrice\":99,\"bidVolumeLots\":9,\"askPrice\":102,\"askVolumeLots\":12},"
                                + "{\"level\":3,\"bidPrice\":98,\"bidVolumeLots\":8,\"askPrice\":103,\"askVolumeLots\":13},"
                                + "{\"level\":4,\"bidPrice\":97,\"bidVolumeLots\":7,\"askPrice\":104,\"askVolumeLots\":14},"
                                + "{\"level\":5,\"bidPrice\":96,\"bidVolumeLots\":6,\"askPrice\":105,\"askVolumeLots\":15}]}");
                    }
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.quoteDetail()).isSameAs(quote.marketData().quoteDetail());
        assertThat(quote.bidLevels()).extracting("price", "size").containsExactly(
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("100"), 10L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("99"), 9L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("98"), 8L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("97"), 7L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("96"), 6L));
        assertThat(quote.askLevels()).extracting("price", "size").containsExactly(
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("101"), 11L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("102"), 12L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("103"), 13L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("104"), 14L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("105"), 15L));
        assertThat(quoteDetailCalls).hasValue(1);
    }

    @Test
    void bookProjectionKeepsOrderedPartialSideButFailsClosedForOnlyTheUnorderedSide() {
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("2330", "台股", "2026-08-24")),
                request -> {
                    if ("/api/market-data/quote-detail".equals(request.url().getPath())) {
                        return ok("{\"stockCode\":\"2330\",\"market\":\"台股\",\"supported\":true,"
                                + "\"available\":true,\"source\":\"FUBON_BOOKS\",\"levels\":["
                                + "{\"level\":1,\"bidPrice\":100,\"bidVolumeLots\":1,\"askPrice\":101,\"askVolumeLots\":1},"
                                + "{\"level\":2,\"bidPrice\":101,\"bidVolumeLots\":1,\"askPrice\":102,\"askVolumeLots\":0},"
                                + "{\"level\":3,\"bidPrice\":99,\"bidVolumeLots\":0,\"askPrice\":102,\"askVolumeLots\":2},"
                                + "{\"level\":4,\"bidPrice\":98,\"bidVolumeLots\":3,\"askPrice\":103,\"askVolumeLots\":3}]}");
                    }
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.bidLevels()).isEmpty();
        assertThat(quote.askLevels()).extracting("price", "size").containsExactly(
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("101"), 1L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("102"), 2L),
                org.assertj.core.groups.Tuple.tuple(new BigDecimal("103"), 3L));
    }

    @Test
    void dividendRowsAndAnnualSummariesUseTheSpecifiedCashYieldAnchors() {
        PublicQuoteMarketDataService service = service(
                request -> ok(rawQuote("2330", "台股", "2026-08-24")),
                request -> {
                    if ("/internal/public-market-data/dividends-readonly-result".equals(request.url().getPath())) {
                        return ok("{\"stockCode\":\"2330\",\"market\":\"台股\",\"source\":\"DB\",\"message\":null,\"rows\":["
                                + "{\"year\":2026,\"cashDividend\":1,\"stockDividend\":0,\"exDividendDate\":\"2026-08-18\",\"previousClose\":null},"
                                + "{\"year\":2026,\"cashDividend\":0.66,\"stockDividend\":0,\"exDividendDate\":\"2026-05-19\",\"previousClose\":27.80},"
                                + "{\"year\":2026,\"cashDividend\":0.42,\"stockDividend\":0.10,\"exDividendDate\":null,\"exRightsDate\":\"2026-02-26\",\"previousClose\":24},"
                                + "{\"year\":2025,\"cashDividend\":0.40,\"stockDividend\":0,\"exDividendDate\":\"2025-11-18\",\"previousClose\":0},"
                                + "{\"year\":2025,\"cashDividend\":0.50,\"stockDividend\":0,\"exDividendDate\":\"2025-05-19\",\"previousClose\":20}]}");
                    }
                    return marketResponse(request);
                });

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.dividendHistory()).isSameAs(quote.marketData().dividends());
        assertThat(quote.dividendHistory().rows()).extracting("cashYieldPct").containsExactly(
                null, new BigDecimal("2.374101"), new BigDecimal("1.750000"), null, new BigDecimal("2.500000"));
        assertThat(quote.dividendHistory().annualSummaries()).extracting(
                "year", "cashDividend", "stockDividend", "cashYieldPct").containsExactly(
                org.assertj.core.groups.Tuple.tuple(2026, new BigDecimal("2.08"), new BigDecimal("0.10"),
                        new BigDecimal("7.482014")),
                org.assertj.core.groups.Tuple.tuple(2025, new BigDecimal("0.90"), BigDecimal.ZERO, null));
    }

    @Test
    void productionQuoteDetailTimeoutDefaultIsTwoSeconds() {
        java.lang.reflect.Constructor<?> constructor = java.util.Arrays.stream(
                        PublicQuoteMarketDataService.class.getConstructors())
                .filter(candidate -> candidate.getParameterCount() == 13)
                .findFirst()
                .orElseThrow();

        Value timeout = constructor.getParameters()[6].getAnnotation(Value.class);

        assertThat(timeout).isNotNull();
        assertThat(timeout.value()).isEqualTo("${public-quote.timeout.quote-detail-seconds:2}");
    }

    @Test
    void invalidDateWindowFailsBeforeItCanCallRawQuote() {
        PublicQuoteMarketDataService service = service(request -> {
            throw new AssertionError("raw quote must not be called for invalid date");
        }, PublicQuoteMarketDataServiceTest::marketResponse);

        assertThatThrownBy(() -> service.one("2330", "台股", "2026-08-25", "2026-08-24"))
                .isInstanceOf(PublicQuoteRequestException.class);
        assertThatThrownBy(() -> service.list("台股", "2010-01-01", "2026-08-24"))
                .isInstanceOf(PublicQuoteRequestException.class);
        assertThatThrownBy(() -> service.list("台股", "2026-08-01", null))
                .isInstanceOf(PublicQuoteRequestException.class);
        assertThatThrownBy(() -> service.list("台股", null, "2026-08-24"))
                .isInstanceOf(PublicQuoteRequestException.class);
        assertThatThrownBy(() -> service.list("台股", "not-a-date", "2026-08-24"))
                .isInstanceOf(PublicQuoteRequestException.class);
        assertThatThrownBy(() -> service.list("台股", "2016-08-23", "2026-08-24"))
                .isInstanceOf(PublicQuoteRequestException.class);
    }

    private static PublicQuoteMarketDataService service(
            java.util.function.Function<ClientRequest, ClientResponse> rawExchange,
            java.util.function.Function<ClientRequest, ClientResponse> marketExchange) {
        WebClient raw = client("http://external", rawExchange);
        WebClient market = client("http://business", marketExchange);
        return new PublicQuoteMarketDataService(raw, market, new StockAnalysisChartDataService(), CLOCK,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), 8);
    }

    private static WebClient client(String baseUrl,
                                    java.util.function.Function<ClientRequest, ClientResponse> exchange) {
        return WebClient.builder().baseUrl(baseUrl)
                .exchangeFunction(request -> Mono.just(exchange.apply(request))).build();
    }

    private static ClientResponse marketResponse(ClientRequest request) {
        return switch (request.url().getPath()) {
            case "/api/market-data/history/stock" -> ok("["
                    + "{\"tradingDate\":\"2026-08-24\",\"openPrice\":99,\"highPrice\":101,"
                    + "\"lowPrice\":98,\"closePrice\":100.5}]");
            case "/api/market-data/indicators/series" -> ok("[]");
            case "/internal/public-market-data/intraday-ticks-readonly" -> ok("{"
                    + "\"tradingDate\":\"2026-08-24\",\"readStatus\":\"DATA\","
                    + "\"ticks\":[{\"time\":\"2026-08-24T09:00:00\",\"price\":100.5}]}" );
            case "/api/market-data/quote-detail" -> ok("{"
                    + "\"stockCode\":\"2330\",\"stockName\":\"台積電\",\"market\":\"台股\","
                    + "\"supported\":true,\"available\":true,\"source\":\"FUBON_BOOKS\",\"levels\":[]}");
            case "/api/market-data/etf-holdings" -> ok("{"
                    + "\"stockCode\":\"2330\",\"market\":\"台股\",\"supported\":true,"
                    + "\"source\":\"ISSUER\",\"asOfDate\":\"2026-08-23\",\"message\":null,"
                    + "\"holdings\":[{\"stockCode\":\"1101\",\"stockName\":\"台泥\","
                    + "\"weight\":1.5,\"shares\":12}]}");
            case "/internal/public-market-data/dividends-readonly-result" -> ok("{"
                    + "\"stockCode\":\"2330\",\"market\":\"台股\",\"source\":\"DB\",\"message\":null,"
                    + "\"rows\":[{\"year\":2025,\"cashDividend\":5,\"stockDividend\":0,"
                    + "\"exDividendDate\":\"2025-06-20\",\"yieldPct\":1,"
                    + "\"cashPaymentDate\":\"2025-07-15\",\"stockPaymentDate\":null,"
                    + "\"fillDays\":25,\"previousClose\":500,\"exRightsDate\":\"2025-07-01\"}]}");
            default -> ClientResponse.create(HttpStatus.NOT_FOUND).build();
        };
    }

    private static ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private static String rawQuote(String code, String market, String tradingDate) {
        return "{"
                + "\"stockCode\":\"" + code + "\",\"stockName\":\"台積電\",\"market\":\"" + market + "\","
                + "\"price\":100.50,\"previousClose\":99.00,\"priceChange\":1.50,\"changePercent\":1.52,"
                + "\"buyPrice\":100.00,\"sellPrice\":101.00,\"openPrice\":99.50,\"highPrice\":101.00,"
                + "\"lowPrice\":99.00,\"volume\":123456,\"tradingDate\":\"" + tradingDate + "\","
                + "\"updatedAt\":\"2026-08-24T10:00:00+08:00\",\"closed\":false,\"source\":\"REDIS\","
                + "\"quoteStatus\":\"LIVE\",\"premiumDiscountPct\":0.25}";
    }

    private static List<String> concat(List<String> values, String... extras) {
        List<String> result = new ArrayList<>(values);
        result.addAll(List.of(extras));
        return result;
    }
}

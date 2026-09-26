package com.steven.assets.bff.publicquote;

import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DetailedLatestQuote;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.RawLatestQuote;
import com.steven.assets.bff.stockanalysis.StockAnalysisChartDataService;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 165／Task 456：raw 204 時的 30 日內最近收盤 fallback（不啟 Spring、stub WebClient）。 */
class PublicQuoteCloseFallbackTest {

    /** 台北 2026-08-24 18:00；fallback 區間固定為 2026-07-25～2026-08-24。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final String HISTORY = "/api/market-data/history/stock";
    private static final String FALLBACK_START = "start=2026-07-25";

    private final List<ClientRequest> rawRequests = new ArrayList<>();
    private final List<ClientRequest> marketRequests = new ArrayList<>();

    @Test
    void rawHitDoesNotCallCloseHistoryAndStaysLive() {
        PublicQuoteMarketDataService service = service(
                request -> Mono.just(ok(rawQuote())),
                request -> Mono.just(ok("[]")), Duration.ofSeconds(1));

        DetailedLatestQuote quote = service.one("2330", "台股", "2026-08-01", "2026-08-24").block();

        assertThat(quote.quoteStatus()).isEqualTo("LIVE");
        assertThat(quote.source()).isEqualTo("REDIS");
        assertThat(fallbackHistoryCalls()).isEmpty();
    }

    @Test
    void rawNoContentWithTwoHistoryRowsBuildsTheNineteenCloseFallbackFields() {
        PublicQuoteMarketDataService service = noContentService(request -> Mono.just(historyOr404(request, "["
                + "{\"tradingDate\":\"2026-08-20\",\"openPrice\":97,\"highPrice\":99,\"lowPrice\":96,\"closePrice\":98},"
                + "{\"tradingDate\":\"2026-08-21\",\"openPrice\":98.5,\"highPrice\":101,\"lowPrice\":98,\"closePrice\":100}"
                + "]")));

        DetailedLatestQuote quote = service.one("2330", "台股", null, null).block();

        assertThat(quote).isNotNull();
        assertThat(quote.stockCode()).isEqualTo("2330");
        assertThat(quote.market()).isEqualTo("台股");
        assertThat(quote.stockName()).isNull();
        assertThat(quote.price()).isEqualByComparingTo("100");
        assertThat(quote.previousClose()).isEqualByComparingTo("98");
        assertThat(quote.priceChange()).isEqualTo(new BigDecimal("2.000000"));
        assertThat(quote.changePercent()).isEqualTo(new BigDecimal("2.040816"));
        assertThat(quote.openPrice()).isEqualByComparingTo("98.5");
        assertThat(quote.highPrice()).isEqualByComparingTo("101");
        assertThat(quote.lowPrice()).isEqualByComparingTo("98");
        assertThat(quote.buyPrice()).isNull();
        assertThat(quote.sellPrice()).isNull();
        assertThat(quote.volume()).isNull();
        assertThat(quote.updatedAt()).isNull();
        assertThat(quote.premiumDiscountPct()).isNull();
        assertThat(quote.tradingDate()).isEqualTo("2026-08-21");
        assertThat(quote.closed()).isTrue();
        assertThat(quote.source()).isEqualTo("STOCK_PRICE_HISTORY");
        assertThat(quote.quoteStatus()).isEqualTo("CLOSE_FALLBACK");

        assertThat(fallbackHistoryCalls()).singleElement().satisfies(request -> {
            String query = request.url().getRawQuery();
            assertThat(query).contains("code=2330", FALLBACK_START, "end=2026-08-24");
            assertThat(request.headers()).doesNotContainKeys("X-User-Id", "X-User-Role", "X-User-Status");
        });
    }

    @Test
    void closeFallbackRowNeverCallsTheFubonBridgeAndUsesTheFallbackFubonShape() {
        PublicQuoteMarketDataService service = noContentService(request -> Mono.just(historyOr404(request,
                "[{\"tradingDate\":\"2026-08-21\",\"closePrice\":100}]")));

        DetailedLatestQuote quote = service.one("2330", "台股", null, null).block();

        assertThat(rawRequests).extracting(request -> request.url().getPath()).containsExactly("/api/quotes/one");
        assertThat(quote.fubonSupported()).isTrue();
        assertThat(quote.fubonAvailable()).isFalse();
        assertThat(quote.fubonMessage()).isEqualTo("暫時無法取得富邦即時回應");
        assertThat(quote.fubonReceivedAt()).isNull();
        assertThat(quote.fubonBatchId()).isNull();
        assertThat(quote.fubonResponseStatus()).isNull();
        assertThat(quote.fubonFailureReason()).isNull();
        assertThat(quote.fubonReturnedOrderBook()).isNull();
        assertThat(quote.marketData()).isNotNull();
    }

    @Test
    void singleHistoryRowLeavesPreviousCloseAndChangesNull() {
        PublicQuoteMarketDataService service = noContentService(request -> Mono.just(historyOr404(request,
                "[{\"tradingDate\":\"2026-08-21\",\"closePrice\":100}]")));

        DetailedLatestQuote quote = service.one("2330", "台股", null, null).block();

        assertThat(quote.price()).isEqualByComparingTo("100");
        assertThat(quote.previousClose()).isNull();
        assertThat(quote.priceChange()).isNull();
        assertThat(quote.changePercent()).isNull();
        assertThat(quote.openPrice()).isNull();
    }

    @Test
    void latestRowWithNullCloseIsSkippedForTheNextValidRow() {
        PublicQuoteMarketDataService service = noContentService(request -> Mono.just(historyOr404(request, "["
                + "{\"tradingDate\":\"2026-08-19\",\"closePrice\":95},"
                + "{\"tradingDate\":\"2026-08-20\",\"closePrice\":0},"
                + "{\"tradingDate\":\"2026-08-21\",\"closePrice\":98},"
                + "{\"tradingDate\":\"2026-08-22\",\"closePrice\":null}]")));

        DetailedLatestQuote quote = service.one("2330", "台股", null, null).block();

        assertThat(quote.tradingDate()).isEqualTo("2026-08-21");
        assertThat(quote.price()).isEqualByComparingTo("98");
        assertThat(quote.previousClose()).isEqualByComparingTo("95");
    }

    @Test
    void nonTaiwanTodayRowIsExcludedButTaiwanTodayRowIsKept() {
        List<PricePointDto> rows = List.of(
                new PricePointDto("2026-08-24", new BigDecimal("200")),
                new PricePointDto("2026-08-21", new BigDecimal("190")),
                new PricePointDto("2026-08-20", new BigDecimal("180")));

        RawLatestQuote us = PublicQuoteMarketDataService.closeFallbackQuote("AAPL", "美股", rows, TODAY).orElseThrow();
        RawLatestQuote tw = PublicQuoteMarketDataService.closeFallbackQuote("2330", "台股", rows, TODAY).orElseThrow();

        assertThat(us.tradingDate()).isEqualTo("2026-08-21");
        assertThat(us.price()).isEqualByComparingTo("190");
        assertThat(us.previousClose()).isEqualByComparingTo("180");
        assertThat(tw.tradingDate()).isEqualTo("2026-08-24");
        assertThat(tw.price()).isEqualByComparingTo("200");

        PublicQuoteMarketDataService service = noContentService(request -> Mono.just(historyOr404(request, "["
                + "{\"tradingDate\":\"2026-08-24\",\"closePrice\":200},"
                + "{\"tradingDate\":\"2026-08-21\",\"closePrice\":190}]")));
        DetailedLatestQuote quote = service.one("AAPL", "美股", null, null).block();
        assertThat(quote.tradingDate()).isEqualTo("2026-08-21");
        assertThat(quote.previousClose()).isNull();
        assertThat(quote.fubonSupported()).isFalse();
    }

    @Test
    void pureFunctionReturnsEmptyWithoutAnyValidClose() {
        assertThat(PublicQuoteMarketDataService.closeFallbackQuote("2330", "台股", List.of(), TODAY)).isEmpty();
        assertThat(PublicQuoteMarketDataService.closeFallbackQuote("2330", "台股", null, TODAY)).isEmpty();
        Optional<RawLatestQuote> invalid = PublicQuoteMarketDataService.closeFallbackQuote("2330", "台股", List.of(
                new PricePointDto("2026-08-21", null),
                new PricePointDto("2026-08-20", new BigDecimal("-1")),
                new PricePointDto("2026-08-19", BigDecimal.ZERO)), TODAY);
        assertThat(invalid).isEmpty();
        assertThat(PublicQuoteMarketDataService.closeFallbackQuote("AAPL", "美股",
                List.of(new PricePointDto("2026-08-24", BigDecimal.TEN)), TODAY)).isEmpty();
    }

    @Test
    void emptyHistoryStaysNoContent() {
        assertThat(noContentService(request -> Mono.just(ok("[]"))).one("2330", "台股", null, null).block()).isNull();
        assertThat(fallbackHistoryCalls()).hasSize(1);
    }

    @Test
    void historyServerErrorStaysNoContent() {
        PublicQuoteMarketDataService service = noContentService(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()));
        assertThat(service.one("2330", "台股", null, null).block()).isNull();
    }

    @Test
    void historyTimeoutStaysNoContent() {
        PublicQuoteMarketDataService service = service(
                request -> {
                    rawRequests.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                },
                request -> Mono.never(), Duration.ofMillis(200));
        assertThat(service.one("2330", "台股", null, null).block(Duration.ofSeconds(5))).isNull();
    }

    @Test
    void rawServerErrorKeepsTheExistingErrorAndNeverCallsHistory() {
        PublicQuoteMarketDataService service = service(
                request -> Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build()),
                request -> Mono.just(ok("[{\"tradingDate\":\"2026-08-21\",\"closePrice\":100}]")),
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> service.one("2330", "台股", null, null).block())
                .isInstanceOf(PublicQuoteRawUnavailableException.class);
        assertThat(marketRequests).isEmpty();
    }

    @Test
    void listNeverCallsTheCloseFallback() {
        PublicQuoteMarketDataService empty = service(
                request -> Mono.just(ok("[]")),
                request -> Mono.just(ok("[{\"tradingDate\":\"2026-08-21\",\"closePrice\":100}]")),
                Duration.ofSeconds(1));
        assertThat(empty.list("台股", null, null).block()).isEmpty();
        assertThat(marketRequests).isEmpty();

        PublicQuoteMarketDataService rows = service(
                request -> Mono.just(ok("[" + rawQuote() + "]")),
                request -> Mono.just(ok("[]")), Duration.ofSeconds(1));
        assertThat(rows.list("台股", "2026-08-01", "2026-08-24").block()).singleElement()
                .extracting("quoteStatus").isEqualTo("LIVE");
        assertThat(fallbackHistoryCalls()).isEmpty();
    }

    private List<ClientRequest> fallbackHistoryCalls() {
        return marketRequests.stream()
                .filter(request -> HISTORY.equals(request.url().getPath()))
                .filter(request -> request.url().getRawQuery() != null
                        && request.url().getRawQuery().contains(FALLBACK_START))
                .toList();
    }

    private PublicQuoteMarketDataService noContentService(Function<ClientRequest, Mono<ClientResponse>> market) {
        return service(request -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build()),
                market, Duration.ofSeconds(1));
    }

    private PublicQuoteMarketDataService service(
            Function<ClientRequest, Mono<ClientResponse>> rawExchange,
            Function<ClientRequest, Mono<ClientResponse>> marketExchange,
            Duration closeFallbackTimeout) {
        WebClient raw = WebClient.builder().baseUrl("http://external").exchangeFunction(request -> {
            rawRequests.add(request);
            return rawExchange.apply(request);
        }).build();
        WebClient market = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
            marketRequests.add(request);
            return marketExchange.apply(request);
        }).build();
        return new PublicQuoteMarketDataService(raw, market, new StockAnalysisChartDataService(), CLOCK,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), 8,
                Duration.ofSeconds(1), closeFallbackTimeout);
    }

    private static ClientResponse historyOr404(ClientRequest request, String historyBody) {
        if (HISTORY.equals(request.url().getPath()) && request.url().getRawQuery().contains(FALLBACK_START)) {
            return ok(historyBody);
        }
        return ClientResponse.create(HttpStatus.NOT_FOUND).build();
    }

    private static ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private static String rawQuote() {
        return "{\"stockCode\":\"2330\",\"stockName\":\"台積電\",\"market\":\"台股\","
                + "\"price\":100.50,\"previousClose\":99.00,\"priceChange\":1.50,\"changePercent\":1.52,"
                + "\"buyPrice\":100.00,\"sellPrice\":101.00,\"openPrice\":99.50,\"highPrice\":101.00,"
                + "\"lowPrice\":99.00,\"volume\":123456,\"tradingDate\":\"2026-08-24\","
                + "\"updatedAt\":\"2026-08-24T10:00:00+08:00\",\"closed\":false,\"source\":\"REDIS\","
                + "\"quoteStatus\":\"LIVE\",\"premiumDiscountPct\":0.25}";
    }
}

package com.steven.assets.bff.publicquote;

import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.ChartMarketData;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DataStatus;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DetailedLatestQuote;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DividendHistory;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.FubonReturnedOrderBook;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.FubonReturnedOrderBookLevel;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.IntradayBridgeResponse;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.IntradayMarketData;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.IntradayTick;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.ListedLatestQuote;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.MarketData;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.QuoteDetail;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.RawLatestQuote;
import com.steven.assets.bff.stockanalysis.EtfHoldingsAggregator;
import com.steven.assets.bff.stockanalysis.StockAnalysisChartDataService;
import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.EtfHoldingsDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

/**
 * Requirement 108／Task 372 的公開市場資料聚合。
 *
 * <p>唯一的輸入是 external-materials 原始 quote 和明列的 market-only business endpoint；
 * 此 service 不讀 configured-admin、Reactor tenant、快照、持股、成本、交易或任何 owner 資料。</p>
 */
@Service
public class PublicQuoteMarketDataService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final ParameterizedTypeReference<List<RawLatestQuote>> RAW_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient rawQuoteClient;
    private final WebClient marketDataClient;
    private final StockAnalysisChartDataService chartDataService;
    private final Clock clock;
    private final Duration rawTimeout;
    private final Duration chartTimeout;
    private final Duration quoteDetailTimeout;
    private final Duration etfTimeout;
    private final Duration dividendsTimeout;
    private final Duration intradayTimeout;
    private final Duration fubonLiveResponseTimeout;
    private final Duration rowTimeout;
    private final Duration globalTimeout;
    private final int listConcurrency;

    @Autowired
    public PublicQuoteMarketDataService(
            @Qualifier("publicQuoteRawClient") WebClient rawQuoteClient,
            @Qualifier("publicMarketDataBusinessClient") WebClient marketDataClient,
            StockAnalysisChartDataService chartDataService,
            @Qualifier("publicQuoteClock") Clock clock,
            @Value("${public-quote.timeout.raw-seconds:3}") long rawTimeoutSeconds,
            @Value("${public-quote.timeout.chart-seconds:7}") long chartTimeoutSeconds,
            @Value("${public-quote.timeout.quote-detail-seconds:2}") long quoteDetailTimeoutSeconds,
            @Value("${public-quote.timeout.etf-seconds:5}") long etfTimeoutSeconds,
            @Value("${public-quote.timeout.dividends-seconds:3}") long dividendsTimeoutSeconds,
            @Value("${public-quote.timeout.intraday-seconds:2}") long intradayTimeoutSeconds,
            @Value("${public-quote.timeout.row-seconds:7}") long rowTimeoutSeconds,
            @Value("${public-quote.timeout.global-seconds:50}") long globalTimeoutSeconds,
            @Value("${public-quote.list-concurrency:8}") int listConcurrency,
            @Value("${public-quote.timeout.fubon-live-response-seconds:2}") long fubonLiveResponseTimeoutSeconds) {
        this(rawQuoteClient, marketDataClient, chartDataService, clock,
                seconds(rawTimeoutSeconds), seconds(chartTimeoutSeconds), seconds(quoteDetailTimeoutSeconds),
                seconds(etfTimeoutSeconds), seconds(dividendsTimeoutSeconds), seconds(intradayTimeoutSeconds),
                seconds(rowTimeoutSeconds), seconds(globalTimeoutSeconds), listConcurrency,
                seconds(fubonLiveResponseTimeoutSeconds));
    }

    PublicQuoteMarketDataService(
            WebClient rawQuoteClient,
            WebClient marketDataClient,
            StockAnalysisChartDataService chartDataService,
            Clock clock,
            Duration rawTimeout,
            Duration chartTimeout,
            Duration quoteDetailTimeout,
            Duration etfTimeout,
            Duration dividendsTimeout,
            Duration intradayTimeout,
            Duration rowTimeout,
            Duration globalTimeout,
            int listConcurrency) {
        this(rawQuoteClient, marketDataClient, chartDataService, clock, rawTimeout, chartTimeout, quoteDetailTimeout,
                etfTimeout, dividendsTimeout, intradayTimeout, rowTimeout, globalTimeout, listConcurrency,
                Duration.ofSeconds(2));
    }

    PublicQuoteMarketDataService(
            WebClient rawQuoteClient,
            WebClient marketDataClient,
            StockAnalysisChartDataService chartDataService,
            Clock clock,
            Duration rawTimeout,
            Duration chartTimeout,
            Duration quoteDetailTimeout,
            Duration etfTimeout,
            Duration dividendsTimeout,
            Duration intradayTimeout,
            Duration rowTimeout,
            Duration globalTimeout,
            int listConcurrency,
            Duration fubonLiveResponseTimeout) {
        this.rawQuoteClient = rawQuoteClient;
        this.marketDataClient = marketDataClient;
        this.chartDataService = chartDataService;
        this.clock = clock.withZone(PublicQuoteMarketDataConfiguration.TAIPEI);
        this.rawTimeout = rawTimeout;
        this.chartTimeout = chartTimeout;
        this.quoteDetailTimeout = quoteDetailTimeout;
        this.etfTimeout = etfTimeout;
        this.dividendsTimeout = dividendsTimeout;
        this.intradayTimeout = intradayTimeout;
        this.fubonLiveResponseTimeout = fubonLiveResponseTimeout;
        this.rowTimeout = rowTimeout;
        this.globalTimeout = globalTimeout;
        this.listConcurrency = Math.min(8, Math.max(1, listConcurrency));
    }

    public Mono<List<ListedLatestQuote>> list(String market, String start, String end) {
        Instant requestStarted = clock.instant();
        DateRange range = validateRange(start, end);
        return fetchRawList(market)
                .flatMap(rows -> enrichList(rows, range, requestStarted));
    }

    public Mono<DetailedLatestQuote> one(String code, String market, String start, String end) {
        Instant requestStarted = clock.instant();
        DateRange range = validateRange(start, end);
        return fetchRawOne(code, market)
                .flatMap(raw -> enrichSingleRow(raw, range)
                        .timeout(remaining(requestStarted), Mono.just(fallbackQuote(raw, range))));
    }

    private Mono<List<RawLatestQuote>> fetchRawList(String market) {
        return rawQuoteClient.get()
                .uri(builder -> {
                    builder.path("/api/quotes");
                    if (market != null) {
                        builder.queryParam("market", market);
                    }
                    return builder.build();
                })
                .exchangeToMono(response -> decodeRawList(response))
                .timeout(rawTimeout)
                .onErrorMap(TimeoutException.class,
                        ex -> new PublicQuoteRawTimeoutException())
                .onErrorMap(this::isUnexpectedRawFailure,
                        ex -> new PublicQuoteRawUnavailableException());
    }

    private Mono<RawLatestQuote> fetchRawOne(String code, String market) {
        return rawQuoteClient.get()
                .uri(builder -> builder.path("/api/quotes/one")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .build())
                .exchangeToMono(this::decodeRawOne)
                .timeout(rawTimeout)
                .onErrorMap(TimeoutException.class,
                        ex -> new PublicQuoteRawTimeoutException())
                .onErrorMap(this::isUnexpectedRawFailure,
                        ex -> new PublicQuoteRawUnavailableException());
    }

    private Mono<List<RawLatestQuote>> decodeRawList(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            return Mono.error(new PublicQuoteRawUnavailableException());
        }
        return response.bodyToMono(RAW_LIST)
                .defaultIfEmpty(List.of())
                .map(List::copyOf);
    }

    private Mono<RawLatestQuote> decodeRawOne(ClientResponse response) {
        if (response.statusCode().equals(HttpStatus.NO_CONTENT)) {
            return Mono.empty();
        }
        if (!response.statusCode().is2xxSuccessful()) {
            return Mono.error(new PublicQuoteRawUnavailableException());
        }
        return response.bodyToMono(RawLatestQuote.class)
                .switchIfEmpty(Mono.error(new PublicQuoteRawUnavailableException()));
    }

    private boolean isUnexpectedRawFailure(Throwable error) {
        return !(error instanceof PublicQuoteRawUnavailableException)
                && !(error instanceof PublicQuoteRawTimeoutException);
    }

    private Mono<List<ListedLatestQuote>> enrichList(
            List<RawLatestQuote> rows, DateRange range, Instant requestStarted) {
        List<RawLatestQuote> immutableRows = List.copyOf(rows);
        ConcurrentMap<Integer, ListedLatestQuote> completed = new ConcurrentHashMap<>();
        Mono<List<ListedLatestQuote>> allRows = Flux.range(0, immutableRows.size())
                .flatMapSequential(index -> enrichListedRow(immutableRows.get(index), range)
                                .onErrorReturn(fallbackListedQuote(immutableRows.get(index), range))
                                .doOnNext(result -> completed.put(index, result)),
                        listConcurrency)
                .collectList();
        return allRows.timeout(remaining(requestStarted), Mono.fromSupplier(() ->
                completedOrFallback(immutableRows, range, completed)));
    }

    private List<ListedLatestQuote> completedOrFallback(
            List<RawLatestQuote> rows,
            DateRange range,
            ConcurrentMap<Integer, ListedLatestQuote> completed) {
        List<ListedLatestQuote> response = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            response.add(completed.getOrDefault(index, fallbackListedQuote(rows.get(index), range)));
        }
        return List.copyOf(response);
    }

    /** List rows keep their historical four-child fan-out and make zero Fubon-bridge calls. */
    private Mono<ListedLatestQuote> enrichListedRow(RawLatestQuote raw, DateRange range) {
        ListedLatestQuote fallback = fallbackListedQuote(raw, range);
        Mono<ChartMarketData> chart = fetchChart(raw, range).onErrorReturn(fallback.marketData().chart());
        Mono<QuoteDetail> quoteDetail = fetchQuoteDetail(raw).onErrorReturn(fallback.marketData().quoteDetail());
        Mono<EtfHoldingsDto> etfConstituents = fetchEtfConstituents(raw)
                .onErrorReturn(fallback.marketData().etfConstituents());
        Mono<DividendHistory> dividends = fetchDividends(raw).onErrorReturn(fallback.marketData().dividends());
        return Mono.zip(chart, quoteDetail, etfConstituents, dividends)
                .map(tuple -> listed(raw, new MarketData(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4())))
                .timeout(rowTimeout, Mono.just(fallback));
    }

    /** Single quote only: the safe Fubon bridge joins the existing four child reads in parallel. */
    private Mono<DetailedLatestQuote> enrichSingleRow(RawLatestQuote raw, DateRange range) {
        DetailedLatestQuote fallback = fallbackQuote(raw, range);
        Mono<ChartMarketData> chart = fetchChart(raw, range).onErrorReturn(fallback.marketData().chart());
        Mono<QuoteDetail> quoteDetail = fetchQuoteDetail(raw).onErrorReturn(fallback.marketData().quoteDetail());
        Mono<EtfHoldingsDto> etfConstituents = fetchEtfConstituents(raw)
                .onErrorReturn(fallback.marketData().etfConstituents());
        Mono<DividendHistory> dividends = fetchDividends(raw).onErrorReturn(fallback.marketData().dividends());
        Mono<FubonProjection> fubon = fetchFubonLiveResponse(raw).onErrorReturn(fallbackFubon(raw));
        return Mono.zip(chart, quoteDetail, etfConstituents, dividends, fubon)
                .map(tuple -> detailed(raw, new MarketData(
                                tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()), tuple.getT5()))
                .timeout(rowTimeout, Mono.just(fallback));
    }

    private Mono<ChartMarketData> fetchChart(RawLatestQuote raw, DateRange range) {
        IntradayMarketData initialIntraday = initialIntradayFallback(raw, range);
        Mono<ChartContent> chartContent = chartDataService
                .fetch(marketDataClient, raw.stockCode(), raw.market(), range.start(), range.end())
                .map(this::toChartContent)
                .timeout(chartTimeout)
                .onErrorReturn(ChartContent.unavailable());
        Mono<IntradayMarketData> intraday = fetchIntraday(raw, range, initialIntraday)
                .onErrorReturn(initialIntraday);
        return Mono.zip(chartContent, intraday)
                .map(tuple -> new ChartMarketData(tuple.getT1().status(), tuple.getT1().message(),
                        range.start(), range.end(), tuple.getT1().series(), tuple.getT2()));
    }

    private ChartContent toChartContent(StockAnalysisChartDataService.ChartFetchResult result) {
        if (!result.historyAvailable() && !result.indicatorsAvailable()) {
            return ChartContent.unavailable();
        }
        if (result.bothEmpty()) {
            return ChartContent.noData();
        }
        return new ChartContent(DataStatus.AVAILABLE, null, result.series());
    }

    private Mono<IntradayMarketData> fetchIntraday(
            RawLatestQuote raw, DateRange range, IntradayMarketData initialFallback) {
        if (initialFallback.status() == DataStatus.NO_DATA) {
            return Mono.just(initialFallback);
        }
        LocalDate target = initialFallback.tradingDate();
        return marketDataClient.get()
                .uri(builder -> builder.path("/internal/public-market-data/intraday-ticks-readonly")
                        .queryParam("code", raw.stockCode())
                        .queryParam("market", raw.market())
                        .queryParam("date", target)
                        .build())
                .retrieve()
                .bodyToMono(IntradayBridgeResponse.class)
                .timeout(intradayTimeout)
                .map(response -> toIntraday(response, target));
    }

    private IntradayMarketData toIntraday(IntradayBridgeResponse response, LocalDate target) {
        if (response == null || !target.equals(response.tradingDate()) || response.readStatus() == null) {
            return unavailableIntraday(target);
        }
        return switch (response.readStatus()) {
            case "DATA" -> response.ticks().isEmpty()
                    ? unavailableIntraday(target)
                    : new IntradayMarketData(DataStatus.AVAILABLE, null, target, response.ticks());
            case "EMPTY" -> response.ticks().isEmpty()
                    ? new IntradayMarketData(DataStatus.NO_DATA, "目前無分時資料", target, List.of())
                    : unavailableIntraday(target);
            case "UNAVAILABLE", "MALFORMED" -> unavailableIntraday(target);
            default -> unavailableIntraday(target);
        };
    }

    private Mono<QuoteDetail> fetchQuoteDetail(RawLatestQuote raw) {
        if (!isTaiwanQuoteDetailSupported(raw)) {
            return Mono.just(unsupportedQuoteDetail(raw));
        }
        return marketDataClient.get()
                .uri(builder -> builder.path("/api/market-data/quote-detail")
                        .queryParam("code", raw.stockCode())
                        .queryParam("market", raw.market())
                        .build())
                .retrieve()
                .bodyToMono(QuoteDetail.class)
                .map(detail -> normalizeQuoteDetail(raw, detail))
                .timeout(quoteDetailTimeout)
                .switchIfEmpty(Mono.error(new IllegalStateException("quote detail body unavailable")));
    }

    private Mono<EtfHoldingsDto> fetchEtfConstituents(RawLatestQuote raw) {
        return marketDataClient.get()
                .uri(builder -> builder.path("/api/market-data/etf-holdings")
                        .queryParam("code", raw.stockCode())
                        .queryParam("market", raw.market())
                        .build())
                .retrieve()
                .bodyToMono(EtfHoldingsDto.class)
                .map(EtfHoldingsAggregator::aggregate)
                .timeout(etfTimeout)
                .switchIfEmpty(Mono.error(new IllegalStateException("ETF constituents body unavailable")));
    }

    private Mono<DividendHistory> fetchDividends(RawLatestQuote raw) {
        return marketDataClient.get()
                .uri(builder -> builder.path("/internal/public-market-data/dividends-readonly-result")
                        .queryParam("code", raw.stockCode())
                        .queryParam("market", raw.market())
                        .queryParam("years", 10)
                        .build())
                .retrieve()
                .bodyToMono(DividendHistory.class)
                .map(this::normalizeDividendHistory)
                .timeout(dividendsTimeout)
                .switchIfEmpty(Mono.error(new IllegalStateException("dividend body unavailable")));
    }

    /** No-tenant, container-internal pure read.  This never reaches a vendor or a dispatcher. */
    private Mono<FubonProjection> fetchFubonLiveResponse(RawLatestQuote raw) {
        if (!isFubonSupported(raw)) return Mono.just(fallbackFubon(raw));
        return rawQuoteClient.get()
                .uri(builder -> builder.path("/internal/fubon-live-response")
                        .queryParam("code", raw.stockCode()).queryParam("market", raw.market()).build())
                .retrieve()
                .bodyToMono(FubonBridgeResponse.class)
                .map(response -> normalizeFubon(raw, response))
                .timeout(fubonLiveResponseTimeout)
                .switchIfEmpty(Mono.just(fallbackFubon(raw)));
    }

    private DetailedLatestQuote fallbackQuote(RawLatestQuote raw, DateRange range) {
        IntradayMarketData intraday = initialIntradayFallback(raw, range);
        ChartMarketData chart = new ChartMarketData(DataStatus.UNAVAILABLE, "市場資料暫時不可用",
                range.start(), range.end(), ChartSeriesDto.empty(), intraday);
        return detailed(raw, new MarketData(chart, unavailableQuoteDetail(raw), unavailableEtf(raw), unavailableDividends(raw)),
                fallbackFubon(raw));
    }

    private ListedLatestQuote fallbackListedQuote(RawLatestQuote raw, DateRange range) {
        IntradayMarketData intraday = initialIntradayFallback(raw, range);
        ChartMarketData chart = new ChartMarketData(DataStatus.UNAVAILABLE, "市場資料暫時不可用",
                range.start(), range.end(), ChartSeriesDto.empty(), intraday);
        return listed(raw, new MarketData(chart, unavailableQuoteDetail(raw), unavailableEtf(raw), unavailableDividends(raw)));
    }

    private IntradayMarketData initialIntradayFallback(RawLatestQuote raw, DateRange range) {
        Optional<LocalDate> target = tradingDateWithin(raw.tradingDate(), range);
        return target.<IntradayMarketData>map(this::unavailableIntraday)
                .orElseGet(() -> new IntradayMarketData(DataStatus.NO_DATA, "目前無分時資料", null, List.of()));
    }

    private IntradayMarketData unavailableIntraday(LocalDate target) {
        return new IntradayMarketData(DataStatus.UNAVAILABLE, "分時資料暫時不可用", target, List.of());
    }

    private QuoteDetail unsupportedQuoteDetail(RawLatestQuote raw) {
        return emptyQuoteDetail(raw, false, "此市場不支援行情五檔");
    }

    private QuoteDetail unavailableQuoteDetail(RawLatestQuote raw) {
        return emptyQuoteDetail(raw, isTaiwanQuoteDetailSupported(raw), "暫時無法取得行情五檔");
    }

    private QuoteDetail emptyQuoteDetail(RawLatestQuote raw, boolean supported, String message) {
        return new QuoteDetail(raw.stockCode(), raw.stockName(), raw.market(), supported, false,
                null, message, null, null, "UNKNOWN",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of());
    }

    /** Fail closed if an upstream proxy ever regresses to a request-time or unknown-source payload. */
    private QuoteDetail normalizeQuoteDetail(RawLatestQuote raw, QuoteDetail detail) {
        if (isApprovedCompleteBook(detail)) {
            return detail;
        }
        if (detail != null && !detail.supported()) {
            return unsupportedQuoteDetail(raw);
        }
        return unavailableQuoteDetail(raw);
    }

    private EtfHoldingsDto unavailableEtf(RawLatestQuote raw) {
        return new EtfHoldingsDto(raw.stockCode(), raw.market(), false, null, null,
                "ETF 成分股資料暫時不可用", List.of());
    }

    private DividendHistory unavailableDividends(RawLatestQuote raw) {
        return new DividendHistory(raw.stockCode(), raw.market(), null,
                "股利資料暫時不可用", List.of(), List.of());
    }

    private boolean isTaiwanQuoteDetailSupported(RawLatestQuote raw) {
        return "台股".equals(raw.market()) && !"0000".equals(raw.stockCode());
    }

    private boolean isFubonSupported(RawLatestQuote raw) {
        return isTaiwanQuoteDetailSupported(raw) && raw.stockCode() != null
                && raw.stockCode().matches("^[0-9]{4,6}[A-Z]?$");
    }

    private Optional<LocalDate> tradingDateWithin(String rawTradingDate, DateRange range) {
        try {
            LocalDate parsed = LocalDate.parse(rawTradingDate);
            if (parsed.isBefore(range.start()) || parsed.isAfter(range.end())) {
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (DateTimeParseException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    private ListedLatestQuote listed(RawLatestQuote raw, MarketData marketData) {
        QuoteDetail quoteDetail = marketData.quoteDetail();
        DividendHistory dividendHistory = marketData.dividends();
        return new ListedLatestQuote(
                raw.stockCode(), raw.stockName(), raw.market(), raw.price(), raw.previousClose(), raw.priceChange(),
                raw.changePercent(), raw.buyPrice(), raw.sellPrice(), raw.openPrice(), raw.highPrice(), raw.lowPrice(),
                raw.volume(), raw.tradingDate(), raw.updatedAt(), raw.closed(), raw.source(), raw.quoteStatus(),
                raw.premiumDiscountPct(), marketData, quoteDetail,
                projectBookSide(quoteDetail, true), projectBookSide(quoteDetail, false), dividendHistory);
    }

    private DetailedLatestQuote detailed(RawLatestQuote raw, MarketData marketData, FubonProjection fubon) {
        RawLatestQuote shared = matchingFubonQuote(raw, fubon);
        QuoteDetail quoteDetail = marketData.quoteDetail();
        DividendHistory dividendHistory = marketData.dividends();
        return new DetailedLatestQuote(
                shared.stockCode(), shared.stockName(), shared.market(), shared.price(), shared.previousClose(),
                shared.priceChange(), shared.changePercent(), shared.buyPrice(), shared.sellPrice(), shared.openPrice(),
                shared.highPrice(), shared.lowPrice(), shared.volume(), shared.tradingDate(), shared.updatedAt(),
                shared.closed(), shared.source(), shared.quoteStatus(), shared.premiumDiscountPct(), marketData, quoteDetail,
                projectBookSide(quoteDetail, true), projectBookSide(quoteDetail, false), dividendHistory,
                fubon.supported(), fubon.available(), fubon.message(), fubon.receivedAt(), fubon.batchId(),
                fubon.status(), fubon.reason(), fubon.returnedOrderBook());
    }

    /**
     * Treat bridge input as untrusted at this boundary.  It can only enrich the distinct metadata
     * and raw returned book; a malformed response becomes a child-local typed unavailable result.
     */
    private FubonProjection normalizeFubon(RawLatestQuote raw, FubonBridgeResponse value) {
        if (value == null || value.supported() != isFubonSupported(raw)) return fallbackFubon(raw);
        if (!value.supported()) return fallbackFubon(raw);
        if (!value.available()) {
            return new FubonProjection(true, false, safeMessage(value.message()), null, null, null, null, null, null);
        }
        if (value.receivedAt() == null || value.batchId() == null || value.batchId().isBlank()) return fallbackFubon(raw);
        if ("FAILURE".equals(value.status()) && value.quote() == null && validFailureReason(value.reason())) {
            return new FubonProjection(true, true, null, value.receivedAt(), value.batchId(), "FAILURE", value.reason(), null, null);
        }
        if (!"SUCCESS".equals(value.status()) || value.reason() != null || !validFubonQuote(raw, value.quote())) {
            return fallbackFubon(raw);
        }
        FubonReturnedOrderBook book = toReturnedOrderBook(value.quote().orderBook());
        if (value.quote().orderBook() != null && book == null) return fallbackFubon(raw);
        return new FubonProjection(true, true, null, value.receivedAt(), value.batchId(), "SUCCESS", null,
                value.quote(), book);
    }

    private FubonProjection fallbackFubon(RawLatestQuote raw) {
        return isFubonSupported(raw)
                ? new FubonProjection(true, false, "暫時無法取得富邦即時回應", null, null, null, null, null, null)
                : new FubonProjection(false, false, "此市場不支援富邦台股即時回應", null, null, null, null, null, null);
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() || message.length() > 120 ? "暫時無法取得富邦即時回應" : message;
    }

    private static boolean validFailureReason(String value) {
        return value != null && value.matches("^[A-Z_]{1,64}$");
    }

    private boolean validFubonQuote(RawLatestQuote raw, FubonBridgeQuote quote) {
        return quote != null && Objects.equals(raw.stockCode(), quote.stockCode()) && Objects.equals(raw.market(), quote.market())
                && quote.stockName() != null && !quote.stockName().isBlank()
                && positive(quote.actualPrice()) && positive(quote.previousClose()) && positive(quote.openPrice())
                && positive(quote.highPrice()) && positive(quote.lowPrice()) && quote.volume() != null && quote.volume() >= 0
                && quote.updatedAt() != null && quote.tradingDate() != null && "FUBON_INTRADAY".equals(quote.source())
                && quote.closed() != null && "LIVE".equals(quote.quoteStatus());
    }

    /** Only a provenance/time-identical SUCCESS may supply the existing single-copy quote fields. */
    private RawLatestQuote matchingFubonQuote(RawLatestQuote raw, FubonProjection fubon) {
        FubonBridgeQuote quote = fubon.quote();
        if (!fubon.available() || !"SUCCESS".equals(fubon.status()) || quote == null
                || !Objects.equals(raw.source(), quote.source()) || !Objects.equals(raw.tradingDate(), quote.tradingDate())
                || !sameMarketInstant(raw.updatedAt(), quote.updatedAt())) return raw;
        BigDecimal change = quote.actualPrice().subtract(quote.previousClose());
        BigDecimal percentage = quote.previousClose().signum() <= 0 ? null
                : change.multiply(HUNDRED).divide(quote.previousClose(), 6, RoundingMode.HALF_UP);
        return new RawLatestQuote(raw.stockCode(), quote.stockName(), raw.market(), quote.actualPrice(),
                quote.previousClose(), change, percentage, quote.buyPrice(), quote.sellPrice(), quote.openPrice(),
                quote.highPrice(), quote.lowPrice(), quote.volume(), quote.tradingDate(), raw.updatedAt(), quote.closed(),
                quote.source(), quote.quoteStatus(), raw.premiumDiscountPct());
    }

    private static boolean sameMarketInstant(String rawUpdatedAt, Instant fubonUpdatedAt) {
        if (fubonUpdatedAt == null || rawUpdatedAt == null || rawUpdatedAt.isBlank()) return false;
        try { return Instant.parse(rawUpdatedAt).equals(fubonUpdatedAt); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(rawUpdatedAt).toInstant().equals(fubonUpdatedAt); }
            catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(rawUpdatedAt)
                            .atZone(PublicQuoteMarketDataConfiguration.TAIPEI).toInstant().equals(fubonUpdatedAt);
                } catch (DateTimeParseException invalid) { return false; }
            }
        }
    }

    private static FubonReturnedOrderBook toReturnedOrderBook(FubonBridgeOrderBook source) {
        if (source == null || source.bookUpdatedAt() == null || source.levels() == null || source.levels().size() != 5) {
            return null;
        }
        List<FubonReturnedOrderBookLevel> levels = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            FubonBridgeOrderBookLevel level = source.levels().get(index);
            if (level == null || level.level() != index + 1
                    || (level.bidPrice() == null) != (level.bidVolumeLots() == null)
                    || (level.askPrice() == null) != (level.askVolumeLots() == null)) return null;
            levels.add(new FubonReturnedOrderBookLevel(level.level(), level.bidPrice(), level.bidVolumeLots(),
                    level.askPrice(), level.askVolumeLots()));
        }
        return new FubonReturnedOrderBook(source.bookUpdatedAt(), source.averagePrice(), source.turnoverYi(),
                source.innerVolumeLots(), source.outerVolumeLots(), levels);
    }

    /**
     * 五檔只可由同一個完整 persisted {@code FUBON_BOOKS}/{@code YAHOO_TW} snapshot 投影；
     * 絕不以 raw buy/sell 補值，也不把半邊／零量／亂序資料投影成 direct levels。
     */
    private List<PublicQuoteMarketDataDto.BookSideLevel> projectBookSide(QuoteDetail detail, boolean bid) {
        if (!isApprovedCompleteBook(detail)) {
            return List.of();
        }
        List<PublicQuoteMarketDataDto.BookSideLevel> projected = new ArrayList<>();
        for (PublicQuoteMarketDataDto.OrderBookLevel level : detail.levels()) {
            BigDecimal price = bid ? level.bidPrice() : level.askPrice();
            Long size = bid ? level.bidVolumeLots() : level.askVolumeLots();
            projected.add(new PublicQuoteMarketDataDto.BookSideLevel(price, size));
        }
        return List.copyOf(projected);
    }

    private boolean isApprovedCompleteBook(QuoteDetail detail) {
        Instant now = clock.instant();
        if (detail == null || !detail.supported() || !detail.available() || detail.stockCode() == null
                || !detail.stockCode().matches("^[0-9]{4,6}[A-Z]?$") || "0000".equals(detail.stockCode())
                || detail.stockName() == null || detail.stockName().isBlank() || !"台股".equals(detail.market())
                || !("FUBON_BOOKS".equals(detail.source()) || "YAHOO_TW".equals(detail.source()))
                || detail.sourceTime() == null || detail.sourceTime().isAfter(now)
                || !detail.sourceTime().atZone(PublicQuoteMarketDataConfiguration.TAIPEI).toLocalDate()
                .equals(now.atZone(PublicQuoteMarketDataConfiguration.TAIPEI).toLocalDate())
                || detail.fetchedAt() == null || !"OPEN".equals(detail.marketStatus())
                || !positive(detail.price()) || !positive(detail.previousClose())
                || detail.levels() == null || detail.levels().size() != 5) {
            return false;
        }
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        java.util.Set<BigDecimal> bids = new java.util.HashSet<>();
        java.util.Set<BigDecimal> asks = new java.util.HashSet<>();
        for (int index = 0; index < 5; index++) {
            PublicQuoteMarketDataDto.OrderBookLevel level = detail.levels().get(index);
            if (level == null || level.level() != index + 1 || level.bidPrice() == null || level.askPrice() == null
                    || level.bidPrice().compareTo(BigDecimal.ZERO) <= 0 || level.askPrice().compareTo(BigDecimal.ZERO) <= 0
                    || level.bidVolumeLots() == null || level.askVolumeLots() == null
                    || level.bidVolumeLots() <= 0 || level.askVolumeLots() <= 0
                    || !bids.add(level.bidPrice().stripTrailingZeros()) || !asks.add(level.askPrice().stripTrailingZeros())
                    || (previousBid != null && previousBid.compareTo(level.bidPrice()) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(level.askPrice()) >= 0)) {
                return false;
            }
            previousBid = level.bidPrice();
            previousAsk = level.askPrice();
        }
        return true;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * 將既有 readonly 股利 child 正規化一次後，同一 instance 同時掛在 nested 與 direct field。
     * 現金殖利率永遠以該 row 的前收計算；年度殖利率則以 anchor date 由新到舊第一筆有前收的
     * row 為唯一候選，候選非正值即 null，不能往較舊 row 尋找正值。
     */
    private DividendHistory normalizeDividendHistory(DividendHistory source) {
        List<PublicQuoteMarketDataDto.DividendRow> rows = source.rows().stream()
                .map(this::normalizeDividendRow)
                .toList();
        return new DividendHistory(source.stockCode(), source.market(), source.source(), source.message(), rows,
                annualDividendSummaries(rows));
    }

    private PublicQuoteMarketDataDto.DividendRow normalizeDividendRow(
            PublicQuoteMarketDataDto.DividendRow row) {
        return new PublicQuoteMarketDataDto.DividendRow(
                row.year(), row.cashDividend(), row.stockDividend(), row.exDividendDate(), row.yieldPct(),
                row.cashPaymentDate(), row.stockPaymentDate(), row.fillDays(), row.previousClose(),
                row.exRightsDate(), cashYieldPct(row.cashDividend(), row.previousClose()));
    }

    private List<PublicQuoteMarketDataDto.AnnualDividendSummary> annualDividendSummaries(
            List<PublicQuoteMarketDataDto.DividendRow> rows) {
        Map<Integer, List<IndexedDividendRow>> byYear = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            PublicQuoteMarketDataDto.DividendRow row = rows.get(index);
            if (row.year() != null) {
                byYear.computeIfAbsent(row.year(), ignored -> new ArrayList<>())
                        .add(new IndexedDividendRow(index, row));
            }
        }
        return byYear.entrySet().stream()
                .sorted(Map.Entry.<Integer, List<IndexedDividendRow>>comparingByKey(Comparator.reverseOrder()))
                .map(entry -> annualDividendSummary(entry.getKey(), entry.getValue()))
                .toList();
    }

    private PublicQuoteMarketDataDto.AnnualDividendSummary annualDividendSummary(
            Integer year, List<IndexedDividendRow> entries) {
        BigDecimal cashDividend = entries.stream()
                .map(entry -> zeroIfNull(entry.row().cashDividend()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stockDividend = entries.stream()
                .map(entry -> zeroIfNull(entry.row().stockDividend()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Optional<PublicQuoteMarketDataDto.DividendRow> yieldCandidate = entries.stream()
                .sorted(Comparator.comparing((IndexedDividendRow entry) -> anchorDate(entry.row()))
                        .reversed().thenComparingInt(IndexedDividendRow::index))
                .map(IndexedDividendRow::row)
                .filter(row -> row.previousClose() != null)
                .findFirst();
        BigDecimal cashYieldPct = yieldCandidate
                .map(row -> cashYieldPct(cashDividend, row.previousClose()))
                .orElse(null);
        return new PublicQuoteMarketDataDto.AnnualDividendSummary(
                year, cashDividend, stockDividend, cashYieldPct);
    }

    private LocalDate anchorDate(PublicQuoteMarketDataDto.DividendRow row) {
        LocalDate exDividend = parseIsoDate(row.exDividendDate());
        return exDividend != null ? exDividend : Optional.ofNullable(parseIsoDate(row.exRightsDate()))
                .orElse(LocalDate.MIN);
    }

    private LocalDate parseIsoDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private BigDecimal cashYieldPct(BigDecimal cashDividend, BigDecimal previousClose) {
        if (previousClose == null || previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return zeroIfNull(cashDividend).multiply(HUNDRED)
                .divide(previousClose, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private DateRange validateRange(String rawStart, String rawEnd) {
        boolean missingStart = rawStart == null;
        boolean missingEnd = rawEnd == null;
        LocalDate today = LocalDate.now(clock);
        if (missingStart && missingEnd) {
            return new DateRange(today.minusYears(1), today);
        }
        if (missingStart || missingEnd) {
            throw new PublicQuoteRequestException("start 與 end 必須同時提供");
        }
        try {
            LocalDate start = LocalDate.parse(rawStart);
            LocalDate end = LocalDate.parse(rawEnd);
            if (start.isAfter(end)) {
                throw new PublicQuoteRequestException("start 不可晚於 end");
            }
            if (start.isBefore(today.minusYears(10)) || end.isAfter(today)) {
                throw new PublicQuoteRequestException("日期範圍必須在最近十年且不得晚於今天");
            }
            return new DateRange(start, end);
        } catch (DateTimeParseException invalid) {
            throw new PublicQuoteRequestException("start 與 end 必須是 ISO 日期");
        }
    }

    private Duration remaining(Instant requestStarted) {
        Duration elapsed = Duration.between(requestStarted, clock.instant());
        Duration remaining = globalTimeout.minus(elapsed);
        return remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining;
    }

    private static Duration seconds(long seconds) {
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    /** Internal external-materials bridge transfer shape; never returned directly by 9090. */
    record FubonBridgeResponse(
            boolean supported,
            boolean available,
            String message,
            Instant receivedAt,
            String batchId,
            String status,
            String reason,
            FubonBridgeQuote quote) {}

    record FubonBridgeQuote(
            String stockCode,
            String stockName,
            String market,
            BigDecimal actualPrice,
            BigDecimal previousClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            Long volume,
            Instant updatedAt,
            String tradingDate,
            String source,
            Boolean closed,
            String quoteStatus,
            FubonBridgeOrderBook orderBook) {}

    record FubonBridgeOrderBook(
            Instant bookUpdatedAt,
            BigDecimal averagePrice,
            BigDecimal turnoverYi,
            Long innerVolumeLots,
            Long outerVolumeLots,
            List<FubonBridgeOrderBookLevel> levels) {
        FubonBridgeOrderBook { levels = levels == null ? List.of() : List.copyOf(levels); }
    }

    record FubonBridgeOrderBookLevel(
            int level,
            BigDecimal bidPrice,
            Long bidVolumeLots,
            BigDecimal askPrice,
            Long askVolumeLots) {}

    /** Prevalidated bridge state, separated from its nested transfer quote to avoid public duplication. */
    private record FubonProjection(
            boolean supported,
            boolean available,
            String message,
            Instant receivedAt,
            String batchId,
            String status,
            String reason,
            FubonBridgeQuote quote,
            FubonReturnedOrderBook returnedOrderBook) {}

    private record DateRange(LocalDate start, LocalDate end) {}

    private record IndexedDividendRow(int index, PublicQuoteMarketDataDto.DividendRow row) {}

    private record ChartContent(DataStatus status, String message, ChartSeriesDto series) {
        static ChartContent noData() {
            return new ChartContent(DataStatus.NO_DATA, "目前無走勢資料", ChartSeriesDto.empty());
        }

        static ChartContent unavailable() {
            return new ChartContent(DataStatus.UNAVAILABLE, "走勢資料暫時不可用", ChartSeriesDto.empty());
        }
    }
}

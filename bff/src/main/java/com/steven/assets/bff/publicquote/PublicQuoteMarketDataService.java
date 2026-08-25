package com.steven.assets.bff.publicquote;

import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.ChartMarketData;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DataStatus;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DetailedLatestQuote;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DividendHistory;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.IntradayBridgeResponse;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.IntradayMarketData;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.IntradayTick;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
            @Value("${public-quote.list-concurrency:8}") int listConcurrency) {
        this(rawQuoteClient, marketDataClient, chartDataService, clock,
                seconds(rawTimeoutSeconds), seconds(chartTimeoutSeconds), seconds(quoteDetailTimeoutSeconds),
                seconds(etfTimeoutSeconds), seconds(dividendsTimeoutSeconds), seconds(intradayTimeoutSeconds),
                seconds(rowTimeoutSeconds), seconds(globalTimeoutSeconds), listConcurrency);
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
        this.rowTimeout = rowTimeout;
        this.globalTimeout = globalTimeout;
        this.listConcurrency = Math.min(8, Math.max(1, listConcurrency));
    }

    public Mono<List<DetailedLatestQuote>> list(String market, String start, String end) {
        Instant requestStarted = clock.instant();
        DateRange range = validateRange(start, end);
        return fetchRawList(market)
                .flatMap(rows -> enrichList(rows, range, requestStarted));
    }

    public Mono<DetailedLatestQuote> one(String code, String market, String start, String end) {
        Instant requestStarted = clock.instant();
        DateRange range = validateRange(start, end);
        return fetchRawOne(code, market)
                .flatMap(raw -> enrichRow(raw, range)
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

    private Mono<List<DetailedLatestQuote>> enrichList(
            List<RawLatestQuote> rows, DateRange range, Instant requestStarted) {
        List<RawLatestQuote> immutableRows = List.copyOf(rows);
        ConcurrentMap<Integer, DetailedLatestQuote> completed = new ConcurrentHashMap<>();
        Mono<List<DetailedLatestQuote>> allRows = Flux.range(0, immutableRows.size())
                .flatMapSequential(index -> enrichRow(immutableRows.get(index), range)
                                .onErrorReturn(fallbackQuote(immutableRows.get(index), range))
                                .doOnNext(result -> completed.put(index, result)),
                        listConcurrency)
                .collectList();
        return allRows.timeout(remaining(requestStarted), Mono.fromSupplier(() ->
                completedOrFallback(immutableRows, range, completed)));
    }

    private List<DetailedLatestQuote> completedOrFallback(
            List<RawLatestQuote> rows,
            DateRange range,
            ConcurrentMap<Integer, DetailedLatestQuote> completed) {
        List<DetailedLatestQuote> response = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            response.add(completed.getOrDefault(index, fallbackQuote(rows.get(index), range)));
        }
        return List.copyOf(response);
    }

    /** 五個 child 同時訂閱；列級 timeout 只把本列降為預建 fallback。 */
    private Mono<DetailedLatestQuote> enrichRow(RawLatestQuote raw, DateRange range) {
        DetailedLatestQuote fallback = fallbackQuote(raw, range);
        Mono<ChartMarketData> chart = fetchChart(raw, range)
                .onErrorReturn(fallback.marketData().chart());
        Mono<QuoteDetail> quoteDetail = fetchQuoteDetail(raw)
                .onErrorReturn(fallback.marketData().quoteDetail());
        Mono<EtfHoldingsDto> etfConstituents = fetchEtfConstituents(raw)
                .onErrorReturn(fallback.marketData().etfConstituents());
        Mono<DividendHistory> dividends = fetchDividends(raw)
                .onErrorReturn(fallback.marketData().dividends());

        return Mono.zip(chart, quoteDetail, etfConstituents, dividends)
                .map(tuple -> detailed(raw, new MarketData(
                        tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4())))
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
                .timeout(dividendsTimeout)
                .switchIfEmpty(Mono.error(new IllegalStateException("dividend body unavailable")));
    }

    private DetailedLatestQuote fallbackQuote(RawLatestQuote raw, DateRange range) {
        IntradayMarketData intraday = initialIntradayFallback(raw, range);
        ChartMarketData chart = new ChartMarketData(DataStatus.UNAVAILABLE, "市場資料暫時不可用",
                range.start(), range.end(), ChartSeriesDto.empty(), intraday);
        return detailed(raw, new MarketData(chart, unavailableQuoteDetail(raw), unavailableEtf(raw), unavailableDividends(raw)));
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

    /** Fail closed if an upstream proxy ever regresses to a request-time/unknown source. */
    private QuoteDetail normalizeQuoteDetail(RawLatestQuote raw, QuoteDetail detail) {
        if (detail != null && detail.available() && "FUBON_BOOKS".equals(detail.source())) {
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
                "股利資料暫時不可用", List.of());
    }

    private boolean isTaiwanQuoteDetailSupported(RawLatestQuote raw) {
        return "台股".equals(raw.market()) && !"0000".equals(raw.stockCode());
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

    private DetailedLatestQuote detailed(RawLatestQuote raw, MarketData marketData) {
        return new DetailedLatestQuote(
                raw.stockCode(), raw.stockName(), raw.market(), raw.price(), raw.previousClose(), raw.priceChange(),
                raw.changePercent(), raw.buyPrice(), raw.sellPrice(), raw.openPrice(), raw.highPrice(), raw.lowPrice(),
                raw.volume(), raw.tradingDate(), raw.updatedAt(), raw.closed(), raw.source(), raw.quoteStatus(),
                raw.premiumDiscountPct(), marketData);
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

    private record DateRange(LocalDate start, LocalDate end) {}

    private record ChartContent(DataStatus status, String message, ChartSeriesDto series) {
        static ChartContent noData() {
            return new ChartContent(DataStatus.NO_DATA, "目前無走勢資料", ChartSeriesDto.empty());
        }

        static ChartContent unavailable() {
            return new ChartContent(DataStatus.UNAVAILABLE, "走勢資料暫時不可用", ChartSeriesDto.empty());
        }
    }
}

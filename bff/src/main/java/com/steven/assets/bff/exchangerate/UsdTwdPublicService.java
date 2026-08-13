package com.steven.assets.bff.exchangerate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 聚合 business live + history 的固定 USD/TWD 公開唯讀 service。 */
@Service
public class UsdTwdPublicService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ParameterizedTypeReference<List<HistoryPayload>> HISTORY_LIST =
            new ParameterizedTypeReference<>() { };
    private static final Set<String> SOURCES =
            Set.of("BANK_OF_TAIWAN", "MEGA_BANK", "YAHOO", "HISTORY");
    private static final Set<String> LIVE_STATUSES = Set.of("ACTIVE", "INACTIVE", "UNAVAILABLE");
    private static final Set<String> QUOTE_STATUSES =
            Set.of("LIVE", "INDICATIVE", "STALE", "LAST_AVAILABLE");

    private final WebClient businessClient;
    private final Clock clock;

    @Autowired
    public UsdTwdPublicService(WebClient businessServicesClient) {
        this(businessServicesClient, Clock.system(TAIPEI));
    }

    UsdTwdPublicService(WebClient businessServicesClient, Clock clock) {
        this.businessClient = businessServicesClient;
        this.clock = clock.withZone(TAIPEI);
    }

    public Mono<UsdTwdPublicDto.Response> getUsdTwd() {
        LocalDate end = LocalDate.now(clock);
        LocalDate start = end.minusYears(1);
        Mono<List<HistoryPayload>> history = fetchHistory(start, end);
        Mono<LivePayload> live = fetchLive();
        return Mono.zip(history, live)
                .map(tuple -> shape(start, end, tuple.getT1(), tuple.getT2()));
    }

    private Mono<List<HistoryPayload>> fetchHistory(LocalDate start, LocalDate end) {
        return businessClient.get()
                .uri(builder -> builder.path("/api/market-data/exchange-rate")
                        .queryParam("currency", "USD")
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .build())
                .exchangeToMono(response -> decodeHistoryResponse(response))
                .onErrorMap(this::isUntypedFailure,
                        ex -> new UsdTwdBadGatewayException("USD/TWD history downstream failure", ex));
    }

    private Mono<LivePayload> fetchLive() {
        return businessClient.get()
                .uri("/api/market-data/exchange-rate/usd-twd/live")
                .exchangeToMono(response -> decodeLiveResponse(response))
                .onErrorMap(this::isUntypedFailure,
                        ex -> new UsdTwdBadGatewayException("USD/TWD live downstream failure", ex));
    }

    private Mono<List<HistoryPayload>> decodeHistoryResponse(ClientResponse response) {
        if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
            return Mono.error(new UsdTwdUnavailableException());
        }
        if (!response.statusCode().is2xxSuccessful()) {
            return Mono.error(new UsdTwdBadGatewayException("USD/TWD history downstream status 非 2xx"));
        }
        return response.bodyToMono(HISTORY_LIST)
                .switchIfEmpty(Mono.error(new UsdTwdBadGatewayException("USD/TWD history body 為空")));
    }

    private Mono<LivePayload> decodeLiveResponse(ClientResponse response) {
        if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
            return Mono.error(new UsdTwdUnavailableException());
        }
        if (!response.statusCode().is2xxSuccessful()) {
            return Mono.error(new UsdTwdBadGatewayException("USD/TWD live downstream status 非 2xx"));
        }
        return response.bodyToMono(LivePayload.class)
                .switchIfEmpty(Mono.error(new UsdTwdBadGatewayException("USD/TWD live body 為空")));
    }

    private boolean isUntypedFailure(Throwable throwable) {
        return !(throwable instanceof UsdTwdUnavailableException)
                && !(throwable instanceof UsdTwdBadGatewayException);
    }

    private UsdTwdPublicDto.Response shape(
            LocalDate start,
            LocalDate end,
            List<HistoryPayload> historyPayload,
            LivePayload live) {
        if (historyPayload == null) badGateway("history body 為 null");
        if (historyPayload.isEmpty()) throw new UsdTwdUnavailableException();
        if (live == null) badGateway("live body 為 null");

        List<UsdTwdPublicDto.HistoryPoint> history = validateHistory(start, end, historyPayload);
        UsdTwdPublicDto.Spot spot = validateLive(live);
        return new UsdTwdPublicDto.Response(
                "USD/TWD", "USD", "TWD", start, end, 2, TAIPEI.getId(),
                live.liveUpdateStatus(), spot, history.size(), history);
    }

    private List<UsdTwdPublicDto.HistoryPoint> validateHistory(
            LocalDate start,
            LocalDate end,
            List<HistoryPayload> rows) {
        List<UsdTwdPublicDto.HistoryPoint> result = new ArrayList<>(rows.size());
        Set<LocalDate> dates = new HashSet<>();
        for (HistoryPayload row : rows) {
            if (row == null || row.rateDate() == null || row.buyRate() == null || row.sellRate() == null) {
                badGateway("history 欄位缺漏");
            }
            validateRates(row.buyRate(), row.sellRate(), "history");
            if (row.rateDate().isBefore(start) || row.rateDate().isAfter(end)
                    || !dates.add(row.rateDate())) {
                badGateway("history 日期超界或重複");
            }
            result.add(new UsdTwdPublicDto.HistoryPoint(
                    row.rateDate(), row.buyRate(), row.sellRate(), mid(row.buyRate(), row.sellRate())));
        }
        result.sort(Comparator.comparing(UsdTwdPublicDto.HistoryPoint::date));
        return List.copyOf(result);
    }

    private UsdTwdPublicDto.Spot validateLive(LivePayload live) {
        if (!"USD/TWD".equals(live.pair()) || !"USD".equals(live.baseCurrency())
                || !"TWD".equals(live.quoteCurrency()) || live.date() == null
                || !SOURCES.contains(live.source()) || !LIVE_STATUSES.contains(live.liveUpdateStatus())
                || !QUOTE_STATUSES.contains(live.quoteStatus())) {
            badGateway("live 固定欄位或 enum 非法");
        }
        validateRates(live.buyRate(), live.sellRate(), "live");
        validateTimestampContract(live);
        validateStatusContract(live);
        return new UsdTwdPublicDto.Spot(
                live.date(), live.buyRate(), live.sellRate(), mid(live.buyRate(), live.sellRate()),
                live.source(), live.polledAt(), live.sourceUpdatedAt(), live.quoteStatus());
    }

    private void validateTimestampContract(LivePayload live) {
        if ("HISTORY".equals(live.source())) {
            if (live.polledAt() != null || live.sourceUpdatedAt() != null) {
                badGateway("HISTORY 不得含 live timestamp");
            }
            return;
        }
        if (live.polledAt() == null) badGateway("cache source 缺少 polledAt");
        if ("BANK_OF_TAIWAN".equals(live.source())) {
            if (live.sourceUpdatedAt() != null
                    || !live.date().equals(live.polledAt().atZone(TAIPEI).toLocalDate())) {
                badGateway("台銀 timestamp/date 組合非法");
            }
            return;
        }
        if (live.sourceUpdatedAt() == null
                || live.sourceUpdatedAt().isAfter(live.polledAt().plusSeconds(120))
                || !live.date().equals(live.sourceUpdatedAt().atZone(TAIPEI).toLocalDate())) {
            badGateway("Mega/Yahoo timestamp/date 組合非法");
        }
    }

    private void validateStatusContract(LivePayload live) {
        switch (live.liveUpdateStatus()) {
            case "INACTIVE" -> {
                if (!"LAST_AVAILABLE".equals(live.quoteStatus())) badGateway("INACTIVE quoteStatus 非法");
            }
            case "UNAVAILABLE" -> {
                if (!"STALE".equals(live.quoteStatus()) || !"HISTORY".equals(live.source())) {
                    badGateway("UNAVAILABLE quote/source 組合非法");
                }
            }
            case "ACTIVE" -> {
                if ("LIVE".equals(live.quoteStatus())
                        && !("BANK_OF_TAIWAN".equals(live.source()) || "MEGA_BANK".equals(live.source()))) {
                    badGateway("LIVE source 非銀行");
                }
                if ("INDICATIVE".equals(live.quoteStatus()) && !"YAHOO".equals(live.source())) {
                    badGateway("INDICATIVE source 非 Yahoo");
                }
                if ("LAST_AVAILABLE".equals(live.quoteStatus())) badGateway("ACTIVE status 組合非法");
            }
            default -> badGateway("liveUpdateStatus 非法");
        }
    }

    private static void validateRates(BigDecimal buy, BigDecimal sell, String field) {
        if (buy == null || sell == null || buy.signum() <= 0 || sell.signum() <= 0
                || buy.compareTo(sell) > 0) {
            badGateway(field + " rates 非法");
        }
    }

    private static BigDecimal mid(BigDecimal buy, BigDecimal sell) {
        return buy.add(sell).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static void badGateway(String message) {
        throw new UsdTwdBadGatewayException(message);
    }

    private record HistoryPayload(
            LocalDate rateDate,
            BigDecimal buyRate,
            BigDecimal sellRate) {
    }

    private record LivePayload(
            String pair,
            String baseCurrency,
            String quoteCurrency,
            LocalDate date,
            BigDecimal buyRate,
            BigDecimal sellRate,
            String source,
            Instant polledAt,
            Instant sourceUpdatedAt,
            String liveUpdateStatus,
            String quoteStatus) {
    }
}

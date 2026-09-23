package com.steven.assets.bff.snapshotform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.snapshotform.dto.SnapshotFormPanelResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/** SnapshotForm 的唯讀 Panel 聚合；不得啟動刷新、回補或逐股配息抓取。 */
@Service
public class SnapshotFormPanelService {
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration PANEL_BUDGET = Duration.ofSeconds(20);
    private static final Duration OPTIONAL_BUDGET = Duration.ofSeconds(5);

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;
    private final Duration panelBudget;
    private final Duration optionalBudget;

    @Autowired
    public SnapshotFormPanelService(@Qualifier("businessServicesClient") WebClient businessServicesClient,
                                    SnapshotEnricher enricher) {
        this(businessServicesClient, enricher, PANEL_BUDGET, OPTIONAL_BUDGET);
    }

    SnapshotFormPanelService(WebClient businessServicesClient, SnapshotEnricher enricher,
                             Duration panelBudget, Duration optionalBudget) {
        this.businessServicesClient = businessServicesClient;
        this.enricher = enricher;
        this.panelBudget = panelBudget;
        this.optionalBudget = optionalBudget;
    }

    public Mono<SnapshotFormPanelResponse> basic(Long id) {
        return complete(snapshot(id).map(snapshot -> response("basic", id, snapshot, null, Map.of(), List.of())));
    }

    public Mono<SnapshotFormPanelResponse> deposits(Long id) {
        return complete(Mono.zip(snapshot(id), activeList("/api/settings/banks"),
                        activeList("/api/settings/deposit-types"), activeList("/api/settings/transit-fund-types/active"))
                .map(t -> response("deposits", id, t.getT1(), "deposits", Map.of(
                        "banks", t.getT2(), "depositTypes", t.getT3(), "transitFundTypes", t.getT4()), List.of())));
    }

    public Mono<SnapshotFormPanelResponse> funds(Long id) {
        Mono<FundData> data = snapshot(id).flatMap(snapshot -> businessServicesClient.get()
                .uri(uri -> uri.path("/api/funds").queryParam("date", snapshot.date()).build())
                .retrieve().bodyToMono(LIST_MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .map(SnapshotFormPanelService::validRows)
                .map(funds -> new FundData(snapshot, funds)));
        return complete(Mono.zip(data, activeList("/api/settings/banks"))
                .map(t -> response("funds", id, t.getT1().snapshot(), "funds", Map.of(
                        "banks", t.getT2(), "fundMasters", t.getT1().fundMasters()), List.of())));
    }

    public Mono<SnapshotFormPanelResponse> stocks(Long id) {
        Mono<StockData> data = snapshot(id).flatMap(snapshot -> {
            // fingerprint 已在原始 detail 上完成，enrich 僅影響此 request 自己的副本。
            enricher.enrichInvestmentCostOriginal(snapshot.detail());
            return Mono.zip(enricher.fetchSnapshotCloseData(snapshot.detail(), true)
                            .switchIfEmpty(Mono.just(new SnapshotEnricher.SnapshotCloseData(Map.of(), Map.of(), Map.of()))),
                            transactionRates(snapshot))
                    .map(t -> new StockData(snapshot, t.getT1(), t.getT2()));
        });
        return complete(Mono.zip(data, activeList("/api/settings/brokers"), prices(), marketStatus())
                .map(t -> {
                    StockData stock = t.getT1();
                    List<String> warnings = new ArrayList<>(t.getT3().warnings());
                    warnings.addAll(t.getT4().warnings());
                    warnings.addAll(stock.transactionRates().warnings());
                    List<Map<String, Object>> resolvedPrices = resolvePrices(stock, t.getT3().data(), warnings);
                    return response("stocks", id, stock.snapshot(), "stocks", Map.of(
                            "brokers", t.getT2(),
                            "mergedStocks", enricher.buildMergedStocks(stock.snapshot().detail(), stock.close().closeMap(), true, false),
                            "stockPrices", resolvedPrices,
                            "marketStatus", t.getT4().data(),
                            "transactionRates", stock.transactionRates().data()), warnings);
                }));
    }

    private Mono<Snapshot> snapshot(Long id) {
        return businessServicesClient.get().uri("/api/snapshots/{id}", id)
                .retrieve().bodyToMono(MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .flatMap(detail -> {
                    BigDecimal actualId = number(detail.get("id"));
                    if (id == null || actualId == null || actualId.compareTo(BigDecimal.valueOf(id)) != 0) {
                        return Mono.error(invalidResponse());
                    }
                    LocalDate date;
                    try {
                        date = LocalDate.parse((String) detail.get("snapshotDate"));
                    } catch (RuntimeException invalidDate) {
                        return Mono.error(invalidResponse());
                    }
                    for (String child : List.of("deposits", "stocks", "funds")) childRows(detail, child);
                    String version = fingerprint(detail);
                    return effectiveRate(detail, date).map(rate -> new Snapshot(detail, date, version, rate));
                });
    }

    private Mono<BigDecimal> effectiveRate(Map<String, Object> detail, LocalDate date) {
        BigDecimal raw = positive(detail.get("usdExchangeRate"));
        return raw != null ? Mono.just(raw) : exchangeRate(date.toString());
    }

    /** 日期端點只讀已保存資料，絕不呼叫 legacy BFF 的今日 refresh。 */
    private Mono<BigDecimal> exchangeRate(String date) {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/exchange-rate/on-date")
                        .queryParam("currency", "USD").queryParam("date", date).build())
                .retrieve().bodyToMono(MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .map(body -> {
                    BigDecimal rate = positive(body.get("midRate"));
                    if (rate == null) throw invalidResponse();
                    return rate;
                });
    }

    private Mono<OptionalData<Map<String, BigDecimal>>> transactionRates(Snapshot snapshot) {
        LinkedHashSet<String> dates = new LinkedHashSet<>();
        for (Map<String, Object> row : childRows(snapshot.detail(), "stocks")) {
            if (!"美股".equals(row.get("market")) || positive(row.get("transactionExchangeRate")) != null
                    || row.get("transactionDate") == null) continue;
            try {
                dates.add(LocalDate.parse(row.get("transactionDate").toString()).toString());
            } catch (RuntimeException invalidDate) {
                return Mono.error(invalidResponse());
            }
        }
        return Flux.fromIterable(dates)
                .flatMap(date -> exchangeRate(date).timeout(optionalBudget)
                        .map(rate -> new TransactionRate(date, rate))
                        // Only this exact optional on-date lookup treats 404 as missing history.
                        .onErrorResume(error -> status(error) == 401 || status(error) == 403
                                ? Mono.error(error) : Mono.just(new TransactionRate(date, null))), 4)
                .collectList()
                .map(rates -> {
                    Map<String, BigDecimal> values = new TreeMap<>();
                    for (TransactionRate rate : rates) if (rate.rate() != null) values.put(rate.date(), rate.rate());
                    return new OptionalData<>(values, values.size() == dates.size()
                            ? List.of() : List.of("TRANSACTION_RATES_UNAVAILABLE"));
                });
    }

    /** 僅處理本快照持股；歷史市場從不以今天 live 填補缺失的收盤資料。 */
    private List<Map<String, Object>> resolvePrices(StockData stock, List<Map<String, Object>> live,
                                                   List<String> warnings) {
        Map<String, Map<String, Object>> liveByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : live) {
            if (row.get("market") != null && row.get("stockCode") != null) {
                liveByKey.put(row.get("market") + "_" + row.get("stockCode"), row);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> holding : childRows(stock.snapshot().detail(), "stocks")) {
            String market = SnapshotEnricher.asString(holding.get("market"));
            String code = SnapshotEnricher.asString(holding.get("stockCode"));
            if (market == null || market.isBlank() || code == null || code.isBlank()) throw invalidResponse();
            String key = market + "_" + code;
            if (!seen.add(key)) continue;
            Map<String, Object> row = SnapshotEnricher.isCurrentBasedate(stock.snapshot().date(), market)
                    ? liveByKey.get(key) : null;
            if (row == null) row = stock.close().displayRows().get(key);
            if (row == null) {
                warnings.add("CLOSE_PRICES_UNAVAILABLE");
                continue; // mergedStocks retains stored valuation; do not synthesize a price or tradingDate.
            }
            Map<String, Object> quote = new LinkedHashMap<>(row);
            // Names and dividend rates on first load come from the saved holding, not a live quote.
            quote.remove("stockName");
            quote.remove("dividendRate");
            result.add(quote);
        }
        return result;
    }

    private Mono<List<Map<String, Object>>> activeList(String path) {
        return businessServicesClient.get().uri(path).retrieve().bodyToMono(LIST_MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .map(SnapshotFormPanelService::validRows)
                .map(rows -> rows.stream().filter(row -> Boolean.TRUE.equals(row.get("active"))).toList());
    }

    private Mono<OptionalData<List<Map<String, Object>>>> prices() {
        return optional(businessServicesClient.get().uri("/api/market-data/prices")
                        .retrieve().bodyToMono(LIST_MAP).map(SnapshotFormPanelService::validRows),
                List.of(), "PRICES_UNAVAILABLE");
    }

    private Mono<OptionalData<Map<String, Object>>> marketStatus() {
        return optional(businessServicesClient.get().uri("/api/market-data/market-status")
                .retrieve().bodyToMono(MAP), Map.of(), "MARKET_STATUS_UNAVAILABLE");
    }

    private <T> Mono<OptionalData<T>> optional(Mono<T> source, T fallback, String warning) {
        return source.switchIfEmpty(Mono.error(invalidResponse())).timeout(optionalBudget)
                .map(data -> new OptionalData<>(data, List.of()))
                .onErrorResume(error -> preservesStatus(error) ? Mono.error(error)
                        : Mono.just(new OptionalData<>(fallback, List.of(warning))));
    }

    private static SnapshotFormPanelResponse response(String panel, Long id, Snapshot snapshot, String child,
                                                       Map<String, Object> additional, List<String> warnings) {
        Map<String, Object> projection = new LinkedHashMap<>(snapshot.detail());
        projection.remove("deposits");
        projection.remove("stocks");
        projection.remove("funds");
        if (child != null) projection.put(child, childRows(snapshot.detail(), child));
        Map<String, Object> data = new LinkedHashMap<>(additional);
        data.put("snapshot", projection);
        data.put("snapshotVersion", snapshot.version());
        data.put("effectiveUsdExchangeRate", snapshot.effectiveRate());
        return new SnapshotFormPanelResponse(panel, id, data, warnings);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childRows(Map<String, Object> detail, String name) {
        if (!(detail.get(name) instanceof List<?> rows)) throw invalidResponse();
        return validRows((List<Map<String, Object>>) rows);
    }

    private static List<Map<String, Object>> validRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) throw invalidResponse();
        return rows;
    }

    /** 原始 detail 的 canonical JSON：map key 排序，數值消除 scale 差異，array 順序不變。 */
    static String fingerprint(Map<String, Object> detail) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, detail);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void appendCanonical(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null");
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String name)) throw invalidResponse();
                sorted.put(name, child);
            });
            target.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) target.append(',');
                first = false;
                appendCanonical(target, entry.getKey());
                target.append(':');
                appendCanonical(target, entry.getValue());
            }
            target.append('}');
        } else if (value instanceof List<?> list) {
            target.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) target.append(',');
                appendCanonical(target, list.get(i));
            }
            target.append(']');
        } else if (value instanceof Number number) {
            BigDecimal decimal = number(number);
            if (decimal == null) throw invalidResponse();
            target.append(decimal.stripTrailingZeros().toPlainString());
        } else if (value instanceof String || value instanceof Boolean) {
            try {
                target.append(JSON.writeValueAsString(value));
            } catch (JsonProcessingException impossible) {
                throw invalidResponse();
            }
        } else {
            throw invalidResponse();
        }
    }

    private static BigDecimal number(Object value) {
        if (!(value instanceof Number)) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static BigDecimal positive(Object value) {
        BigDecimal number = number(value);
        return number != null && number.signum() > 0 ? number : null;
    }

    private <T> Mono<T> complete(Mono<T> source) {
        return source.timeout(panelBudget).onErrorMap(SnapshotFormPanelService::mapFailure);
    }

    private static Throwable mapFailure(Throwable error) {
        if (preservesStatus(error)) return error; // BusinessErrorAdvice relays the original body and status.
        if (error instanceof ResponseStatusException response
                && (response.getStatusCode().value() == 502 || response.getStatusCode().value() == 504)) return error;
        if (status(error) == 408 || status(error) == 504) return timedOut(error);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof io.netty.channel.ConnectTimeoutException) return timedOut(error);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "管理資產資料來源暫時無法使用", error);
    }

    private static int status(Throwable error) {
        return error instanceof WebClientResponseException response ? response.getStatusCode().value()
                : error instanceof ResponseStatusException response ? response.getStatusCode().value() : 0;
    }

    private static boolean preservesStatus(Throwable error) {
        return status(error) == 401 || status(error) == 403 || status(error) == 404;
    }

    private static ResponseStatusException timedOut(Throwable cause) {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "管理資產資料載入逾時", cause);
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "管理資產資料來源回應不完整");
    }

    private record Snapshot(Map<String, Object> detail, LocalDate date, String version, BigDecimal effectiveRate) {}
    private record FundData(Snapshot snapshot, List<Map<String, Object>> fundMasters) {}
    private record StockData(Snapshot snapshot, SnapshotEnricher.SnapshotCloseData close,
                             OptionalData<Map<String, BigDecimal>> transactionRates) {}
    private record OptionalData<T>(T data, List<String> warnings) {}
    private record TransactionRate(String date, BigDecimal rate) {}
}

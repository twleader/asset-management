package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.LiveAssetsOverlay;
import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.dto.DashboardPanelResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Dashboard 的 Panel 聚合。所有 I/O 都沿用具 tenant context 的 business client。
 * 必要資料不做 empty fallback；選配 live/status 有獨立短預算，整份聚合另有 20 秒上限。
 */
@Service
public class DashboardPanelService {
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final Duration PANEL_BUDGET = Duration.ofSeconds(20);
    private static final Duration OPTIONAL_BUDGET = Duration.ofSeconds(5);

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;
    private final DashboardLookthroughService lookthroughService;
    private final Duration panelBudget;
    private final Duration optionalBudget;

    @Autowired
    public DashboardPanelService(@Qualifier("businessServicesClient") WebClient businessServicesClient,
                                 SnapshotEnricher enricher,
                                 DashboardLookthroughService lookthroughService) {
        this(businessServicesClient, enricher, lookthroughService, PANEL_BUDGET, OPTIONAL_BUDGET);
    }

    DashboardPanelService(WebClient businessServicesClient, SnapshotEnricher enricher,
                          DashboardLookthroughService lookthroughService,
                          Duration panelBudget, Duration optionalBudget) {
        this.businessServicesClient = businessServicesClient;
        this.enricher = enricher;
        this.lookthroughService = lookthroughService;
        this.panelBudget = panelBudget;
        this.optionalBudget = optionalBudget;
    }

    public Mono<List<Map<String, Object>>> snapshots() {
        return complete(readList("/api/snapshots"));
    }

    public Mono<DashboardPanelResponse> kpis(Long id) {
        return stockSummary("kpis", id);
    }

    public Mono<DashboardPanelResponse> allocation(Long id, String tab) {
        return switch (tab) {
            case "category" -> stockSummary("allocation", id);
            case "assetClass" -> complete(Mono.zip(detail(id), history(id),
                            readList("/api/snapshots/" + id + "/holdings-classified"))
                    .map(t -> response("allocation", id, Map.of(
                            "snapshot", projectSnapshot(t.getT1(), null),
                            "history", t.getT2(), "classifiedHoldings", t.getT3()), List.of())));
            case "twStock" -> complete(enrichedDetail(id).flatMap(stock ->
                    lookthroughService.taiwan(stock.detail(), stock.mergedStocks())
                            .map(lookthrough -> response("allocation", id, Map.of(
                                    "snapshot", projectSnapshot(stock.detail(), null),
                                    "twLookthrough", lookthrough), List.of()))));
            case "usStock" -> complete(enrichedDetail(id).flatMap(stock ->
                    lookthroughService.unitedStates(stock.detail(), stock.mergedStocks())
                            .map(lookthrough -> response("allocation", id, Map.of(
                                    "snapshot", projectSnapshot(stock.detail(), null),
                                    "usLookthrough", lookthrough), List.of()))));
            default -> Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支援的資產配置分類"));
        };
    }

    public Mono<DashboardPanelResponse> trend() {
        return complete(Mono.zip(history(null), liveAssets())
                .map(t -> {
                    LiveAssetsOverlay.applyToLatest(t.getT1(), t.getT2().data());
                    return response("trend", null, Map.of("history", t.getT1()), t.getT2().warnings());
                }));
    }

    public Mono<DashboardPanelResponse> deposits(Long id) {
        return snapshotChildren("deposits", id);
    }

    public Mono<DashboardPanelResponse> funds(Long id) {
        return snapshotChildren("funds", id);
    }

    public Mono<DashboardPanelResponse> stockValues(Long id) {
        return stocks("stock-values", id);
    }

    public Mono<DashboardPanelResponse> holdings(Long id) {
        return stocks("holdings", id);
    }

    private Mono<DashboardPanelResponse> snapshotChildren(String child, Long id) {
        return complete(detail(id).map(snapshot -> response(child, id,
                Map.of("snapshot", projectSnapshot(snapshot, child)), List.of())));
    }

    private Mono<DashboardPanelResponse> stockSummary(String panel, Long id) {
        // detail/close 是一條相依鏈；history、live 與整條鏈平行訂閱。
        return complete(Mono.zip(enrichedDetail(id), history(id), liveAssets())
                .map(t -> {
                    StockSnapshot stock = t.getT1();
                    OptionalData live = t.getT3();
                    LiveAssetsOverlay.applyToLatest(t.getT2(), live.data());
                    return response(panel, id, Map.of(
                            "snapshot", projectSnapshot(stock.detail(), null),
                            "history", t.getT2(),
                            "mergedStocks", stock.mergedStocks(),
                            "stockPrices", prices(stock, live.data()),
                            "liveAssets", live.data()), live.warnings());
                }));
    }

    private Mono<DashboardPanelResponse> stocks(String panel, Long id) {
        return complete(Mono.zip(enrichedDetail(id), liveAssets(), marketStatus())
                .map(t -> {
                    StockSnapshot stock = t.getT1();
                    OptionalData live = t.getT2();
                    OptionalData status = t.getT3();
                    List<String> warnings = new ArrayList<>(live.warnings());
                    warnings.addAll(status.warnings());
                    return response(panel, id, Map.of(
                            "snapshot", projectSnapshot(stock.detail(), null),
                            "mergedStocks", stock.mergedStocks(),
                            "stockPrices", prices(stock, live.data()),
                            "marketStatus", status.data(),
                            "liveAssets", live.data()), warnings);
                }));
    }

    private Mono<StockSnapshot> enrichedDetail(Long id) {
        return detail(id).flatMap(detail -> {
            if (!(detail.get("stocks") instanceof List<?> stocks)
                    || stocks.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) {
                return Mono.error(invalidResponse());
            }
            enricher.enrichInvestmentCostOriginal(detail);
            return enricher.fetchSnapshotCloseData(detail, true).map(close -> new StockSnapshot(
                    detail, enricher.buildMergedStocks(detail, close.closeMap(), false, true), close));
        });
    }

    private List<Map<String, Object>> prices(StockSnapshot stock, Map<String, Object> live) {
        LocalDate basedate = LocalDate.parse(stock.detail().get("snapshotDate").toString());
        SnapshotEnricher.SnapshotCloseData close = stock.close();
        return SnapshotEnricher.mergePerMarketPrices(basedate, DashboardStockPrices.fromLiveAssets(live),
                close.closeMap(), close.changeMap(), close.displayRows());
    }

    private Mono<Map<String, Object>> detail(Long id) {
        return businessServicesClient.get().uri("/api/snapshots/{id}", id)
                .retrieve().bodyToMono(MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .map(detail -> {
                    if (!String.valueOf(id).equals(String.valueOf(detail.get("id")))
                            || detail.get("snapshotDate") == null) throw invalidResponse();
                    return detail;
                });
    }

    private Mono<List<Map<String, Object>>> history(Long id) {
        return businessServicesClient.get().uri(builder -> {
                    builder.path("/api/snapshots/history");
                    if (id != null) builder.queryParam("snapshotId", id);
                    return builder.build();
                }).retrieve().bodyToMono(LIST_MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .map(rows -> {
                    if (id != null && (rows.isEmpty() || rows.size() > 2
                            || !String.valueOf(id).equals(String.valueOf(rows.getLast().get("id"))))) {
                        throw invalidResponse();
                    }
                    // Overlay 只改這份回應，不會污染其他 Panel 或任何共用來源。
                    return rows.stream().map(row -> (Map<String, Object>) new LinkedHashMap<>(row)).toList();
                });
    }

    private Mono<List<Map<String, Object>>> readList(String path) {
        return businessServicesClient.get().uri(path).retrieve().bodyToMono(LIST_MAP)
                .switchIfEmpty(Mono.error(invalidResponse()));
    }

    private Mono<OptionalData> liveAssets() {
        return optional("/api/market-data/live-assets", "LIVE_ASSETS_UNAVAILABLE");
    }

    private Mono<OptionalData> marketStatus() {
        return optional("/api/market-data/market-status", "MARKET_STATUS_UNAVAILABLE");
    }

    private Mono<OptionalData> optional(String path, String warning) {
        return businessServicesClient.get().uri(path).retrieve().bodyToMono(MAP)
                .switchIfEmpty(Mono.error(invalidResponse()))
                .timeout(optionalBudget)
                .map(data -> new OptionalData(data, List.of()))
                .onErrorResume(error -> preservesStatus(error)
                        ? Mono.error(error)
                        : Mono.just(new OptionalData(Map.of(), List.of(warning))));
    }

    /** 只投影 Panel 必要的 child，避免每份 Panel 都攜帶整頁 detail。 */
    private static Map<String, Object> projectSnapshot(Map<String, Object> detail, String child) {
        Map<String, Object> snapshot = new LinkedHashMap<>(detail);
        snapshot.remove("deposits");
        snapshot.remove("funds");
        snapshot.remove("stocks");
        if (child != null) {
            Object rows = detail.get(child);
            if (!(rows instanceof List<?> list)
                    || list.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) throw invalidResponse();
            snapshot.put(child, rows);
        }
        return snapshot;
    }

    private static DashboardPanelResponse response(String panel, Long id,
                                                    Map<String, Object> data, List<String> warnings) {
        return new DashboardPanelResponse(panel, id, data, warnings);
    }

    private <T> Mono<T> complete(Mono<T> publisher) {
        return publisher.timeout(panelBudget).onErrorMap(DashboardPanelService::mapFailure);
    }

    private static Throwable mapFailure(Throwable error) {
        if (error instanceof ResponseStatusException response) {
            int status = response.getStatusCode().value();
            if (preservesStatus(error) || status == 400 || status == 502 || status == 504) return error;
        }
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            if (preservesStatus(error)) {
                // 交由既有 BusinessErrorAdvice 原樣轉送狀態與 body。
                return error;
            }
            if (status == 408 || status == 504) return timedOut(error);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof io.netty.channel.ConnectTimeoutException) return timedOut(error);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "儀表板資料來源暫時無法使用", error);
    }

    private static boolean preservesStatus(Throwable error) {
        int status = error instanceof WebClientResponseException response ? response.getStatusCode().value()
                : error instanceof ResponseStatusException response ? response.getStatusCode().value() : 0;
        return status == 401 || status == 403 || status == 404;
    }

    private static ResponseStatusException timedOut(Throwable cause) {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "儀表板資料載入逾時", cause);
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "儀表板資料來源回應不完整");
    }

    private record OptionalData(Map<String, Object> data, List<String> warnings) {}
    private record StockSnapshot(Map<String, Object> detail, List<Map<String, Object>> mergedStocks,
                                 SnapshotEnricher.SnapshotCloseData close) {}
}

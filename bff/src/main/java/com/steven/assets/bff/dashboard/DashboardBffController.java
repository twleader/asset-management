package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.dto.DashboardSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF aggregation controller for the Dashboard page (/dashboard).
 *
 * 設計原則：一個前端頁面對應一支 BFF controller。前端只 render，
 * aggregation 與計算（profit / profitRate / 收盤價對齊等）一律由 BFF 預先處理。
 */
@Slf4j
@RestController
@RequestMapping("/api/bff/dashboard")
@RequiredArgsConstructor
public class DashboardBffController {

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping("/summary")
    public Mono<ResponseEntity<DashboardSummaryDto>> getSummary() {
        Mono<List<Map<String, Object>>> snapshotsMono = businessServicesClient.get()
                .uri("/api/snapshots")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> historyMono = businessServicesClient.get()
                .uri("/api/snapshots/history")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> pricesMono = businessServicesClient.get()
                .uri("/api/market-data/prices")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<Map<String, Object>> marketStatusMono = businessServicesClient.get()
                .uri("/api/market-data/market-status")
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        return Mono.zip(snapshotsMono, historyMono, pricesMono, marketStatusMono)
                .flatMap(tuple -> {
                    List<Map<String, Object>> snapshots = tuple.getT1();
                    List<Map<String, Object>> history = tuple.getT2();
                    List<Map<String, Object>> prices = tuple.getT3();
                    Map<String, Object> marketStatus = tuple.getT4();

                    DashboardSummaryDto dto = new DashboardSummaryDto();
                    dto.setSnapshots(snapshots);
                    dto.setHistory(history);
                    dto.setStockPrices(prices);
                    dto.setMarketStatus(marketStatus);

                    if (snapshots.isEmpty()) {
                        dto.setLatestSnapshotDetail(Collections.emptyMap());
                        dto.setMergedStocks(Collections.emptyList());
                        return Mono.just(ResponseEntity.ok(dto));
                    }

                    Object latestId = snapshots.get(0).get("id");
                    return businessServicesClient.get()
                            .uri("/api/snapshots/{id}", latestId)
                            .retrieve()
                            .bodyToMono(MAP)
                            .onErrorReturn(Collections.emptyMap())
                            .flatMap(detail -> {
                                enricher.enrichInvestmentCostOriginal(detail);
                                dto.setLatestSnapshotDetail(detail);
                                return enricher.fetchSnapshotClosePrices(detail)
                                        .map(closeMap -> {
                                            dto.setMergedStocks(
                                                    enricher.buildMergedStocks(detail, closeMap, false));
                                            return ResponseEntity.ok(dto);
                                        });
                            });
                });
    }

    /**
     * GET /api/bff/dashboard/realtime
     * 5 分鐘輪詢用：只回傳即時股價與市場開盤狀態。
     */
    @GetMapping("/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> getRealtime() {
        Mono<List<Map<String, Object>>> pricesMono = businessServicesClient.get()
                .uri("/api/market-data/prices")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());
        Mono<Map<String, Object>> statusMono = businessServicesClient.get()
                .uri("/api/market-data/market-status")
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());
        return Mono.zip(pricesMono, statusMono).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("stockPrices", t.getT1());
            body.put("marketStatus", t.getT2());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * GET /api/bff/dashboard/snapshot/{id}
     * 切換快照時用：取得指定快照的 enriched detail + mergedStocks。
     */
    @GetMapping("/snapshot/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getSnapshot(@PathVariable Long id) {
        return businessServicesClient.get()
                .uri("/api/snapshots/{id}", id)
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap())
                .flatMap(detail -> {
                    enricher.enrichInvestmentCostOriginal(detail);
                    return enricher.fetchSnapshotClosePrices(detail).map(closeMap -> {
                        Map<String, Object> body = new HashMap<>(detail);
                        body.put("mergedStocks",
                                enricher.buildMergedStocks(detail, closeMap, false));
                        return ResponseEntity.ok(body);
                    });
                });
    }
}

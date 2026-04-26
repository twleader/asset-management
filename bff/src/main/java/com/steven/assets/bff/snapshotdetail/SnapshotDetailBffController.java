package com.steven.assets.bff.snapshotdetail;

import com.steven.assets.bff.common.SnapshotEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF aggregation controller for SnapshotDetailView (/snapshots/{id}).
 *
 * 回傳 enriched 快照 detail + mergedStocks（含 brokerRows 子陣列，
 * 供編輯頁直接使用，不需前端再做 buildGroups 計算）。
 */
@Slf4j
@RestController
@RequestMapping("/api/bff/snapshot-detail")
@RequiredArgsConstructor
public class SnapshotDetailBffController {

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/snapshot-detail/{id}
     * 回傳：原始 snapshot detail + mergedStocks（依 stockCode + market 合併，
     * 含 brokerRows、stockPrice、unitPriceTwd、profit、profitRate、avgCostOriginal）。
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getDetail(@PathVariable Long id) {
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
                                enricher.buildMergedStocks(detail, closeMap, true));
                        return ResponseEntity.ok(body);
                    });
                });
    }

    /**
     * GET /api/bff/snapshot-detail/brokers
     * 編輯券商欄位用：回傳 active brokers。
     */
    @GetMapping("/brokers")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getActiveBrokers() {
        return businessServicesClient.get()
                .uri("/api/settings/brokers")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(list -> ResponseEntity.ok(list.stream()
                        .filter(b -> Boolean.TRUE.equals(b.get("active")))
                        .toList()));
    }

    /**
     * PUT /api/bff/snapshot-detail/{id}
     * 儲存編輯後的快照。
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        return businessServicesClient.put()
                .uri("/api/snapshots/{id}", id)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }
}

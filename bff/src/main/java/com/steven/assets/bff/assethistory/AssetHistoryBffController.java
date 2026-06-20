package com.steven.assets.bff.assethistory;

import com.steven.assets.bff.common.LiveAssetsOverlay;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * AssetHistoryView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/asset-history")
@RequiredArgsConstructor
public class AssetHistoryBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/asset-history
     * 回傳資產歷史，並為每筆預先標註 isLastOfYear（每年最後一筆）。
     * 若最新一筆 snapshotDate == 今日（市場時區）且 live-assets 也指向同一筆 snapshot，
     * 用 live 計算覆蓋最新列的 totalTwStockValue / totalUsStockValue / totalStockValue
     * / totalAssets / increase / increaseRate / investmentRate，使本頁與 Dashboard 顯示一致。
     */
    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> getHistory() {
        Mono<List<Map<String, Object>>> historyMono = businessServicesClient.get()
                .uri("/api/snapshots/history")
                .retrieve()
                .bodyToMono(LIST_MAP);
        Mono<Map<String, Object>> liveMono = businessServicesClient.get()
                .uri("/api/market-data/live-assets")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorReturn(Map.of());
        return Mono.zip(historyMono, liveMono).map(tuple -> {
            List<Map<String, Object>> history = tuple.getT1();
            Map<String, Object> live = tuple.getT2();

            Map<String, Long> lastIdPerYear = new TreeMap<>();
            for (Map<String, Object> r : history) {
                Object date = r.get("snapshotDate");
                Object idObj = r.get("id");
                if (date == null || idObj == null) continue;
                String year = date.toString().substring(0, 4);
                long id = ((Number) idObj).longValue();
                lastIdPerYear.put(year, id);
            }
            Set<Long> lastOfYearIds = new HashSet<>(lastIdPerYear.values());
            for (Map<String, Object> r : history) {
                Object idObj = r.get("id");
                long id = idObj == null ? -1 : ((Number) idObj).longValue();
                r.put("isLastOfYear", lastOfYearIds.contains(id));
            }

            // 最新一筆以 live-assets（休市時為最後收盤價）覆蓋股票現值與資產總計。
            // 共用 LiveAssetsOverlay，與 Dashboard summary 同一算法 → 兩頁同義欄位同值。
            LiveAssetsOverlay.applyToLatest(history, live);
            return ResponseEntity.ok(history);
        });
    }

    @PostMapping("/recalc-dividends")
    public Mono<ResponseEntity<Map<String, Object>>> recalcDividends() {
        return businessServicesClient.post()
                .uri("/api/snapshots/recalc-dividends")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return businessServicesClient.delete()
                .uri("/api/snapshots/{id}", id)
                .retrieve()
                .toBodilessEntity()
                .map(r -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> exportExcel() {
        return businessServicesClient.get()
                .uri("/api/snapshots/export")
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    HashMap<String, String> headers = new HashMap<>();
                    e.getHeaders().forEach((k, v) -> {
                        if (v != null && !v.isEmpty()) headers.put(k, v.get(0));
                    });
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }
}

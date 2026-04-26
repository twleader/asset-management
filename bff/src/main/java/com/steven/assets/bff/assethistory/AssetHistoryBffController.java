package com.steven.assets.bff.assethistory;

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
     */
    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> getHistory() {
        return businessServicesClient.get()
                .uri("/api/snapshots/history")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .map(history -> {
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

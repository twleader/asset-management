package com.steven.assets.bff.commodityprice;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CommodityPriceView（公開資訊 → 油價金價）專屬 BFF（Requirement 40 / Task 202）。
 *
 * 三個標的（WTI／BRENT／GOLD）為全域公開行情，無 owner 過濾。
 * 外部來源失敗一律降級回空序列而非 5xx——頁面顯示「查無資料」比整頁錯誤好。
 */
@RestController
@RequestMapping("/api/bff/commodity-price")
@RequiredArgsConstructor
public class CommodityPriceBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<Map<String, List<Map<String, Object>>>> SERIES_MAP =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/commodity-price
     * 開頁載入：先 trigger refresh 補最新收盤（失敗不擋主流程），再回傳近 10 年三序列。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getHistory() {
        Mono<Void> refresh = businessServicesClient.post()
                .uri("/api/market-data/commodity/refresh")
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty());

        String today = LocalDate.now().toString();
        String tenYearsAgo = LocalDate.now().minusYears(10).toString();

        return refresh.then(businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/commodity")
                        .queryParam("start", tenYearsAgo)
                        .queryParam("end", today).build())
                .retrieve()
                .bodyToMono(SERIES_MAP)
                .onErrorReturn(Collections.emptyMap())
                .map(series -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("series", series);
                    return ResponseEntity.ok(body);
                }));
    }

    /** POST /api/bff/commodity-price/refresh — 手動刷新（三標的增量回補 ＋ 十年清理）。 */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh() {
        return businessServicesClient.post()
                .uri("/api/market-data/commodity/refresh")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(ResponseEntity::ok);
    }

    // ===== 排程自動匯出設定（Requirement 41 / Task 203，per-user owner-scoped）=====
    // 沿用 businessServicesClient（WebClientConfig.tenantHeaderFilter 自動帶 X-User-* → 後端 ownerFilter 縮到本人）。

    @GetMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSchedule() {
        return businessServicesClient.get()
                .uri("/api/commodity-export/schedule")
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSchedule(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/commodity-export/schedule")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/export/run-now")
    public Mono<ResponseEntity<Map<String, Object>>> runExportNow() {
        return businessServicesClient.post()
                .uri("/api/commodity-export/run-now")
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /** 目錄列舉沿用 Requirement 34 既有的 business 端點（語意相同＝列出基底下子目錄），不新增第四份實作。 */
    @GetMapping("/export/browse")
    public Mono<ResponseEntity<Map<String, Object>>> browseExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/export-schedule/browse?subpath={subpath}", subpath)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/bff/commodity-price/export?start=&end= — passthrough 下載 .xlsx。
     * 連同 business 回的 Content-Disposition 一併轉出，前端才能取到預設檔名。
     */
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return businessServicesClient.get()
                .uri(uri -> {
                    var u = uri.path("/api/market-data/commodity/export");
                    if (start != null && !start.isBlank()) u.queryParam("start", start);
                    if (end != null && !end.isBlank()) u.queryParam("end", end);
                    return u.build();
                })
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }
}

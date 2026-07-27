package com.steven.assets.bff.exchangerate;

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
 * ExchangeRateView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/exchange-rate")
@RequiredArgsConstructor
public class ExchangeRateBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/exchange-rate?currency=USD
     * 先 trigger refresh 取得最新；再回傳近 10 年的歷史。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "USD") String currency) {
        Mono<Void> refresh = businessServicesClient.post()
                .uri(uri -> uri.path("/api/market-data/exchange-rate/refresh")
                        .queryParam("currency", currency).build())
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty());

        String today = LocalDate.now().toString();
        String tenYearsAgo = LocalDate.now().minusYears(10).toString();

        return refresh.then(businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/exchange-rate")
                        .queryParam("currency", currency)
                        .queryParam("start", tenYearsAgo)
                        .queryParam("end", today).build())
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(rates -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("rates", rates);
                    return ResponseEntity.ok(body);
                }));
    }

    @PostMapping("/backfill")
    public Mono<ResponseEntity<Map<String, Object>>> backfill(
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(required = false) String since) {
        return businessServicesClient.post()
                .uri(uri -> {
                    var u = uri.path("/api/market-data/exchange-rate/backfill-history")
                            .queryParam("currency", currency);
                    if (since != null) u.queryParam("since", since);
                    return u.build();
                })
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    // ===== 排程自動匯出設定（Requirement 42 / Task 204，per-user owner-scoped）=====
    // 沿用 businessServicesClient（WebClientConfig.tenantHeaderFilter 自動帶 X-User-* → 後端 ownerFilter 縮到本人）。

    @GetMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSchedule() {
        return businessServicesClient.get()
                .uri("/api/exchange-rate-export/schedule")
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSchedule(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/exchange-rate-export/schedule")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/export/run-now")
    public Mono<ResponseEntity<Map<String, Object>>> runExportNow() {
        return businessServicesClient.post()
                .uri("/api/exchange-rate-export/run-now")
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /** 目錄列舉沿用 Requirement 34 既有的 business 端點（語意相同＝列出基底下子目錄），不新增第五份實作。 */
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
     * GET /api/bff/exchange-rate/export?currency=&start=&end= — passthrough 下載 .xlsx。
     * 連同 business 回的 Content-Disposition 一併轉出，前端才能取到預設檔名。
     */
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return businessServicesClient.get()
                .uri(uri -> {
                    var u = uri.path("/api/market-data/exchange-rate/export")
                            .queryParam("currency", currency);
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

    /**
     * Google Drive 資料夾樹懶載入（Requirement 51 / Task 243）：passthrough 至 business
     * {@code /api/export-schedule/browse-gdrive}。
     *
     * <p><b>八個匯出頁全部指向同一支 business 端點</b>——Drive 目錄列舉全庫只有一份實作
     * （CLAUDE.md「同義欄位、同一 business service API」）；BFF 各建一支則是「一頁一 BFF」的要求，
     * 兩者不衝突。
     *
     * <p><b>刻意不做 onErrorReturn 降級</b>：remote 未設定／授權失效時 business 回 503 帶可讀訊息，
     * 必須讓它浮到前端 dialog 顯示。降級成空清單會讓使用者誤讀為「Drive 裡沒有資料夾」而以為選錯位置。
     */
    @GetMapping("/export/browse-gdrive")
    public Mono<ResponseEntity<Map<String, Object>>> browseGdriveExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/export-schedule/browse-gdrive?subpath={subpath}", subpath)
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }
}

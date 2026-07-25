package com.steven.assets.bff.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * TransactionView 專屬 BFF（交易紀錄，Requirement 49 / Task 237、238）。
 *
 * <p>一頁一 BFF：前端只呼叫 {@code /api/bff/transaction/*}，本 controller 負責跨服務 aggregation
 * （交易紀錄年度彙總＋市場／券商下拉主檔）。X-User-* 標頭由全域 WebClient filter 自動往下帶，
 * 讓 business 端 {@code TenantFilterAspect} 能 owner-scope，此處不自行處理標頭。
 */
@RestController
@RequestMapping("/api/bff/transaction")
@RequiredArgsConstructor
public class TransactionBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * 年度彙總列表 + 同時帶回市場類型、active 券商供下拉選單用。
     * business {@code GET /api/asset-transactions} 固定回年度彙總清單（比照 RealizedGainController），
     * 各年度 records 已含在彙總內，年度篩選由前端客戶端切換，故本端點不帶 year 參數。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getAll() {
        return Mono.zip(
                businessServicesClient.get().uri("/api/asset-transactions").retrieve().bodyToMono(LIST_MAP),
                businessServicesClient.get().uri("/api/settings/market-types").retrieve().bodyToMono(LIST_MAP),
                businessServicesClient.get().uri("/api/settings/brokers").retrieve().bodyToMono(LIST_MAP)
        ).map(t -> {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("summaries", t.getT1());
            body.put("markets", t.getT2());
            body.put("brokers", t.getT3().stream()
                    .filter(b -> Boolean.TRUE.equals(b.get("active")))
                    .toList());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 輸入代號自動帶出股名（新增/編輯交易列用）。轉呼「同一支」business API
     * {@code /api/stock-alerts/lookup-name}（與 stock-alert／已實現損益頁同源，符合「同義欄位同一 business service API」），
     * 不在 business 端新增第二份實作。code/market 由 business 端白名單驗證。
     */
    @GetMapping("/lookup-name")
    public Mono<ResponseEntity<Map<String, Object>>> lookupName(@RequestParam String code,
                                                                @RequestParam String market) {
        return businessServicesClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/stock-alerts/lookup-name")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .build())
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return businessServicesClient.post().uri("/api/asset-transactions")
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        return businessServicesClient.put().uri("/api/asset-transactions/{id}", id)
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return businessServicesClient.delete().uri("/api/asset-transactions/{id}", id)
                .retrieve().toBodilessEntity().map(r -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> exportExcel() {
        return businessServicesClient.get()
                .uri("/api/asset-transactions/export")
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }

    // ===== 排程自動匯出（Requirement 49 / Task 238）=====

    @GetMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSchedule() {
        return businessServicesClient.get().uri("/api/asset-transactions/export/schedule")
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @PutMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSchedule(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put().uri("/api/asset-transactions/export/schedule")
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @PostMapping("/export/run-now")
    public Mono<ResponseEntity<Map<String, Object>>> runExportNow() {
        return businessServicesClient.post().uri("/api/asset-transactions/export/run-now")
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    /**
     * 資料夾瀏覽（唯讀）。轉呼 Requirement 34 既有的「同一支」business API {@code /api/export-schedule/browse}
     * ——語意相同＝列出基底家目錄下的子目錄，故不在 business 端新增第二份實作
     * （CLAUDE.md「同義欄位、同一 business service API」）；本頁仍走自己的 BFF 路由（一頁一 BFF）。
     * 以 URI template 展開讓 subpath 自動 URL-encode。
     */
    @GetMapping("/export/browse")
    public Mono<ResponseEntity<Map<String, Object>>> browseExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/export-schedule/browse?subpath={subpath}", subpath)
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }
}

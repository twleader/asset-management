package com.steven.assets.bff.realizedgain;

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
 * RealizedGainView 專屬 BFF。
 * 同時整合 brokers 列表（前端下拉用），避免另呼叫 institutionApi。
 */
@RestController
@RequestMapping("/api/bff/realized-gain")
@RequiredArgsConstructor
public class RealizedGainBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /** 列表 + 同時帶回 active brokers 供下拉選單用。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getAll() {
        return Mono.zip(
                businessServicesClient.get().uri("/api/realized-gains").retrieve().bodyToMono(LIST_MAP),
                businessServicesClient.get().uri("/api/settings/brokers").retrieve().bodyToMono(LIST_MAP)
        ).map(t -> {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("gains", t.getT1());
            body.put("brokers", t.getT2().stream()
                    .filter(b -> Boolean.TRUE.equals(b.get("active")))
                    .toList());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 輸入股號自動帶出股名（新增/編輯損益列用）。走本頁 BFF 但轉呼「同一支」business API
     * {@code /api/stock-alerts/lookup-name}（與 stock-alert 頁同源，符合「同義欄位同一 business service API」），
     * 不再從前端跨頁呼叫 stock-alert 的 BFF。code/market 由 business 端白名單驗證。
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
        return businessServicesClient.post().uri("/api/realized-gains")
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        return businessServicesClient.put().uri("/api/realized-gains/{id}", id)
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return businessServicesClient.delete().uri("/api/realized-gains/{id}", id)
                .retrieve().toBodilessEntity().map(r -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> exportExcel() {
        return businessServicesClient.get()
                .uri("/api/realized-gains/export")
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }

    // ===== 排程自動匯出（Requirement 39 / Task 196）=====

    @GetMapping("/export/schedules")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSchedules() {
        return businessServicesClient.get().uri("/api/realized-gains/export/schedules").retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}).map(ResponseEntity::ok);
    }
    @PostMapping("/export/schedules")
    public Mono<ResponseEntity<Map<String, Object>>> createExportSchedule(@RequestBody Map<String, Object> body) {
        return businessServicesClient.post().uri("/api/realized-gains/export/schedules").bodyValue(body).retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}).map(ResponseEntity::ok);
    }
    @PutMapping("/export/schedules/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return businessServicesClient.put().uri("/api/realized-gains/export/schedules/{id}", id).bodyValue(body).retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}).map(ResponseEntity::ok);
    }
    @DeleteMapping("/export/schedules/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteExportSchedule(@PathVariable Long id) {
        return businessServicesClient.delete().uri("/api/realized-gains/export/schedules/{id}", id).retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}).map(ResponseEntity::ok);
    }
    @PostMapping("/export/schedules/{id}/run-now")
    public Mono<ResponseEntity<Map<String, Object>>> runExportNow(@PathVariable Long id) {
        return businessServicesClient.post().uri("/api/realized-gains/export/schedules/{id}/run-now", id).retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}).map(ResponseEntity::ok);
    }
    @RequestMapping(value = "/export/schedule", method = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.PUT})
    public void retiredExportSettings() { throw retiredExport(); }
    @PostMapping("/export/run-now")
    public void retiredExportRunNow() { throw retiredExport(); }
    private org.springframework.web.server.ResponseStatusException retiredExport() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE,
                "匯出設定已改為多筆獨立排程，請重新整理頁面");
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

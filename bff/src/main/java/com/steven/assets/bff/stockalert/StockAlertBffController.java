package com.steven.assets.bff.stockalert;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
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

import java.util.Map;

/**
 * 警示條件頁（{@code /stocks?tab=alert}）的觸發即時匯出 BFF（Requirement 54 / Task 254）。
 *
 * <p><b>與既有的 {@link StockAlertBffRoutes} 萬用 route 並存，順序靠 handler mapping 的 order 決定。</b>
 * 該 route 是 {@code /api/bff/stock-alert/**} → rewrite 成 {@code /api/stock-alerts${seg}}，會把
 * {@code /export-setting/browse-gdrive} 錯誤地轉成 {@code /api/stock-alerts/export-setting/browse-gdrive}
 * （business 端沒有這個端點 → 404）。WebFlux 的 {@code RequestMappingHandlerMapping}（order 0）先於
 * Gateway 的 {@code RoutePredicateHandlerMapping}（order 1），故本 controller 會先接走這幾條，
 * 其餘（CRUD／reorder／check／lookup-name／lookup-code／recipients／groups）仍由萬用 route passthrough。
 * 同一模式的既有先例：{@code TradingRadarBffController} ＋ {@code TradingRadarBffRoutes}。
 *
 * <p>X-User-* 標頭由全域 WebClient filter 自動往下帶，讓 business 端 {@code TenantFilterAspect}
 * 能 owner-scope，此處不自行處理標頭。
 */
@RestController
@RequestMapping("/api/bff/stock-alert")
@RequiredArgsConstructor
public class StockAlertBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /** 觸發即時匯出設定（owner-scoped）。 */
    @GetMapping("/export-setting")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSetting() {
        return businessServicesClient.get().uri("/api/stock-alerts/export-setting")
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    /**
     * 更新設定。
     *
     * <p><b>刻意不吞掉 403</b>——Drive 同步僅限主要管理者啟用，被拒必須讓前端看到
     * （錯誤轉譯由 {@code BusinessErrorAdvice} 統一處理）。
     */
    @PutMapping("/export-setting")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSetting(
            @RequestBody Map<String, Object> body) {
        return businessServicesClient.put().uri("/api/stock-alerts/export-setting")
                .bodyValue(body)
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    /** 立即匯出當日已發生的觸發（驗證落點用；當日無觸發時仍寫出空的觸發清單）。 */
    @PostMapping("/export-setting/run-now")
    public Mono<ResponseEntity<Map<String, Object>>> runNowExport() {
        return businessServicesClient.post().uri("/api/stock-alerts/export-setting/run-now")
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    /**
     * 本機資料夾瀏覽（唯讀）。轉呼既有的「同一支」business API {@code /api/export-schedule/browse}
     * ——語意相同＝列出基底家目錄下的子目錄，故不在 business 端新增第二份實作
     * （CLAUDE.md「同義欄位、同一 business service API」）；本頁仍走自己的 BFF 路由（一頁一 BFF）。
     * 以 URI template 展開讓 subpath 自動 URL-encode。
     */
    @GetMapping("/export-setting/browse")
    public Mono<ResponseEntity<Map<String, Object>>> browseExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/export-schedule/browse?subpath={subpath}", subpath)
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    /**
     * Google Drive 資料夾樹懶載入：passthrough 至 business {@code /api/export-schedule/browse-gdrive}。
     *
     * <p><b>全庫只有這一份 Drive 目錄列舉實作</b>，十個匯出頁的 BFF 全部指向它。
     *
     * <p><b>刻意不做 onErrorReturn 降級</b>：remote 未設定／授權失效時 business 回 503 帶可讀訊息，
     * 必須讓它浮到前端 dialog 顯示。降級成空清單會讓使用者誤讀為「Drive 裡沒有資料夾」而以為選錯位置。
     */
    @GetMapping("/export-setting/browse-gdrive")
    public Mono<ResponseEntity<Map<String, Object>>> browseGdriveExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/export-schedule/browse-gdrive?subpath={subpath}", subpath)
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }
}

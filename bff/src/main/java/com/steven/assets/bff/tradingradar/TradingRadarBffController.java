package com.steven.assets.bff.tradingradar;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 今日交易雷達頁的 BFF controller（Requirement 48 追加 / Task 231）。
 *
 * <p>本頁其餘 API 走 {@link TradingRadarBffRoutes} 的 Gateway rewrite passthrough；
 * <b>唯獨資料夾瀏覽必須用 controller，不能加 gateway route</b>：
 * {@code /api/bff/trading-radar/export/browse} 完全落在既有 wildcard {@code /api/bff/trading-radar/**} 之內，
 * Gateway 同 order 時依宣告順序先匹配者勝出，新 route 若排在 wildcard 之後，請求會被 rewrite 成 business
 * 不存在的 {@code /api/trading-radar/export/browse} → 404，資料夾選擇器靜默失效。
 * WebFlux 的 {@code RequestMappingHandlerMapping}（order 0）先於 Gateway 的
 * {@code RoutePredicateHandlerMapping}（order 1），故 controller 自動勝出，不需調整任何 route order。
 */
@RestController
@RequestMapping("/api/bff/trading-radar")
@RequiredArgsConstructor
public class TradingRadarBffController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient businessServicesClient;

    /**
     * 資料夾瀏覽（唯讀）。轉呼 Requirement 34 既有的「同一支」business API
     * {@code /api/export-schedule/browse}——語意相同＝列出基底家目錄下的子目錄，故不在 business 端新增
     * 第二份實作（CLAUDE.md「同義欄位、同一 business service API」）；本頁仍走自己的 BFF（一頁一 BFF）。
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

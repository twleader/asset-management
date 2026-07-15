package com.steven.assets.bff.tradingcalendar;

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

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * TradingCalendarView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/trading-calendar")
@RequiredArgsConstructor
public class TradingCalendarBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/trading-calendar?year=YYYY
     * 一次回傳：休市日列表 + 即時市場開盤狀態。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return Mono.zip(
                businessServicesClient.get()
                        .uri(uri -> uri.path("/api/market-data/holidays")
                                .queryParam("year", targetYear).build())
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap()),
                businessServicesClient.get()
                        .uri("/api/market-data/market-status")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
        ).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("holidays", t.getT1());
            body.put("marketStatus", t.getT2());
            body.put("year", targetYear);
            return ResponseEntity.ok(body);
        });
    }

    @GetMapping("/market-status")
    public Mono<ResponseEntity<Map<String, Object>>> marketStatus() {
        return businessServicesClient.get()
                .uri("/api/market-data/market-status")
                .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
                .map(ResponseEntity::ok);
    }

    // ===== 交易日曆匯出到指定路徑（Requirement 37 / Task 184）=====

    /**
     * POST /api/bff/trading-calendar/export?year=&format=json|excel&subpath=
     * 產出整年交易日曆並寫檔到指定目錄，回 {path,sizeBytes,format,year,totalDays}。
     */
    @PostMapping("/export")
    public Mono<ResponseEntity<Map<String, Object>>> export(
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false, defaultValue = "") String subpath) {
        return businessServicesClient.post()
                .uri(uri -> uri.path("/api/trading-calendar-export/run")
                        .queryParamIfPresent("year", java.util.Optional.ofNullable(year))
                        .queryParam("format", format)
                        .queryParam("subpath", subpath)
                        .build())
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /** 唯讀資料夾瀏覽（檔案總管式選擇器逐層懶載入）；subpath 由 WebClient 展開並 URL-encode。 */
    @GetMapping("/export/browse")
    public Mono<ResponseEntity<Map<String, Object>>> browseExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/trading-calendar-export/browse?subpath={subpath}", subpath)
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    // ===== 每日排程自動匯出設定（Task 185，per-user owner-scoped）=====

    @GetMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSchedule() {
        return businessServicesClient.get()
                .uri("/api/trading-calendar-export/schedule")
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSchedule(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/trading-calendar-export/schedule")
                .bodyValue(body)
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }
}

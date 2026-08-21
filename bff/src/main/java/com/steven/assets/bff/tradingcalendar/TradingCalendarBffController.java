package com.steven.assets.bff.tradingcalendar;

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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * TradingCalendarView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/trading-calendar")
public class TradingCalendarBffController {

    private final WebClient businessServicesClient;
    private final TradingCalendarYearWindow yearWindow;

    public TradingCalendarBffController(WebClient businessServicesClient) {
        this(businessServicesClient, new TradingCalendarYearWindow());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TradingCalendarBffController(WebClient businessServicesClient, TradingCalendarYearWindow yearWindow) {
        this.businessServicesClient = businessServicesClient;
        this.yearWindow = yearWindow;
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/trading-calendar?year=YYYY
     * 一次回傳：休市日列表 + 即時市場開盤狀態。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(required = false) Integer year) {
        TradingCalendarYearWindow.Window window = yearWindow.snapshot();
        int targetYear = year != null ? year : window.currentYear();
        if (!window.accepts(targetYear)) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "message", "年度僅可查詢去年、今年或明年",
                    "availableYears", window.availableYears(),
                    "minYear", window.minYear(),
                    "maxYear", window.maxYear())));
        }
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
            body.put("availableYears", window.availableYears());
            body.put("minYear", window.minYear());
            body.put("maxYear", window.maxYear());
            body.put("availability", availability(t.getT1()));
            return ResponseEntity.ok(body);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> availability(Map<String, Object> holidays) {
        Map<String, String> result = new HashMap<>();
        for (String market : new String[]{"tw", "us", "uk"}) {
            Object value = holidays.get(market);
            boolean present = value instanceof Map<?, ?> map && !map.isEmpty();
            result.put(market, present ? "AVAILABLE" : "UNAVAILABLE");
        }
        return result;
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
     * POST /api/bff/trading-calendar/export?subpath=
     * 產出整年交易日曆並寫檔到指定目錄，<b>一律同時產 JSON 與 Excel 兩份</b>（Requirement 55 / Task 271）；
     * 回 {path,sizeBytes,jsonPath,jsonSizeBytes,jsonGdrivePath,year,totalDays,…}。
     * {@code format} 參數自 Requirement 55 起移除——使用者不再需要二選一。
     */
    @PostMapping("/export")
    public Mono<ResponseEntity<Map<String, Object>>> export(
            @RequestParam(required = false, defaultValue = "") String subpath) {
        return businessServicesClient.post()
                .uri(uri -> uri.path("/api/trading-calendar-export/run")
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

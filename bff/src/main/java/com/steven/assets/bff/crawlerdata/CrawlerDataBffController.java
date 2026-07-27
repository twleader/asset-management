package com.steven.assets.bff.crawlerdata;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CrawlerDataView 專屬 BFF（「公開資訊」分組，Requirement 38 / Task 192、212）：
 * 依日期查 {@code news_headline} 爬回資料，並讀／寫公開資訊爬蟲（NewsPoller）執行時間設定與輸出檔案路徑設定。
 * 一頁一 BFF，WebClient 轉呼 business（與今日股市分析同讀一份 {@code news_headline}）。
 *
 * <p>權限（BFF SecurityConfig）：{@code GET} 已登入者皆可；排程與輸出路徑的 {@code PUT} 限 ADMIN。
 */
@RestController
@RequestMapping("/api/bff/crawler-data")
@RequiredArgsConstructor
public class CrawlerDataBffController {

    private static final String NEWS_POLLER = "news-poller";

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /** 查指定日期爬回的 news_headline。dateField=fetched（預設）｜published；category 選填。 */
    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> query(
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "fetched") String dateField,
            @RequestParam(required = false) String category) {
        return businessServicesClient.get()
                .uri(uri -> {
                    var u = uri.path("/api/news-headlines").queryParam("dateField", dateField);
                    if (date != null && !date.isBlank()) u.queryParam("date", date);
                    if (category != null && !category.isBlank()) u.queryParam("category", category);
                    return u.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(ResponseEntity::ok);
    }

    /** 讀 NewsPoller 執行時間點清單 [{hour,minute,enabled}]。 */
    @GetMapping("/schedule")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getSchedule() {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/crawler-schedule").queryParam("crawler", NEWS_POLLER).build())
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(ResponseEntity::ok);
    }

    /** 整批覆寫 NewsPoller 執行時間點（限 ADMIN，BFF SecurityConfig 已擋）。 */
    @PutMapping("/schedule")
    public Mono<ResponseEntity<List<Map<String, Object>>>> saveSchedule(
            @RequestBody List<Map<String, Object>> times) {
        return businessServicesClient.put()
                .uri(uri -> uri.path("/api/crawler-schedule").queryParam("crawler", NEWS_POLLER).build())
                .bodyValue(times == null ? List.of() : times)
                .retrieve()
                .bodyToMono(LIST_MAP)
                .map(ResponseEntity::ok);
    }

    /**
     * 讀 NewsPoller 公開資訊 JSON 輸出路徑設定（Task 212）：{@code {crawlerKey,outputSubpath,baseDir,absolutePath,updatedAt}}。
     *
     * <p>**刻意不做 onErrorReturn 降級**（與上方查詢／排程 GET 不同）：路徑若因上游異常回空值，使用者可能在
     * 空欄位上按儲存而把設定重設為預設目錄。讓錯誤浮上來、前端據以停用儲存，比悄悄顯示空值安全。
     */
    @GetMapping("/export-path")
    public Mono<ResponseEntity<Map<String, Object>>> getExportPath() {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/crawler-export-path").queryParam("crawler", NEWS_POLLER).build())
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /** 更新 NewsPoller 輸出子路徑（限 ADMIN，BFF SecurityConfig 已擋；跳脫基底由 business 擋為 400）。 */
    @PutMapping("/export-path")
    public Mono<ResponseEntity<Map<String, Object>>> saveExportPath(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri(uri -> uri.path("/api/crawler-export-path").queryParam("crawler", NEWS_POLLER).build())
                .bodyValue(body == null ? Map.of() : body)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /**
     * 資料夾樹懶載入：passthrough 至 business **既有**的 {@code /api/export-schedule/browse}（Requirement 34），
     * 不新增第二份目錄列舉實作——語意相同＝列出同一基底（{@code EXPORT_OUTPUT_DIR}）下的子目錄，
     * 依 CLAUDE.md「同義欄位、同一 business service API」；BFF 端另立路由則是「一頁一 BFF」要求。
     */
    @GetMapping("/export-path/browse")
    public Mono<ResponseEntity<Map<String, Object>>> browseExportDir(
            @RequestParam(required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/export-schedule/browse").queryParam("subpath", subpath).build())
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /**
     * Google Drive 資料夾樹懶載入（Requirement 50 / Task 241）：passthrough 至 business
     * {@code /api/export-schedule/browse-gdrive}。
     *
     * <p>與上方本機 {@code browse} 並列為兩支（語意不同：本機基底 vs. Drive remote），但「Drive 目錄列舉」
     * 全庫只有 business 那一支實作，本頁只是 passthrough——日後其餘匯出頁接 Drive 時同樣沿用它。
     *
     * <p>**刻意不做 onErrorReturn 降級**：remote 未設定／授權失效時 business 回 503 帶可讀訊息，
     * 必須讓它浮到前端 dialog 顯示。若降級成空清單，使用者會誤讀為「Drive 裡沒有資料夾」而以為選錯位置。
     */
    @GetMapping("/export-path/browse-gdrive")
    public Mono<ResponseEntity<Map<String, Object>>> browseGdriveExportDir(
            @RequestParam(required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/export-schedule/browse-gdrive").queryParam("subpath", subpath).build())
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }
}

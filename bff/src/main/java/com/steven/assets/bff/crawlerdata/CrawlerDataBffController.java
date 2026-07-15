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
 * CrawlerDataView 專屬 BFF（「公開資訊」分組，Requirement 37 / Task 184）：
 * 依日期查 {@code news_headline} 爬回資料，並讀／寫公開資訊爬蟲（NewsPoller）執行時間設定。
 * 一頁一 BFF，WebClient 轉呼 business（與今日股市分析同讀一份 {@code news_headline}）。
 *
 * <p>權限（BFF SecurityConfig）：{@code GET} 已登入者皆可；排程 {@code PUT} 限 ADMIN。
 */
@RestController
@RequestMapping("/api/bff/crawler-data")
@RequiredArgsConstructor
public class CrawlerDataBffController {

    private static final String NEWS_POLLER = "news-poller";

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
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
}

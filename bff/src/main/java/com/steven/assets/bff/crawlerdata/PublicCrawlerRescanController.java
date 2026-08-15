package com.steven.assets.bff.crawlerdata;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 公開觸發「重新搜尋」（Requirement 71）：只委派 {@link PublicCrawlerRescanService}，
 * 不直接持有 WebClient 或發起 HTTP 呼叫。
 *
 * <p>獨立成類別、不併入 {@link CrawlerDataBffController}：後者 class-level
 * {@code @RequestMapping("/api/bff/crawler-data")} 會與方法級路徑相串接，無法產生
 * {@code /api/public/...} 這個頂層路徑（比照既有 {@code PublicUsdTwdController}／
 * {@code PublicMarketIndexController} 的同一結構性原因，非風格選擇）。
 */
@RestController
@RequestMapping("/api/public/crawler-data/rescan")
@RequiredArgsConstructor
public class PublicCrawlerRescanController {

    private final PublicCrawlerRescanService service;

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> rescan() {
        return service.rescan().map(ResponseEntity::ok);
    }
}

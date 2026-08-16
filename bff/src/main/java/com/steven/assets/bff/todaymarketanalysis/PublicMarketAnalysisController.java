package com.steven.assets.bff.todaymarketanalysis;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 公開讀取「今日股市分析」（Requirement 79）：只委派 {@link PublicMarketAnalysisService}，
 * 不直接持有 WebClient 或發起 HTTP 呼叫。
 *
 * <p>獨立成類別、不併入 {@link TodayMarketAnalysisBffController}：後者 class-level
 * {@code @RequestMapping("/api/bff/today-market-analysis")} 會與方法級路徑相串接，無法產生
 * {@code /api/public/...} 這個頂層路徑（比照既有 {@code PublicUsdTwdController}／
 * {@code PublicMarketIndexController}／{@code LatestAssetsPublicController}／
 * {@code PublicCrawlerRescanController} 的同一結構性原因，非風格選擇）。
 */
@RestController
@RequestMapping("/api/public/market-analysis/today")
@RequiredArgsConstructor
public class PublicMarketAnalysisController {

    private final PublicMarketAnalysisService service;

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> today() {
        return service.today().map(ResponseEntity::ok);
    }
}

package com.steven.assets.bff.portfolioadvice;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 公開讀取「最新資產配置建議」（Requirement 79）：只委派 {@link PublicPortfolioAdviceService}，
 * 不直接持有 WebClient 或發起 HTTP 呼叫。
 *
 * <p>獨立成類別、不併入 {@link PortfolioAdviceBffController}：後者 class-level
 * {@code @RequestMapping("/api/bff/portfolio-advice")} 會與方法級路徑相串接，無法產生
 * {@code /api/public/...} 這個頂層路徑（比照既有 {@code LatestAssetsPublicController}／
 * {@code PublicCrawlerRescanController} 的同一結構性原因，非風格選擇）。
 */
@RestController
@RequestMapping("/api/public/portfolio-advice/latest")
@RequiredArgsConstructor
public class PublicPortfolioAdviceController {

    private final PublicPortfolioAdviceService service;

    @GetMapping
    public Mono<ResponseEntity<byte[]>> latest() {
        return service.latest();
    }
}

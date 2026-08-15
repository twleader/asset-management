package com.steven.assets.bff.crawlerdata;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 公開觸發「重新搜尋」（Requirement 71）：Docker host／Tailscale 免登入可呼叫的第六條 Nginx 9090 路由，
 * 語意等同既有 ADMIN 限定「立即抓取並匯出」（{@link CrawlerDataBffController#fetchAndRunExportNow()}）
 * 的匿名版本，由 business 端 30 秒全域 Redis 冷卻節流。
 *
 * <p>刻意不做 {@code onErrorReturn} 降級：呼叫端需要看到 business 端真實失敗，不能被吞成空物件
 * （同 Requirement 63 對兩支既有 ADMIN 端點的既有理由）。business 例外由
 * {@link PublicCrawlerRescanExceptionAdvice} 消毒，不在這裡處理。
 */
@Service
@RequiredArgsConstructor
public class PublicCrawlerRescanService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final WebClient businessServicesClient;

    public Mono<Map<String, Object>> rescan() {
        return businessServicesClient.post()
                .uri("/api/crawler-export-path/public-rescan")
                .retrieve()
                .bodyToMono(MAP);
    }
}

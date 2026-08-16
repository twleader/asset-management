package com.steven.assets.bff.todaymarketanalysis;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 公開讀取「今日股市分析」（Requirement 79）：Docker host／Tailscale 免登入可讀的第七條 Nginx 9090 路由，
 * 轉呼 business 既有的 {@code GET /api/market-analysis/today}。
 *
 * <p><b>不需要 owner 解析</b>：{@code daily_market_analysis} 無 {@code owner_user_id}、不受
 * {@code TenantFilterAspect} 過濾，屬全域參考資料，故本服務不做 configured-admin bootstrap、
 * 也不帶 {@code X-User-*} header（比照 {@link TodayMarketAnalysisBffController} 對同一端點的呼叫）。
 *
 * <p><b>取用方式刻意寫死為 {@code .retrieve().bodyToMono(...)}</b>（同
 * {@code PublicCrawlerRescanService}），不採 {@code exchangeToMono} 的 byte-relay：後者在非 2xx 時
 * <b>不擲例外</b>，business 的原始 body 會原樣送到匿名呼叫者手上，使
 * {@link PublicMarketAnalysisExceptionAdvice} 的 {@code WebClientResponseException} handler 變成死碼，
 * 與「非 2xx 一律消毒成固定文案」的錯誤契約直接衝突。原樣 relay 在本條僅限縮為「2xx 時 body 內容不改寫」。
 *
 * <p>刻意不做 {@code onErrorReturn} 降級：呼叫端需要看到 business 端真實失敗，不能被吞成 200 空物件。
 */
@Service
@RequiredArgsConstructor
public class PublicMarketAnalysisService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final WebClient businessServicesClient;

    public Mono<Map<String, Object>> today() {
        return businessServicesClient.get()
                .uri("/api/market-analysis/today")
                .retrieve()
                .bodyToMono(MAP);
    }
}

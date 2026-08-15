package com.steven.assets.bff.crawlerdata;

import com.steven.assets.bff.common.BusinessErrorAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicCrawlerRescanExceptionAdvice}（Requirement 71 / Task 329）：驗證它真的<b>蓋過</b>全域
 * {@link BusinessErrorAdvice}，而不是測試環境根本沒有全域 advice 可競爭。
 *
 * <p><b>不照抄</b> {@code PublicUsdTwdControllerTest}／{@code PublicMarketIndexControllerTest} 既有的
 * {@code .controllerAdvice(僅自己)} 組裝方式——那種寫法從未把 {@link BusinessErrorAdvice} 一起放進
 * 同一個測試 context，測不到「跨 advice 競爭」這件事，會給假陽性。本檔的 {@code WebTestClient} 一律
 * <b>同時</b>註冊兩者：{@code .controllerAdvice(new PublicCrawlerRescanExceptionAdvice(), new BusinessErrorAdvice())}，
 * 斷言在兩者都對同一個 {@code WebClientResponseException} 型別宣告 handler 的情況下，回應仍是
 * {@link PublicCrawlerRescanExceptionAdvice} 消毒後的固定文案，而非 {@link BusinessErrorAdvice}
 * 會原樣轉發的 business body／狀態碼。
 */
class PublicCrawlerRescanExceptionAdviceTest {

    private static final String INTERNAL_SECRET = "internal-db-connection-string-secret";

    /**
     * business 回 500（帶一段含內部細節的 body）：若 {@link BusinessErrorAdvice} 勝出，回應會是
     * 原樣轉發的 500 ＋ 含 {@link #INTERNAL_SECRET} 的 body；若 {@link PublicCrawlerRescanExceptionAdvice}
     * 勝出（本類別宣告的 {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 應確保如此），回應是消毒後的
     * 固定 502 文案，且完全不含該字串。
     */
    @Test
    void 兩個advice都註冊時業務500仍由專屬advice消毒為502且不含business原始文字() {
        WebTestClient client = clientWithBothAdvices(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"detail\":\"" + INTERNAL_SECRET + "\"}")
                        .build()));

        client.post().uri("/api/public/crawler-data/rescan")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)   // 不是 500（BusinessErrorAdvice 會原樣轉發的狀態碼）
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.title").isEqualTo("Crawler rescan downstream failure")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain(INTERNAL_SECRET));
    }

    /** 403（業務層另一種常見非 2xx）同樣被消毒為 502，而非 BusinessErrorAdvice 會原樣轉發的 403。 */
    @Test
    void 兩個advice都註冊時業務403仍由專屬advice消毒為502() {
        WebTestClient client = clientWithBothAdvices(request ->
                Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"detail\":\"" + INTERNAL_SECRET + "\"}")
                        .build()));

        client.post().uri("/api/public/crawler-data/rescan")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain(INTERNAL_SECRET));
    }

    /**
     * 連線失敗（非 {@code WebClientResponseException}，{@code BusinessErrorAdvice} 完全不處理）由本 advice
     * 的兜底 handler 回 503。
     *
     * <p><b>不能用自訂 {@code exchangeFunction} 丟 {@code IOException} 來模擬</b>：自訂 exchangeFunction
     * 會整個取代 {@code WebClient} 真正的連線層，此時拋出的例外<b>不會</b>被包裝成
     * {@code WebClientRequestException}（{@code WebClientException} 的子類別），而是原封不動往外冒，
     * 兩個 advice 的 {@code @ExceptionHandler} 都攔不到、落回 Spring 預設 500——之前用這個寫法時
     * 實測就是回 500 而非預期的 503。故本測試改用<b>真的連不上的埠</b>，讓 Reactor Netty 的連線層
     * 產生真正的 {@code WebClientRequestException}。
     */
    @Test
    void 連線失敗時回503() throws IOException {
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }   // 關閉後這個埠沒人監聽 → 連線一定被拒絕（不是逾時）
        WebClient downstream = WebClient.builder().baseUrl("http://127.0.0.1:" + deadPort).build();
        PublicCrawlerRescanService service = new PublicCrawlerRescanService(downstream);
        WebTestClient client = WebTestClient.bindToController(new PublicCrawlerRescanController(service))
                .controllerAdvice(new PublicCrawlerRescanExceptionAdvice(), new BusinessErrorAdvice())
                .build();

        client.post().uri("/api/public/crawler-data/rescan")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Crawler rescan service unavailable");
    }

    private static WebTestClient clientWithBothAdvices(
            Function<ClientRequest, Mono<ClientResponse>> exchangeFunction) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(exchangeFunction::apply)
                .build();
        PublicCrawlerRescanService service = new PublicCrawlerRescanService(downstream);
        return WebTestClient.bindToController(new PublicCrawlerRescanController(service))
                // 刻意同時註冊全域 BusinessErrorAdvice：這是本檔與既有三支 sibling 測試的關鍵差異，
                // 唯有兩者同場競爭，才驗得到 @Order(HIGHEST_PRECEDENCE) 真的讓專屬 advice 勝出。
                .controllerAdvice(new PublicCrawlerRescanExceptionAdvice(), new BusinessErrorAdvice())
                .build();
    }
}

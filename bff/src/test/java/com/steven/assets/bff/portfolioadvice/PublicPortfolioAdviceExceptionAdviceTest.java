package com.steven.assets.bff.portfolioadvice;

import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicPortfolioAdviceExceptionAdvice}（Requirement 79 / Task 338）：驗證它真的<b>蓋過</b>全域
 * {@link BusinessErrorAdvice}（兩者都對 {@code WebClientResponseException} 宣告 handler），
 * 並驗證「主要管理者不可用」回<b>具名的 503</b>（不是 500、也不是 404）。
 *
 * <p>本 advice 涵蓋的 {@code WebClientResponseException} 來自 <b>bootstrap lookup</b>
 * （{@code BusinessUserClient.configuredAdmin()} 走 {@code .retrieve()}）；資料本身的 downstream
 * 呼叫走 byte-relay、非 2xx 不擲例外，那部分由 {@code PublicPortfolioAdviceControllerTest} 驗證。
 */
class PublicPortfolioAdviceExceptionAdviceTest {

    private static final String INTERNAL_SECRET = "internal-db-connection-string-secret";

    /** bootstrap lookup 回 500（含內部細節）：專屬 advice 勝出、消毒為固定 502 文案，不含該字串。 */
    @Test
    void 兩個advice都註冊時bootstrap500仍由專屬advice消毒為502且不含business原始文字() {
        WebTestClient client = clientWithBothAdvices(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"detail\":\"" + INTERNAL_SECRET + "\"}");

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)   // 不是 500（BusinessErrorAdvice 會原樣轉發的狀態碼）
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.title").isEqualTo("Portfolio advice downstream failure")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain(INTERNAL_SECRET));
    }

    /** 403（另一種常見非 2xx）同樣被消毒為 502，而非 BusinessErrorAdvice 會原樣轉發的 403。 */
    @Test
    void 兩個advice都註冊時bootstrap403仍由專屬advice消毒為502() {
        WebTestClient client = clientWithBothAdvices(HttpStatus.FORBIDDEN,
                "{\"detail\":\"" + INTERNAL_SECRET + "\"}");

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain(INTERNAL_SECRET));
    }

    /**
     * configured admin 存在但非 active（或非主要管理者）→ 具名 exception →
     * <b>503 SERVICE_UNAVAILABLE</b>（沿用 {@code LatestAssetsPublicExceptionAdvice.unavailable()} 的既有狀態碼），
     * 不是 500、也不是 404。
     */
    @Test
    void 主要管理者不可用時回具名503而非500或404() {
        WebTestClient client = clientWithBothAdvices(HttpStatus.OK,
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"DISABLED\",\"protectedAdmin\":true}");

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Portfolio advice unavailable")
                .jsonPath("$.detail").isEqualTo("主要管理者不可用");
    }

    /**
     * 連線失敗由本 advice 的兜底 handler 回 503。用<b>真的連不上的埠</b>而非自訂 exchangeFunction
     * 丟 {@code IOException}——後者不會被包裝成 {@code WebClientRequestException}，兩個 advice 都攔不到
     * （{@code PublicCrawlerRescanExceptionAdviceTest} 已記載這個踩坑）。
     */
    @Test
    void 連線失敗時回503() throws IOException {
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        WebClient downstream = WebClient.builder().baseUrl("http://127.0.0.1:" + deadPort).build();
        PublicPortfolioAdviceService service =
                new PublicPortfolioAdviceService(new BusinessUserClient(downstream), downstream);
        WebTestClient client = WebTestClient.bindToController(new PublicPortfolioAdviceController(service))
                .controllerAdvice(new PublicPortfolioAdviceExceptionAdvice(), new BusinessErrorAdvice())
                .build();

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Portfolio advice service unavailable");
    }

    private static WebTestClient clientWithBothAdvices(HttpStatus bootstrapStatus, String bootstrapBody) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(request -> {
                    if ("/internal/users/configured-admin".equals(request.url().getPath())) {
                        return Mono.just(ClientResponse.create(bootstrapStatus)
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .body(bootstrapBody).build());
                    }
                    return Mono.error(new AssertionError(
                            "bootstrap 失敗時不該再呼叫 business 的資料端點：" + request.url()));
                })
                .build();
        PublicPortfolioAdviceService service =
                new PublicPortfolioAdviceService(new BusinessUserClient(downstream), downstream);
        return WebTestClient.bindToController(new PublicPortfolioAdviceController(service))
                // 刻意同時註冊全域 BusinessErrorAdvice：唯有兩者同場競爭，才驗得到
                // @Order(HIGHEST_PRECEDENCE) 真的讓專屬 advice 勝出。
                .controllerAdvice(new PublicPortfolioAdviceExceptionAdvice(), new BusinessErrorAdvice())
                .build();
    }
}

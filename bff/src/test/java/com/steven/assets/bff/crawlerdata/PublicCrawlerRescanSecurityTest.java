package com.steven.assets.bff.crawlerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code SecurityConfig} 對 {@code POST /api/public/crawler-data/rescan} 的匿名放行規則
 * （Requirement 71 / Task 329）：只有本路徑的 {@code POST} 免驗證，其餘 method、descendant 路徑，
 * 以及相鄰 {@code /api/bff/crawler-data/**}（含既有兩支 ADMIN 端點）維持既有 401／規則不受影響。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class PublicCrawlerRescanSecurityTest {

    @LocalServerPort
    private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 匿名 POST 被放行、確實到達 controller（不是 401）：下游 business 連不上（{@code localhost:1}），
     * 故實際回應是 {@link PublicCrawlerRescanExceptionAdvice} 消毒後的 502／503，而非安全層擋下的 401——
     * 這正是「有到達 controller」的證明。
     */
    @Test
    void 匿名POST被放行且到達controller() {
        client.post().uri("/api/public/crawler-data/rescan")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status)
                                .isIn(HttpStatus.BAD_GATEWAY.value(), HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    /** 本路徑的其餘 method、descendant 路徑、以及相鄰 ADMIN 端點皆維持既有 401（未登入）。 */
    @Test
    void 其餘method與descendant及相鄰ADMIN端點維持401() {
        client.get().uri("/api/public/crawler-data/rescan").exchange().expectStatus().isUnauthorized();
        client.put().uri("/api/public/crawler-data/rescan").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/api/public/crawler-data/rescan").exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/public/crawler-data/rescan").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/public/crawler-data/rescan/child").exchange().expectStatus().isUnauthorized();
        // 相鄰的既有 ADMIN 端點（Requirement 63）不受本次新規則影響：未登入一律 401。
        client.post().uri("/api/bff/crawler-data/export/run-now").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/bff/crawler-data/export/fetch-and-run-now").exchange().expectStatus().isUnauthorized();
        // 相鄰同分組但一般 GET 端點（開放已登入者，非匿名）未帶 session 時同樣 401。
        client.get().uri("/api/bff/crawler-data").exchange().expectStatus().isUnauthorized();
    }
}

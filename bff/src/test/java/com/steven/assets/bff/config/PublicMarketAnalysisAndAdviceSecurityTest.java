package com.steven.assets.bff.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SecurityConfig} 對第七、八條匿名唯讀路由的放行規則（Requirement 79 / Task 338）：
 * 只有 {@code GET /api/public/market-analysis/today} 與 {@code GET /api/public/portfolio-advice/latest}
 * 免驗證；其餘 method、descendant 路徑，以及相鄰的 {@code /api/bff/today-market-analysis/**}、
 * {@code /api/bff/portfolio-advice/**} 規則維持既有 401／不受放寬。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class PublicMarketAnalysisAndAdviceSecurityTest {

    @LocalServerPort
    private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /**
     * 匿名 GET 被放行、確實到達 controller（不是 401）：下游 business 連不上（{@code localhost:1}），
     * 故實際回應是各自 exception advice 消毒後的 502／503，而非安全層擋下的 401——這正是
     * 「有到達 controller」的證明。
     */
    @Test
    void 兩條路徑的匿名GET被放行且到達controller() {
        client.get().uri("/api/public/market-analysis/today")
                .exchange()
                .expectStatus().value(status -> assertThat(status)
                        .isIn(HttpStatus.BAD_GATEWAY.value(), HttpStatus.SERVICE_UNAVAILABLE.value()));
        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().value(status -> assertThat(status)
                        .isIn(HttpStatus.BAD_GATEWAY.value(), HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    /** 兩條路徑的其餘 method 皆維持既有 401（未登入），放行僅限 GET。 */
    @Test
    void 兩條路徑的非GET維持401() {
        for (String path : new String[]{
                "/api/public/market-analysis/today", "/api/public/portfolio-advice/latest"}) {
            client.post().uri(path).exchange().expectStatus().isUnauthorized();
            client.put().uri(path).exchange().expectStatus().isUnauthorized();
            client.patch().uri(path).exchange().expectStatus().isUnauthorized();
            client.delete().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    /** descendant 路徑不在放行清單；相鄰的既有頁面 BFF 端點（含 ADMIN 規則）不受本次放行影響。 */
    @Test
    void descendant與相鄰頁面BFF端點維持401() {
        client.get().uri("/api/public/market-analysis/today/extra").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/public/portfolio-advice/latest/extra").exchange().expectStatus().isUnauthorized();
        // 相鄰的頁面 BFF：GET 開放已登入者（未帶 session 一律 401）
        client.get().uri("/api/bff/today-market-analysis").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/bff/portfolio-advice").exchange().expectStatus().isUnauthorized();
        // 相鄰的既有 ADMIN 端點（Requirement 31／32）：未登入一律 401，不因本次放行而鬆動
        client.post().uri("/api/bff/today-market-analysis/generate").exchange().expectStatus().isUnauthorized();
        client.put().uri("/api/bff/today-market-analysis/settings").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/bff/portfolio-advice/generate").exchange().expectStatus().isUnauthorized();
        client.put().uri("/api/bff/portfolio-advice/settings").exchange().expectStatus().isUnauthorized();
    }
}

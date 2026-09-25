package com.steven.assets.bff.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Requirement 163／Task 454：只有 exact 匿名 GET 可達 SRPP controller；其他 method、子路徑、尾斜線維持 401。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.invalid"
})
class PublicSrppDailyContextSecurityTest {

    private static final String PATH = "/api/public/srpp/daily-context";
    private static final String QUERY = "?tradingDate=2026-09-24&slot=09:05&policyBundleSha256=" + "0".repeat(64);

    @LocalServerPort private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetReachesScopedController() {
        // business 不可達 → configured-admin lookup 失敗 → 503 OWNER_UNAVAILABLE（證明未被 401 擋下）。
        client.get().uri(PATH + QUERY).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE")
                .jsonPath("$.instance").isEqualTo(PATH)
                .jsonPath("$.retryable").isEqualTo(false);
        client.get().uri(PATH + "?foo=1").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void otherMethodsDescendantsAndTrailingSlashRemainProtected() {
        client.post().uri(PATH + QUERY).exchange().expectStatus().isUnauthorized();
        client.put().uri(PATH + QUERY).exchange().expectStatus().isUnauthorized();
        client.get().uri(PATH + "/extra").exchange().expectStatus().isUnauthorized();
        client.get().uri(PATH + "/").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/public-srpp/daily-context" + QUERY).exchange().expectStatus().isUnauthorized();
    }
}

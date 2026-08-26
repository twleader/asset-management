package com.steven.assets.bff.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Requirements 112/113: only the three new exact anonymous GET endpoints bypass application login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.invalid"
})
class PublicTransactionAndCalendarSecurityTest {

    @LocalServerPort private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetsReachTheirScopedControllers() {
        client.get().uri("/api/public/transactions")
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        client.get().uri("/api/public/trading-calendar?year=2026")
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void methodsDescendantsAndInternalBridgesRemainProtected() {
        for (String path : new String[]{
                "/api/public/transactions", "/api/public/trading-calendar", "/api/public/trading-radar/stock"}) {
            client.post().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path + "/extra").exchange().expectStatus().isUnauthorized();
            client.get().uri(path + "/").exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/internal/public-transaction-history/current")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/public-market-data/trading-calendar?year=2026")
                .exchange().expectStatus().isUnauthorized();
    }
}

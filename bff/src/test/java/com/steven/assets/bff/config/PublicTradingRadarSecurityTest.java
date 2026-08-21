package com.steven.assets.bff.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Requirement 86：匿名放行只限 public trading-radar 的 exact GET。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.invalid"
})
class PublicTradingRadarSecurityTest {

    @LocalServerPort private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetReachesController() {
        client.get().uri("/api/public/trading-radar/today")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void otherMethodsRemainUnauthorized() {
        String path = "/api/public/trading-radar/today";
        client.post().uri(path).exchange().expectStatus().isUnauthorized();
        client.put().uri(path).exchange().expectStatus().isUnauthorized();
        client.patch().uri(path).exchange().expectStatus().isUnauthorized();
        client.delete().uri(path).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void descendantTrailingSlashAndPrivateSiblingRemainUnauthorized() {
        client.get().uri("/api/public/trading-radar/today/extra")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/public/trading-radar/today/")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/bff/trading-radar")
                .exchange().expectStatus().isUnauthorized();
    }
}

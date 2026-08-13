package com.steven.assets.bff.exchangerate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class PublicUsdTwdSecurityTest {

    @LocalServerPort
    private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetIsPermittedAndReachesController() {
        client.get().uri("/api/public/exchange-rate/usd-twd")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status)
                                .isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.BAD_GATEWAY.value()));
    }

    @Test
    void otherMethodsDescendantAndAdjacentBffPathRemainUnauthorized() {
        client.post().uri("/api/public/exchange-rate/usd-twd").exchange().expectStatus().isUnauthorized();
        client.put().uri("/api/public/exchange-rate/usd-twd").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/api/public/exchange-rate/usd-twd").exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/public/exchange-rate/usd-twd").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/public/exchange-rate/usd-twd/child").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/bff/exchange-rate").exchange().expectStatus().isUnauthorized();
    }
}

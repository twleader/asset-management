package com.steven.assets.bff.publicquote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** SecurityConfig 只放行兩條 exact public GET；readonly internal bridge 絕不可變成 browser route。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "external-materials.base-url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class PublicQuoteMarketDataSecurityTest {

    @LocalServerPort
    private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetsReachThePublicAggregation() {
        client.get().uri("/api/quotes").exchange().expectStatus().value(status ->
                assertThat(status).isIn(HttpStatus.BAD_GATEWAY.value(), HttpStatus.GATEWAY_TIMEOUT.value()));
        client.get().uri("/api/quotes/one?code=2330&market=%E5%8F%B0%E8%82%A1").exchange()
                .expectStatus().value(status ->
                        assertThat(status).isIn(HttpStatus.BAD_GATEWAY.value(), HttpStatus.GATEWAY_TIMEOUT.value()));
    }

    @Test
    void methodsDescendantsAndInternalBridgeRemainProtected() {
        client.post().uri("/api/quotes").exchange().expectStatus().isUnauthorized();
        client.put().uri("/api/quotes").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/api/quotes/one").exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/quotes/one").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/quotes/one/").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/quotes/private").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/public-market-data/dividends-readonly-result?code=2330&market=%E5%8F%B0%E8%82%A1")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/public-market-data/intraday-ticks-readonly?code=2330&market=%E5%8F%B0%E8%82%A1&date=2026-08-24")
                .exchange().expectStatus().isUnauthorized();
    }
}

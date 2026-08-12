package com.steven.assets.bff.gdptwse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 精確驗證只有匿名 GET /api/public/market-index 被 SecurityConfig 放行。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PublicMarketIndexSecurityTest.StubChartServiceConfiguration.class)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class PublicMarketIndexSecurityTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetReachesController() {
        client.get()
                .uri("/api/public/market-index?market=TWSE&range=1y")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.market").isEqualTo("TWSE")
                .jsonPath("$.mode").isEqualTo("DAILY");
    }

    @Test
    void adjacentLegacyEndpointRemainsProtected() {
        client.get()
                .uri("/api/bff/gdp-twse/index-daily?market=TWSE&years=10")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void samePathOtherMethodAndDescendantPathRemainProtected() {
        client.post()
                .uri("/api/public/market-index")
                .exchange()
                .expectStatus().isUnauthorized();

        client.put()
                .uri("/api/public/market-index")
                .exchange()
                .expectStatus().isUnauthorized();

        client.patch()
                .uri("/api/public/market-index")
                .exchange()
                .expectStatus().isUnauthorized();

        client.delete()
                .uri("/api/public/market-index")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get()
                .uri("/api/public/market-index/child")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @TestConfiguration
    static class StubChartServiceConfiguration {

        @Bean
        @Primary
        MarketIndexChartService securityTestMarketIndexChartService() {
            WebClient client = WebClient.builder()
                    .baseUrl("http://business")
                    .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body("[]")
                            .build()))
                    .build();
            return new MarketIndexChartService(client);
        }
    }
}

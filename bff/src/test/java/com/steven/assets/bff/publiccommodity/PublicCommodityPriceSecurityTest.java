package com.steven.assets.bff.publiccommodity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** SecurityConfig permits only the new exact anonymous GET, never a public wildcard. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.invalid"
})
class PublicCommodityPriceSecurityTest {

    @LocalServerPort private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousExactGetReachesItsScopedController() {
        client.get().uri(PublicCommodityPriceController.PATH)
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void methodsDescendantsAndTrailingSlashRemainProtected() {
        client.post().uri(PublicCommodityPriceController.PATH).exchange().expectStatus().isUnauthorized();
        client.get().uri(PublicCommodityPriceController.PATH + "/extra").exchange().expectStatus().isUnauthorized();
        client.get().uri(PublicCommodityPriceController.PATH + "/").exchange().expectStatus().isUnauthorized();
    }
}

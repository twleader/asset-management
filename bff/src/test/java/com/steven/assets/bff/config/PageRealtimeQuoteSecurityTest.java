package com.steven.assets.bff.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"business-services.url=http://localhost:1", "ADMIN_EMAIL=test@example.invalid"})
class PageRealtimeQuoteSecurityTest {
    @LocalServerPort private int port;

    @Test
    void bothNewPageRealtimeRoutesRequireLoginBeforeBusinessIo() {
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        for (String page : java.util.List.of("watch-stock", "trading-radar")) {
            client.get().uri("/api/bff/" + page + "/realtime").exchange().expectStatus().isUnauthorized();
        }
    }
}

package com.steven.assets.bff.portfolioadvice;

import com.steven.assets.bff.apierrorlogs.ApiErrorLogIngestClient;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*;

@SpringBootTest
@TestPropertySource(properties="business-services.url=http://127.0.0.1:1")
class SrppSettingsSecurityTest {
    @Autowired ApplicationContext context;
    @MockitoBean BusinessUserClient users;
    @MockitoBean ApiErrorLogIngestClient ingest;
    WebTestClient client;
    @BeforeEach void setup(){reset(users,ingest);client=WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();}
    @Test void anonymousCannotReadAndOrdinaryUserCannotPublishBeforeOwnerLookup() {
        client.get().uri("/api/bff/portfolio-advice/srpp").exchange().expectStatus().isUnauthorized();
        String publish="/api/bff/portfolio-advice/srpp/drafts/5bd55e21-7e39-5fa7-8c35-d2b2664e54e1/publish";
        client.post().uri(publish).bodyValue("{}").exchange().expectStatus().isUnauthorized();
        client.mutateWith(mockUser().roles("USER")).post().uri(publish).bodyValue("{}").exchange().expectStatus().isForbidden();
        verifyNoInteractions(users,ingest);
    }
}

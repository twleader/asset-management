package com.steven.assets.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalTreasuryYieldClientTest {

    @Test
    void fetchSendsServiceCredentialAndSupplementaryAdminRole() {
        AtomicReference<ClientRequest> seen = new AtomicReference<>();
        WebClient webClient = WebClient.builder()
                .baseUrl("http://external.test")
                .exchangeFunction(request -> {
                    seen.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("[]")
                            .build());
                })
                .build();
        ExternalTreasuryYieldClient client = new ExternalTreasuryYieldClient(
                webClient, "test-only-service-token");

        assertThat(client.fetch(2026)).isEmpty();
        assertThat(seen.get().headers().getFirst("X-Internal-Service-Token"))
                .isEqualTo("test-only-service-token");
        assertThat(seen.get().headers().getFirst("X-User-Role")).isEqualTo("ADMIN");
    }

    @Test
    void missingConfiguredCredentialFailsBeforeNetworkCall() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new AssertionError("network must not be called")))
                .build();

        assertThatThrownBy(() -> new ExternalTreasuryYieldClient(webClient, " ").fetch(2026))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential");
    }
}

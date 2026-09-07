package com.steven.assets.bff.apierrorlogs;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorLogsBffControllerTest {
    @Test
    void operations_relays_api_url_unchanged_and_forwards_source_query() {
        String businessResponse = "[{\"source\":\"FUBON_API\",\"operationKey\":\"FUBON_PORTFOLIO_READ\",\"apiName\":\"庫存與未實現損益\",\"apiUrl\":\"POST /internal/portfolio/read\",\"displayOrder\":10}]";
        AtomicReference<URI> requestUri = new AtomicReference<>();
        WebClient business = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    requestUri.set(request.url());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(businessResponse).build());
                }).build();
        WebTestClient client = WebTestClient.bindToController(new ApiErrorLogsBffController(business)).build();

        client.get().uri("/api/bff/api-error-logs/operations?source=FUBON_API").exchange()
                .expectStatus().isOk().expectBody().json(businessResponse);

        assertThat(requestUri.get().getPath()).isEqualTo("/api/api-error-logs/operations");
        assertThat(requestUri.get().getQuery()).isEqualTo("source=FUBON_API");
    }

    /**
     * Task 421／Requirement 143 AC: "BFF 既有三個 proxy endpoint 不需改程式碼，但須有測試證明"。
     * {@code list(...)} uses the same {@code Mono<Object>} opaque relay as {@code operations(...)}, so
     * a business response carrying the new {@code httpStatus} field (non-null for an OPEN_API row, JSON
     * {@code null} for a FUBON_API row) must be forwarded byte-for-byte without the BFF dropping or
     * coercing the field.
     */
    @Test
    void list_relays_http_status_field_unchanged_for_both_a_real_value_and_json_null() {
        String businessResponse = "[{\"id\":1,\"source\":\"OPEN_API\",\"operationKey\":\"OPEN_MARKET_INDEX\",\"apiName\":\"大盤指數\","
                + "\"messageHeader\":\"caller error\",\"httpStatus\":400,\"occurredAt\":\"2026-09-07T01:00:00Z\"},"
                + "{\"id\":2,\"source\":\"FUBON_API\",\"operationKey\":\"FUBON_PORTFOLIO_READ\",\"apiName\":\"庫存與未實現損益\","
                + "\"messageHeader\":\"fubon failure\",\"httpStatus\":null,\"occurredAt\":\"2026-09-07T01:05:00Z\"}]";
        AtomicReference<URI> requestUri = new AtomicReference<>();
        WebClient business = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    requestUri.set(request.url());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(businessResponse).build());
                }).build();
        WebTestClient client = WebTestClient.bindToController(new ApiErrorLogsBffController(business)).build();

        client.get().uri("/api/bff/api-error-logs?source=ALL&sort=NEWEST").exchange()
                .expectStatus().isOk().expectBody().json(businessResponse);

        assertThat(requestUri.get().getPath()).isEqualTo("/api/api-error-logs");
    }
}

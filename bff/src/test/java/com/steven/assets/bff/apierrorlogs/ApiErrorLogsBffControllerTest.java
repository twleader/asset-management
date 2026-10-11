package com.steven.assets.bff.apierrorlogs;

import com.steven.assets.bff.common.BusinessErrorAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorLogsBffControllerTest {
    @Test
    void operations_relays_api_url_unchanged_and_forwards_source_query() {
        String businessResponse = "[{\"source\":\"FUBON_API\",\"operationKey\":\"FUBON_PORTFOLIO_READ\",\"apiName\":\"庫存與未實現損益\",\"apiUrl\":\"POST /internal/portfolio/read\",\"displayOrder\":10}]";
        AtomicReference<URI> requestUri = new AtomicReference<>();
        WebTestClient client = client(business(HttpStatus.OK, businessResponse, requestUri, null));

        client.get().uri("/api/bff/api-error-logs/operations?source=FUBON_API").exchange()
                .expectStatus().isOk().expectBody().json(businessResponse);

        assertThat(requestUri.get().getPath()).isEqualTo("/api/api-error-logs/operations");
        assertThat(requestUri.get().getQuery()).isEqualTo("source=FUBON_API");
    }

    @Test
    void list_relays_typed_page_metadata_iso_instants_and_nullable_http_status() {
        String businessResponse = """
                {"items":[
                  {"id":1,"source":"OPEN_API","operationKey":"OPEN_MARKET_INDEX","apiName":"大盤指數",
                   "messageHeader":"caller error","httpStatus":400,"occurredAt":"2026-09-07T01:00:00Z"},
                  {"id":2,"source":"FUBON_API","operationKey":"FUBON_PORTFOLIO_READ","apiName":"庫存與未實現損益",
                   "messageHeader":"fubon failure","httpStatus":null,"occurredAt":"2026-09-07T01:05:00Z"}],
                 "totalElements":205,"page":0,"pageSize":100,"totalPages":3}
                """;
        AtomicReference<URI> requestUri = new AtomicReference<>();
        WebTestClient client = client(business(HttpStatus.OK, businessResponse, requestUri, null));

        client.get().uri("/api/bff/api-error-logs?source=ALL&sort=NEWEST").exchange()
                .expectStatus().isOk().expectBody().json(businessResponse)
                .jsonPath("$.items[0].occurredAt").isEqualTo("2026-09-07T01:00:00Z")
                .jsonPath("$.items[1].httpStatus").isEmpty()
                .jsonPath("$.items[0].stackTrace").doesNotExist()
                .jsonPath("$.items[0].dedupeKey").doesNotExist();

        assertThat(requestUri.get().getPath()).isEqualTo("/api/api-error-logs");
        assertThat(requestUri.get().getQuery()).isEqualTo("source=ALL&sort=NEWEST&page=0");
    }

    @Test
    void list_forwards_all_filter_dimensions_and_page_without_local_paging_or_counting() {
        String businessResponse = """
                {"items":[{"id":9,"source":"FUBON_API","operationKey":"FUBON_PORTFOLIO_READ",
                  "apiName":"庫存與未實現損益","messageHeader":"failure","httpStatus":null,
                  "occurredAt":"2026-10-11T01:00:00Z"}],
                 "totalElements":201,"page":2,"pageSize":100,"totalPages":3}
                """;
        AtomicReference<URI> requestUri = new AtomicReference<>();
        WebTestClient client = client(business(HttpStatus.OK, businessResponse, requestUri, null));

        client.get().uri("/api/bff/api-error-logs?source=FUBON_API&sort=OLDEST&operationKey=FUBON_PORTFOLIO_READ&page=2")
                .exchange().expectStatus().isOk().expectBody().json(businessResponse);

        assertThat(requestUri.get().getQuery())
                .isEqualTo("source=FUBON_API&sort=OLDEST&page=2&operationKey=FUBON_PORTFOLIO_READ");
    }

    @Test
    void empty_and_out_of_range_pages_preserve_requested_page_and_all_metadata() {
        for (String businessResponse : new String[]{
                "{\"items\":[],\"totalElements\":0,\"page\":0,\"pageSize\":100,\"totalPages\":0}",
                "{\"items\":[],\"totalElements\":205,\"page\":8,\"pageSize\":100,\"totalPages\":3}"}) {
            AtomicReference<URI> requestUri = new AtomicReference<>();
            WebTestClient client = client(business(HttpStatus.OK, businessResponse, requestUri, null));
            client.get().uri("/api/bff/api-error-logs?page=8").exchange()
                    .expectStatus().isOk().expectBody().json(businessResponse);
        }
    }

    @Test
    void malformed_page_is_400_without_an_upstream_request() {
        AtomicReference<URI> requestUri = new AtomicReference<>();
        WebTestClient client = client(business(HttpStatus.OK, "{}", requestUri, null));

        client.get().uri("/api/bff/api-error-logs?page=invalid").exchange().expectStatus().isBadRequest();
        assertThat(requestUri).hasValue(null);
    }

    @Test
    void oversized_upstream_200_body_is_502_with_a_fixed_problem_detail() {
        String businessResponse = """
                {"items":[{"id":1,"source":"OPEN_API","operationKey":"OPEN_MARKET_INDEX","apiName":"大盤指數",
                "messageHeader":"%s","httpStatus":400,"occurredAt":"2026-09-07T01:00:00Z"}],
                "totalElements":1,"page":0,"pageSize":100,"totalPages":1}
                """.formatted("internal secret".repeat(100));
        WebTestClient client = client(business(HttpStatus.OK, businessResponse, new AtomicReference<>(), 128));

        expectDecodeFailure(client);
    }

    @Test
    void malformed_upstream_200_json_is_502_with_the_same_fixed_problem_detail() {
        WebTestClient client = client(business(HttpStatus.OK, "{\"items\":[internal secret", new AtomicReference<>(), null));

        expectDecodeFailure(client);
    }

    @Test
    void non_2xx_business_status_and_problem_body_keep_the_existing_relay_contract() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_REQUEST}) {
            String problem = "{\"status\":" + status.value() + ",\"detail\":\"既有業務錯誤\"}";
            AtomicReference<URI> requestUri = new AtomicReference<>();
            WebTestClient client = client(business(status, problem, requestUri, null));

            client.get().uri("/api/bff/api-error-logs?page=-1").exchange().expectStatus().isEqualTo(status)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody(String.class).isEqualTo(problem);
            assertThat(requestUri.get().getQuery()).isEqualTo("source=ALL&sort=NEWEST&page=-1");
        }
    }

    private static void expectDecodeFailure(WebTestClient client) {
        client.get().uri("/api/bff/api-error-logs").exchange().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.detail").isEqualTo("無法讀取上游回應，請稍後重試")
                .jsonPath("$.stackTrace").doesNotExist()
                .consumeWith(result -> {
                    assertThat(result.getResponseBody()).isNotEmpty();
                    assertThat(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("internal secret", "DataBufferLimitException", "DecodingException");
                });
    }

    private static WebTestClient client(WebClient business) {
        return WebTestClient.bindToController(new ApiErrorLogsBffController(business))
                .controllerAdvice(new BusinessErrorAdvice()).build();
    }

    private static WebClient business(HttpStatus status, String body, AtomicReference<URI> requestUri, Integer maxBytes) {
        ExchangeStrategies strategies = maxBytes == null ? ExchangeStrategies.withDefaults()
                : ExchangeStrategies.builder().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxBytes)).build();
        WebClient.Builder builder = WebClient.builder().baseUrl("http://business").exchangeStrategies(strategies)
                .exchangeFunction(request -> {
                    requestUri.set(request.url());
                    return Mono.just(ClientResponse.create(status, strategies)
                            .header("Content-Type", status.is2xxSuccessful() ? MediaType.APPLICATION_JSON_VALUE : MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                            .body(body).build());
                });
        return builder.build();
    }
}

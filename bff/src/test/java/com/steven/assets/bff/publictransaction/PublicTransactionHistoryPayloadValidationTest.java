package com.steven.assets.bff.publictransaction;

import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/** A 200 must still be rejected unless it is the complete documented ledger JSON contract. */
class PublicTransactionHistoryPayloadValidationTest {

    private static final String ADMIN =
            "{\"id\":1,\"email\":\"owner@example.invalid\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";
    private static final String SENTINEL = "internal-ledger-secret";

    @Test
    void validJsonIsReserializedAsThePublicApplicationJsonContract() {
        WebTestClient client = client(MediaType.parseMediaType("application/json;charset=UTF-8"),
                PublicContractJsonFixtures.TRANSACTION_HISTORY);

        client.get().uri("/api/public/transactions")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.selection.mode").isEqualTo("ALL")
                .jsonPath("$.records").isArray();
    }

    @Test
    void successfulHtmlMalformedEmptyAndSchemaMismatchedBodiesAreSanitized502() {
        for (Payload payload : new Payload[]{
                new Payload(MediaType.TEXT_HTML, "<html>" + SENTINEL + "</html>"),
                new Payload(MediaType.APPLICATION_JSON, "{\"selection\":"),
                new Payload(MediaType.APPLICATION_JSON, "{\"selection\":{}}"),
                new Payload(MediaType.APPLICATION_JSON, ""),
                new Payload(MediaType.APPLICATION_JSON, withExtraRootProperty()),
                new Payload(MediaType.APPLICATION_JSON, PublicContractJsonFixtures.TRANSACTION_HISTORY
                        .replace("\"year\":null", "\"year\":\"not-an-integer\"")),
                new Payload(MediaType.APPLICATION_JSON, PublicContractJsonFixtures.TRANSACTION_HISTORY
                        .replace("\"records\":[]", "\"records\":{}")),
                new Payload(MediaType.APPLICATION_JSON, withDuplicateRecordsProperty())}) {
            WebTestClient client = client(payload.contentType(), payload.body());

            client.get().uri("/api/public/transactions")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(502)
                    .jsonPath("$.title").isEqualTo("Transaction history downstream failure")
                    .consumeWith(result -> assertThat(text(result.getResponseBodyContent()))
                            .doesNotContain(SENTINEL, "payload is invalid", "JSON contract mismatch"));
        }
    }

    private static WebTestClient client(MediaType payloadContentType, String payload) {
        WebClient downstream = WebClient.builder().baseUrl("http://business.invalid")
                .exchangeFunction(request -> {
                    if ("/internal/users/configured-admin".equals(request.url().getPath())) {
                        return Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(ADMIN).build());
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, payloadContentType.toString())
                            .body(payload).build());
                }).build();
        PublicTransactionHistoryService service =
                new PublicTransactionHistoryService(new BusinessUserClient(downstream), downstream);
        return WebTestClient.bindToController(new PublicTransactionHistoryController(service))
                .controllerAdvice(new PublicTransactionHistoryExceptionAdvice())
                .build();
    }

    private record Payload(MediaType contentType, String body) {}

    private static String withExtraRootProperty() {
        String value = PublicContractJsonFixtures.TRANSACTION_HISTORY;
        return value.substring(0, value.length() - 1) + ",\"unexpected\":true}";
    }

    private static String withDuplicateRecordsProperty() {
        String value = PublicContractJsonFixtures.TRANSACTION_HISTORY;
        return value.substring(0, value.length() - 1) + ",\"records\":[]}";
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}

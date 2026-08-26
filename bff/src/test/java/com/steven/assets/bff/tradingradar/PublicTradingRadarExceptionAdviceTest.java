package com.steven.assets.bff.tradingradar;

import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Scoped advice 必須壓過會 relay 上游 body 的全域 BusinessErrorAdvice。 */
class PublicTradingRadarExceptionAdviceTest {

    private static final String ADMIN_JSON =
            "{\"id\":1,\"email\":\"owner@example.invalid\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";
    private static final String SENTINEL = "internal-upstream-secret";

    @Test
    void downstream3xx4xx5xxAreAlwaysSanitizedTo502() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.FOUND, HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR}) {
            WebTestClient client = client(ADMIN_JSON, status,
                    "{\"detail\":\"" + SENTINEL + "\"}", false);

            client.get().uri("/api/public/trading-radar/today")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(502)
                    .jsonPath("$.title").isEqualTo("Trading radar downstream failure")
                    .consumeWith(result -> assertThat(text(result.getResponseBodyContent()))
                            .doesNotContain(SENTINEL, "business.internal.invalid"));
        }
    }

    @Test
    void downstreamTransportFailureIsFixed503() {
        WebTestClient client = client(ADMIN_JSON, HttpStatus.OK, "{}", true);

        client.get().uri("/api/public/trading-radar/today")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Trading radar service unavailable")
                .consumeWith(result -> assertThat(text(result.getResponseBodyContent()))
                        .doesNotContain(SENTINEL));
    }

    @Test
    void successfulHtmlMalformedEmptyAndSchemaMismatchedBodiesAreSanitized502ForBothShapes() {
        for (Payload payload : new Payload[]{
                new Payload(MediaType.TEXT_HTML, "<html>" + SENTINEL + "</html>"),
                new Payload(MediaType.APPLICATION_JSON, "{\"ruleVersion\":"),
                new Payload(MediaType.APPLICATION_JSON, "{\"ruleVersion\":\"TW_RULES_V17\"}"),
                new Payload(MediaType.APPLICATION_JSON, "")}) {
            WebTestClient client = client(ADMIN_JSON, HttpStatus.OK, payload.body(), false, payload.contentType());

            client.get().uri("/api/public/trading-radar/today")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(502)
                    .jsonPath("$.title").isEqualTo("Trading radar downstream failure")
                    .consumeWith(result -> assertThat(text(result.getResponseBodyContent()))
                            .doesNotContain(SENTINEL, "payload is invalid", "JSON contract mismatch"));

            client.get().uri(uri -> uri.path("/api/public/trading-radar/stock")
                            .queryParam("stockCode", "2330").queryParam("market", "台股").build())
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(502)
                    .jsonPath("$.title").isEqualTo("Trading radar downstream failure")
                    .consumeWith(result -> assertThat(text(result.getResponseBodyContent()))
                            .doesNotContain(SENTINEL, "payload is invalid", "JSON contract mismatch"));
        }
    }

    @Test
    void validListRowWithNullableFundamentalAndWeeklyIndicatorsIsReserializedAsApplicationJson() {
        WebTestClient client = client(ADMIN_JSON, HttpStatus.OK,
                PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK,
                false, MediaType.parseMediaType("application/json;charset=UTF-8"));

        client.get().uri("/api/public/trading-radar/today")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.ruleVersion").isEqualTo("TW_RULES_V17")
                .jsonPath("$.stocks[0].stockCode").isEqualTo("2330")
                .jsonPath("$.stocks[0].fundamental").doesNotExist()
                .jsonPath("$.stocks[0].weeklyIndicators").doesNotExist();
    }

    @Test
    void missingOrInactiveConfiguredAdminIsFixed503() {
        for (String bootstrap : new String[]{
                null,
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"DISABLED\",\"protectedAdmin\":true}",
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":false}"}) {
            assertUnavailable(client(bootstrap, HttpStatus.OK, "{}", false));
        }
    }

    /** codec malformed JSON 必須在 bootstrap publisher 邊界被壓成固定 503。 */
    @Test
    void malformedBootstrapJsonDoesNotLeakCodecMessageOrBody() {
        String malformed = "{\"id\":1,\"secret\":\"" + SENTINEL + "\"";
        WebTestClient client = client(malformed, HttpStatus.OK, "{}", false);

        assertUnavailable(client);
    }

    /** toUser 的 Number cast 失敗不是 WebClientException，也必須固定 503。 */
    @Test
    void wrongTypedBootstrapIdDoesNotLeakProjectionMessageOrSentinel() {
        String wrongId = "{\"id\":\"" + SENTINEL
                + "\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";
        WebTestClient client = client(wrongId, HttpStatus.OK, "{}", false);

        assertUnavailable(client);
    }

    @Test
    void bootstrapHttpAndTransportErrorsAreBothFixed503() {
        assertUnavailable(bootstrapFailureClient(false));
        assertUnavailable(bootstrapFailureClient(true));
    }

    private static void assertUnavailable(WebTestClient client) {
        client.get().uri("/api/public/trading-radar/today")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Trading radar unavailable")
                .jsonPath("$.detail").isEqualTo("主要管理者不可用")
                .consumeWith(result -> assertThat(text(result.getResponseBodyContent()))
                        .doesNotContain(SENTINEL, "ClassCastException", "JSON decode"));
    }

    private static WebTestClient client(
            String bootstrapBody, HttpStatus radarStatus, String radarBody, boolean transportFailure) {
        return client(bootstrapBody, radarStatus, radarBody, transportFailure, MediaType.APPLICATION_JSON);
    }

    private static WebTestClient client(
            String bootstrapBody, HttpStatus radarStatus, String radarBody, boolean transportFailure,
            MediaType radarContentType) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business.invalid")
                .exchangeFunction(request -> {
                    if ("/internal/users/configured-admin".equals(request.url().getPath())) {
                        if (bootstrapBody == null) {
                            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                        }
                        return Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(bootstrapBody).build());
                    }
                    if (transportFailure) {
                        return Mono.error(new WebClientException(SENTINEL) {});
                    }
                    ClientResponse.Builder response = ClientResponse.create(radarStatus)
                            .header(HttpHeaders.CONTENT_TYPE, radarContentType.toString())
                            .body(radarBody);
                    if (radarStatus.is3xxRedirection()) {
                        response.header(HttpHeaders.LOCATION,
                                "https://business.internal.invalid/" + SENTINEL);
                    }
                    return Mono.just(response.build());
                })
                .build();
        PublicTradingRadarService service =
                new PublicTradingRadarService(new BusinessUserClient(downstream), downstream);
        return WebTestClient.bindToController(new PublicTradingRadarController(service))
                .controllerAdvice(new PublicTradingRadarExceptionAdvice(), new BusinessErrorAdvice())
                .build();
    }

    private static WebTestClient bootstrapFailureClient(boolean transportFailure) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business.invalid")
                .exchangeFunction(request -> {
                    if (!"/internal/users/configured-admin".equals(request.url().getPath())) {
                        return Mono.error(new AssertionError("bootstrap 失敗後不可呼叫 radar downstream"));
                    }
                    if (transportFailure) {
                        return Mono.error(new WebClientException(SENTINEL) {});
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"detail\":\"" + SENTINEL + "\"}").build());
                })
                .build();
        PublicTradingRadarService service =
                new PublicTradingRadarService(new BusinessUserClient(downstream), downstream);
        return WebTestClient.bindToController(new PublicTradingRadarController(service))
                .controllerAdvice(new PublicTradingRadarExceptionAdvice(), new BusinessErrorAdvice())
                .build();
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private record Payload(MediaType contentType, String body) {}
}

package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.apierrorlogs.ApiErrorLogDiagnosticRenderer;
import com.steven.assets.bff.apierrorlogs.ApiErrorLogIngestClient;
import com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.HASH;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Requirement 163／Task 454.4–454.5：advice 輸出固定七欄 problem、所有回應帶 {@code Cache-Control: private, no-store}，
 * 且錯誤日誌除 409 {@code POLICY_UNSUPPORTED} 外每個應用錯誤恰記一次。
 */
class PublicSrppDailyContextControllerTest {

    private static final String URI = "/api/public/srpp/daily-context?tradingDate=2026-09-24&slot=09:05&policyBundleSha256=" + HASH;
    private static final String ADMIN = "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";

    private final AtomicReference<Mono<ClientResponse>> business = new AtomicReference<>();
    private ApiErrorLogIngestClient ingest;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        WebClient webClient = WebClient.builder().baseUrl("http://business.invalid")
                .exchangeFunction(request -> "/internal/users/configured-admin".equals(request.url().getPath())
                        ? Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(ADMIN).build())
                        : business.get())
                .build();
        PublicSrppDailyContextService service = new PublicSrppDailyContextService(new BusinessUserClient(webClient), webClient);
        ingest = mock(ApiErrorLogIngestClient.class);
        client = WebTestClient.bindToController(new PublicSrppDailyContextController(service))
                .controllerAdvice(new PublicSrppDailyContextExceptionAdvice())
                .webFilter(new PublicApiErrorCaptureWebFilter(ingest, new ApiErrorLogDiagnosticRenderer("")))
                .build();
    }

    @Test
    void successRelaysBusinessBytesWithNoStoreAndNoErrorLog() {
        String summary = text("summary-partial.json");
        business.set(Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(summary).build()));

        EntityExchangeResult<byte[]> result = client.get().uri(URI).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectHeader().doesNotExist(HttpHeaders.ETAG)
                .expectBody().returnResult();

        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8)).isEqualTo(summary);
        verifyNoInteractions(ingest);
    }

    @Test
    void policyUnsupportedIsNotLogged() {
        businessProblem(409, "POLICY_UNSUPPORTED");
        JsonNode body = problem(HttpStatus.CONFLICT, SrppProblemCatalog.POLICY_UNSUPPORTED);
        assertThat(body.get("retryable").booleanValue()).isFalse();
        verifyNoInteractions(ingest);
    }

    @Test
    void everyOtherApplicationErrorIsLoggedExactlyOnce() {
        client.get().uri(URI + "&foo=1").exchange();
        verifyLogged(400);

        businessProblem(404, "CONTEXT_NOT_FOUND");
        problem(HttpStatus.NOT_FOUND, SrppProblemCatalog.CONTEXT_NOT_FOUND);
        verifyLogged(404);

        businessProblem(409, "CONTEXT_STALE");
        problem(HttpStatus.CONFLICT, SrppProblemCatalog.CONTEXT_STALE);
        verifyLogged(409);

        businessProblem(500, "INTERNAL_ERROR");
        problem(HttpStatus.BAD_GATEWAY, SrppProblemCatalog.UPSTREAM_INVALID);
        verifyLogged(502);

        businessProblem(503, "CONTEXT_NOT_READY");
        JsonNode notReady = problem(HttpStatus.SERVICE_UNAVAILABLE, SrppProblemCatalog.CONTEXT_NOT_READY);
        assertThat(notReady.get("retryable").booleanValue()).isTrue();
        verifyLogged(503);
    }

    @Test
    void invalidRequestHasExactProblemShapeAndNoStore() {
        JsonNode body = problemAt(URI + "&foo=1", HttpStatus.BAD_REQUEST, SrppProblemCatalog.INVALID_REQUEST);
        assertThat(body.get("detail").textValue()).isEqualTo(SrppProblemCatalog.INVALID_REQUEST.detail());
    }

    private void businessProblem(int status, String code) {
        String body = "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":" + status
                + ",\"detail\":\"BUSINESS-LEAK\",\"instance\":\"/api/public/srpp/daily-context\",\"code\":\"" + code
                + "\",\"retryable\":false}";
        business.set(Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE).body(body).build()));
    }

    private JsonNode problem(HttpStatus status, SrppProblemCatalog expected) {
        return problemAt(URI, status, expected);
    }

    private JsonNode problemAt(String uri, HttpStatus status, SrppProblemCatalog expected) {
        byte[] bytes = client.get().uri(uri).exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectBody().returnResult().getResponseBody();
        JsonNode body = SrppDailyContextResponseValidator.parseStrict(bytes);
        List<String> fields = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("type", "title", "status", "detail", "instance", "code", "retryable");
        assertThat(body.get("type").textValue()).isEqualTo("about:blank");
        assertThat(body.get("status").intValue()).isEqualTo(status.value());
        assertThat(body.get("instance").textValue()).isEqualTo("/api/public/srpp/daily-context");
        assertThat(body.get("code").textValue()).isEqualTo(expected.name());
        assertThat(body.get("title").textValue()).isEqualTo(expected.title());
        assertThat(body.get("detail").textValue()).isEqualTo(expected.detail()).doesNotContain("BUSINESS-LEAK");
        return body;
    }

    private void verifyLogged(int status) {
        verify(ingest, times(1)).ingest(eq("OPEN_SRPP_DAILY_CONTEXT"), eq("SRPP 共用計算結果"), anyString(), anyString(),
                any(Instant.class), eq(status), eq((String) null));
    }
}

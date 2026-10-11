package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.apierrorlogs.ApiErrorLogIngestClient;
import com.steven.assets.bff.latestassets.LatestAssetsPublicService;
import com.steven.assets.bff.latestassets.LatestAssetsUnavailableException;
import com.steven.assets.bff.publiccalendar.PublicTradingCalendarRelay;
import com.steven.assets.bff.publiccalendar.PublicTradingCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real Netty HTTP checks: JSON shape, date/decimal semantics, and the existing strict SRPP boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.invalid"
})
class Boot41WebFluxWireTest {
    private static final WirePayload PAYLOAD = new WirePayload(
            new BigDecimal("12345678901234567890.123456789"), null,
            LocalDate.of(2026, 10, 11), Instant.parse("2026-10-11T01:05:00Z"));

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("businessServicesClient") WebClient businessClient;
    @Autowired GlobalCorsProperties cors;
    @MockitoBean PublicTradingCalendarService calendar;
    @MockitoBean SrppOwnerResolver owners;
    @MockitoBean ApiErrorLogIngestClient ingest;
    @MockitoBean LatestAssetsPublicService latestAssets;
    private WebTestClient browser;

    @BeforeEach
    void prepare() {
        browser = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        when(calendar.current(any())).thenReturn(Mono.just(
                new PublicTradingCalendarRelay(json.valueToTree(PAYLOAD))));
    }

    @Test
    void actualResponseKeepsExactDecimalsNullsAndIsoDates() throws Exception {
        byte[] bytes = browser.get().uri("/api/public/trading-calendar?year=2026").exchange()
                .expectStatus().isOk().expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().returnResult().getResponseBody();
        String wire = new String(bytes, StandardCharsets.UTF_8);
        assertThat(wire).contains("\"amount\":12345678901234567890.123456789", "\"missing\":null",
                "\"date\":\"2026-10-11\"", "\"observedAt\":\"2026-10-11T01:05:00Z\"");
        assertThat(json.readValue(bytes, WirePayload.class)).isEqualTo(PAYLOAD);
        assertThatThrownBy(() -> Class.forName("tools.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void bareAndCustomWebClientsUseJackson2OverRealHttp() {
        String base = "http://localhost:" + port;
        WirePayload bare = WebClient.builder().baseUrl(base).build().get()
                .uri("/api/public/trading-calendar?year=2026").retrieve().bodyToMono(WirePayload.class).block();
        WirePayload configured = businessClient.mutate().baseUrl(base).build().get()
                .uri("/api/public/trading-calendar?year=2026").retrieve().bodyToMono(WirePayload.class).block();
        JsonNode detached = WebClient.builder().baseUrl(base).build().get()
                .uri("/api/public/trading-calendar?year=2026").retrieve().bodyToMono(JsonNode.class).block();
        assertThat(bare).isEqualTo(PAYLOAD);
        assertThat(configured).isEqualTo(PAYLOAD);
        assertThat(detached.isObject()).isTrue();
        assertThat(detached.path("missing").isNull()).isTrue();
        assertThat(detached.path("date").textValue()).isEqualTo("2026-10-11");
    }

    @Test
    void springProblemDetailKeepsTheCompleteExistingHttpContract() {
        when(latestAssets.getLatest(null)).thenReturn(Mono.error(
                new LatestAssetsUnavailableException("主要管理者不可用")));
        browser.get().uri("/api/assets/latest").exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Latest assets unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("主要管理者不可用")
                .jsonPath("$.instance").isEqualTo("/api/assets/latest");
    }

    @Test
    void duplicateRequestKeysAreRejectedBeforeOwnerOrUpstreamWork() throws Exception {
        String duplicate = "{\"tradingDate\":\"2026-10-11\",\"tradingDate\":\"2026-10-12\","
                + "\"slot\":\"09:05\",\"policyBundleSha256\":\"" + "0".repeat(64)
                + "\",\"swaggerSha256\":\"" + "0".repeat(64) + "\"}";
        byte[] body = browser.post().uri(SrppCaptureController.DECISION).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(duplicate).exchange().expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult().getResponseBody();
        assertThat(json.readTree(body).path("code").textValue()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(owners, ingest);
    }

    @Test
    void oneMiBRequestStillReachesTheExistingOwnerGate() throws Exception {
        when(owners.resolve(null)).thenReturn(Mono.error(
                new SrppCaptureProblemException(SrppCaptureProblemCatalog.OWNER_UNAVAILABLE)));
        byte[] body = browser.post().uri(SrppCaptureController.DECISION).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestWithSize(1024 * 1024)).exchange().expectStatus().isEqualTo(503)
                .expectBody().returnResult().getResponseBody();
        assertThat(json.readTree(body).path("code").textValue()).isEqualTo("OWNER_UNAVAILABLE");
        verify(owners).resolve(null);
    }

    @Test
    void oneMiBPlusOneRequestKeepsBaselineRejectionBeforeOwnerOrBusinessReads() throws Exception {
        String oversized = requestWithSize(1024 * 1024 + 1);
        byte[] body = browser.post().uri(SrppCaptureController.DECISION).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(oversized).exchange().expectStatus().isEqualTo(500)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult().getResponseBody();
        assertThat(json.readTree(body).path("code").textValue()).isEqualTo("INTERNAL_ERROR");
        verifyNoInteractions(owners);
        verify(ingest).ingest(eq("OPEN_SRPP_DAILY_DECISION"), anyString(), anyString(),
                anyString(), any(Instant.class), eq(500), isNull());
    }

    @Test
    void gatewayCorsStillBindsTheSameOriginsAndCredentialsUnderTheNewPrefix() {
        var config = cors.getCorsConfigurations().get("/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost", "https://localhost",
                "http://localhost:5173", "https://asset-management.asuscomm.com");
        assertThat(config.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(config.getAllowCredentials()).isTrue();
    }

    private static String requestWithSize(int bytes) {
        String request = "{\"tradingDate\":\"2026-10-11\",\"slot\":\"09:05\",\"policyBundleSha256\":\""
                + "0".repeat(64) + "\",\"swaggerSha256\":\"" + "0".repeat(64) + "\"}";
        return request + " ".repeat(bytes - request.length());
    }

    public record WirePayload(BigDecimal amount, BigDecimal missing, LocalDate date, Instant observedAt) {}
}

package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Hostile A1 boundary checks selected by the official *OrderPlan* BFF Maven glob. */
class SrppOrderPlanBffTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final String QUERY = "?consumer=Codex&tradingDate=2026-10-14&reportSlot=12:00&policyBundleSha256=" + "a".repeat(64) + "&swaggerSha256=" + "b".repeat(64) + "&calculationRunId=11111111-1111-4111-8111-111111111111&decisions=A.0~ADOPT&asOf=2026-10-14T04:00:00Z";

    @Test void selectedPublicEmailBecomesOnlyBusinessOwnerHeadersAndIsNeverForwarded() {
        BusinessUserClient users = mock(BusinessUserClient.class);
        when(users.byEmail("selected@example.invalid")).thenReturn(Mono.just(new BffUser(9L, "selected@example.invalid", null, null, "USER", "ACTIVE", false)));
        WebClient business = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo("/internal/public-srpp/order-plan");
            assertThat(request.url().getQuery()).doesNotContain("email");
            assertThat(request.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("9");
            assertThat(request.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
            assertThat(request.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).header("Content-Type", "application/problem+json")
                    .body("{\"type\":\"about:blank\",\"title\":\"pending\",\"status\":503,\"detail\":\"pending\",\"instance\":\"/api/public/srpp/order-plan\",\"code\":\"CONTEXT_NOT_READY\",\"retryable\":true}").build());
        }).build();
        var controller = new PublicSrppProjectionController(new PublicSrppProjectionService(users, business, CLOCK));
        WebTestClient.bindToController(controller).controllerAdvice(new PublicSrppProjectionExceptionAdvice()).build().get()
                .uri(SrppProjectionQuery.ORDER + QUERY + "&email=selected@example.invalid").exchange().expectStatus().isEqualTo(503);
        verify(users).byEmail("selected@example.invalid"); verify(users, never()).configuredAdmin();
    }

    @Test void rejectsHostilePlanIdEvenWhenContentHashWasRecomputed() {
        SrppProjectionQuery query = query(); ObjectNode root = SrppProjectionQueryTest.orderProjection();
        root.put("planId", "f".repeat(64)); SrppProjectionQueryTest.rehashContentOnly(root);
        assertThatThrownBy(() -> PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON, root.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsHostileQuoteSourceBindingEvenWhenRootHashAndPlanIdAreValid() {
        SrppProjectionQuery query = query(); ObjectNode root = SrppProjectionQueryTest.orderProjection(); ObjectNode leg = (ObjectNode) root.path("legs").get(0);
        leg.put("limitPrice", "1"); leg.put("amount", "1"); ObjectNode capture = leg.putObject("quoteCapture");
        capture.put("sourceId", "market:美股:0050").put("sourceRevision", "r1").put("sourceSha256", "e".repeat(64)).put("capturedAt", "2026-10-14T03:00:00Z");
        capture.putObject("receiptRef").put("kind", "QUOTE").put("sha256", "e".repeat(64)).put("capturedAt", "2026-10-14T03:00:00Z"); SrppProjectionQueryTest.rehash(root);
        assertThatThrownBy(() -> PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON, root.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsSubunitReduceAmountAtTheBffBoundary() {
        var values = new LinkedMultiValueMap<String, String>();
        values.add("consumer", "Codex"); values.add("tradingDate", "2026-10-14"); values.add("reportSlot", "12:00");
        values.add("policyBundleSha256", "a".repeat(64)); values.add("swaggerSha256", "b".repeat(64));
        values.add("calculationRunId", "11111111-1111-4111-8111-111111111111"); values.add("decisions", "A.0~REDUCE~0.1~OTHER"); values.add("asOf", "2026-10-14T04:00:00Z");
        assertThatThrownBy(() -> SrppProjectionQuery.order(values, new org.springframework.http.HttpHeaders(), CLOCK)).isInstanceOf(SrppReportProblemException.class);
    }

    private static SrppProjectionQuery query() { return new SrppProjectionQuery(SrppProjectionQuery.ORDER, "/internal/public-srpp/order-plan", null, "Codex", "2026-10-14", "12:00", "a".repeat(64), "b".repeat(64), "11111111-1111-4111-8111-111111111111", "A.0~ADOPT", "2026-10-14T04:00:00Z", null); }
}

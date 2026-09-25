package com.steven.assets.bff.config;

import com.steven.assets.bff.publicsrpp.PublicSrppDailyContextExceptionAdvice;
import com.steven.assets.bff.publicsrpp.PublicSrppDailyContextService;
import com.steven.assets.bff.publicsrpp.SrppProblemCatalog;
import com.steven.assets.bff.publicsrpp.SrppProblemException;
import com.steven.assets.bff.publicsrpp.SrppTestFixtures;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.HASH;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.bytes;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.text;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Requirement 163／Task 454.5：query 400 零 outbound、owner selector、business 轉譯與原位元組回傳。 */
class PublicSrppDailyContextConfiguredAdminContextTest {

    private static final String ADMIN = "{\"id\":1,\"email\":\"owner@example.invalid\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";
    private static final String OTHER = "{\"id\":2,\"email\":\"selected@example.invalid\",\"role\":\"USER\",\"status\":\"ACTIVE\",\"protectedAdmin\":false}";
    private static final String LATEST = "?tradingDate=2026-09-24&slot=09:05&policyBundleSha256=" + HASH;
    private static final Duration SHORT = Duration.ofSeconds(1);

    private final List<ClientRequest> calls = new ArrayList<>();

    // ------------------------------------------------------------------ 400 matrix

    @Test
    void everyInvalidQueryIsRejectedWithZeroOutbound() {
        String ok = "tradingDate=2026-09-24&slot=09:05&policyBundleSha256=" + HASH;
        List<String> invalid = List.of(
                "",
                "slot=09:05&policyBundleSha256=" + HASH,
                ok + "&foo=1",
                ok + "&slot=09:05",
                ok + "&view=",
                ok + "&view",
                ok.replace("2026-09-24", "2026-02-30"),
                ok.replace("2026-09-24", "2026-9-24"),
                ok.replace("2026-09-24", "%202026-09-24"),
                ok.replace("09:05", "9:05"),
                ok.replace("09:05", "09:10"),
                ok.replace(HASH, HASH.toUpperCase()),
                ok.replace(HASH, HASH.substring(1)),
                ok + "&email=not-an-email",
                ok + "&email=%20selected@example.invalid",
                ok + "&email=selected@example.invalid%20",
                ok + "&email=" + "x".repeat(250) + "@a.co",
                ok + "&view=other",
                ok + "&packageId=7E1FB982-4AE4-4E7E-BAA5-C2C80BD93491",
                ok + "&view=evidence&packageId=7e1fb982-4ae4-4e7e-baa5-c2c80bd93491",
                ok + "&view=evidence&sourceId=assets",
                ok + "&view=evidence&packageId=7e1fb982-4ae4-4e7e-baa5-c2c80bd93491&sourceId=Assets",
                ok + "&sourceId=assets");
        PublicSrppDailyContextService service = service(request -> json(HttpStatus.OK, "{}"));
        for (String query : invalid) {
            Throwable error = catchThrowable(() -> service.read(get("?" + query)).block());
            assertThat(problemOf(error)).as(query).isEqualTo(SrppProblemCatalog.INVALID_REQUEST);
        }
        MockServerHttpRequest withBody = MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/public/srpp/daily-context" + LATEST))
                .header(HttpHeaders.CONTENT_LENGTH, "2").build();
        assertThat(problemOf(catchThrowable(() -> service.read(withBody).block()))).isEqualTo(SrppProblemCatalog.INVALID_REQUEST);
        MockServerHttpRequest chunked = MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/public/srpp/daily-context" + LATEST))
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked").build();
        assertThat(problemOf(catchThrowable(() -> service.read(chunked).block()))).isEqualTo(SrppProblemCatalog.INVALID_REQUEST);
        assertThat(calls).isEmpty();
    }

    // ------------------------------------------------------------------ owner

    @Test
    void omittedEmailUsesConfiguredAdminAndIgnoresCallerIdentity() {
        PublicSrppDailyContextService service = service(request -> json(HttpStatus.OK, text("summary-partial.json")));

        byte[] body = service.read(get(LATEST))
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                .block();

        assertThat(body).isEqualTo(text("summary-partial.json").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(paths()).containsExactly("/internal/users/configured-admin", "/internal/public-srpp/daily-context");
        ClientRequest business = businessCall();
        assertThat(business.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("1");
        assertThat(business.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("ADMIN");
        assertThat(business.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
        assertThat(UriComponentsBuilder.fromUri(business.url()).build().getQueryParams().toSingleValueMap())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("tradingDate", "2026-09-24", "slot", "09:05",
                        "policyBundleSha256", HASH, "view", "summary"));
    }

    @Test
    void emailSelectsActiveNonAdminAccountAndIsNeverForwarded() {
        PublicSrppDailyContextService service = service(request -> json(HttpStatus.OK, text("summary-partial.json")));

        service.read(get(LATEST + "&email=selected@example.invalid"))
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(1L, "ADMIN", "ACTIVE")))
                .block();

        assertThat(paths()).containsExactly("/internal/users/by-email", "/internal/public-srpp/daily-context");
        assertThat(calls.get(0).headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
        ClientRequest business = businessCall();
        assertThat(business.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("2");
        assertThat(business.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
        assertThat(business.url().toString()).doesNotContain("email").doesNotContain("selected");
    }

    @Test
    void callerSuppliedIdentityHeadersDoNotChangeOwner() {
        PublicSrppDailyContextService service = service(request -> json(HttpStatus.OK, text("summary-partial.json")));
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/public/srpp/daily-context" + LATEST))
                .header(AuthConstants.HDR_USER_ID, "77").header(AuthConstants.HDR_USER_ROLE, "USER").build();

        service.read(request).block();

        assertThat(businessCall().headers().get(AuthConstants.HDR_USER_ID)).containsExactly("1");
    }

    @Test
    void missingInactiveTimedOutAndFailedOwnersAreIndistinguishable() {
        List<Throwable> errors = new ArrayList<>();
        errors.add(catchThrowable(() -> serviceWithLookup(r -> emptyOk()).read(get(LATEST + "&email=missing@example.invalid")).block()));
        errors.add(catchThrowable(() -> serviceWithLookup(r -> json(HttpStatus.OK,
                "{\"id\":2,\"role\":\"USER\",\"status\":\"DISABLED\",\"protectedAdmin\":false}"))
                .read(get(LATEST + "&email=disabled@example.invalid")).block()));
        errors.add(catchThrowable(() -> serviceWithLookup(r -> Mono.never()).read(get(LATEST + "&email=slow@example.invalid")).block()));
        errors.add(catchThrowable(() -> serviceWithLookup(r -> json(HttpStatus.INTERNAL_SERVER_ERROR, "{\"detail\":\"secret\"}"))
                .read(get(LATEST + "&email=broken@example.invalid")).block()));
        errors.add(catchThrowable(() -> serviceWithLookup(r -> Mono.never()).read(get(LATEST)).block()));
        errors.add(catchThrowable(() -> serviceWithLookup(r -> json(HttpStatus.OK,
                "{\"id\":3,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":false}")).read(get(LATEST)).block()));
        errors.add(catchThrowable(() -> serviceWithLookup(r -> emptyOk()).read(get(LATEST)).block()));

        PublicSrppDailyContextExceptionAdvice advice = new PublicSrppDailyContextExceptionAdvice();
        List<String> rendered = new ArrayList<>();
        for (Throwable error : errors) {
            assertThat(problemOf(error)).isEqualTo(SrppProblemCatalog.OWNER_UNAVAILABLE);
            ResponseEntity<PublicSrppDailyContextExceptionAdvice.SrppProblemBody> response = advice.problem(
                    (SrppProblemException) error, MockServerWebExchange.from(get(LATEST)));
            rendered.add(response.getStatusCode() + "|" + response.getHeaders() + "|"
                    + new String(bytes(SrppTestFixtures.JSON.valueToTree(response.getBody()))));
        }
        assertThat(rendered).hasSize(7).containsOnly(rendered.get(0));
        assertThat(paths()).doesNotContain("/internal/public-srpp/daily-context");
    }

    // ------------------------------------------------------------------ business translation

    @Test
    void wellFormedBusinessProblemsAreReEmittedWithTheSameCode() {
        assertThat(businessProblem(409, "POLICY_UNSUPPORTED")).isEqualTo(SrppProblemCatalog.POLICY_UNSUPPORTED);
        assertThat(businessProblem(409, "CONTEXT_STALE")).isEqualTo(SrppProblemCatalog.CONTEXT_STALE);
        assertThat(businessProblem(404, "CONTEXT_NOT_FOUND")).isEqualTo(SrppProblemCatalog.CONTEXT_NOT_FOUND);
        assertThat(businessProblem(404, "SOURCE_EVIDENCE_NOT_FOUND")).isEqualTo(SrppProblemCatalog.SOURCE_EVIDENCE_NOT_FOUND);
        assertThat(businessProblem(503, "CALENDAR_UNAVAILABLE")).isEqualTo(SrppProblemCatalog.CALENDAR_UNAVAILABLE);
        assertThat(businessProblem(503, "CONTEXT_NOT_READY")).isEqualTo(SrppProblemCatalog.CONTEXT_NOT_READY);
        assertThat(businessProblem(400, "INVALID_REQUEST")).isEqualTo(SrppProblemCatalog.INVALID_REQUEST);
    }

    @Test
    void malformedOrMismatchedBusinessErrorsBecomeUpstreamInvalid() {
        assertThat(businessProblem(409, "CONTEXT_NOT_FOUND")).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        assertThat(businessProblem(500, "INTERNAL_ERROR")).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        assertThat(businessProblem(502, "UPSTREAM_INVALID")).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        assertThat(businessProblem(409, "SOMETHING_ELSE")).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        String mismatchedStatus = problemJson(409, "POLICY_UNSUPPORTED").replace("\"status\":409", "\"status\":404");
        assertThat(translate(409, MediaType.APPLICATION_PROBLEM_JSON, mismatchedStatus)).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        String extraField = problemJson(409, "POLICY_UNSUPPORTED").replace("}", ",\"extra\":1}");
        assertThat(translate(409, MediaType.APPLICATION_PROBLEM_JSON, extraField)).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        assertThat(translate(409, MediaType.APPLICATION_JSON, problemJson(409, "POLICY_UNSUPPORTED")))
                .isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        assertThat(translate(409, MediaType.APPLICATION_PROBLEM_JSON, "<html>")).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
    }

    @Test
    void timeoutAndConnectionFailureAreContextNotReady() {
        Throwable timeout = catchThrowable(() -> service(request -> Mono.never()).read(get(LATEST)).block());
        assertThat(problemOf(timeout)).isEqualTo(SrppProblemCatalog.CONTEXT_NOT_READY);
        Throwable refused = catchThrowable(() -> service(request -> Mono.error(new WebClientRequestException(
                new IOException("refused"), HttpMethod.GET, URI.create("http://business.invalid/x"), HttpHeaders.EMPTY)))
                .read(get(LATEST)).block());
        assertThat(problemOf(refused)).isEqualTo(SrppProblemCatalog.CONTEXT_NOT_READY);
    }

    @Test
    void invalidSuccessPayloadIsUpstreamInvalid() {
        var root = tree("summary-stale-pinned.json");
        Throwable stale = catchThrowable(() -> service(request -> json(HttpStatus.OK, new String(bytes(root))))
                .read(get(LATEST)).block());
        assertThat(problemOf(stale)).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
        Throwable html = catchThrowable(() -> service(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/html").body("<html>").build())).read(get(LATEST)).block());
        assertThat(problemOf(html)).isEqualTo(SrppProblemCatalog.UPSTREAM_INVALID);
    }

    @Test
    void evidenceSuccessIsRelayedByteForByte() {
        String evidence = text("evidence-assets.json");
        byte[] body = service(request -> json(HttpStatus.OK, evidence))
                .read(get(LATEST + "&view=evidence&packageId=7e1fb982-4ae4-4e7e-baa5-c2c80bd93491&sourceId=assets"))
                .block();
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(evidence);
        assertThat(businessCall().url().getQuery()).contains("view=evidence").contains("sourceId=assets")
                .contains("packageId=7e1fb982-4ae4-4e7e-baa5-c2c80bd93491");
    }

    // ------------------------------------------------------------------ helpers

    private SrppProblemCatalog businessProblem(int status, String code) {
        return translate(status, MediaType.APPLICATION_PROBLEM_JSON, problemJson(status, code));
    }

    private SrppProblemCatalog translate(int status, MediaType type, String body) {
        calls.clear();
        Throwable error = catchThrowable(() -> service(request -> Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", type.toString()).body(body).build())).read(get(LATEST)).block());
        return problemOf(error);
    }

    static String problemJson(int status, String code) {
        return "{\"type\":\"about:blank\",\"title\":\"Upstream title\",\"status\":" + status
                + ",\"detail\":\"上游文字不得外洩\",\"instance\":\"/api/public/srpp/daily-context\",\"code\":\"" + code
                + "\",\"retryable\":false}";
    }

    private static SrppProblemCatalog problemOf(Throwable error) {
        assertThat(error).isInstanceOf(SrppProblemException.class);
        return ((SrppProblemException) error).problem();
    }

    private static MockServerHttpRequest get(String query) {
        return MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/public/srpp/daily-context" + query)).build();
    }

    private List<String> paths() {
        return calls.stream().map(r -> r.url().getPath()).toList();
    }

    private ClientRequest businessCall() {
        return calls.stream().filter(r -> "/internal/public-srpp/daily-context".equals(r.url().getPath()))
                .findFirst().orElseThrow();
    }

    private static Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
    }

    private static Mono<ClientResponse> emptyOk() {
        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).build());
    }

    private PublicSrppDailyContextService service(Function<ClientRequest, Mono<ClientResponse>> business) {
        return build(request -> {
            String path = request.url().getPath();
            if ("/internal/users/configured-admin".equals(path)) return json(HttpStatus.OK, ADMIN);
            if ("/internal/users/by-email".equals(path)) return json(HttpStatus.OK, OTHER);
            return business.apply(request);
        });
    }

    private PublicSrppDailyContextService serviceWithLookup(Function<ClientRequest, Mono<ClientResponse>> lookup) {
        return build(request -> {
            String path = request.url().getPath();
            if (path.startsWith("/internal/users/")) return lookup.apply(request);
            return json(HttpStatus.OK, text("summary-partial.json"));
        });
    }

    private PublicSrppDailyContextService build(Function<ClientRequest, Mono<ClientResponse>> exchange) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business.invalid")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    calls.add(request);
                    return exchange.apply(request);
                })
                .build();
        return new PublicSrppDailyContextService(new BusinessUserClient(client), client, SHORT, SHORT);
    }
}

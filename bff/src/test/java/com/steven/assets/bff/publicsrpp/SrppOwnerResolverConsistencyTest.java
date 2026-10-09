package com.steven.assets.bff.publicsrpp;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requirement 184／Task 484.7：{@link SrppOwnerResolver} 與既有 {@code PublicSrppOrchestratedService.owner}（private）
 * 對同一組 {@link BusinessUserClient} mock 的外部可觀察行為必須一致：成功時送給 business 的 X-User-* headers 相同、
 * caller 的 Reactor identity 被清除；失敗時都是 {@code OWNER_UNAVAILABLE}。舊方法只能經 {@code read()} 觀察。
 */
class SrppOwnerResolverConsistencyTest {
    private static final String HASH = "0".repeat(64);
    private static final String QUERY = "/api/public/srpp/calculation-context?tradingDate=2026-09-24&slot=09:05&policyBundleSha256=" + HASH;
    private static final TenantIdentity CALLER = new TenantIdentity(99L, "ADMIN", "ACTIVE");
    private static final String OWNER_UNAVAILABLE = "OWNER_UNAVAILABLE";

    /** business 被呼叫時記下 headers 與當下 Reactor context 是否仍帶 caller identity，然後一律中止。 */
    private record Call(String userId, String role, String status, boolean callerIdentityPresent) {}
    private record Outcome(String result, List<Call> calls) {}

    private BusinessUserClient users;
    private final List<Call> calls = new ArrayList<>();
    private WebClient business;

    @BeforeEach
    void setUp() {
        users = mock(BusinessUserClient.class);
        business = WebClient.builder().baseUrl("http://business.invalid")
                .exchangeFunction(request -> Mono.deferContextual(ctx -> {
                    calls.add(call(request, ctx.hasKey(AuthConstants.CTX_IDENTITY)));
                    return Mono.error(new IllegalStateException("stop after owner stage"));
                }))
                .build();
    }

    private static Call call(ClientRequest request, boolean identity) {
        return new Call(request.headers().getFirst(AuthConstants.HDR_USER_ID), request.headers().getFirst(AuthConstants.HDR_USER_ROLE),
                request.headers().getFirst(AuthConstants.HDR_USER_STATUS), identity);
    }

    private static BffUser user(Long id, String role, String status, boolean configuredAdmin) {
        return new BffUser(id, "u" + id + "@example.invalid", "name", null, role, status, configuredAdmin);
    }

    enum Scenario {
        CONFIGURED_ADMIN(null) { void stub(BusinessUserClient u) { when(u.configuredAdmin()).thenReturn(Mono.just(user(1L, "ADMIN", "ACTIVE", true))); } },
        BY_EMAIL_ACTIVE("selected@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("selected@example.invalid")).thenReturn(Mono.just(user(2L, "USER", "ACTIVE", false))); } },
        BY_EMAIL_CONFIGURED_ADMIN("owner@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("owner@example.invalid")).thenReturn(Mono.just(user(1L, "ADMIN", "ACTIVE", true))); } },
        BY_EMAIL_NOT_ACTIVE("selected@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("selected@example.invalid")).thenReturn(Mono.just(user(2L, "USER", "DISABLED", false))); } },
        DEFAULT_NOT_CONFIGURED_ADMIN(null) { void stub(BusinessUserClient u) { when(u.configuredAdmin()).thenReturn(Mono.just(user(3L, "ADMIN", "ACTIVE", false))); } },
        DEFAULT_NOT_ACTIVE(null) { void stub(BusinessUserClient u) { when(u.configuredAdmin()).thenReturn(Mono.just(user(1L, "ADMIN", "PENDING", true))); } },
        DEFAULT_EMPTY(null) { void stub(BusinessUserClient u) { when(u.configuredAdmin()).thenReturn(Mono.empty()); } },
        BY_EMAIL_EMPTY("selected@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("selected@example.invalid")).thenReturn(Mono.empty()); } },
        DEFAULT_ERROR(null) { void stub(BusinessUserClient u) { when(u.configuredAdmin()).thenReturn(Mono.error(new IllegalStateException("lookup failed"))); } },
        BY_EMAIL_ERROR("selected@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("selected@example.invalid")).thenReturn(Mono.error(new IllegalStateException("lookup failed"))); } },
        NULL_ID(null) { void stub(BusinessUserClient u) { when(u.configuredAdmin()).thenReturn(Mono.just(user(null, "ADMIN", "ACTIVE", true))); } },
        NULL_ROLE("selected@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("selected@example.invalid")).thenReturn(Mono.just(user(2L, null, "ACTIVE", false))); } },
        NULL_STATUS("selected@example.invalid") { void stub(BusinessUserClient u) { when(u.byEmail("selected@example.invalid")).thenReturn(Mono.just(user(2L, "USER", null, false))); } };

        final String email;
        Scenario(String email) { this.email = email; }
        abstract void stub(BusinessUserClient users);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Scenario.class)
    void resolverMatchesExistingOrchestratedOwnerBehaviour(Scenario scenario) {
        scenario.stub(users);

        Outcome legacy = run(() -> legacy(scenario.email));
        Outcome resolver = run(() -> resolver(scenario.email));

        assertThat(resolver).isEqualTo(legacy);
        if (!legacy.result().equals(OWNER_UNAVAILABLE)) {
            assertThat(legacy.calls()).singleElement().satisfies(c -> {
                assertThat(c.userId()).isNotNull();
                assertThat(c.callerIdentityPresent()).isFalse();
            });
        }
    }

    @ParameterizedTest(name = "{0} 逾時 5 秒 → OWNER_UNAVAILABLE")
    @EnumSource(value = Scenario.class, names = {"CONFIGURED_ADMIN", "BY_EMAIL_ACTIVE"})
    void lookupTimeoutIsOwnerUnavailableForBoth(Scenario scenario) {
        when(users.configuredAdmin()).thenReturn(Mono.never());
        when(users.byEmail(scenario.email == null ? "unused@example.invalid" : scenario.email)).thenReturn(Mono.never());

        for (Supplier<Mono<?>> call : List.<Supplier<Mono<?>>>of(() -> legacy(scenario.email), () -> resolver(scenario.email))) {
            StepVerifier.withVirtualTime(call::get)
                    .expectSubscription()
                    .expectNoEvent(Duration.ofSeconds(4))
                    .thenAwait(Duration.ofSeconds(2))
                    .expectErrorSatisfies(error -> assertThat(code(error)).isEqualTo(OWNER_UNAVAILABLE))
                    .verify(Duration.ofSeconds(5));
        }
        assertThat(calls).isEmpty();
    }

    private Mono<?> legacy(String email) {
        PublicSrppOrchestratedService service = new PublicSrppOrchestratedService(users, business);
        String uri = email == null ? QUERY : QUERY + "&email=" + email;
        return service.read(MockServerHttpRequest.get(uri).build(), SrppOrchestratedQuery.Route.CONTEXT)
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, CALLER));
    }

    private Mono<?> resolver(String email) {
        SrppOwnerResolver resolver = new SrppOwnerResolver(users);
        return resolver.resolve(email)
                .flatMap(owner -> SrppOwnerResolver.withoutCallerIdentity(business.post().uri("/internal/srpp/probe")
                        .headers(SrppOwnerResolver.ownerHeaders(owner)).exchangeToMono(response -> Mono.just("unreachable"))))
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, CALLER));
    }

    private Outcome run(Supplier<Mono<?>> call) {
        calls.clear();
        String result;
        try {
            call.get().block(Duration.ofSeconds(5));
            result = "UNEXPECTED_SUCCESS";
        } catch (RuntimeException error) {
            String code = code(error);
            result = OWNER_UNAVAILABLE.equals(code) ? OWNER_UNAVAILABLE : "OWNER_RESOLVED";
        }
        return new Outcome(result, List.copyOf(calls));
    }

    private static String code(Throwable error) {
        if (error instanceof SrppOrchestratedProblemException problem) return problem.problem().name();
        if (error instanceof SrppCaptureProblemException problem) return problem.problem().name();
        return error.getClass().getSimpleName();
    }
}

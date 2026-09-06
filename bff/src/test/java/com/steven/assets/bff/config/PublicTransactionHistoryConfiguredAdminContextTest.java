package com.steven.assets.bff.config;

import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.publictransaction.PublicTransactionHistoryEmailRequestException;
import com.steven.assets.bff.publictransaction.PublicTransactionHistoryExceptionAdvice;
import com.steven.assets.bff.publictransaction.PublicTransactionHistoryRequestException;
import com.steven.assets.bff.publictransaction.PublicTransactionHistoryService;
import com.steven.assets.bff.publictransaction.PublicTransactionHistoryUnavailableException;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Requirement 112: configured admin, not Reactor caller identity, selects the ledger owner.
 *
 * <p>Requirement 140：{@code email} 分支的案例併入本檔，不新建平行測試檔。
 */
class PublicTransactionHistoryConfiguredAdminContextTest {

    private static final String ADMIN_JSON =
            "{\"id\":7,\"email\":\"owner@example.invalid\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";
    private static final String OTHER_ACCOUNT_JSON =
            "{\"id\":2,\"email\":\"selected@example.invalid\",\"role\":\"USER\",\"status\":\"ACTIVE\",\"protectedAdmin\":false}";
    private static final String OTHER_TRANSACTION_JSON =
            "{\"selection\":{\"mode\":\"ALL\",\"year\":null,\"start\":null,\"end\":null},"
            + "\"allTimeSummary\":{\"buyCount\":3,\"sellCount\":1,\"totalBuyAmountTwd\":1000,\"totalSellAmountTwd\":500},"
            + "\"summary\":{\"buyCount\":3,\"sellCount\":1,\"totalBuyAmountTwd\":1000,\"totalSellAmountTwd\":500},"
            + "\"yearSummaries\":[],\"records\":[]}";

    private record Call(String uri, String userId, String role, String status) {}

    @Test
    void selectionUsesConfiguredAdminHeadersAndForwardsOnlyValidatedFilter() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = server(calls);
        try {
            WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter()).build();
            PublicTransactionHistoryService service =
                    new PublicTransactionHistoryService(new BusinessUserClient(client), client);

            var relay = service.current(List.of("2026"), null, null, null)
                    .contextWrite(Context.of(AuthConstants.CTX_IDENTITY,
                            new TenantIdentity(99L, "USER", "ACTIVE")))
                    .block();

            assertThat(relay).isNotNull();
            assertThat(relay.body().path("selection").path("mode").asText()).isEqualTo("ALL");
            assertThat(calls).hasSize(2);
            assertThat(calls.getFirst()).satisfies(call -> {
                assertThat(call.uri()).isEqualTo("/internal/users/configured-admin");
                assertThat(call.userId()).isNull();
            });
            assertThat(calls.get(1)).satisfies(call -> {
                assertThat(call.uri()).isEqualTo("/internal/public-transaction-history/current?year=2026");
                assertThat(call.userId()).isEqualTo("7");
                assertThat(call.role()).isEqualTo("ADMIN");
                assertThat(call.status()).isEqualTo("ACTIVE");
            });
        } finally {
            server.disposeNow();
        }
    }

    /** 情境 2：合法 email 對應一個 active 且非 configured-admin 的帳號，資料可觀察地屬於該帳號。 */
    @Test
    void validEmailForActiveNonConfiguredAdminAccountReturnsThatAccountsData() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = serverWithByEmail(calls, OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_TRANSACTION_JSON);
        try {
            WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter()).build();
            PublicTransactionHistoryService service =
                    new PublicTransactionHistoryService(new BusinessUserClient(client), client);

            var relay = service.current(null, null, null, " selected@example.invalid ").block();

            assertThat(relay).isNotNull();
            assertThat(relay.body().path("allTimeSummary").path("buyCount").asInt()).isEqualTo(3);
            List<Call> byEmailCalls = calls.stream()
                    .filter(c -> c.uri().startsWith("/internal/users/by-email")).toList();
            assertThat(byEmailCalls).hasSize(1);
            assertThat(URLDecoder.decode(byEmailCalls.getFirst().uri(), StandardCharsets.UTF_8))
                    .isEqualTo("/internal/users/by-email?email=selected@example.invalid");
            assertNoTenantHeaders(byEmailCalls.getFirst());
            List<Call> ledgerCalls = calls.stream()
                    .filter(c -> "/internal/public-transaction-history/current".equals(c.uri())).toList();
            assertThat(ledgerCalls).hasSize(1);
            assertThat(ledgerCalls.getFirst().userId()).isEqualTo("2");
            assertThat(ledgerCalls.getFirst().role()).isEqualTo("USER");
            assertThat(ledgerCalls.getFirst().status()).isEqualTo("ACTIVE");
        } finally {
            server.disposeNow();
        }
    }

    /** email lookup 的所有失敗（含 business 的實際 200 空 body）都必須是同一個 canonical 503。 */
    @Test
    void emailLookupFailuresFailClosedWithIdenticalContract() {
        PublicTransactionHistoryService notFound = serviceForLookup(request -> emptyLookupResponse());
        PublicTransactionHistoryService inactive = serviceForLookup(request -> lookupResponse(HttpStatus.OK,
                "{\"id\":2,\"role\":\"USER\",\"status\":\"DISABLED\",\"protectedAdmin\":false}"));
        PublicTransactionHistoryService client4xx = serviceForLookup(request -> lookupResponse(HttpStatus.BAD_REQUEST,
                "{\"detail\":\"lookup body must not escape\"}"));
        PublicTransactionHistoryService server5xx = serviceForLookup(request -> lookupResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"detail\":\"lookup body must not escape\"}"));
        PublicTransactionHistoryService malformed = serviceForLookup(request -> lookupResponse(HttpStatus.OK, "not-json"));
        PublicTransactionHistoryService transport = serviceForLookup(request -> Mono.error(new WebClientRequestException(
                new IOException("synthetic lookup transport failure"), HttpMethod.GET,
                URI.create("http://business.invalid/internal/users/by-email"), HttpHeaders.EMPTY)));
        PublicTransactionHistoryService timeout = serviceForLookup(request -> Mono.never());

        List<Throwable> failures = List.of(
                catchThrowable(() -> notFound.current(null, null, null, "missing@example.invalid").block()),
                catchThrowable(() -> inactive.current(null, null, null, "disabled@example.invalid").block()),
                catchThrowable(() -> client4xx.current(null, null, null, "selected@example.invalid").block()),
                catchThrowable(() -> server5xx.current(null, null, null, "selected@example.invalid").block()),
                catchThrowable(() -> malformed.current(null, null, null, "selected@example.invalid").block()),
                catchThrowable(() -> transport.current(null, null, null, "selected@example.invalid").block()),
                catchThrowable(() -> timeout.current(null, null, null, "selected@example.invalid").block()));

        var advice = new PublicTransactionHistoryExceptionAdvice();
        List<String> contracts = failures.stream().map(error -> unavailableContract(advice, error)).toList();
        assertThat(contracts).containsOnly("503|Transaction history unavailable|交易紀錄服務暫時不可用");
    }

    /** 情境 5：格式不合法的 email（缺 @、缺網域、超過 254 字元）在呼叫 business 前就地拒絕。 */
    @Test
    void malformedEmailFailsFastWithoutAnyNetworkCall() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = serverWithByEmail(calls, null, HttpStatus.OK,
                PublicContractJsonFixtures.TRANSACTION_HISTORY);
        try {
            WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter()).build();
            PublicTransactionHistoryService service =
                    new PublicTransactionHistoryService(new BusinessUserClient(client), client);
            String tooLong = "x".repeat(250) + "@a.co";
            assertThat(tooLong.length()).isGreaterThan(254);

            assertThatThrownBy(() -> service.current(null, null, null, "not-an-email").block())
                    .isInstanceOf(PublicTransactionHistoryEmailRequestException.class);
            assertThatThrownBy(() -> service.current(null, null, null, "missing-domain@").block())
                    .isInstanceOf(PublicTransactionHistoryEmailRequestException.class);
            assertThatThrownBy(() -> service.current(null, null, null, tooLong).block())
                    .isInstanceOf(PublicTransactionHistoryEmailRequestException.class);
            // email 格式錯誤優先於 filter；email 合法後既有 filter 錯誤仍保持原契約。
            assertThatThrownBy(() -> service.current(List.of("bad"), null, null, "not-an-email").block())
                    .isInstanceOf(PublicTransactionHistoryEmailRequestException.class);
            assertThatThrownBy(() -> service.current(List.of("bad"), null, null, "selected@example.invalid").block())
                    .isInstanceOf(PublicTransactionHistoryRequestException.class);
            assertThat(calls).isEmpty();

            var problem = new PublicTransactionHistoryExceptionAdvice()
                    .invalidEmail(new PublicTransactionHistoryEmailRequestException()).getBody();
            assertThat(problem.getDetail()).isEqualTo("email 格式不合法");
        } finally {
            server.disposeNow();
        }
    }

    /** 情境 6：context 已有另一個登入者／代看者身分時，email 分支下游收到的仍是 email 解析出的帳號。 */
    @Test
    void emailBranchIgnoresReactorContextIdentity() {
        List<Call> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = serverWithByEmail(calls, OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_TRANSACTION_JSON);
        try {
            WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter()).build();
            PublicTransactionHistoryService service =
                    new PublicTransactionHistoryService(new BusinessUserClient(client), client);

            service.current(null, null, null, "selected@example.invalid")
                    .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                    .block();

            List<Call> byEmailCalls = calls.stream()
                    .filter(c -> c.uri().startsWith("/internal/users/by-email")).toList();
            assertThat(byEmailCalls).hasSize(1);
            assertNoTenantHeaders(byEmailCalls.getFirst());
            List<Call> ledgerCalls = calls.stream()
                    .filter(c -> "/internal/public-transaction-history/current".equals(c.uri())).toList();
            assertThat(ledgerCalls).hasSize(1);
            assertThat(ledgerCalls.getFirst().userId()).isEqualTo("2");
            assertThat(ledgerCalls.getFirst().role()).isEqualTo("USER");
            assertThat(ledgerCalls.getFirst().status()).isEqualTo("ACTIVE");
        } finally {
            server.disposeNow();
        }
    }

    private static DisposableServer server(List<Call> calls) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    calls.add(new Call(request.uri(),
                            request.requestHeaders().get(AuthConstants.HDR_USER_ID),
                            request.requestHeaders().get(AuthConstants.HDR_USER_ROLE),
                            request.requestHeaders().get(AuthConstants.HDR_USER_STATUS)));
                    boolean bootstrap = "/internal/users/configured-admin".equals(request.uri());
                    response.status(HttpStatus.OK.value()).header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    return response.sendString(reactor.core.publisher.Mono.just(bootstrap ? ADMIN_JSON
                            : PublicContractJsonFixtures.TRANSACTION_HISTORY));
                }).bindNow();
    }

    /**
     * Requirement 140 email 分支專用替身：{@code /internal/users/by-email} 回 {@code byEmailBody}
     * （{@code null} 模擬 business 實際的 200 空 body 查無帳號）；
     * {@code /internal/users/configured-admin} 與 ledger 端點回 {@code ledgerBody}
     * （情境 3／4／5 不會真的打到 ledger 這一步）。
     */
    private static DisposableServer serverWithByEmail(
            List<Call> calls, String byEmailBody, HttpStatus byEmailStatus, String ledgerBody) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    calls.add(new Call(request.uri(),
                            request.requestHeaders().get(AuthConstants.HDR_USER_ID),
                            request.requestHeaders().get(AuthConstants.HDR_USER_ROLE),
                            request.requestHeaders().get(AuthConstants.HDR_USER_STATUS)));
                    boolean byEmail = request.uri().startsWith("/internal/users/by-email");
                    if (byEmail) {
                        if (byEmailBody == null) {
                            response.status(HttpStatus.OK.value())
                                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                            return response.send();
                        }
                        response.status(byEmailStatus.value()).header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                        return response.sendString(Mono.just(byEmailBody));
                    }
                    response.status(HttpStatus.OK.value()).header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    return response.sendString(Mono.just(ledgerBody));
                }).bindNow();
    }

    private static PublicTransactionHistoryService serviceForLookup(
            Function<ClientRequest, Mono<ClientResponse>> byEmailResponse) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business.invalid")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    if (request.url().getPath().equals("/internal/users/by-email")) {
                        return byEmailResponse.apply(request);
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(PublicContractJsonFixtures.TRANSACTION_HISTORY).build());
                })
                .build();
        return new PublicTransactionHistoryService(new BusinessUserClient(client), client);
    }

    private static Mono<ClientResponse> emptyLookupResponse() {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).build());
    }

    private static Mono<ClientResponse> lookupResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }

    private static void assertNoTenantHeaders(Call request) {
        assertThat(request.userId()).isNull();
        assertThat(request.role()).isNull();
        assertThat(request.status()).isNull();
    }

    private static String unavailableContract(PublicTransactionHistoryExceptionAdvice advice, Throwable error) {
        assertThat(error).isInstanceOf(PublicTransactionHistoryUnavailableException.class);
        var response = advice.unavailable((PublicTransactionHistoryUnavailableException) error);
        return response.getStatusCode().value() + "|" + response.getBody().getTitle()
                + "|" + response.getBody().getDetail();
    }
}

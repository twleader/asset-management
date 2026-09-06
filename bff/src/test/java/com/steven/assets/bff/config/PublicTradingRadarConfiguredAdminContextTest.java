package com.steven.assets.bff.config;

import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import com.steven.assets.bff.tradingradar.PublicTradingRadarEmailRequestException;
import com.steven.assets.bff.tradingradar.PublicTradingRadarExceptionAdvice;
import com.steven.assets.bff.tradingradar.PublicTradingRadarRequestException;
import com.steven.assets.bff.tradingradar.PublicTradingRadarService;
import com.steven.assets.bff.tradingradar.PublicTradingRadarUnavailableException;
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
 * Requirement 86：configured-admin headers 不得被匿名 caller 或代看 context 覆寫。
 *
 * <p>Requirement 140：{@code email} 分支的案例併入本檔，不新建平行測試檔。
 */
class PublicTradingRadarConfiguredAdminContextTest {

    private static final String ADMIN_JSON = """
            {"id":1,"email":"owner@example.invalid","role":"ADMIN","status":"ACTIVE","protectedAdmin":true}
            """;
    private static final String RADAR_JSON = PublicContractJsonFixtures.TRADING_RADAR_LIST;
    private static final String OTHER_ACCOUNT_JSON = """
            {"id":2,"email":"selected@example.invalid","role":"USER","status":"ACTIVE","protectedAdmin":false}
            """;
    private static final String OTHER_RADAR_JSON = PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK;

    private record CapturedRequest(
            String method, String uri, String userId, String role, String status) {}

    @Test
    void anonymousAuthenticatedAndImpersonatedContextsAlwaysUseConfiguredAdmin() {
        List<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = mockBusinessServer(calls);
        try {
            WebClient client = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter())
                    .build();
            PublicTradingRadarService service =
                    new PublicTradingRadarService(new BusinessUserClient(client), client);

            for (Context context : List.of(
                    Context.empty(),
                    Context.of(AuthConstants.CTX_IDENTITY,
                            new TenantIdentity(22L, "USER", "ACTIVE")),
                    Context.of(AuthConstants.CTX_IDENTITY,
                            new TenantIdentity(99L, "ADMIN", "ACTIVE")))) {
                var response = service.today(null).contextWrite(context).block();

                assertThat(response).isNotNull();
                assertThat(response.body().toString()).isEqualTo(RADAR_JSON);
            }

            List<CapturedRequest> bootstraps = calls.stream()
                    .filter(r -> "/internal/users/configured-admin".equals(r.uri())).toList();
            assertThat(bootstraps).hasSize(3).allSatisfy(bootstrap -> {
                assertThat(bootstrap.method()).isEqualTo("GET");
                assertThat(bootstrap.userId()).isNull();
                assertThat(bootstrap.role()).isNull();
                assertThat(bootstrap.status()).isNull();
            });

            List<CapturedRequest> radarCalls = calls.stream()
                    .filter(r -> "/internal/public-trading-radar/current/list".equals(r.uri())).toList();
            assertThat(radarCalls).hasSize(3).allSatisfy(radar -> {
                assertThat(radar.method()).isEqualTo("GET");
                assertThat(radar.userId()).isEqualTo("1");
                assertThat(radar.role()).isEqualTo("ADMIN");
                assertThat(radar.status()).isEqualTo("ACTIVE");
            });
        } finally {
            server.disposeNow();
        }
    }

    /** 情境 2：合法 email 對應一個 active 且非 configured-admin 的帳號，資料可觀察地屬於該帳號。 */
    @Test
    void validEmailForActiveNonConfiguredAdminAccountReturnsThatAccountsData() {
        List<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = mockBusinessServerWithByEmail(calls, OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_RADAR_JSON);
        try {
            WebClient client = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter())
                    .build();
            PublicTradingRadarService service =
                    new PublicTradingRadarService(new BusinessUserClient(client), client);

            var response = service.today(" selected@example.invalid ").block();

            assertThat(response).isNotNull();
            assertThat(response.body().toString()).isEqualTo(OTHER_RADAR_JSON);
            List<CapturedRequest> byEmailCalls = calls.stream()
                    .filter(r -> r.uri().startsWith("/internal/users/by-email")).toList();
            assertThat(byEmailCalls).hasSize(1);
            assertThat(URLDecoder.decode(byEmailCalls.getFirst().uri(), StandardCharsets.UTF_8))
                    .isEqualTo("/internal/users/by-email?email=selected@example.invalid");
            assertNoTenantHeaders(byEmailCalls.getFirst());
            List<CapturedRequest> radarCalls = calls.stream()
                    .filter(r -> "/internal/public-trading-radar/current/list".equals(r.uri())).toList();
            assertThat(radarCalls).hasSize(1);
            assertThat(radarCalls.getFirst().userId()).isEqualTo("2");
            assertThat(radarCalls.getFirst().role()).isEqualTo("USER");
            assertThat(radarCalls.getFirst().status()).isEqualTo("ACTIVE");
        } finally {
            server.disposeNow();
        }
    }

    /** email lookup 的所有失敗（含 business 的實際 200 空 body）都必須是同一個 canonical 503。 */
    @Test
    void emailLookupFailuresFailClosedWithIdenticalContract() {
        PublicTradingRadarService notFound = serviceForLookup(request -> emptyLookupResponse());
        PublicTradingRadarService inactive = serviceForLookup(request -> lookupResponse(HttpStatus.OK,
                "{\"id\":2,\"role\":\"USER\",\"status\":\"DISABLED\",\"protectedAdmin\":false}"));
        PublicTradingRadarService client4xx = serviceForLookup(request -> lookupResponse(HttpStatus.BAD_REQUEST,
                "{\"detail\":\"lookup body must not escape\"}"));
        PublicTradingRadarService server5xx = serviceForLookup(request -> lookupResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"detail\":\"lookup body must not escape\"}"));
        PublicTradingRadarService malformed = serviceForLookup(request -> lookupResponse(HttpStatus.OK, "not-json"));
        PublicTradingRadarService transport = serviceForLookup(request -> Mono.error(new WebClientRequestException(
                new IOException("synthetic lookup transport failure"), HttpMethod.GET,
                URI.create("http://business.invalid/internal/users/by-email"), HttpHeaders.EMPTY)));
        PublicTradingRadarService timeout = serviceForLookup(request -> Mono.never());

        List<Throwable> failures = List.of(
                catchThrowable(() -> notFound.today("missing@example.invalid").block()),
                catchThrowable(() -> inactive.today("disabled@example.invalid").block()),
                catchThrowable(() -> client4xx.today("selected@example.invalid").block()),
                catchThrowable(() -> server5xx.today("selected@example.invalid").block()),
                catchThrowable(() -> malformed.today("selected@example.invalid").block()),
                catchThrowable(() -> transport.today("selected@example.invalid").block()),
                catchThrowable(() -> timeout.today("selected@example.invalid").block()));

        var advice = new PublicTradingRadarExceptionAdvice();
        List<String> contracts = failures.stream().map(error -> unavailableContract(advice, error)).toList();
        assertThat(contracts).containsOnly("503|Trading radar unavailable|主要管理者不可用");
    }

    /** 情境 5：格式不合法的 email（缺 @、缺網域、超過 254 字元）在呼叫 business 前就地拒絕。 */
    @Test
    void malformedEmailFailsFastWithoutAnyNetworkCall() {
        List<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = mockBusinessServerWithByEmail(calls, null, HttpStatus.OK, RADAR_JSON);
        try {
            WebClient client = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter()).build();
            PublicTradingRadarService service =
                    new PublicTradingRadarService(new BusinessUserClient(client), client);
            String tooLong = "x".repeat(250) + "@a.co";
            assertThat(tooLong.length()).isGreaterThan(254);

            assertThatThrownBy(() -> service.today("not-an-email").block())
                    .isInstanceOf(PublicTradingRadarEmailRequestException.class);
            assertThatThrownBy(() -> service.today("missing-domain@").block())
                    .isInstanceOf(PublicTradingRadarEmailRequestException.class);
            assertThatThrownBy(() -> service.today(tooLong).block())
                    .isInstanceOf(PublicTradingRadarEmailRequestException.class);
            // email 格式錯誤優先於 stock selector；email 合法後仍保留既有 selector 契約。
            assertThatThrownBy(() -> service.stock(null, null, "not-an-email").block())
                    .isInstanceOf(PublicTradingRadarEmailRequestException.class);
            assertThatThrownBy(() -> service.stock(null, null, "selected@example.invalid").block())
                    .isInstanceOf(PublicTradingRadarRequestException.class);
            assertThat(calls).isEmpty();

            var problem = new PublicTradingRadarExceptionAdvice()
                    .invalidEmail(new PublicTradingRadarEmailRequestException()).getBody();
            assertThat(problem.getDetail()).isEqualTo("email 格式不合法");
        } finally {
            server.disposeNow();
        }
    }

    /** 情境 6：context 已有另一個登入者／代看者身分時，email 分支下游收到的仍是 email 解析出的帳號。 */
    @Test
    void emailBranchIgnoresReactorContextIdentity() {
        List<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        DisposableServer server = mockBusinessServerWithByEmail(calls, OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_RADAR_JSON);
        try {
            WebClient client = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .filter(WebClientConfig.tenantHeaderFilter()).build();
            PublicTradingRadarService service =
                    new PublicTradingRadarService(new BusinessUserClient(client), client);

            service.today("selected@example.invalid")
                    .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                    .block();

            List<CapturedRequest> byEmailCalls = calls.stream()
                    .filter(r -> r.uri().startsWith("/internal/users/by-email")).toList();
            assertThat(byEmailCalls).hasSize(1);
            assertNoTenantHeaders(byEmailCalls.getFirst());
            List<CapturedRequest> radarCalls = calls.stream()
                    .filter(r -> "/internal/public-trading-radar/current/list".equals(r.uri())).toList();
            assertThat(radarCalls).hasSize(1);
            assertThat(radarCalls.getFirst().userId()).isEqualTo("2");
            assertThat(radarCalls.getFirst().role()).isEqualTo("USER");
            assertThat(radarCalls.getFirst().status()).isEqualTo("ACTIVE");
        } finally {
            server.disposeNow();
        }
    }

    private static DisposableServer mockBusinessServer(List<CapturedRequest> calls) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    calls.add(new CapturedRequest(
                            request.method().name(), request.uri(),
                            request.requestHeaders().get(AuthConstants.HDR_USER_ID),
                            request.requestHeaders().get(AuthConstants.HDR_USER_ROLE),
                            request.requestHeaders().get(AuthConstants.HDR_USER_STATUS)));
                    boolean bootstrap = "/internal/users/configured-admin".equals(request.uri());
                    response.status(HttpStatus.OK.value())
                            .header("Content-Type", bootstrap
                                    ? MediaType.APPLICATION_JSON_VALUE
                                    : "application/json;charset=UTF-8");
                    return response.sendString(reactor.core.publisher.Mono.just(
                            bootstrap ? ADMIN_JSON : RADAR_JSON));
                })
                .bindNow();
    }

    /**
     * Requirement 140 email 分支專用替身：{@code /internal/users/by-email} 回 {@code byEmailBody}
     * （{@code null} 模擬 business 實際的 200 空 body 查無帳號）；
     * {@code /internal/users/configured-admin} 與 radar 端點回 {@code radarBody}
     * （情境 3／4／5 不會真的打到 radar 這一步）。
     */
    private static DisposableServer mockBusinessServerWithByEmail(
            List<CapturedRequest> calls, String byEmailBody, HttpStatus byEmailStatus, String radarBody) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    calls.add(new CapturedRequest(
                            request.method().name(), request.uri(),
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
                    response.status(HttpStatus.OK.value()).header("Content-Type", "application/json;charset=UTF-8");
                    return response.sendString(Mono.just(radarBody));
                })
                .bindNow();
    }

    private static PublicTradingRadarService serviceForLookup(
            Function<ClientRequest, Mono<ClientResponse>> byEmailResponse) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business.invalid")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    if (request.url().getPath().equals("/internal/users/by-email")) {
                        return byEmailResponse.apply(request);
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(RADAR_JSON).build());
                })
                .build();
        return new PublicTradingRadarService(new BusinessUserClient(client), client);
    }

    private static Mono<ClientResponse> emptyLookupResponse() {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).build());
    }

    private static Mono<ClientResponse> lookupResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }

    private static void assertNoTenantHeaders(CapturedRequest request) {
        assertThat(request.userId()).isNull();
        assertThat(request.role()).isNull();
        assertThat(request.status()).isNull();
    }

    private static String unavailableContract(PublicTradingRadarExceptionAdvice advice, Throwable error) {
        assertThat(error).isInstanceOf(PublicTradingRadarUnavailableException.class);
        var response = advice.unavailable((PublicTradingRadarUnavailableException) error);
        return response.getStatusCode().value() + "|" + response.getBody().getTitle()
                + "|" + response.getBody().getDetail();
    }
}

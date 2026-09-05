package com.steven.assets.bff.config;

import com.steven.assets.bff.portfolioadvice.PublicPortfolioAdviceExceptionAdvice;
import com.steven.assets.bff.portfolioadvice.PublicPortfolioAdviceRequestException;
import com.steven.assets.bff.portfolioadvice.PublicPortfolioAdviceService;
import com.steven.assets.bff.portfolioadvice.PublicPortfolioAdviceUnavailableException;
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
import reactor.util.context.Context;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 公開「最新資產配置建議」端點（Requirement 79 / Task 338）不得把登入者或管理者代看的 Reactor
 * tenant context 帶到 business。同一個 client 的 tenant filter 會覆寫 {@code X-User-*}；故這是
 * context 隔離測試，而非只測 service 有沒有呼叫 configured-admin 的回歸測試。
 *
 * <p>本檔置於 {@code config} package，因為要組出真實行為必須套用
 * {@link WebClientConfig#tenantHeaderFilter()}（package-private），比照既有
 * {@code LatestAssetsConfiguredAdminContextTest} 的既有手法。
 *
 * <p>Requirement 140：{@code email} 分支的案例併入本檔，不新建平行測試檔。
 */
class PublicPortfolioAdviceConfiguredAdminContextTest {

    private static final String ADMIN_JSON = """
            {"id":1,"email":"owner@example.invalid","role":"ADMIN","status":"ACTIVE","protectedAdmin":true}
            """;
    private static final String ADVICE_JSON = """
            {"status":"OK","targetAllocation":{"stock":0.6}}
            """;
    private static final String OTHER_ACCOUNT_JSON = """
            {"id":2,"email":"selected@example.invalid","role":"USER","status":"ACTIVE","protectedAdmin":false}
            """;
    private static final String OTHER_ADVICE_JSON = """
            {"status":"OK","targetAllocation":{"stock":0.2}}
            """;

    @Test
    void anonymousRequestUsesConfiguredAdminHeaders() {
        assertAdviceHeaders(Context.empty());
    }

    /** context 中已有另一個登入者身分：下游收到的仍必須是 configured admin（id=1）而非該登入者（id=22）。 */
    @Test
    void authenticatedRequestCannotOverwriteConfiguredAdminHeaders() {
        assertAdviceHeaders(Context.of(AuthConstants.CTX_IDENTITY,
                new TenantIdentity(22L, "USER", "ACTIVE")));
    }

    /** effective id=99 模擬 ADMIN 代看另一使用者；公開建議的 owner 仍必須固定為 configured admin id=1。 */
    @Test
    void impersonationIdentityCannotOverwriteConfiguredAdminHeaders() {
        assertAdviceHeaders(Context.of(AuthConstants.CTX_IDENTITY,
                new TenantIdentity(99L, "ADMIN", "ACTIVE")));
    }

    @Test
    void missingConfiguredAdminFailsClosed() {
        PublicPortfolioAdviceService service = serviceFor(null, "{}", new ArrayList<>());

        assertThatThrownBy(() -> service.latest(null).block())
                .isInstanceOf(PublicPortfolioAdviceUnavailableException.class);
    }

    @Test
    void inactiveOrNonConfiguredAdminFailsClosed() {
        PublicPortfolioAdviceService inactive = serviceFor(
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"DISABLED\",\"protectedAdmin\":true}",
                "{}", new ArrayList<>());
        PublicPortfolioAdviceService ordinaryAdmin = serviceFor(
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":false}",
                "{}", new ArrayList<>());

        assertThatThrownBy(() -> inactive.latest(null).block())
                .isInstanceOf(PublicPortfolioAdviceUnavailableException.class);
        assertThatThrownBy(() -> ordinaryAdmin.latest(null).block())
                .isInstanceOf(PublicPortfolioAdviceUnavailableException.class);
    }

    /** 建議配置比例等金融數值不可先 decode 成 Map/Double 再重編碼：逐位元組 relay。 */
    @Test
    void financialJsonIsRelayedByteForByteWithoutMapDoubleRoundTrip() {
        String payload = "{\"status\":\"OK\",\"targetAllocation\":{\"stock\":0.123456789012345678901234567890}}";
        PublicPortfolioAdviceService service = serviceFor(ADMIN_JSON, payload, new ArrayList<>());

        byte[] body = service.latest(null).block().getBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    /** downstream 非 2xx 屬 byte-relay 範圍：狀態碼、body 與 content type 原樣傳回。 */
    @Test
    void downstreamNonSuccessStatusBodyAndContentTypeAreRelayed() {
        String problem = "{\"title\":\"No advice\"}";
        PublicPortfolioAdviceService service = serviceFor(
                ADMIN_JSON, problem, HttpStatus.NOT_FOUND, MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new ArrayList<>());

        var response = service.latest(null).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(problem);
    }

    /** 情境 2：合法 email 對應一個 active 且非 configured-admin 的帳號，資料可觀察地屬於該帳號。 */
    @Test
    void validEmailForActiveNonConfiguredAdminAccountReturnsThatAccountsData() {
        List<ClientRequest> calls = new ArrayList<>();
        PublicPortfolioAdviceService service = serviceForEmail(OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_ADVICE_JSON, calls);

        var response = service.latest(" selected@example.invalid ").block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(OTHER_ADVICE_JSON);
        ClientRequest byEmail = calls.stream()
                .filter(r -> "/internal/users/by-email".equals(r.url().getPath())).findFirst().orElseThrow();
        assertThat(byEmail.url().getQuery()).isEqualTo("email=selected@example.invalid");
        assertNoTenantHeaders(byEmail);
        ClientRequest advice = calls.stream()
                .filter(r -> "/api/portfolio-advice/latest".equals(r.url().getPath())).findFirst().orElseThrow();
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("2");
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
    }

    /** email lookup 的所有失敗（含 business 的實際 200 空 body）都必須是同一個 canonical 503。 */
    @Test
    void emailLookupFailuresFailClosedWithIdenticalContract() {
        PublicPortfolioAdviceService notFound = serviceForEmail(null, HttpStatus.OK, "{}", new ArrayList<>());
        PublicPortfolioAdviceService inactive = serviceForEmail(
                "{\"id\":2,\"role\":\"USER\",\"status\":\"DISABLED\",\"protectedAdmin\":false}",
                HttpStatus.OK, "{}", new ArrayList<>());
        PublicPortfolioAdviceService client4xx = serviceForEmail(
                "{\"detail\":\"lookup body must not escape\"}", HttpStatus.BAD_REQUEST, "{}", new ArrayList<>());
        PublicPortfolioAdviceService server5xx = serviceForEmail(
                "{\"detail\":\"lookup body must not escape\"}", HttpStatus.INTERNAL_SERVER_ERROR,
                "{}", new ArrayList<>());
        PublicPortfolioAdviceService malformed = serviceForEmail("not-json", HttpStatus.OK, "{}", new ArrayList<>());
        PublicPortfolioAdviceService transport = serviceForLookup(request -> Mono.error(new WebClientRequestException(
                new IOException("synthetic lookup transport failure"), HttpMethod.GET,
                URI.create("http://business.invalid/internal/users/by-email"), HttpHeaders.EMPTY)), "{}", new ArrayList<>());
        PublicPortfolioAdviceService timeout = serviceForLookup(request -> Mono.never(), "{}", new ArrayList<>());

        Throwable notFoundError = catchThrowable(() -> notFound.latest("missing@example.invalid").block());
        Throwable inactiveError = catchThrowable(() -> inactive.latest("disabled@example.invalid").block());
        Throwable client4xxError = catchThrowable(() -> client4xx.latest("selected@example.invalid").block());
        Throwable server5xxError = catchThrowable(() -> server5xx.latest("selected@example.invalid").block());
        Throwable malformedError = catchThrowable(() -> malformed.latest("selected@example.invalid").block());
        Throwable transportError = catchThrowable(() -> transport.latest("selected@example.invalid").block());
        Throwable timeoutError = catchThrowable(() -> timeout.latest("selected@example.invalid").block());

        var advice = new PublicPortfolioAdviceExceptionAdvice();
        List<String> contracts = List.of(notFoundError, inactiveError, client4xxError, server5xxError,
                        malformedError, transportError, timeoutError).stream()
                .map(error -> unavailableContract(advice, error))
                .toList();

        assertThat(contracts).containsOnly("503|Portfolio advice unavailable|指定帳號不可用");
    }

    /** 情境 5：格式不合法的 email（缺 @、缺網域、超過 254 字元）在呼叫 business 前就地拒絕。 */
    @Test
    void malformedEmailFailsFastWithoutAnyNetworkCall() {
        List<ClientRequest> calls = new ArrayList<>();
        PublicPortfolioAdviceService service = serviceForEmail(null, HttpStatus.OK, "{}", calls);
        String tooLong = "x".repeat(250) + "@a.co";
        assertThat(tooLong.length()).isGreaterThan(254);

        assertThatThrownBy(() -> service.latest("not-an-email").block())
                .isInstanceOf(PublicPortfolioAdviceRequestException.class);
        assertThatThrownBy(() -> service.latest("missing-domain@").block())
                .isInstanceOf(PublicPortfolioAdviceRequestException.class);
        assertThatThrownBy(() -> service.latest(tooLong).block())
                .isInstanceOf(PublicPortfolioAdviceRequestException.class);
        assertThat(calls).isEmpty();

        var problem = new PublicPortfolioAdviceExceptionAdvice()
                .invalidRequest(new PublicPortfolioAdviceRequestException()).getBody();
        assertThat(problem.getDetail()).isEqualTo("email 格式不合法");
    }

    /** 情境 6：context 已有另一個登入者／代看者身分時，email 分支下游收到的仍是 email 解析出的帳號。 */
    @Test
    void emailBranchIgnoresReactorContextIdentity() {
        List<ClientRequest> calls = new ArrayList<>();
        PublicPortfolioAdviceService service = serviceForEmail(OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_ADVICE_JSON, calls);

        service.latest("selected@example.invalid")
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                .block();

        ClientRequest byEmail = calls.stream()
                .filter(r -> "/internal/users/by-email".equals(r.url().getPath())).findFirst().orElseThrow();
        assertNoTenantHeaders(byEmail);
        ClientRequest advice = calls.stream()
                .filter(r -> "/api/portfolio-advice/latest".equals(r.url().getPath())).findFirst().orElseThrow();
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("2");
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
    }

    private void assertAdviceHeaders(Context context) {
        List<ClientRequest> calls = new ArrayList<>();
        PublicPortfolioAdviceService service = serviceFor(ADMIN_JSON, ADVICE_JSON, calls);

        service.latest(null).contextWrite(context).block();

        ClientRequest bootstrap = calls.stream()
                .filter(r -> "/internal/users/configured-admin".equals(r.url().getPath()))
                .findFirst().orElseThrow();
        assertThat(bootstrap.headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
        assertThat(bootstrap.headers().containsKey(AuthConstants.HDR_USER_ROLE)).isFalse();
        assertThat(bootstrap.headers().containsKey(AuthConstants.HDR_USER_STATUS)).isFalse();

        ClientRequest advice = calls.stream()
                .filter(r -> "/api/portfolio-advice/latest".equals(r.url().getPath()))
                .findFirst().orElseThrow();
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("1");
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("ADMIN");
        assertThat(advice.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
    }

    private static PublicPortfolioAdviceService serviceFor(String configuredAdminBody, String adviceBody,
                                                           List<ClientRequest> calls) {
        return serviceFor(configuredAdminBody, adviceBody, HttpStatus.OK,
                MediaType.APPLICATION_JSON_VALUE, calls);
    }

    private static PublicPortfolioAdviceService serviceFor(String configuredAdminBody, String adviceBody,
                                                           HttpStatus adviceStatus, String adviceContentType,
                                                           List<ClientRequest> calls) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business.invalid")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    calls.add(request);
                    boolean bootstrap = "/internal/users/configured-admin".equals(request.url().getPath());
                    String body = bootstrap ? configuredAdminBody : adviceBody;
                    if (bootstrap && body == null) {
                        return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                    }
                    HttpStatus status = bootstrap ? HttpStatus.OK : adviceStatus;
                    String contentType = bootstrap ? MediaType.APPLICATION_JSON_VALUE : adviceContentType;
                    return Mono.just(ClientResponse.create(status)
                            .header("Content-Type", contentType).body(body).build());
                })
                .build();
        return new PublicPortfolioAdviceService(new BusinessUserClient(client), client);
    }

    /**
     * Requirement 140 email 分支專用替身：{@code /internal/users/by-email} 回 {@code byEmailBody}
     * （{@code null} 模擬 business 實際的 200 空 body 查無帳號）；
     * {@code /api/portfolio-advice/latest} 一律回 {@code adviceBody}（情境 3／4／5 不會真的打到這一步）。
     */
    private static PublicPortfolioAdviceService serviceForEmail(String byEmailBody, HttpStatus byEmailStatus,
                                                                 String adviceBody, List<ClientRequest> calls) {
        return serviceForLookup(request -> {
            if (byEmailBody == null) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).build());
            }
            return Mono.just(ClientResponse.create(byEmailStatus)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(byEmailBody).build());
        }, adviceBody, calls);
    }

    private static PublicPortfolioAdviceService serviceForLookup(
            Function<ClientRequest, Mono<ClientResponse>> byEmailResponse,
            String adviceBody, List<ClientRequest> calls) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business.invalid")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    calls.add(request);
                    boolean byEmail = "/internal/users/by-email".equals(request.url().getPath());
                    if (byEmail) {
                        return byEmailResponse.apply(request);
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(adviceBody).build());
                })
                .build();
        return new PublicPortfolioAdviceService(new BusinessUserClient(client), client);
    }

    private static void assertNoTenantHeaders(ClientRequest request) {
        assertThat(request.headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
        assertThat(request.headers().containsKey(AuthConstants.HDR_USER_ROLE)).isFalse();
        assertThat(request.headers().containsKey(AuthConstants.HDR_USER_STATUS)).isFalse();
    }

    private static String unavailableContract(PublicPortfolioAdviceExceptionAdvice advice, Throwable error) {
        assertThat(error).isInstanceOf(PublicPortfolioAdviceUnavailableException.class);
        var response = advice.unavailable((PublicPortfolioAdviceUnavailableException) error);
        return response.getStatusCode().value() + "|" + response.getBody().getTitle()
                + "|" + response.getBody().getDetail();
    }
}

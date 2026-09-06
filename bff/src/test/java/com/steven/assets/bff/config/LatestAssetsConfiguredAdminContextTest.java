package com.steven.assets.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.latestassets.LatestAssetsPayloadException;
import com.steven.assets.bff.latestassets.LatestAssetsPublicExceptionAdvice;
import com.steven.assets.bff.latestassets.LatestAssetsPublicService;
import com.steven.assets.bff.latestassets.LatestAssetsRequestException;
import com.steven.assets.bff.latestassets.LatestAssetsUnavailableException;
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
 * 公開 latest endpoint 不得把登入者或管理者代看的 Reactor tenant context 帶到 business。
 * 同一個 client 的 tenant filter 會覆寫 X-User-*；故這是 context 隔離而非只測 service 有沒有
 * 呼叫 configured-admin 的回歸測試。
 *
 * <p>Requirement 140：{@code email} 分支（依 email 選擇非 configured-admin 帳號）的案例併入本檔，
 * 不新建平行測試檔——與既有 configured-admin 分支共用同一個 {@code serviceFor}／context 測試手法。
 */
class LatestAssetsConfiguredAdminContextTest {

    private static final String ADMIN_JSON = """
            {"id":1,"email":"owner@example.invalid","role":"ADMIN","status":"ACTIVE","protectedAdmin":true}
            """;
    private static final String VALID_LATEST_JSON = """
            {"snapshot":{"id":8},"liveAssets":{"snapshotId":8}}
            """;
    private static final String OTHER_ACCOUNT_JSON = """
            {"id":2,"email":"selected@example.invalid","role":"USER","status":"ACTIVE","protectedAdmin":false}
            """;
    private static final String OTHER_LATEST_JSON = """
            {"snapshot":{"id":42},"liveAssets":{"snapshotId":42}}
            """;

    @Test
    void anonymousRequestUsesConfiguredAdminHeaders() {
        assertLatestHeaders(Context.empty());
    }

    @Test
    void authenticatedRequestCannotOverwriteConfiguredAdminHeaders() {
        assertLatestHeaders(Context.of(AuthConstants.CTX_IDENTITY,
                new TenantIdentity(22L, "USER", "ACTIVE")));
    }

    @Test
    void impersonationIdentityCannotOverwriteConfiguredAdminHeaders() {
        // effective id=99 模擬 ADMIN 代看另一使用者；latest owner 仍必須固定為 configured admin id=1。
        assertLatestHeaders(Context.of(AuthConstants.CTX_IDENTITY,
                new TenantIdentity(99L, "ADMIN", "ACTIVE")));
    }

    @Test
    void missingConfiguredAdminFailsClosed() {
        LatestAssetsPublicService service = serviceFor(null, "{}", new ArrayList<>());

        assertThatThrownBy(() -> service.getLatest(null).block())
                .isInstanceOf(LatestAssetsUnavailableException.class);
    }

    @Test
    void inactiveOrNonConfiguredAdminFailsClosed() {
        LatestAssetsPublicService inactive = serviceFor(
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"DISABLED\",\"protectedAdmin\":true}",
                "{}", new ArrayList<>());
        LatestAssetsPublicService ordinaryAdmin = serviceFor(
                "{\"id\":1,\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":false}",
                "{}", new ArrayList<>());

        assertThatThrownBy(() -> inactive.getLatest(null).block())
                .isInstanceOf(LatestAssetsUnavailableException.class);
        assertThatThrownBy(() -> ordinaryAdmin.getLatest(null).block())
                .isInstanceOf(LatestAssetsUnavailableException.class);
    }

    @Test
    void financialJsonIsRelayedByteForByteWithoutMapDoubleRoundTrip() {
        String payload = "{\"preciseAmount\":0.123456789012345678901234567890,"
                + "\"snapshot\":{\"id\":8},\"liveAssets\":{\"snapshotId\":8}}";
        LatestAssetsPublicService service = serviceFor(ADMIN_JSON, payload, new ArrayList<>());

        byte[] body = service.getLatest(null).block().getBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void malformedOrMismatchedSuccessPayloadFailsClosed() {
        LatestAssetsPublicService empty = serviceFor(ADMIN_JSON, "", new ArrayList<>());
        LatestAssetsPublicService malformed = serviceFor(ADMIN_JSON, "not-json", new ArrayList<>());
        LatestAssetsPublicService mismatch = serviceFor(ADMIN_JSON,
                "{\"snapshot\":{\"id\":8},\"liveAssets\":{\"snapshotId\":9}}", new ArrayList<>());

        assertThatThrownBy(() -> empty.getLatest(null).block()).isInstanceOf(LatestAssetsPayloadException.class);
        assertThatThrownBy(() -> malformed.getLatest(null).block()).isInstanceOf(LatestAssetsPayloadException.class);
        assertThatThrownBy(() -> mismatch.getLatest(null).block()).isInstanceOf(LatestAssetsPayloadException.class);
    }

    @Test
    void downstreamNonSuccessStatusBodyAndContentTypeAreRelayed() {
        String problem = "{\"title\":\"No snapshot\"}";
        LatestAssetsPublicService service = serviceFor(
                ADMIN_JSON, problem, HttpStatus.NOT_FOUND, MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new ArrayList<>());

        var response = service.getLatest(null).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(problem);
    }

    /** 情境 2：合法 email 對應一個 active 且非 configured-admin 的帳號，資料可觀察地屬於該帳號。 */
    @Test
    void validEmailForActiveNonConfiguredAdminAccountReturnsThatAccountsData() {
        List<ClientRequest> calls = new ArrayList<>();
        LatestAssetsPublicService service = serviceForEmail(OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_LATEST_JSON, calls);

        var response = service.getLatest(" selected@example.invalid ").block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(OTHER_LATEST_JSON);
        ClientRequest byEmail = calls.stream()
                .filter(r -> "/internal/users/by-email".equals(r.url().getPath())).findFirst().orElseThrow();
        assertThat(byEmail.url().getQuery()).isEqualTo("email=selected@example.invalid");
        assertNoTenantHeaders(byEmail);
        ClientRequest latest = calls.stream()
                .filter(r -> "/api/assets/latest".equals(r.url().getPath())).findFirst().orElseThrow();
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("2");
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
    }

    /** email lookup 的所有失敗（含 business 的實際 200 空 body）都必須是同一個 canonical 503。 */
    @Test
    void emailLookupFailuresFailClosedWithIdenticalContract() {
        LatestAssetsPublicService notFound = serviceForEmail(null, HttpStatus.OK, "{}", new ArrayList<>());
        LatestAssetsPublicService inactive = serviceForEmail(
                "{\"id\":2,\"role\":\"USER\",\"status\":\"DISABLED\",\"protectedAdmin\":false}",
                HttpStatus.OK, "{}", new ArrayList<>());
        LatestAssetsPublicService client4xx = serviceForEmail(
                "{\"detail\":\"lookup body must not escape\"}", HttpStatus.BAD_REQUEST, "{}", new ArrayList<>());
        LatestAssetsPublicService server5xx = serviceForEmail(
                "{\"detail\":\"lookup body must not escape\"}", HttpStatus.INTERNAL_SERVER_ERROR,
                "{}", new ArrayList<>());
        LatestAssetsPublicService malformed = serviceForEmail("not-json", HttpStatus.OK, "{}", new ArrayList<>());
        LatestAssetsPublicService transport = serviceForLookup(request -> Mono.error(new WebClientRequestException(
                new IOException("synthetic lookup transport failure"), HttpMethod.GET,
                URI.create("http://business.invalid/internal/users/by-email"), HttpHeaders.EMPTY)), "{}", new ArrayList<>());
        LatestAssetsPublicService timeout = serviceForLookup(request -> Mono.never(), "{}", new ArrayList<>());

        Throwable notFoundError = catchThrowable(() -> notFound.getLatest("missing@example.invalid").block());
        Throwable inactiveError = catchThrowable(() -> inactive.getLatest("disabled@example.invalid").block());
        Throwable client4xxError = catchThrowable(() -> client4xx.getLatest("selected@example.invalid").block());
        Throwable server5xxError = catchThrowable(() -> server5xx.getLatest("selected@example.invalid").block());
        Throwable malformedError = catchThrowable(() -> malformed.getLatest("selected@example.invalid").block());
        Throwable transportError = catchThrowable(() -> transport.getLatest("selected@example.invalid").block());
        Throwable timeoutError = catchThrowable(() -> timeout.getLatest("selected@example.invalid").block());

        var advice = new LatestAssetsPublicExceptionAdvice();
        List<String> contracts = List.of(notFoundError, inactiveError, client4xxError, server5xxError,
                        malformedError, transportError, timeoutError).stream()
                .map(error -> unavailableContract(advice, error))
                .toList();

        assertThat(contracts).containsOnly("503|Latest assets unavailable|指定帳號不可用");
    }

    /** 情境 5：格式不合法的 email（缺 @、缺網域、超過 254 字元）在呼叫 business 前就地拒絕。 */
    @Test
    void malformedEmailFailsFastWithoutAnyNetworkCall() {
        List<ClientRequest> calls = new ArrayList<>();
        LatestAssetsPublicService service = serviceForEmail(null, HttpStatus.OK, "{}", calls);
        String tooLong = "x".repeat(250) + "@a.co";
        assertThat(tooLong.length()).isGreaterThan(254);

        assertThatThrownBy(() -> service.getLatest("not-an-email").block())
                .isInstanceOf(LatestAssetsRequestException.class);
        assertThatThrownBy(() -> service.getLatest("missing-domain@").block())
                .isInstanceOf(LatestAssetsRequestException.class);
        assertThatThrownBy(() -> service.getLatest(tooLong).block())
                .isInstanceOf(LatestAssetsRequestException.class);
        assertThat(calls).isEmpty();

        var problem = new LatestAssetsPublicExceptionAdvice()
                .invalidRequest(new LatestAssetsRequestException()).getBody();
        assertThat(problem.getDetail()).isEqualTo("email 格式不合法");
    }

    /** 情境 6：context 已有另一個登入者／代看者身分時，email 分支下游收到的仍是 email 解析出的帳號。 */
    @Test
    void emailBranchIgnoresReactorContextIdentity() {
        List<ClientRequest> calls = new ArrayList<>();
        LatestAssetsPublicService service = serviceForEmail(OTHER_ACCOUNT_JSON, HttpStatus.OK, OTHER_LATEST_JSON, calls);

        service.getLatest("selected@example.invalid")
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                .block();

        ClientRequest byEmail = calls.stream()
                .filter(r -> "/internal/users/by-email".equals(r.url().getPath())).findFirst().orElseThrow();
        assertNoTenantHeaders(byEmail);
        ClientRequest latest = calls.stream()
                .filter(r -> "/api/assets/latest".equals(r.url().getPath())).findFirst().orElseThrow();
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("2");
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
    }

    private void assertLatestHeaders(Context context) {
        List<ClientRequest> calls = new ArrayList<>();
        LatestAssetsPublicService service = serviceFor(ADMIN_JSON, VALID_LATEST_JSON, calls);

        service.getLatest(null).contextWrite(context).block();

        ClientRequest bootstrap = calls.stream()
                .filter(r -> "/internal/users/configured-admin".equals(r.url().getPath()))
                .findFirst().orElseThrow();
        assertThat(bootstrap.headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
        assertThat(bootstrap.headers().containsKey(AuthConstants.HDR_USER_ROLE)).isFalse();
        assertThat(bootstrap.headers().containsKey(AuthConstants.HDR_USER_STATUS)).isFalse();

        ClientRequest latest = calls.stream()
                .filter(r -> "/api/assets/latest".equals(r.url().getPath()))
                .findFirst().orElseThrow();
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("1");
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("ADMIN");
        assertThat(latest.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
    }

    private static LatestAssetsPublicService serviceFor(String configuredAdminBody, String latestBody,
                                                         List<ClientRequest> calls) {
        return serviceFor(configuredAdminBody, latestBody, HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE, calls);
    }

    private static LatestAssetsPublicService serviceFor(String configuredAdminBody, String latestBody,
                                                         HttpStatus latestStatus, String latestContentType,
                                                         List<ClientRequest> calls) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business.invalid")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    calls.add(request);
                    boolean bootstrap = "/internal/users/configured-admin".equals(request.url().getPath());
                    String body = bootstrap ? configuredAdminBody : latestBody;
                    if (bootstrap && body == null) {
                        return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                    }
                    HttpStatus status = bootstrap ? HttpStatus.OK : latestStatus;
                    String contentType = bootstrap ? MediaType.APPLICATION_JSON_VALUE : latestContentType;
                    return reactor.core.publisher.Mono.just(ClientResponse.create(status)
                            .header("Content-Type", contentType).body(body).build());
                })
                .build();
        return new LatestAssetsPublicService(new BusinessUserClient(client), client, new ObjectMapper());
    }

    /**
     * Requirement 140 email 分支專用替身：{@code /internal/users/by-email} 回 {@code byEmailBody}
     * （{@code null} 模擬 business 實際的 200 空 body 查無帳號）；
     * {@code /api/assets/latest} 一律回 {@code latestBody}（因為情境 3／4／5 不會真的打到這一步）。
     */
    private static LatestAssetsPublicService serviceForEmail(String byEmailBody, HttpStatus byEmailStatus,
                                                              String latestBody, List<ClientRequest> calls) {
        return serviceForLookup(request -> {
            if (byEmailBody == null) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).build());
            }
            return Mono.just(ClientResponse.create(byEmailStatus)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(byEmailBody).build());
        }, latestBody, calls);
    }

    private static LatestAssetsPublicService serviceForLookup(
            Function<ClientRequest, Mono<ClientResponse>> byEmailResponse,
            String latestBody, List<ClientRequest> calls) {
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
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(latestBody).build());
                })
                .build();
        return new LatestAssetsPublicService(new BusinessUserClient(client), client, new ObjectMapper());
    }

    private static void assertNoTenantHeaders(ClientRequest request) {
        assertThat(request.headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
        assertThat(request.headers().containsKey(AuthConstants.HDR_USER_ROLE)).isFalse();
        assertThat(request.headers().containsKey(AuthConstants.HDR_USER_STATUS)).isFalse();
    }

    private static String unavailableContract(LatestAssetsPublicExceptionAdvice advice, Throwable error) {
        assertThat(error).isInstanceOf(LatestAssetsUnavailableException.class);
        var response = advice.unavailable((LatestAssetsUnavailableException) error);
        return response.getStatusCode().value() + "|" + response.getBody().getTitle()
                + "|" + response.getBody().getDetail();
    }
}

package com.steven.assets.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.latestassets.LatestAssetsPayloadException;
import com.steven.assets.bff.latestassets.LatestAssetsPublicService;
import com.steven.assets.bff.latestassets.LatestAssetsUnavailableException;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公開 latest endpoint 不得把登入者或管理者代看的 Reactor tenant context 帶到 business。
 * 同一個 client 的 tenant filter 會覆寫 X-User-*；故這是 context 隔離而非只測 service 有沒有
 * 呼叫 configured-admin 的回歸測試。
 */
class LatestAssetsConfiguredAdminContextTest {

    private static final String ADMIN_JSON = """
            {"id":1,"email":"owner@example.com","role":"ADMIN","status":"ACTIVE","protectedAdmin":true}
            """;
    private static final String VALID_LATEST_JSON = """
            {"snapshot":{"id":8},"liveAssets":{"snapshotId":8}}
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

        assertThatThrownBy(() -> service.getLatest().block())
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

        assertThatThrownBy(() -> inactive.getLatest().block())
                .isInstanceOf(LatestAssetsUnavailableException.class);
        assertThatThrownBy(() -> ordinaryAdmin.getLatest().block())
                .isInstanceOf(LatestAssetsUnavailableException.class);
    }

    @Test
    void financialJsonIsRelayedByteForByteWithoutMapDoubleRoundTrip() {
        String payload = "{\"preciseAmount\":0.123456789012345678901234567890,"
                + "\"snapshot\":{\"id\":8},\"liveAssets\":{\"snapshotId\":8}}";
        LatestAssetsPublicService service = serviceFor(ADMIN_JSON, payload, new ArrayList<>());

        byte[] body = service.getLatest().block().getBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void malformedOrMismatchedSuccessPayloadFailsClosed() {
        LatestAssetsPublicService empty = serviceFor(ADMIN_JSON, "", new ArrayList<>());
        LatestAssetsPublicService malformed = serviceFor(ADMIN_JSON, "not-json", new ArrayList<>());
        LatestAssetsPublicService mismatch = serviceFor(ADMIN_JSON,
                "{\"snapshot\":{\"id\":8},\"liveAssets\":{\"snapshotId\":9}}", new ArrayList<>());

        assertThatThrownBy(() -> empty.getLatest().block()).isInstanceOf(LatestAssetsPayloadException.class);
        assertThatThrownBy(() -> malformed.getLatest().block()).isInstanceOf(LatestAssetsPayloadException.class);
        assertThatThrownBy(() -> mismatch.getLatest().block()).isInstanceOf(LatestAssetsPayloadException.class);
    }

    @Test
    void downstreamNonSuccessStatusBodyAndContentTypeAreRelayed() {
        String problem = "{\"title\":\"No snapshot\"}";
        LatestAssetsPublicService service = serviceFor(
                ADMIN_JSON, problem, HttpStatus.NOT_FOUND, MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new ArrayList<>());

        var response = service.getLatest().block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(problem);
    }

    private void assertLatestHeaders(Context context) {
        List<ClientRequest> calls = new ArrayList<>();
        LatestAssetsPublicService service = serviceFor(ADMIN_JSON, VALID_LATEST_JSON, calls);

        service.getLatest().contextWrite(context).block();

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
}

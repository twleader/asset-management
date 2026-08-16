package com.steven.assets.bff.config;

import com.steven.assets.bff.portfolioadvice.PublicPortfolioAdviceService;
import com.steven.assets.bff.portfolioadvice.PublicPortfolioAdviceUnavailableException;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公開「最新資產配置建議」端點（Requirement 79 / Task 338）不得把登入者或管理者代看的 Reactor
 * tenant context 帶到 business。同一個 client 的 tenant filter 會覆寫 {@code X-User-*}；故這是
 * context 隔離測試，而非只測 service 有沒有呼叫 configured-admin 的回歸測試。
 *
 * <p>本檔置於 {@code config} package，因為要組出真實行為必須套用
 * {@link WebClientConfig#tenantHeaderFilter()}（package-private），比照既有
 * {@code LatestAssetsConfiguredAdminContextTest} 的既有手法。
 */
class PublicPortfolioAdviceConfiguredAdminContextTest {

    private static final String ADMIN_JSON = """
            {"id":1,"email":"owner@example.com","role":"ADMIN","status":"ACTIVE","protectedAdmin":true}
            """;
    private static final String ADVICE_JSON = """
            {"status":"OK","targetAllocation":{"stock":0.6}}
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

        assertThatThrownBy(() -> service.latest().block())
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

        assertThatThrownBy(() -> inactive.latest().block())
                .isInstanceOf(PublicPortfolioAdviceUnavailableException.class);
        assertThatThrownBy(() -> ordinaryAdmin.latest().block())
                .isInstanceOf(PublicPortfolioAdviceUnavailableException.class);
    }

    /** 建議配置比例等金融數值不可先 decode 成 Map/Double 再重編碼：逐位元組 relay。 */
    @Test
    void financialJsonIsRelayedByteForByteWithoutMapDoubleRoundTrip() {
        String payload = "{\"status\":\"OK\",\"targetAllocation\":{\"stock\":0.123456789012345678901234567890}}";
        PublicPortfolioAdviceService service = serviceFor(ADMIN_JSON, payload, new ArrayList<>());

        byte[] body = service.latest().block().getBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    /** downstream 非 2xx 屬 byte-relay 範圍：狀態碼、body 與 content type 原樣傳回。 */
    @Test
    void downstreamNonSuccessStatusBodyAndContentTypeAreRelayed() {
        String problem = "{\"title\":\"No advice\"}";
        PublicPortfolioAdviceService service = serviceFor(
                ADMIN_JSON, problem, HttpStatus.NOT_FOUND, MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new ArrayList<>());

        var response = service.latest().block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(problem);
    }

    private void assertAdviceHeaders(Context context) {
        List<ClientRequest> calls = new ArrayList<>();
        PublicPortfolioAdviceService service = serviceFor(ADMIN_JSON, ADVICE_JSON, calls);

        service.latest().contextWrite(context).block();

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
}

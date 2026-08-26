package com.steven.assets.bff.config;

import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
import com.steven.assets.bff.tradingradar.PublicTradingRadarService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.util.context.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 86：configured-admin headers 不得被匿名 caller 或代看 context 覆寫。 */
class PublicTradingRadarConfiguredAdminContextTest {

    private static final String ADMIN_JSON = """
            {"id":1,"email":"owner@example.invalid","role":"ADMIN","status":"ACTIVE","protectedAdmin":true}
            """;
    private static final String RADAR_JSON = PublicContractJsonFixtures.TRADING_RADAR_LIST;

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
                var response = service.today().contextWrite(context).block();

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
}

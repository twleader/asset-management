package com.steven.assets.bff.config;

import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.publictransaction.PublicTransactionHistoryService;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.security.TenantIdentity;
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

/** Requirement 112: configured admin, not Reactor caller identity, selects the ledger owner. */
class PublicTransactionHistoryConfiguredAdminContextTest {

    private static final String ADMIN_JSON =
            "{\"id\":7,\"email\":\"owner@example.invalid\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";

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

            var relay = service.current(List.of("2026"), null, null)
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
}

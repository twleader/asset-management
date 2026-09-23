package com.steven.assets.bff.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import com.steven.assets.bff.tradingradar.TradingRadarPanelService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.steven.assets.bff.tradingradar.TradingRadarPanelFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the actual production Reactor tenant filter, not a copied header implementation. */
class TradingRadarPanelTenantRelayTest {
    @Test
    void allEightMethodsUseCurrentOwnerAndNeverCacheAcrossOwners() {
        List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        WebClient client = WebClient.builder().baseUrl("http://business")
                .filter(WebClientConfig.tenantHeaderFilter()).exchangeFunction(request -> {
                    requests.add(request);
                    String owner = request.headers().getFirst(AuthConstants.HDR_USER_ID);
                    assertThat(owner).isIn("77", "88");
                    assertThat(request.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
                    assertThat(request.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
                    String path = request.url().getPath();
                    ObjectNode body;
                    if (path.contains("/panels/")) {
                        body = panel(path.substring(path.lastIndexOf('/') + 1));
                        if (path.endsWith("tw-stocks")) {
                            ((ObjectNode) body.get("data").get("stocks").get(0)).put("stockName", "owner " + owner);
                        }
                    } else if (path.endsWith("stock-evaluation")) {
                        body = evaluation("台股", "2330");
                    } else {
                        body = job(JOB_ID, "QUEUED");
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body.toString()).build());
                }).build();
        TradingRadarPanelService service = new TradingRadarPanelService(client);
        Mono<List<String>> owners = Mono.zip(invokeAll(service, 77L), invokeAll(service, 88L)).map(pair -> List.of(pair.getT1(), pair.getT2()));
        assertThat(owners.block()).containsExactly("owner 77", "owner 88");
        assertThat(requests).hasSize(16);
        for (String owner : List.of("77", "88")) {
            assertThat(requests.stream().filter(request -> owner.equals(request.headers().getFirst(AuthConstants.HDR_USER_ID))))
                    .hasSize(8);
        }
        assertThat(requests).allSatisfy(request -> assertThat(request.url().getHost()).isEqualTo("business"));
    }

    private Mono<String> invokeAll(TradingRadarPanelService service, long owner) {
        return Mono.zip(service.twMarket(), service.usMarket(), service.twStocks(), service.usStocks(),
                        service.publicInformation(), service.stockEvaluation("2330", "台股"),
                        service.startRefreshJob(), service.refreshJob(JOB_ID))
                .map(results -> results.getT3().data().get("stocks").get(0).get("stockName").textValue())
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(owner, "USER", "ACTIVE")));
    }
}

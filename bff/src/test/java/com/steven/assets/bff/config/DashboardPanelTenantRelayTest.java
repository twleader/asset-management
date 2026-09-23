package com.steven.assets.bff.config;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.DashboardLookthroughService;
import com.steven.assets.bff.dashboard.DashboardPanelService;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用 production tenant filter 驗證平行與相依鏈；同一 service 跨 owner 不共用資料。 */
class DashboardPanelTenantRelayTest {
    @Test
    void everyPanelPropagatesTheCurrentOwnerThroughDetailHistoryCloseAndEtfRequests() {
        List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        WebClient client = WebClient.builder().baseUrl("http://business")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> {
                    requests.add(request);
                    String owner = request.headers().getFirst(AuthConstants.HDR_USER_ID);
                    assertThat(owner).isIn("77", "88");
                    assertThat(request.headers().getFirst(AuthConstants.HDR_USER_ROLE)).isEqualTo("USER");
                    assertThat(request.headers().getFirst(AuthConstants.HDR_USER_STATUS)).isEqualTo("ACTIVE");
                    String body = switch (request.url().getPath()) {
                        case "/api/snapshots" -> "[{\"id\":15,\"snapshotDate\":\"2020-01-02\"}]";
                        case "/api/snapshots/15" -> """
                                {"id":15,"snapshotDate":"2020-01-02","totalAssets":%s,"usdExchangeRate":30,
                                 "deposits":[],"funds":[],"stocks":[
                                  {"stockCode":"0050","stockName":"元大台灣50","market":"台股","shares":1,
                                   "currentValue":100,"investmentCost":80},
                                  {"stockCode":"VTI","stockName":"Vanguard","market":"美股","shares":1,
                                   "currentValue":300,"investmentCost":250}]}
                                """.formatted(owner);
                        case "/api/snapshots/history" -> "[{\"id\":15,\"snapshotDate\":\"2020-01-02\",\"totalAssets\":" + owner + "}]";
                        case "/api/snapshots/15/holdings-classified", "/api/market-data/history/prices-on-date" -> "[]";
                        case "/api/market-data/etf-holdings" -> "{\"supported\":false,\"holdings\":[]}";
                        case "/api/market-data/live-assets", "/api/market-data/market-status" -> "{}";
                        default -> throw new AssertionError("Unexpected path " + request.url());
                    };
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build());
                }).build();
        SnapshotEnricher enricher = new SnapshotEnricher(client);
        DashboardPanelService service = new DashboardPanelService(client, enricher, new DashboardLookthroughService(client));
        for (long owner : List.of(77L, 88L)) {
            Context identity = Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(owner, "USER", "ACTIVE"));
            var kpis = service.kpis(15L).contextWrite(identity).block();
            assertThat(((Map<?, ?>) kpis.data().get("snapshot")).get("totalAssets")).isEqualTo((int) owner);
            service.allocation(15L, "category").contextWrite(identity).block();
            service.allocation(15L, "assetClass").contextWrite(identity).block();
            service.allocation(15L, "twStock").contextWrite(identity).block();
            service.allocation(15L, "usStock").contextWrite(identity).block();
            service.trend().contextWrite(identity).block();
            service.deposits(15L).contextWrite(identity).block();
            service.funds(15L).contextWrite(identity).block();
            service.stockValues(15L).contextWrite(identity).block();
            service.holdings(15L).contextWrite(identity).block();
            service.snapshots().contextWrite(identity).block();
        }
        for (String owner : List.of("77", "88")) {
            assertThat(requests.stream().filter(request -> owner.equals(request.headers().getFirst(AuthConstants.HDR_USER_ID)))
                    .map(request -> request.url().getPath()).toList())
                    .contains("/api/snapshots/15", "/api/snapshots/history", "/api/snapshots/15/holdings-classified",
                            "/api/market-data/history/prices-on-date", "/api/market-data/etf-holdings",
                            "/api/market-data/live-assets", "/api/market-data/market-status", "/api/snapshots");
        }
        assertThat(requests).allSatisfy(request -> assertThat(request.url().getHost()).isEqualTo("business"));
    }
}

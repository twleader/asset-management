package com.steven.assets.bff.common;

import com.steven.assets.bff.assethistory.AssetHistoryBffController;
import com.steven.assets.bff.realizedgain.RealizedGainBffController;
import com.steven.assets.bff.commodityprice.CommodityPriceBffController;
import com.steven.assets.bff.exchangerate.ExchangeRateBffController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ExportScheduleRetiredHttpTest {
    enum Page { ASSETS, GAIN, COMMODITY, RATE }
    @ParameterizedTest @EnumSource(Page.class)
    void allRetiredHttpRoutesReturnRefreshDetailAndNeverCallUpstream(Page page) {
        var calls = new AtomicInteger();
        var upstream = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.error(new AssertionError("Unexpected retired upstream request"));
        }).build();
        Object controller = switch (page) {
            case ASSETS -> new AssetHistoryBffController(upstream);
            case GAIN -> new RealizedGainBffController(upstream);
            case COMMODITY -> new CommodityPriceBffController(upstream);
            case RATE -> new ExchangeRateBffController(upstream);
        };
        String settings = switch (page) {
            case ASSETS -> "/api/bff/asset-history/export-schedule";
            case GAIN -> "/api/bff/realized-gain/export/schedule";
            case COMMODITY -> "/api/bff/commodity-price/export/schedule";
            case RATE -> "/api/bff/exchange-rate/export/schedule";
        };
        String run = page == Page.ASSETS ? settings + "/run-now" : settings.replace("/schedule", "/run-now");
        var client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ExportScheduleHttpErrorAdvice()).build();
        client.get().uri(settings).exchange().expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.detail").value(value -> assertThat(value.toString()).contains("重新整理"));
        client.put().uri(settings).bodyValue("{}").exchange().expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.detail").value(value -> assertThat(value.toString()).contains("重新整理"));
        client.post().uri(run).exchange().expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.detail").value(value -> assertThat(value.toString()).contains("重新整理"));
        assertThat(calls.get()).isZero();
    }
}

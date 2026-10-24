package com.steven.assets.bff.config;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.snapshotform.SnapshotFormBffController;
import com.steven.assets.bff.snapshotform.SnapshotFormPanelService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotFormQuoteTimestampTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enrichedPricesCarryOnlyTheSelectedSourcesTimestamp(boolean current) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        String date = (current ? today : today.minusDays(7)).toString();
        var client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
            String body = switch (request.url().getPath()) {
                case "/api/market-data/history/prices-on-date" -> "[{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":100,\"updatedAt\":\"historical\"}]";
                case "/api/market-data/prices" -> "[{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":110,\"updatedAt\":\"2026-10-23T10:00:00+08:00\"}]";
                default -> "{}";
            };
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build());
        }).build();
        var enricher = new SnapshotEnricher(client);
        var controller = new SnapshotFormBffController(client, enricher, new SnapshotFormPanelService(client, enricher));
        var response = controller.batchPrices(date, List.of(Map.of("code", "2330", "market", "台股"))).block();
        assertThat(response.getBody()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("updatedAt", current ? "2026-10-23T10:00:00+08:00" : "historical");
            assertThat(row).doesNotContainKey("timestamp");
        });
    }
}

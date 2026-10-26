package com.steven.assets.integration.marketdata;

import com.steven.assets.service.port.PriceRefreshPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PriceRefreshHttpAdapter implements PriceRefreshPort {
    private final WebClient client;
    public PriceRefreshHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).build();
    }
    public JsonNode refreshTradingRadarPrices() {
        return client.post()
                .uri("/internal/refresh/tw-radar")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(java.time.Duration.ofSeconds(30));
    }

    public java.util.Map<String, Object> triggerRefresh() {
        client.post()
                .uri("/internal/refresh")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .subscribe(
                        resp -> log.info("price-service refresh 完成 tw={} us={}",
                                resp.path("twUpdated").asInt(0),
                                resp.path("usUpdated").asInt(0)),
                        err -> log.warn("呼叫 price-service /internal/refresh 失敗: {}", err.getMessage())
                );
        return java.util.Map.of("triggered", true);
    }

}

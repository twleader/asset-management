package com.steven.assets.integration.marketdata;

import com.steven.assets.service.port.DividendSyncPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class DividendSyncHttpAdapter implements DividendSyncPort {
    private final WebClient client;
    public DividendSyncHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).build();
    }
    @Override public void sync(String code, String market) {
        client.post()
                        .uri(uri -> uri.path("/internal/dividend/sync")
                                .queryParam("code", code)
                                .queryParam("market", market).build())
                        .retrieve()
                        .toBodilessEntity()
                        .block();
    }
}

package com.steven.assets.client;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.service.TreasuryYieldClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/** WebClient adapter for the external-materials Treasury endpoint. */
@Component
public class ExternalTreasuryYieldClient implements TreasuryYieldClient {

    private static final String INTERNAL_ROLE_HEADER = "X-User-Role";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final WebClient client;
    private final String treasuryToken;

    @Autowired
    public ExternalTreasuryYieldClient(
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String baseUrl,
            @Value("${asset.internal.treasury-token:}") String treasuryToken) {
        this(WebClient.builder().baseUrl(baseUrl).build(), treasuryToken);
    }

    ExternalTreasuryYieldClient(WebClient client, String treasuryToken) {
        this.client = client;
        this.treasuryToken = treasuryToken;
    }

    @Override
    public List<TreasuryYieldDto.FetchBatch> fetch(int year) {
        if (treasuryToken == null || treasuryToken.isBlank()) {
            throw new IllegalStateException("Treasury internal service credential 未設定");
        }
        List<TreasuryYieldDto.FetchBatch> batches = client.get()
                .uri(uri -> uri.path("/internal/macro/treasury-yield")
                        .queryParam("year", year).build())
                .header(INTERNAL_TOKEN_HEADER, treasuryToken)
                // Supplementary authorization context only.  The external
                // service authenticates this request with the shared token;
                // this caller-supplied role is never sufficient by itself.
                .header(INTERNAL_ROLE_HEADER, "ADMIN")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<TreasuryYieldDto.FetchBatch>>() {})
                .block();
        return batches == null ? List.of() : List.copyOf(batches);
    }
}

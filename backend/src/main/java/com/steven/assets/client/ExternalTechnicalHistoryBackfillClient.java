package com.steven.assets.client;

import com.steven.assets.dto.TradingRadarTechnicalBackfillJobDto;
import com.steven.assets.integration.fubon.FubonConfigState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class ExternalTechnicalHistoryBackfillClient {
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String BASE_PATH = "/internal/technical-indicators/history-backfill-jobs";
    private final WebClient client;
    private final FubonConfigState config;

    public ExternalTechnicalHistoryBackfillClient(
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String baseUrl,
            FubonConfigState config) {
        this.client = WebClient.builder().baseUrl(baseUrl).build();
        this.config = config;
    }

    public TradingRadarTechnicalBackfillJobDto start() {
        FubonConfigState.Snapshot snapshot = config.snapshot();
        if (snapshot.state() != FubonConfigState.State.READY) throw new IllegalStateException("FUBON_NOT_READY");
        return client.post().uri(BASE_PATH).header(TOKEN_HEADER, snapshot.token()).retrieve()
                .bodyToMono(TradingRadarTechnicalBackfillJobDto.class).block(Duration.ofSeconds(10));
    }

    public TradingRadarTechnicalBackfillJobDto get(String jobId) {
        FubonConfigState.Snapshot snapshot = config.snapshot();
        if (snapshot.state() != FubonConfigState.State.READY) throw new IllegalStateException("FUBON_NOT_READY");
        return client.get().uri(BASE_PATH + "/{jobId}", jobId).header(TOKEN_HEADER, snapshot.token()).retrieve()
                .bodyToMono(TradingRadarTechnicalBackfillJobDto.class).block(Duration.ofSeconds(10));
    }
}

package com.steven.assets.integration.crawler;

import com.steven.assets.service.port.CrawlerRunPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import com.steven.assets.dto.CrawlerExportPathDto.RunNowResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

@Component
public class CrawlerRunHttpAdapter implements CrawlerRunPort {
    private final WebClient client;
    public CrawlerRunHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).build();
    }
    @Override public RunNowResponse run(Operation operation, long timeoutSeconds, RunNowResponse running) {
        String path = switch (operation) {
            case EXPORT -> "/internal/news-poller/export-now";
            case FETCH_AND_EXPORT -> "/internal/news-poller/fetch-and-export-now";
            case PUBLIC_RESCAN -> "/internal/news-poller/public-rescan";
        };
        return client.post().uri(path).retrieve().bodyToMono(RunNowResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(TimeoutException.class, e -> Mono.just(running)).block();
    }
}

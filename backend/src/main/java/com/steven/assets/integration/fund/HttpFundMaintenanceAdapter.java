package com.steven.assets.integration.fund;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.service.port.FundMaintenancePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Component
@Slf4j
public class HttpFundMaintenanceAdapter implements FundMaintenancePort {
    private final WebClient externalClient;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpFundMaintenanceAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this(WebClient.builder().baseUrl(externalUrl).build());
    }
    HttpFundMaintenanceAdapter(WebClient externalClient) { this.externalClient = externalClient; }
    public Map<String, Object> refreshNav() {
        try {
            JsonNode resp = externalClient.post()
                    .uri("/internal/fund-nav/refresh")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return Map.of("success", 0, "failed", 0, "total", 0);
            return Map.of(
                    "success", resp.path("success").asInt(0),
                    "failed", resp.path("failed").asInt(0),
                    "total", resp.path("total").asInt(0));
        } catch (Exception e) {
            log.warn("呼叫 external-materials-service /internal/fund-nav/refresh 失敗: {}", e.toString());
            return Map.of("error", e.toString());
        }
    }

    public Map<String, Object> backfillNav(int years) {
        return proxyBackfill("/internal/fund-nav/backfill?years=" + years);
    }

    public Map<String, Object> backfillDividend(int years) {
        return proxyBackfill("/internal/fund-dividend/backfill?years=" + years);
    }

    private Map<String, Object> proxyBackfill(String path) {
        try {
            JsonNode resp = externalClient.post()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(java.time.Duration.ofMinutes(5));
            if (resp == null) return Map.of("fundOk", 0, "fundFail", 0, "total", 0, "rowsWritten", 0);
            return Map.of(
                    "fundOk", resp.path("fundOk").asInt(0),
                    "fundFail", resp.path("fundFail").asInt(0),
                    "total", resp.path("total").asInt(0),
                    "rowsWritten", resp.path("rowsWritten").asInt(0));
        } catch (Exception e) {
            log.warn("呼叫 external-materials-service {} 失敗: {}", path, e.toString());
            return Map.of("error", e.toString());
        }
    }

    public Map<String, Object> refreshDividend() {
        try {
            JsonNode resp = externalClient.post()
                    .uri("/internal/fund-dividend/refresh")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return Map.of("success", 0, "failed", 0, "total", 0, "written", 0);
            return Map.of(
                    "success", resp.path("success").asInt(0),
                    "failed", resp.path("failed").asInt(0),
                    "total", resp.path("total").asInt(0),
                    "written", resp.path("written").asInt(0));
        } catch (Exception e) {
            log.warn("呼叫 external-materials-service /internal/fund-dividend/refresh 失敗: {}", e.toString());
            return Map.of("error", e.toString());
        }
    }

}

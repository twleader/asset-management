package com.steven.assets.bff.portfolioadvice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合端點的下游回應由 exchangeFunction 完整控制，不連接 business service、資料庫或券商。
 */
class PortfolioAdviceBffControllerTest {

    private static final List<String> SECTIONS = List.of(
            "latest", "history", "profile", "settings", "currentAllocation", "projection");
    private static final String DOWNSTREAM_ERROR_SENTINEL = "must not reach the response";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 六區成功時回完整資料與空的fetchErrors() {
        ResponseEntity<Map<String, Object>> response = controller(successes()).get(20).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("fetchErrors", List.of());
        assertThat(response.getBody().get("history")).isEqualTo(List.of(Map.of("status", "OK")));
        assertThat(response.getBody().get("profile")).isEqualTo(Map.of("riskTolerance", "LOW"));
        assertThat(response.getBody().get("settings")).isEqualTo(Map.of("engine", "local"));
        assertThat(response.getBody().get("currentAllocation")).isEqualTo(Map.of("items", List.of()));
        assertThat(response.getBody().get("projection")).isEqualTo(Map.of("available", false));
        assertThat(asMap(response.getBody().get("latest")))
                .containsEntry("status", "OK")
                .containsKey("rebalanceGroups");
    }

    @Test
    void 單一HTTP失敗只標記該區並保留其他五區且不洩漏下游錯誤內容() throws JsonProcessingException {
        Map<String, Stub> stubs = successes();
        stubs.put("/api/portfolio-advice/profile", error());

        ResponseEntity<Map<String, Object>> response = controller(stubs).get(20).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("fetchErrors", List.of("profile"));
        assertThat(response.getBody().get("profile")).isEqualTo(Map.of());
        assertThat(response.getBody().get("history")).isEqualTo(List.of(Map.of("status", "OK")));
        assertThat(response.getBody().get("settings")).isEqualTo(Map.of("engine", "local"));
        assertThat(response.getBody().get("projection")).isEqualTo(Map.of("available", false));
        assertThat(JSON.writeValueAsString(response.getBody())).doesNotContain(DOWNSTREAM_ERROR_SENTINEL);
    }

    @Test
    void HTTP200但沒有body只讓該區降級() {
        Map<String, Stub> stubs = successes();
        stubs.put("/api/portfolio-advice/current-allocation", new Stub(HttpStatus.OK, null));

        ResponseEntity<Map<String, Object>> response = controller(stubs).get(20).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("fetchErrors", List.of("currentAllocation"));
        assertThat(response.getBody().get("currentAllocation")).isEqualTo(Map.of());
        assertThat(response.getBody().get("latest")).isInstanceOf(Map.class);
    }

    @Test
    void 六區全失敗仍回200與固定順序的識別值() {
        Map<String, Stub> failures = Map.of(
                "/api/portfolio-advice/latest", error(),
                "/api/portfolio-advice/history", error(),
                "/api/portfolio-advice/profile", error(),
                "/api/portfolio-advice/settings", error(),
                "/api/portfolio-advice/current-allocation", error(),
                "/api/portfolio-advice/projection", error());

        ResponseEntity<Map<String, Object>> response = controller(failures).get(20).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("fetchErrors", SECTIONS);
        assertThat(response.getBody().get("history")).isEqualTo(List.of());
        assertThat(asMap(response.getBody().get("latest")))
                .containsEntry("rebalanceGroups", List.of());
    }

    @Test
    void 合法空JSON與空歷史仍視為成功() {
        Map<String, Stub> stubs = successes();
        stubs.put("/api/portfolio-advice/latest", ok("{\"status\":\"NONE\"}"));
        stubs.put("/api/portfolio-advice/history", ok("[]"));
        stubs.put("/api/portfolio-advice/projection", ok("{\"available\":false}"));

        ResponseEntity<Map<String, Object>> response = controller(stubs).get(20).block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).containsEntry("fetchErrors", List.of());
        assertThat(asMap(response.getBody().get("latest"))).containsEntry("status", "NONE");
        assertThat(response.getBody().get("history")).isEqualTo(List.of());
        assertThat(response.getBody().get("projection")).isEqualTo(Map.of("available", false));
    }

    private static PortfolioAdviceBffController controller(Map<String, Stub> stubs) {
        WebClient client = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    Stub stub = stubs.get(request.url().getPath());
                    if (stub == null) return Mono.error(new AssertionError("unexpected path: " + request.url()));
                    ClientResponse.Builder response = ClientResponse.create(stub.status())
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    if (stub.body() != null) response.body(stub.body());
                    return Mono.just(response.build());
                })
                .build();
        return new PortfolioAdviceBffController(client);
    }

    private static Map<String, Stub> successes() {
        return new java.util.HashMap<>(Map.of(
                "/api/portfolio-advice/latest", ok("{\"status\":\"OK\",\"rebalancePlan\":[]}"),
                "/api/portfolio-advice/history", ok("[{\"status\":\"OK\"}]"),
                "/api/portfolio-advice/profile", ok("{\"riskTolerance\":\"LOW\"}"),
                "/api/portfolio-advice/settings", ok("{\"engine\":\"local\"}"),
                "/api/portfolio-advice/current-allocation", ok("{\"items\":[]}"),
                "/api/portfolio-advice/projection", ok("{\"available\":false}")));
    }

    private static Stub ok(String body) {
        return new Stub(HttpStatus.OK, body);
    }

    private static Stub error() {
        return new Stub(HttpStatus.BAD_GATEWAY, "{\"detail\":\"" + DOWNSTREAM_ERROR_SENTINEL + "\"}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private record Stub(HttpStatus status, String body) {}
}

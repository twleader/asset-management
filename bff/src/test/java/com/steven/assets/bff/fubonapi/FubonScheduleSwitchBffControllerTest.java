package com.steven.assets.bff.fubonapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FubonScheduleSwitchBffControllerTest {

    private final List<ClientRequest> seen = new ArrayList<>();

    private FubonScheduleSwitchBffController controller(String json) {
        WebClient client = WebClient.builder().exchangeFunction(req -> {
            seen.add(req);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(json).build());
        }).build();
        return new FubonScheduleSwitchBffController(client);
    }

    @Test
    void listForwardsToBusinessSettingsPath() {
        var body = controller("[{\"scheduleKey\":\"trade\",\"enabled\":true}]").list().block().getBody();
        assertThat(body).hasSize(1);
        assertThat(seen.get(0).method()).isEqualTo(HttpMethod.GET);
        assertThat(seen.get(0).url().getPath()).isEqualTo("/api/settings/fubon-schedule-switches");
    }

    @Test
    void updateForwardsOnlyTheEnabledFlag() {
        var body = controller("{\"scheduleKey\":\"trade\",\"enabled\":false}")
                .update("trade", Map.of("enabled", false, "scheduleKey", "other")).block().getBody();
        assertThat(body).containsEntry("enabled", false);
        assertThat(seen.get(0).method()).isEqualTo(HttpMethod.PUT);
        assertThat(seen.get(0).url().getPath()).isEqualTo("/api/settings/fubon-schedule-switches/trade");
    }

    @Test
    void invalidEnabledIsRejectedWith400WithoutCallingBusiness() {
        var controller = controller("{}");
        java.util.HashMap<String, Object> nullValue = new java.util.HashMap<>();
        nullValue.put("enabled", null);
        for (Map<String, Object> bad : List.of(Map.<String, Object>of(), nullValue, Map.<String, Object>of("enabled", "true"))) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> controller.update("trade", bad).block());
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(seen).isEmpty();
    }
}

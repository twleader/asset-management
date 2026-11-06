package com.steven.assets.bff.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionBffControllerTest {
    @Test void businessRejectionsPassThroughAllThreeAuthenticatedPaths() {
        for (int status : List.of(400, 403, 404)) {
            var client = WebClient.builder().baseUrl("http://business").exchangeFunction(request ->
                    Mono.just(ClientResponse.create(org.springframework.http.HttpStatusCode.valueOf(status))
                            .header("Content-Type", "application/problem+json")
                            .body("{\"status\":" + status + ",\"detail\":\"拒絕\"}").build())).build();
            var controller = new TransactionBffController(client);
            var responses = List.of(controller.create(Map.of()).block(), controller.update(1L, Map.of()).block(),
                    controller.classification("股票", "00881", "台股", "名稱").block());
            for (var response : responses) {
                assertThat(response.getStatusCode().value()).isEqualTo(status);
                assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
                assertThat(response.getBody()).containsEntry("detail", "拒絕");
            }
        }
    }
    @Test void listAddsCategoriesFromSameSettingsAuthorityIncludingInactive() {
        var client = WebClient.builder().baseUrl("http://business").exchangeFunction(request -> {
            String body = request.url().getPath().endsWith("asset-classes")
                    ? "[{\"code\":\"EXTRA\",\"displayName\":\"其他\",\"active\":false}]" : "[]";
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", "application/json").body(body).build());
        }).build();
        var result = new TransactionBffController(client).getAll().block();
        assertThat((List<?>) result.getBody().get("categories")).hasSize(1);
        assertThat(result.getBody().get("categories").toString()).contains("EXTRA", "active=false");
    }
}

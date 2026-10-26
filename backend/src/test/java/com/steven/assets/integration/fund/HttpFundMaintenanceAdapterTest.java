package com.steven.assets.integration.fund;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class HttpFundMaintenanceAdapterTest {
    @Test
    void fourFixedPostCapabilitiesKeepOnlyOriginalCountFieldsAndYears() {
        List<ClientRequest> requests = new ArrayList<>();
        var adapter = adapter(requests, "{\"success\":2,\"failed\":3,\"total\":5,\"written\":7,\"fundOk\":11,\"fundFail\":13,\"rowsWritten\":17,\"extra\":99}");
        assertThat(adapter.refreshNav()).isEqualTo(Map.of("success",2,"failed",3,"total",5));
        assertThat(adapter.refreshDividend()).isEqualTo(Map.of("success",2,"failed",3,"total",5,"written",7));
        assertThat(adapter.backfillNav(12)).isEqualTo(Map.of("fundOk",11,"fundFail",13,"total",5,"rowsWritten",17));
        assertThat(adapter.backfillDividend(-1)).isEqualTo(Map.of("fundOk",11,"fundFail",13,"total",5,"rowsWritten",17));
        assertThat(requests).extracting(r -> r.url().toString()).containsExactly(
                "http://fixture/internal/fund-nav/refresh", "http://fixture/internal/fund-dividend/refresh",
                "http://fixture/internal/fund-nav/backfill?years=12", "http://fixture/internal/fund-dividend/backfill?years=-1");
        assertThat(requests).allMatch(r -> r.method() == HttpMethod.POST);
    }
    @Test
    void emptyBodiesAndMissingFieldsKeepOriginalZeroShape() {
        var empty = new HttpFundMaintenanceAdapter(WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build())).build());
        assertThat(empty.refreshNav()).isEqualTo(Map.of("success",0,"failed",0,"total",0));
        assertThat(empty.refreshDividend()).isEqualTo(Map.of("success",0,"failed",0,"total",0,"written",0));
        assertThat(empty.backfillNav(10)).isEqualTo(Map.of("fundOk",0,"fundFail",0,"total",0,"rowsWritten",0));
        assertThat(adapter(new ArrayList<>(), "{}").backfillDividend(10)).isEqualTo(empty.backfillNav(10));
    }
    @Test
    void failuresStayErrorResponsesRatherThanSuccessfulCounts() {
        var failed = new HttpFundMaintenanceAdapter(WebClient.builder().exchangeFunction(r -> Mono.error(new IllegalStateException("fixture failure"))).build());
        for (var result : List.of(failed.refreshNav(), failed.refreshDividend(), failed.backfillNav(10), failed.backfillDividend(10)))
            assertThat(result).containsOnlyKeys("error").hasEntrySatisfying("error", error -> assertThat(error.toString()).contains("fixture failure"));
    }
    private HttpFundMaintenanceAdapter adapter(List<ClientRequest> requests, String body) {
        return new HttpFundMaintenanceAdapter(WebClient.builder().baseUrl("http://fixture").exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(body).build());
        }).build());
    }
}

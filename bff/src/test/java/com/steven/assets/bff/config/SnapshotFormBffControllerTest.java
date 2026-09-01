package com.steven.assets.bff.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.common.BusinessErrorAdvice;
import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import com.steven.assets.bff.snapshotform.SnapshotFormBffController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 410：SnapshotForm 的新增／更新必須沿用 tenant-aware businessServicesClient。
 *
 * <p>測試放在 config package 是為了直接以 {@link WebClientConfig#tenantHeaderFilter()} 組裝
 * 可控 exchange function 的 WebClient，而非複製 filter 邏輯。所有 downstream response 都是
 * in-memory fixture，不會啟動服務或寫入任何使用者快照。
 */
class SnapshotFormBffControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Context TENANT = Context.of(AuthConstants.CTX_IDENTITY,
            new TenantIdentity(42L, "USER", "ACTIVE"));
    private static final String SUCCESS_JSON =
            "{\"id\":73,\"snapshotDate\":\"2026-09-01\",\"notes\":\"forwarded\"}";
    private static final String PROBLEM_JSON =
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                    + "\"detail\":\"該日期的快照已存在\"}";

    private record CapturedRequest(HttpMethod method, String path, String body,
                                   String userId, String role, String status) {}

    @Test
    void listSnapshots_forwardsGetToBusinessSnapshots() {
        CopyOnWriteArrayList<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        SnapshotFormBffController controller = controller(capturingClient(calls, HttpStatus.OK,
                "[{\"id\":73,\"snapshotDate\":\"2026-09-01\"}]"));

        List<Map<String, Object>> body = controller.listSnapshots().contextWrite(TENANT).block();

        assertThat(body).singleElement().satisfies(row ->
                assertThat(row).containsEntry("id", 73));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo(HttpMethod.GET);
            assertThat(call.path()).isEqualTo("/api/snapshots");
            assertTenantHeaders(call);
        });
    }

    @Test
    void createSnapshot_forwardsPostPayloadSuccessJsonAndTenantHeaders() throws Exception {
        CopyOnWriteArrayList<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        SnapshotFormBffController controller = controller(capturingClient(calls, HttpStatus.OK, SUCCESS_JSON));
        Map<String, Object> payload = payload("2026-09-01", "new snapshot");

        Map<String, Object> response = controller.createSnapshot(payload).contextWrite(TENANT).block();

        assertThat(response).containsEntry("id", 73).containsEntry("notes", "forwarded");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo(HttpMethod.POST);
            assertThat(call.path()).isEqualTo("/api/snapshots");
            assertTenantHeaders(call);
        });
        assertThat(MAPPER.readTree(calls.getFirst().body())).isEqualTo(MAPPER.valueToTree(payload));
    }

    @Test
    void updateSnapshot_forwardsPutPayloadSuccessJsonAndTenantHeaders() throws Exception {
        CopyOnWriteArrayList<CapturedRequest> calls = new CopyOnWriteArrayList<>();
        SnapshotFormBffController controller = controller(capturingClient(calls, HttpStatus.OK, SUCCESS_JSON));
        Map<String, Object> payload = payload("2026-09-02", "updated snapshot");

        Map<String, Object> response = controller.updateSnapshot(73L, payload).contextWrite(TENANT).block();

        assertThat(response).containsEntry("id", 73).containsEntry("notes", "forwarded");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo(HttpMethod.PUT);
            assertThat(call.path()).isEqualTo("/api/snapshots/73");
            assertTenantHeaders(call);
        });
        assertThat(MAPPER.readTree(calls.getFirst().body())).isEqualTo(MAPPER.valueToTree(payload));
    }

    @Test
    void createSnapshot_businessProblemDetailKeeps400ContentTypeAndDetail() {
        WebTestClient client = webTestClient(HttpStatus.BAD_REQUEST, PROBLEM_JSON);

        client.post().uri("/api/bff/snapshot-form")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("2026-09-01", "duplicate"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("該日期的快照已存在");
    }

    @Test
    void updateSnapshot_businessProblemDetailKeeps400ContentTypeAndDetail() {
        WebTestClient client = webTestClient(HttpStatus.BAD_REQUEST, PROBLEM_JSON);

        client.put().uri("/api/bff/snapshot-form/73")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("2026-09-01", "duplicate"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("該日期的快照已存在");
    }

    private static Map<String, Object> payload(String snapshotDate, String notes) {
        return Map.of("snapshotDate", snapshotDate, "notes", notes,
                "deposits", List.of(Map.of("bankId", 1, "amount", 1000)));
    }

    private static SnapshotFormBffController controller(WebClient client) {
        return new SnapshotFormBffController(client, mock(SnapshotEnricher.class));
    }

    private static WebTestClient webTestClient(HttpStatus status, String body) {
        return WebTestClient.bindToController(controller(WebClient.builder()
                        .baseUrl("http://business")
                        .filter(WebClientConfig.tenantHeaderFilter())
                        .exchangeFunction(request -> response(status, body))
                        .build()))
                .controllerAdvice(new BusinessErrorAdvice())
                .build();
    }

    private static WebClient capturingClient(CopyOnWriteArrayList<CapturedRequest> calls,
                                               HttpStatus status, String responseBody) {
        return WebClient.builder()
                .baseUrl("http://business")
                .filter(WebClientConfig.tenantHeaderFilter())
                .exchangeFunction(request -> requestBody(request).flatMap(body -> {
                    calls.add(new CapturedRequest(request.method(), request.url().getPath(), body,
                            request.headers().getFirst(AuthConstants.HDR_USER_ID),
                            request.headers().getFirst(AuthConstants.HDR_USER_ROLE),
                            request.headers().getFirst(AuthConstants.HDR_USER_STATUS)));
                    return response(status, responseBody);
                }))
                .build();
    }

    private static Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE,
                        status.isError() ? MediaType.APPLICATION_PROBLEM_JSON_VALUE : MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private static Mono<String> requestBody(ClientRequest request) {
        if (request.method() == HttpMethod.GET) {
            return Mono.just("");
        }
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), request.url());
        return request.body().insert(output, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        // getBodyAsString 必須在 body inserter 完成後才建立；否則它會抓到 Mock request
        // 建構時的 sentinel error，而不是已 cache 的 request body。
        }).then(Mono.defer(output::getBodyAsString));
    }

    private static void assertTenantHeaders(CapturedRequest call) {
        assertThat(call.userId()).isEqualTo("42");
        assertThat(call.role()).isEqualTo("USER");
        assertThat(call.status()).isEqualTo("ACTIVE");
    }
}

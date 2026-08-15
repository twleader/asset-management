package com.steven.assets.bff.crawlerdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PublicCrawlerRescanService}（Requirement 71 / Task 329）：轉呼 business
 * {@code POST /api/crawler-export-path/public-rescan}，逐欄位透傳、<b>不做 onErrorReturn 降級</b>——
 * 呼叫端需要看到 business 端真實失敗（由 {@link PublicCrawlerRescanExceptionAdvice} 消毒，
 * 不在本服務層處理）。
 */
class PublicCrawlerRescanServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 呼叫businessPublicRescan端點且以POST逐欄位透傳body() {
        CopyOnWriteArrayList<ClientRequest> requests = new CopyOnWriteArrayList<>();
        PublicCrawlerRescanService service = service(request -> {
            requests.add(request);
            return jsonResponse(Map.of(
                    "status", "OK", "mode", "FETCH_AND_EXPORT", "upserted", 3, "failed", 0));
        });

        Map<String, Object> body = service.rescan().block();

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).url().getPath()).isEqualTo("/api/crawler-export-path/public-rescan");
        assertThat(requests.get(0).method().name()).isEqualTo("POST");
        assertThat(body).containsEntry("status", "OK")
                .containsEntry("mode", "FETCH_AND_EXPORT")
                .containsEntry("upserted", 3)
                .containsEntry("failed", 0);
    }

    /** business 回非 2xx 時不吞成空物件／預設值——直接以例外訊號往上冒，交由 ExceptionAdvice 消毒。 */
    @Test
    void business非2xx時不降級直接拋出WebClientResponseException() {
        PublicCrawlerRescanService service = service(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"detail\":\"internal secret detail\"}")
                        .build()));

        assertThatThrownBy(() -> service.rescan().block())
                .isInstanceOf(WebClientResponseException.class);
    }

    /** 連線失敗（transport 例外）同樣不吞——不得回退成空物件讓呼叫端誤以為成功。 */
    @Test
    void 連線失敗時不降級直接拋出例外() {
        AtomicInteger calls = new AtomicInteger();
        PublicCrawlerRescanService service = service(request -> {
            calls.incrementAndGet();
            return Mono.error(new IOException("connection refused"));
        });

        assertThatThrownBy(() -> service.rescan().block()).isNotNull();
        assertThat(calls).hasValue(1);
    }

    private static PublicCrawlerRescanService service(
            Function<ClientRequest, Mono<ClientResponse>> exchangeFunction) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(exchangeFunction::apply)
                .build();
        return new PublicCrawlerRescanService(downstream);
    }

    private static Mono<ClientResponse> jsonResponse(Object body) {
        try {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(MAPPER.writeValueAsString(body))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

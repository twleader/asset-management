package com.steven.assets.bff.todaymarketanalysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.security.AuthConstants;
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
 * {@link PublicMarketAnalysisService}（Requirement 79 / Task 338）：轉呼 business
 * {@code GET /api/market-analysis/today}，<b>不做 onErrorReturn 降級</b>——呼叫端需要看到 business
 * 端真實失敗（由 {@link PublicMarketAnalysisExceptionAdvice} 消毒，不在本服務層處理）。
 *
 * <p>本條資料是全域參考（{@code daily_market_analysis} 無 {@code owner_user_id}），故服務層
 * <b>不做 configured-admin bootstrap、也不顯式帶 {@code X-User-*}</b>——這一點與第八條
 * （owner-scoped 的 {@code PublicPortfolioAdviceService}）刻意不同。
 */
class PublicMarketAnalysisServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 以GET呼叫businessToday端點且不帶租戶header() {
        CopyOnWriteArrayList<ClientRequest> requests = new CopyOnWriteArrayList<>();
        PublicMarketAnalysisService service = service(request -> {
            requests.add(request);
            return jsonResponse(Map.of("status", "OK", "analysisDate", "2026-08-16"));
        });

        Map<String, Object> body = service.today().block();

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).url().getPath()).isEqualTo("/api/market-analysis/today");
        assertThat(requests.get(0).method().name()).isEqualTo("GET");
        // 全域參考資料：服務層不自行帶入任何身分 header（沒有 owner 概念可解析）
        assertThat(requests.get(0).headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
        assertThat(requests.get(0).headers().containsKey(AuthConstants.HDR_USER_ROLE)).isFalse();
        assertThat(requests.get(0).headers().containsKey(AuthConstants.HDR_USER_STATUS)).isFalse();
        assertThat(body).containsEntry("status", "OK").containsEntry("analysisDate", "2026-08-16");
    }

    /**
     * business 非 2xx 必須以例外訊號往上冒。這正是本條刻意採
     * {@code .retrieve().bodyToMono(...)}（而非 byte-relay {@code exchangeToMono}）的理由：
     * byte-relay 不會擲例外，business 原始 body 會原樣落到匿名呼叫者手上，
     * {@link PublicMarketAnalysisExceptionAdvice} 的 handler 也會變成死碼。
     */
    @Test
    void business非2xx時不降級直接拋出WebClientResponseException() {
        PublicMarketAnalysisService service = service(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"detail\":\"internal secret detail\"}")
                        .build()));

        assertThatThrownBy(() -> service.today().block())
                .isInstanceOf(WebClientResponseException.class);
    }

    /** 連線失敗同樣不吞——不得回退成 200 空物件讓呼叫端誤以為成功。 */
    @Test
    void 連線失敗時不降級直接拋出例外() {
        AtomicInteger calls = new AtomicInteger();
        PublicMarketAnalysisService service = service(request -> {
            calls.incrementAndGet();
            return Mono.error(new IOException("connection refused"));
        });

        assertThatThrownBy(() -> service.today().block()).isNotNull();
        assertThat(calls).hasValue(1);
    }

    private static PublicMarketAnalysisService service(
            Function<ClientRequest, Mono<ClientResponse>> exchangeFunction) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(exchangeFunction::apply)
                .build();
        return new PublicMarketAnalysisService(downstream);
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

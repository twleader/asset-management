package com.steven.assets.bff.portfolioadvice;

import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicPortfolioAdviceController}（Requirement 79 / Task 338）：只委派
 * {@link PublicPortfolioAdviceService}，匿名呼叫成功時<b>逐位元組</b>原樣 relay body。
 *
 * <p>本條走 byte-relay（{@code exchangeToMono} + {@code toEntity(byte[].class)}），金融數值不會被
 * decode 成 Map/Double 再重編碼，故這裡才用得了 byte 級斷言——第七條（走
 * {@code .retrieve().bodyToMono(Map)}）只能做逐欄語意等價比對，兩者刻意不同。
 */
class PublicPortfolioAdviceControllerTest {

    private static final String ADMIN_JSON =
            "{\"id\":1,\"email\":\"owner@example.com\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\",\"protectedAdmin\":true}";

    /**
     * controller 本身零 WebClient 互動：手刻替身（子類覆寫 {@code latest()}）只被委派一次，並用反射斷言
     * controller 類別上沒有任何 {@link WebClient} 型別欄位。替身的 {@code WebClient} 若真的被呼叫會
     * 直接擲出 {@link AssertionError}。
     */
    @Test
    void controller只委派service且不持有WebClient() {
        AtomicInteger calls = new AtomicInteger();
        byte[] payload = "{\"status\":\"NONE\"}".getBytes(StandardCharsets.UTF_8);
        WebClient neverCalled = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> Mono.error(
                        new AssertionError("service 替身應直接回傳結果，不該真的發起 HTTP 呼叫")))
                .build();
        PublicPortfolioAdviceService service =
                new PublicPortfolioAdviceService(new BusinessUserClient(neverCalled), neverCalled) {
                    @Override
                    public Mono<ResponseEntity<byte[]>> latest() {
                        calls.incrementAndGet();
                        return Mono.just(ResponseEntity.ok(payload));
                    }
                };
        PublicPortfolioAdviceController controller = new PublicPortfolioAdviceController(service);

        ResponseEntity<byte[]> response = controller.latest().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(payload);
        assertThat(calls).hasValue(1);
        assertThat(Arrays.stream(PublicPortfolioAdviceController.class.getDeclaredFields())
                .map(Field::getType))
                .noneMatch(WebClient.class::isAssignableFrom);
    }

    /** 匿名呼叫成功時 200，且高精度金融數值逐位元組原樣 relay（不經 Map/Double round-trip）。 */
    @Test
    void 匿名呼叫成功時回200且逐位元組原樣relay() {
        String payload = "{\"status\":\"OK\",\"targetAllocation\":{\"stock\":0.123456789012345678901234567890}}";
        WebTestClient client = client(payload, HttpStatus.OK);

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(result -> assertThat(
                        new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                        .isEqualTo(payload));
    }

    /** 尚無任何一筆建議時 business 回 {@code {status:"NONE"}} 的 HTTP 200——原樣 relay，不代為產生。 */
    @Test
    void 尚無建議時business的NONE形狀原樣relay() {
        WebTestClient client = client("{\"status\":\"NONE\"}", HttpStatus.OK);

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("NONE");
    }

    /**
     * business 端非 2xx 屬 byte-relay 範圍：狀態碼與 body 原樣傳回，<b>不</b>被吞成 200 空物件，
     * 也<b>不</b>經 {@link PublicPortfolioAdviceExceptionAdvice} 消毒（該 advice 只涵蓋 bootstrap
     * lookup 的例外與「主要管理者不可用」）——這正是兩條路由錯誤契約不同之處。
     */
    @Test
    void business端非2xx時不吞錯誤而原樣relay狀態碼與body() {
        String problem = "{\"title\":\"No advice\"}";
        WebTestClient client = client(problem, HttpStatus.NOT_FOUND);

        client.get().uri("/api/public/portfolio-advice/latest")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .consumeWith(result -> assertThat(
                        new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                        .isEqualTo(problem));
    }

    private static WebTestClient client(String latestBody, HttpStatus latestStatus) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(request -> {
                    boolean bootstrap = "/internal/users/configured-admin".equals(request.url().getPath());
                    return Mono.just(ClientResponse.create(bootstrap ? HttpStatus.OK : latestStatus)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(bootstrap ? ADMIN_JSON : latestBody)
                            .build());
                })
                .build();
        PublicPortfolioAdviceService service =
                new PublicPortfolioAdviceService(new BusinessUserClient(downstream), downstream);
        return WebTestClient.bindToController(new PublicPortfolioAdviceController(service))
                .controllerAdvice(new PublicPortfolioAdviceExceptionAdvice())
                .build();
    }
}

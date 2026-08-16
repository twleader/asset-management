package com.steven.assets.bff.portfolioadvice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 公開「最新資產配置建議」的封閉錯誤契約（Requirement 79）：不回傳 business 原始 body 或例外訊息。
 *
 * <p><b>{@code assignableTypes} 範圍窄不等於 Spring 保證它蓋過全域 {@code BusinessErrorAdvice}</b>——
 * Spring 跨 {@code @ControllerAdvice} bean 解析同一例外型別依 {@code @Order}／bean 註冊順序決定
 * （Requirement 71 已逐字記載此教訓），故本類別<b>明確宣告 {@code @Order(Ordered.HIGHEST_PRECEDENCE)}</b>。
 *
 * <p><b>本 advice 的覆蓋範圍與第七條（今日股市分析）刻意不同</b>：
 * {@link PublicPortfolioAdviceService} 的 downstream 呼叫走 {@code exchangeToMono} 的 byte-relay，
 * 非 2xx <b>不會</b>擲出 {@code WebClientResponseException}，故 business 的非 2xx 屬 relay 範圍。
 * 這裡的 {@code WebClientResponseException} handler 涵蓋的是 bootstrap lookup
 * （{@code BusinessUserClient.configuredAdmin()}，該支用 {@code .retrieve()}）擲出的例外。
 *
 * <p>「主要管理者不可用」是合法的結構化訊號，比照既有 {@code LatestAssetsPublicExceptionAdvice.unavailable()}
 * 回具名的 {@code 503 SERVICE_UNAVAILABLE}（<b>不是 404</b>），不在消毒範圍。
 */
@RestControllerAdvice(assignableTypes = PublicPortfolioAdviceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicPortfolioAdviceExceptionAdvice {

    /** bootstrap 沒有可信 owner：具名 503，回可判讀的訊息（非消毒範圍）。 */
    @ExceptionHandler(PublicPortfolioAdviceUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(PublicPortfolioAdviceUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Portfolio advice unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    /** bootstrap lookup 的 business 非 2xx：一律消毒成固定文案的 502，不帶出上游 body。 */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> handleBusinessError(WebClientResponseException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "資產配置建議暫時無法取得，請稍後再試");
        problem.setTitle("Portfolio advice downstream failure");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    /** 連線失敗、逾時等 transport 失敗：固定文案的 503。 */
    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> handleTransportFailure(WebClientException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "資產配置建議服務暫時無法連線");
        problem.setTitle("Portfolio advice service unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}

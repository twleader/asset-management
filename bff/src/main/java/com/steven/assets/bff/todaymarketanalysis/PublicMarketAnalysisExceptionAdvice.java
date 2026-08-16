package com.steven.assets.bff.todaymarketanalysis;

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
 * 公開「今日股市分析」的封閉錯誤契約（Requirement 79）：不回傳 business 原始 body 或例外訊息。
 *
 * <p><b>{@code assignableTypes} 範圍窄不等於 Spring 保證它蓋過全域 {@code BusinessErrorAdvice}</b>——
 * 兩者對同一個 {@code WebClientResponseException} 各自宣告 handler 時，Spring 跨
 * {@code @ControllerAdvice} bean 解析同一例外型別依 {@code @Order}／bean 註冊順序決定，不會因為某個
 * advice 的 {@code assignableTypes} 較窄就自動優先（Requirement 71 已逐字記載此教訓；既有四支 sibling
 * 中只有 {@code PublicCrawlerRescanExceptionAdvice} 宣告了 {@code @Order}）。故本類別<b>明確宣告
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE)}</b>。
 *
 * <p>若讓全域 {@code BusinessErrorAdvice} 勝出，business 端 {@code GlobalExceptionHandler} 對未分類
 * 例外會把 {@code ex.getMessage()}（可能含內部主機名、SQL 錯誤文字）塞進 {@code ProblemDetail.detail}
 * 原樣轉發——對已登入 ADMIN 端點是既有取捨，對<b>完全匿名</b>的本端點不可沿用。
 */
@RestControllerAdvice(assignableTypes = PublicMarketAnalysisController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicMarketAnalysisExceptionAdvice {

    /** business 非 2xx：一律消毒成固定文案的 502，不帶出上游 body。 */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> handleBusinessError(WebClientResponseException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "今日股市分析暫時無法取得，請稍後再試");
        problem.setTitle("Market analysis downstream failure");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    /** 連線失敗、逾時等 transport 失敗：固定文案的 503。 */
    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> handleTransportFailure(WebClientException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "今日股市分析服務暫時無法連線");
        problem.setTitle("Market analysis service unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}

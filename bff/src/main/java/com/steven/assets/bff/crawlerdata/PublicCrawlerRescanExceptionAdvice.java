package com.steven.assets.bff.crawlerdata;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

/**
 * 公開觸發重新搜尋的封閉錯誤契約（Requirement 71）：不回傳 business 原始 body 或例外訊息。
 *
 * <p>比照 {@code PublicUsdTwdExceptionAdvice}／{@code LatestAssetsPublicExceptionAdvice} 既有的
 * scoped-advice 命名模式（{@code assignableTypes} 限定只對 {@link PublicCrawlerRescanController}
 * 生效）。<b>但 {@code assignableTypes} 範圍窄不等於 Spring 保證它蓋過全域
 * {@code BusinessErrorAdvice}</b>——兩者對同一個 {@code WebClientResponseException} 各自宣告
 * handler，Spring 跨 {@code @ControllerAdvice} bean 解析同一例外型別時依 {@code @Order}／bean
 * 註冊順序決定，不會因為某個 advice 的 {@code assignableTypes} 範圍較窄就自動優先；三支既有
 * sibling 皆未宣告 {@code @Order}：{@code PublicUsdTwdExceptionAdvice}／
 * {@code PublicMarketIndexExceptionAdvice} 的既有測試只用
 * {@code WebTestClient.bindToController(...).controllerAdvice(僅自己)} 組裝、從未把
 * {@code BusinessErrorAdvice} 一起放進同一個測試 context；{@code LatestAssetsPublicExceptionAdvice}
 * （三支裡唯一真正會與 {@code BusinessErrorAdvice} 競爭同一個 {@code WebClientResponseException}
 * handler 的一支，另兩支各自只處理專屬 domain exception）則連任何既有測試都沒有。故「scoped
 * advice 會蓋過全域 advice」這件事在本專案<b>從未被任何既有測試證明過</b>。故本類別<b>明確宣告
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE)}</b>，不依賴未定義的 bean 註冊順序去「碰運氣蓋過」
 * 全域 advice——這是唯一能給出決定性保證的做法。
 *
 * <p>與 {@code LatestAssetsPublicExceptionAdvice.downstream()} 刻意不同：後者對 business 404 是
 * 原樣 relay，因為那是「查無可信 owner」這個合法結構化訊號；本端點的 business 端
 * {@code POST /api/crawler-export-path/public-rescan} 設計上一律回 200（結果全部表達在
 * {@code RunNowResponse.status}），故出現非 2xx 本身即代表未預期的失敗，一律消毒。
 */
@RestControllerAdvice(assignableTypes = PublicCrawlerRescanController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicCrawlerRescanExceptionAdvice {

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> handleBusinessError(WebClientResponseException ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "重新搜尋觸發暫時失敗，請稍後再試");
        problem.setTitle("Crawler rescan downstream failure");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> handleTransportFailure(WebClientException ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "重新搜尋服務暫時無法連線");
        problem.setTitle("Crawler rescan service unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}

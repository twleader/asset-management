package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

/**
 * SRPP 公開路由專屬、最高優先的錯誤出口（Requirement 163／Task 454）。
 *
 * <p>所有錯誤都輸出 {@code application/problem+json}，欄位恰為 {@code type, title, status, detail, instance,
 * code, retryable}，文案一律取自 {@link SrppProblemCatalog}，不轉送 business 或例外文字；並帶
 * {@code Cache-Control: private, no-store}。
 *
 * <p>錯誤日誌（Requirement 143）：400／404／409／5xx 皆呼叫 {@code capture}；唯一具名例外是 409
 * {@code POLICY_UNSUPPORTED}——空 registry 期間它是正式環境的常態回應，也是 Tailscale preflight 的預期結果，
 * 記錄只會淹沒真正的錯誤。
 */
@RestControllerAdvice(assignableTypes = PublicSrppDailyContextController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicSrppDailyContextExceptionAdvice {

    @ExceptionHandler(SrppProblemException.class)
    public ResponseEntity<SrppProblemBody> problem(SrppProblemException ex, ServerWebExchange exchange) {
        if (ex.problem() != SrppProblemCatalog.POLICY_UNSUPPORTED) {
            capture(exchange, ex);
        }
        return respond(ex.problem());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SrppProblemBody> unexpected(Exception ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        return respond(SrppProblemCatalog.INTERNAL_ERROR);
    }

    static ResponseEntity<SrppProblemBody> respond(SrppProblemCatalog problem) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, PublicSrppDailyContextController.CACHE_CONTROL)
                .body(SrppProblemBody.of(problem));
    }

    /** RFC 9457 problem 的固定七欄；不含帳號、SQL、URI 或例外堆疊。 */
    @JsonPropertyOrder({"type", "title", "status", "detail", "instance", "code", "retryable"})
    public record SrppProblemBody(String type, String title, int status, String detail, String instance,
                                  String code, boolean retryable) {
        static SrppProblemBody of(SrppProblemCatalog problem) {
            return new SrppProblemBody("about:blank", problem.title(), problem.status().value(), problem.detail(),
                    SrppProblemCatalog.INSTANCE, problem.name(), problem.retryable());
        }
    }
}

package com.steven.assets.bff.publiccalendar;

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

/** Calendar authority partial failures are valid 200 payloads; only bridge failures become these fixed problems. */
@RestControllerAdvice(assignableTypes = PublicTradingCalendarController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicTradingCalendarExceptionAdvice {

    @ExceptionHandler(PublicTradingCalendarRequestException.class)
    public ResponseEntity<ProblemDetail> invalid(PublicTradingCalendarRequestException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_REQUEST, "Invalid trading calendar request", "交易日曆年度不合法");
    }

    @ExceptionHandler(PublicTradingCalendarTimeoutException.class)
    public ResponseEntity<ProblemDetail> timeout(PublicTradingCalendarTimeoutException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.GATEWAY_TIMEOUT, "Trading calendar timeout", "交易日曆服務逾時");
    }

    @ExceptionHandler(PublicTradingCalendarPayloadException.class)
    public ResponseEntity<ProblemDetail> invalidPayload(PublicTradingCalendarPayloadException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY, "Trading calendar downstream failure", "交易日曆暫時無法取得");
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> downstream(WebClientResponseException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY, "Trading calendar downstream failure", "交易日曆暫時無法取得");
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> transport(WebClientException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Trading calendar service unavailable", "交易日曆服務暫時無法連線");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}

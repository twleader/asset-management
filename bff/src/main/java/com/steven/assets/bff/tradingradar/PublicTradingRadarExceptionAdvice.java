package com.steven.assets.bff.tradingradar;

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

/** 公開交易雷達的固定、無上游細節錯誤契約。 */
@RestControllerAdvice(assignableTypes = PublicTradingRadarController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicTradingRadarExceptionAdvice {

    @ExceptionHandler(PublicTradingRadarUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(PublicTradingRadarUnavailableException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Trading radar unavailable", "主要管理者不可用");
    }
    public ResponseEntity<ProblemDetail> unavailable(PublicTradingRadarUnavailableException ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Trading radar unavailable", "主要管理者不可用");
    }

    @ExceptionHandler(PublicTradingRadarRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(PublicTradingRadarRequestException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid trading radar request", "股票代號或市場別格式不合法");
    }

    @ExceptionHandler(PublicTradingRadarEmailRequestException.class)
    public ResponseEntity<ProblemDetail> invalidEmail(PublicTradingRadarEmailRequestException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid trading radar email", "email 格式不合法");
    }
    public ResponseEntity<ProblemDetail> invalidEmail(PublicTradingRadarEmailRequestException ignored) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid trading radar email", "email 格式不合法");
    }

    @ExceptionHandler(PublicTradingRadarStockNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(PublicTradingRadarStockNotFoundException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.NOT_FOUND,
                "Trading radar stock not found", "今日交易雷達找不到指定股票");
    }

    @ExceptionHandler(PublicTradingRadarTimeoutException.class)
    public ResponseEntity<ProblemDetail> timeout(PublicTradingRadarTimeoutException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.GATEWAY_TIMEOUT,
                "Trading radar timeout", "今日交易雷達服務逾時");
    }

    @ExceptionHandler(PublicTradingRadarPayloadException.class)
    public ResponseEntity<ProblemDetail> invalidPayload(PublicTradingRadarPayloadException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY,
                "Trading radar downstream failure", "今日交易雷達暫時無法取得，請稍後再試");
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> downstream(WebClientResponseException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY,
                "Trading radar downstream failure", "今日交易雷達暫時無法取得，請稍後再試");
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> transport(WebClientException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Trading radar service unavailable", "今日交易雷達服務暫時無法連線");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}

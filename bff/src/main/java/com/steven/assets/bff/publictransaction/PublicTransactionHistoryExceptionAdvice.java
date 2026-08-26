package com.steven.assets.bff.publictransaction;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Fixed public errors; raw business body, SQL, endpoint and owner information are never relayed. */
@RestControllerAdvice(assignableTypes = PublicTransactionHistoryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicTransactionHistoryExceptionAdvice {

    @ExceptionHandler(PublicTransactionHistoryRequestException.class)
    public ResponseEntity<ProblemDetail> invalid(PublicTransactionHistoryRequestException ignored) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid transaction history request", "交易紀錄篩選條件不合法");
    }

    @ExceptionHandler(PublicTransactionHistoryUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(PublicTransactionHistoryUnavailableException ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Transaction history unavailable", "交易紀錄服務暫時不可用");
    }

    @ExceptionHandler(PublicTransactionHistoryTimeoutException.class)
    public ResponseEntity<ProblemDetail> timeout(PublicTransactionHistoryTimeoutException ignored) {
        return problem(HttpStatus.GATEWAY_TIMEOUT, "Transaction history timeout", "交易紀錄服務逾時");
    }

    @ExceptionHandler(PublicTransactionHistoryPayloadException.class)
    public ResponseEntity<ProblemDetail> invalidPayload(PublicTransactionHistoryPayloadException ignored) {
        return problem(HttpStatus.BAD_GATEWAY, "Transaction history downstream failure", "交易紀錄暫時無法取得");
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> downstream(WebClientResponseException ignored) {
        return problem(HttpStatus.BAD_GATEWAY, "Transaction history downstream failure", "交易紀錄暫時無法取得");
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> transport(WebClientException ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Transaction history service unavailable", "交易紀錄服務暫時無法連線");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}

package com.steven.assets.bff.publicquote;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

/** 公開 quote raw 上游的封閉錯誤契約；不回傳 container URL、body 或 stack trace。 */
@RestControllerAdvice(assignableTypes = PublicQuoteMarketDataController.class)
public class PublicQuoteMarketDataExceptionAdvice {

    @ExceptionHandler(PublicQuoteRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(PublicQuoteRequestException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_REQUEST, "報價日期參數不合法");
    }

    @ExceptionHandler(PublicQuoteRawTimeoutException.class)
    public ResponseEntity<ProblemDetail> rawTimeout(PublicQuoteRawTimeoutException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.GATEWAY_TIMEOUT, "原始報價暫時逾時");
    }

    @ExceptionHandler(PublicQuoteRawUnavailableException.class)
    public ResponseEntity<ProblemDetail> rawUnavailable(PublicQuoteRawUnavailableException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY, "原始報價暫時不可用");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Public quote data unavailable");
        return ResponseEntity.status(status).body(problem);
    }
}

final class PublicQuoteRequestException extends RuntimeException {
    PublicQuoteRequestException(String message) { super(message); }
}

final class PublicQuoteRawUnavailableException extends RuntimeException {
    PublicQuoteRawUnavailableException() { super("public quote raw unavailable"); }
}

final class PublicQuoteRawTimeoutException extends RuntimeException {
    PublicQuoteRawTimeoutException() { super("public quote raw timeout"); }
}

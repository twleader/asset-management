package com.steven.assets.bff.gdptwse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只處理公開股市大盤 API 的 validation／typed payload 錯誤，不介入既有 business error passthrough。 */
@RestControllerAdvice(assignableTypes = PublicMarketIndexController.class)
public class PublicMarketIndexExceptionAdvice {

    @ExceptionHandler(PublicMarketIndexRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(PublicMarketIndexRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid market index request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MalformedMarketIndexPayloadException.class)
    public ResponseEntity<ProblemDetail> handleMalformedPayload(MalformedMarketIndexPayloadException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Malformed market index payload");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }
}

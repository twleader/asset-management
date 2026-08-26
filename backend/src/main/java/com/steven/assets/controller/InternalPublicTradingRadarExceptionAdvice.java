package com.steven.assets.controller;

import com.steven.assets.service.PublicTradingRadarProjectionService.PublicTradingRadarProjectionException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Internal selector failures carry no stock/user detail; BFF maps the status to its public fixed problem. */
@RestControllerAdvice(assignableTypes = InternalPublicTradingRadarController.class)
public class InternalPublicTradingRadarExceptionAdvice {

    @ExceptionHandler(PublicTradingRadarProjectionException.class)
    public ResponseEntity<ProblemDetail> invalidSelector(PublicTradingRadarProjectionException exception) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(exception.status(), "公開交易雷達選擇條件不合法或不存在");
        body.setTitle("Public trading radar request failed");
        return ResponseEntity.status(exception.status()).body(body);
    }
}

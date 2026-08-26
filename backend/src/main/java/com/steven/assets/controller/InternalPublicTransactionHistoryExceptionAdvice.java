package com.steven.assets.controller;

import com.steven.assets.service.InvalidPublicTransactionHistoryRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Does not reflect query values or tenant information to an internal caller. */
@RestControllerAdvice(assignableTypes = InternalPublicTransactionHistoryController.class)
public class InternalPublicTransactionHistoryExceptionAdvice {

    @ExceptionHandler(InvalidPublicTransactionHistoryRequestException.class)
    public ResponseEntity<ProblemDetail> invalid(InvalidPublicTransactionHistoryRequestException ignored) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "交易紀錄篩選條件不合法");
        body.setTitle("Invalid public transaction history request");
        return ResponseEntity.badRequest().body(body);
    }
}

package com.steven.assets.controller;

import com.steven.assets.srpp.SrppReadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Requirement 163／Task 453.4：本 controller 範圍內的未預期例外一律轉為固定文案的 500 {@code INTERNAL_ERROR}
 * problem，不回傳帳號、SQL 或例外訊息。優先於 {@code GlobalExceptionHandler}。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InternalSrppDailyContextController.class)
public class InternalSrppDailyContextExceptionAdvice {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception exception) {
        log.warn("SRPP daily-context 讀取發生未預期錯誤 type={}", exception.getClass().getSimpleName());
        return InternalSrppDailyContextController.toResponse(SrppReadResult.problem("INTERNAL_ERROR"));
    }
}

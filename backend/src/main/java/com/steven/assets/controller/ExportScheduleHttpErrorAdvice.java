package com.steven.assets.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Preserve the four schedule APIs' HTTP contract before the application's generic 500 handler. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ExportScheduleController.class, RealizedGainExportController.class,
        CommodityExportController.class, ExchangeRateExportController.class})
public class ExportScheduleHttpErrorAdvice {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail invalidBody(HttpMessageNotReadableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "匯出排程欄位格式不合法；時間與資料範圍須為整數");
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail explicitStatus(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }
}

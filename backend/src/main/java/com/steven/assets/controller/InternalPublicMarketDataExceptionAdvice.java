package com.steven.assets.controller;

import com.steven.assets.service.InvalidPublicTradingCalendarRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Task 372 readonly bridge 的 request-boundary 400；不讓缺日期或錯日期落入全域 500 兜底。 */
@RestControllerAdvice(assignableTypes = InternalPublicMarketDataController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalPublicMarketDataExceptionAdvice {

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalidRequestParameter(Exception ignored) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "市場資料參數不合法");
        problem.setTitle("Invalid public market data request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidPublicTradingCalendarRequestException.class)
    public ResponseEntity<ProblemDetail> invalidCalendarYear(InvalidPublicTradingCalendarRequestException ignored) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "交易日曆年度不合法");
        problem.setTitle("Invalid public trading calendar request");
        return ResponseEntity.badRequest().body(problem);
    }
}

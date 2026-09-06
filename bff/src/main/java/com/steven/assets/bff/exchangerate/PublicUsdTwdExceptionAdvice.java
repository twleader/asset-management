package com.steven.assets.bff.exchangerate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

/** 公開 USD/TWD API 的封閉錯誤契約；不回傳 stacktrace 或下游 response body。 */
@RestControllerAdvice(assignableTypes = PublicUsdTwdController.class)
public class PublicUsdTwdExceptionAdvice {

    @ExceptionHandler(UsdTwdUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(UsdTwdUnavailableException ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "USD/TWD 匯率資料暫時不存在");
        problem.setTitle("USD/TWD data not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(UsdTwdBadGatewayException.class)
    public ResponseEntity<ProblemDetail> handleBadGateway(UsdTwdBadGatewayException ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "USD/TWD 下游資料暫時不可用");
        problem.setTitle("USD/TWD downstream data unavailable");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }
}

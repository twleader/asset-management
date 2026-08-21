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

/** 公開交易雷達的固定、無上游細節錯誤契約。 */
@RestControllerAdvice(assignableTypes = PublicTradingRadarController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicTradingRadarExceptionAdvice {

    @ExceptionHandler(PublicTradingRadarUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(PublicTradingRadarUnavailableException ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Trading radar unavailable", "主要管理者不可用");
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> downstream(WebClientResponseException ignored) {
        return problem(HttpStatus.BAD_GATEWAY,
                "Trading radar downstream failure", "今日交易雷達暫時無法取得，請稍後再試");
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> transport(WebClientException ignored) {
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

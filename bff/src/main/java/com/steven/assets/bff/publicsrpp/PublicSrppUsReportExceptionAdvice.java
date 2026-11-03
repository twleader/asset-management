package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;


@RestControllerAdvice(assignableTypes = PublicSrppUsReportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicSrppUsReportExceptionAdvice {
    @ExceptionHandler(SrppUsReportProblemException.class)
    ResponseEntity<ProblemBody> problem(SrppUsReportProblemException ex, ServerWebExchange exchange) {
        return respond(ex.problem(), exchange.getRequest().getPath().value());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> unexpected(Exception ex, ServerWebExchange exchange) {
        return respond(SrppUsReportProblemCatalog.UPSTREAM_INVALID, exchange.getRequest().getPath().value());
    }
    private static ResponseEntity<ProblemBody> respond(SrppUsReportProblemCatalog problem, String path) {
        return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ProblemBody("about:blank", problem.title(), problem.status().value(), problem.detail(),
                        path, problem.name(), problem.retryable()));
    }
    @JsonPropertyOrder({"type", "title", "status", "detail", "instance", "code", "retryable"})
    record ProblemBody(String type, String title, int status, String detail, String instance, String code, boolean retryable) {}
}

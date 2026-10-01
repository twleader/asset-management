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

import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

@RestControllerAdvice(assignableTypes = PublicSrppOrchestratedController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicSrppOrchestratedExceptionAdvice {
    @ExceptionHandler(SrppOrchestratedProblemException.class)
    ResponseEntity<ProblemBody> problem(SrppOrchestratedProblemException ex, ServerWebExchange exchange) {
        if (ex.problem() != SrppOrchestratedProblemCatalog.POLICY_UNSUPPORTED) capture(exchange, ex);
        return respond(ex.problem(), exchange.getRequest().getPath().value());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> unexpected(Exception ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        return respond(SrppOrchestratedProblemCatalog.UPSTREAM_INVALID, exchange.getRequest().getPath().value());
    }
    private static ResponseEntity<ProblemBody> respond(SrppOrchestratedProblemCatalog problem, String path) {
        return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new ProblemBody("about:blank", problem.title(), problem.status().value(), problem.detail(),
                        path, problem.name(), problem.retryable()));
    }
    @JsonPropertyOrder({"type", "title", "status", "detail", "instance", "code", "retryable"})
    record ProblemBody(String type, String title, int status, String detail, String instance, String code, boolean retryable) {}
}

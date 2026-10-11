package com.steven.assets.bff.aiflow;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice(assignableTypes={AiFlowBffController.class,AiFlowProducerController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
final class AiFlowExceptionAdvice {
    @ExceptionHandler(AiFlowProblem.class) ResponseEntity<AiFlowDtos.Problem> problem(AiFlowProblem p,ServerWebExchange x) {return respond(p,x);}
    @ExceptionHandler(Exception.class) ResponseEntity<AiFlowDtos.Problem> unexpected(Exception e,ServerWebExchange x) {return respond(new AiFlowProblem("UPSTREAM_INVALID"),x);}
    private ResponseEntity<AiFlowDtos.Problem> respond(AiFlowProblem p,ServerWebExchange x) {
        return ResponseEntity.status(p.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(p.body(x.getRequest().getPath().value()));
    }
}

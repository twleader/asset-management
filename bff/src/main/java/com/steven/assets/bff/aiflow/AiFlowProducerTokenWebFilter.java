package com.steven.assets.bff.aiflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.security.AuthConstants;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 授權失敗直接完成回應；不進 owner bootstrap 或 error ingestion。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
final class AiFlowProducerTokenWebFilter implements WebFilter {
    static final String PATH="/api/srpp/ai-flow/events";
    private final AiFlowCredentials credentials;
    private final ObjectMapper json;
    AiFlowProducerTokenWebFilter(AiFlowCredentials credentials,ObjectMapper json) { this.credentials=credentials; this.json=json; }
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String path=exchange.getRequest().getPath().value();
        if(!path.startsWith("/api/srpp/ai-flow") && !path.matches("/api(?:;[^/]*)?/srpp(?:;[^/]*)?/ai-flow.*"))
            return chain.filter(exchange);
        try {
            if(!PATH.equals(path)) return direct(exchange,new AiFlowProblem("NOT_FOUND"));
            if(!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
                exchange.getResponse().getHeaders().setAllow(java.util.Set.of(HttpMethod.POST));
                return direct(exchange,new AiFlowProblem("METHOD_NOT_ALLOWED"));
            }
            credentials.authenticate(exchange.getRequest().getHeaders().get(AiFlowCredentials.HEADER));
            var sanitized=exchange.mutate().request(r->r.headers(h->{
                h.remove(AuthConstants.HDR_USER_ID); h.remove(AuthConstants.HDR_USER_ROLE); h.remove(AuthConstants.HDR_USER_STATUS);
            })).build();
            return chain.filter(sanitized).contextWrite(ctx->ctx.delete(AuthConstants.CTX_IDENTITY));
        } catch(AiFlowProblem problem) { return direct(exchange,problem); }
    }
    private Mono<Void> direct(ServerWebExchange x,AiFlowProblem problem) {
        x.getResponse().setStatusCode(HttpStatusCode.valueOf(problem.status()));
        x.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        x.getResponse().getHeaders().setCacheControl("private, no-store");
        try { return x.getResponse().writeWith(Mono.just(x.getResponse().bufferFactory().wrap(json.writeValueAsBytes(problem.body(x.getRequest().getPath().value()))))); }
        catch(Exception ignored) { return x.getResponse().setComplete(); }
    }
}

package com.steven.assets.bff.portfolioadvice;
import org.springframework.stereotype.Component;import org.springframework.core.Ordered;import org.springframework.http.HttpHeaders;import org.springframework.web.server.*;import reactor.core.publisher.Mono;
/** Covers pre-controller security, parsing, method and route failures as well as success. */
@Component public final class SrppClaudeAuthNoStoreWebFilter implements WebFilter,Ordered {
 public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
 public Mono<Void>filter(ServerWebExchange exchange,WebFilterChain chain){String path=exchange.getRequest().getPath().value();if(path.equals("/api/bff/portfolio-advice/srpp/ai-auth/claude")||path.startsWith("/api/bff/portfolio-advice/srpp/ai-auth/claude/")){exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL,"no-store");exchange.getResponse().beforeCommit(()->{exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL,"no-store");return Mono.empty();});}return chain.filter(exchange);}
}

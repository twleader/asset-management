package com.steven.assets.bff.publicsrpp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class PublicSrppOrchestratedController {
    static final String CONTEXT = "/api/public/srpp/calculation-context";
    static final String CALCULATIONS = "/api/public/srpp/calculations";
    static final String MARKET_FACTS = "/api/public/srpp/market-facts";
    private final PublicSrppOrchestratedService service;

    @GetMapping(CONTEXT)
    public Mono<ResponseEntity<byte[]>> context(ServerHttpRequest request) {
        return read(request, SrppOrchestratedQuery.Route.CONTEXT);
    }
    @GetMapping(CALCULATIONS)
    public Mono<ResponseEntity<byte[]>> calculations(ServerHttpRequest request) {
        return read(request, SrppOrchestratedQuery.Route.CALCULATIONS);
    }
    @GetMapping(MARKET_FACTS)
    public Mono<ResponseEntity<byte[]>> marketFacts(ServerHttpRequest request) {
        return read(request, SrppOrchestratedQuery.Route.MARKET_FACTS);
    }

    private Mono<ResponseEntity<byte[]>> read(ServerHttpRequest request, SrppOrchestratedQuery.Route route) {
        return service.read(request, route).map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bytes));
    }
}

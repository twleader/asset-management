package com.steven.assets.bff.publicsrpp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 9090 第十四條 exact GET（Requirement 163／Task 454）：只把原始 request 交給 service；
 * 成功回應為 business 原位元組，帶 {@code Cache-Control: private, no-store}。
 */
@RestController
@RequiredArgsConstructor
public class PublicSrppDailyContextController {

    public static final String PATH = "/api/public/srpp/daily-context";
    static final String CACHE_CONTROL = "private, no-store";

    private final PublicSrppDailyContextService service;

    @GetMapping(PATH)
    public Mono<ResponseEntity<byte[]>> read(ServerHttpRequest request) {
        return service.read(request).map(body -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(body));
    }
}

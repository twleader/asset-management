package com.steven.assets.bff.srppmail;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** Exact 9090 endpoint: raw request bytes are forwarded unchanged to protect JCS input semantics. */
@RestController
@RequiredArgsConstructor
public class SrppDailyReportMailController {
    public static final String BASE = "/api/srpp/daily-report-mail";
    private final SrppDailyReportMailService service;
    @PostMapping(value = BASE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<byte[]>> post(@RequestBody Mono<byte[]> body) { return body.defaultIfEmpty(new byte[0]).flatMap(service::submit); }
    @GetMapping(BASE + "/{idempotencyKey}")
    public Mono<ResponseEntity<byte[]>> get(@PathVariable String idempotencyKey) { return service.find(idempotencyKey); }
}
